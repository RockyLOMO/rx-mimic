"""Deploy through one ANZHI_SSH JSON secret; keep credentials out of arguments/files."""
import argparse
import base64
from dataclasses import dataclass, field
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.parse import unquote, urlsplit
import uuid

import paramiko


ROOT = "D:/rx-mimic"


@dataclass(frozen=True)
class Connection:
    host: str
    port: int
    username: str
    host_key: paramiko.PKey = field(repr=False)
    password: str = field(default=None, repr=False)
    private_key: paramiko.PKey = field(default=None, repr=False)


def parse_connection(raw):
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        raise ValueError("ANZHI_SSH must contain a JSON object") from None
    if not isinstance(data, dict):
        raise ValueError("ANZHI_SSH must contain a JSON object")
    keys = set(data)
    valid_key_sets = [
        {"endpoint", "password", "hostKey"},
        {"endpoint", "privateKey", "hostKey"},
        {"endpoint", "password", "privateKey", "hostKey"},
    ]
    if keys not in valid_key_sets:
        raise ValueError("ANZHI_SSH requires endpoint, hostKey, and password or privateKey")
    if not all(isinstance(data[key], str) and data[key].strip() for key in data):
        raise ValueError("ANZHI_SSH fields must be nonempty strings")
    try:
        url = urlsplit(data["endpoint"])
        host, port = url.hostname, url.port
    except ValueError:
        raise ValueError("Invalid SSH endpoint or port") from None
    if (url.scheme != "ssh" or not host or not url.username or url.password is not None
            or url.path not in ("", "/") or url.query or url.fragment
            or port is None or not 1 <= port <= 65535):
        raise ValueError("endpoint must be ssh://user@host:port")
    username = unquote(url.username)
    if not re.fullmatch(r"[A-Za-z0-9_.\\-]+", username):
        raise ValueError("Unsupported SSH username")
    if any(character.isspace() for character in host):
        raise ValueError("Invalid SSH host")
    key_parts = data["hostKey"].split()
    if len(key_parts) != 2:
        raise ValueError("hostKey must contain the SSH key type and base64 public key")
    try:
        entry = paramiko.hostkeys.HostKeyEntry.from_line("pinned-host " + data["hostKey"])
        if entry is None or entry.key is None:
            raise ValueError()
    except Exception:
        raise ValueError("Invalid pinned SSH public key") from None
    private_key = None

    if "privateKey" in data:

        key_stream = io.StringIO(data["privateKey"].strip())

        for key_class in (paramiko.Ed25519Key, paramiko.RSAKey, paramiko.ECDSAKey):

            try:

                key_stream.seek(0)

                private_key = key_class.from_private_key(key_stream)

                break

            except Exception:

                continue

        if private_key is None:

            raise ValueError("Invalid pinned SSH private key")

    return Connection(host, port, username, entry.key, data.get("password"), private_key)


def connect(config):
    client = paramiko.SSHClient()
    name = config.host if config.port == 22 else f"[{config.host}]:{config.port}"
    client.get_host_keys().add(name, config.host_key.get_name(), config.host_key)
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    try:
        auth_kwargs = {
            "allow_agent": False,
            "look_for_keys": False,
            "timeout": 30,
            "auth_timeout": 30,
            "banner_timeout": 30,
        }
        if config.private_key is not None:
            auth_kwargs["pkey"] = config.private_key
        elif config.password is not None:
            auth_kwargs["password"] = config.password
        else:
            raise ValueError("Neither privateKey nor password provided in ANZHI_SSH")
        client.connect(config.host, port=config.port, username=config.username, **auth_kwargs)
        client.get_transport().set_keepalive(20)
        return client
    except Exception:
        client.close()
        raise


def powershell(client, source, timeout=1800):
    encoded = base64.b64encode(source.encode("utf-16le")).decode("ascii")
    command = "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand " + encoded
    _, stdout, stderr = client.exec_command(command, timeout=timeout)
    # A deployment can take minutes; drain stdout/stderr together to avoid SSH window deadlocks.
    channel = stdout.channel
    deadline = time.monotonic() + timeout
    while True:
        if time.monotonic() >= deadline:
            channel.close()
            raise TimeoutError("Remote deployment command timed out")
        if channel.recv_ready():
            sys.stdout.write(channel.recv(65536).decode("utf-8", errors="replace"))
            sys.stdout.flush()
        if channel.recv_stderr_ready():
            sys.stderr.write(channel.recv_stderr(65536).decode("utf-8", errors="replace"))
            sys.stderr.flush()
        if channel.exit_status_ready() and not channel.recv_ready() and not channel.recv_stderr_ready():
            break
        import select
        select.select([channel], [], [], 0.2)
    status = channel.recv_exit_status()
    if status:
        raise RuntimeError(f"Remote PowerShell failed (exit {status})")


def validate_package(directory, version):
    if not re.fullmatch(r"[a-f0-9]{40}", version or ""):
        raise ValueError("Release must be a full lowercase Git SHA")
    for name in ("app.jar", "manifest.json", "service.ps1"):
        if not (directory / name).is_file():
            raise ValueError(f"Package missing {name}")
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    with (directory / "app.jar").open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if manifest.get("version") != version or manifest.get("sha256", "").lower() != digest:
        raise ValueError("Local package version/hash mismatch")


def deployment_script(stage, version):
    # Both substitutions are generated/validated locally, never raw connection configuration.
    if not re.fullmatch(r"upload-[a-f0-9]{32}", stage) or not re.fullmatch(r"[a-f0-9]{40}", version):
        raise ValueError("Invalid deployment stage/version")
    return r"""$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$root='D:\rx-mimic'
$source=Join-Path $root '__STAGE__'
$target=Join-Path $root 'releases\__VERSION__'
if(Test-Path -LiteralPath $target){throw 'Immutable release already exists; inspect it instead of overwriting'}
$manifest=Get-Content -LiteralPath (Join-Path $source 'manifest.json') -Raw | ConvertFrom-Json
if($manifest.version -ne '__VERSION__' -or (Get-FileHash -LiteralPath (Join-Path $source 'app.jar') -Algorithm SHA256).Hash -ne $manifest.sha256){throw 'Upload hash/version mismatch'}
Move-Item -LiteralPath $source -Destination $target
& (Join-Path $target 'service.ps1') -Action Deploy -Root $root -Version '__VERSION__'
if (-not $?) { exit 1 }
""".replace("__STAGE__", stage).replace("__VERSION__", version)


def upload_package(client, directory, stage):
    with client.open_sftp() as sftp:
        sftp.get_channel().settimeout(120)
        sftp.mkdir(ROOT + "/" + stage)
        for name in ("app.jar", "manifest.json", "service.ps1"):
            sftp.put(str(directory / name), ROOT + "/" + stage + "/" + name)
            print(f"Uploaded {name}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Authenticate and inspect bootstrap; no writes/deploy")
    parser.add_argument("--package-dir", type=Path)
    parser.add_argument("--release-sha")
    args = parser.parse_args()
    config = parse_connection(os.environ.get("ANZHI_SSH"))
    if os.environ.get("GITHUB_ACTIONS") == "true":
        # JSON secrets are masked as a whole; also mask the password as an individual value.
        if config.password:
            masked = config.password.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print("::add-mask::" + masked, flush=True)
    if not args.check:
        if args.package_dir is None:
            raise ValueError("--package-dir is required")
        validate_package(args.package_dir, args.release_sha)
    with connect(config) as client:
        print(f"Pinned SSH authentication succeeded: {config.username}@{config.host}:{config.port}", flush=True)
        if args.check:
            powershell(client, r"""$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'
if(!(Test-Path -LiteralPath 'D:\rx-mimic\scripts\service.ps1')){throw 'Initialize D:\rx-mimic before deployment'}
whoami
Get-ScheduledTask -TaskName rx-mimic | Select-Object TaskName,State | ConvertTo-Json
""")
            return
        stage = "upload-" + uuid.uuid4().hex
        upload_package(client, args.package_dir, stage)
        powershell(client, deployment_script(stage, args.release_sha))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Deployment failed: {error}", file=sys.stderr)
        sys.exit(1)

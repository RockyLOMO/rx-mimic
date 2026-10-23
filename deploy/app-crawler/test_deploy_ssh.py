import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import paramiko
import deploy_ssh


class DeploymentSshTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = paramiko.ECDSAKey.generate(bits=384)

    def config(self, **changes):
        data = {"endpoint": "ssh://AnZhi@cloud.f-li.cn:6014", "password": "unit-test-password",
                "hostKey": self.key.get_name() + " " + self.key.get_base64()}
        data.update(changes)
        return json.dumps(data)

    def test_single_endpoint_and_no_password_in_repr(self):
        config = deploy_ssh.parse_connection(self.config())
        self.assertEqual(("cloud.f-li.cn", 6014, "AnZhi"), (config.host, config.port, config.username))
        self.assertNotIn(config.password, repr(config))

    def test_invalid_configuration_fails_without_echoing_secret(self):
        for endpoint in ("cloud.f-li.cn:6014", "ssh://AnZhi@cloud.f-li.cn", "ssh://AnZhi@host:70000",
                         "ssh://AnZhi:unit-test-password@host:22", "ssh://AnZhi@host:22/a"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError) as error:
                deploy_ssh.parse_connection(self.config(endpoint=endpoint))
            self.assertNotIn("unit-test-password", str(error.exception))
        for raw in (None, "[]", "{}", self.config(hostKey="invalid"), self.config(extra="value")):
            with self.assertRaises(ValueError):
                deploy_ssh.parse_connection(raw)

    def test_private_key_authentication(self):
        import io
        rsa_key = paramiko.RSAKey.generate(2048)
        stream = io.StringIO()
        rsa_key.write_private_key(stream)
        data = {
            "endpoint": "ssh://AnZhi@cloud.f-li.cn:6014",
            "privateKey": stream.getvalue(),
            "hostKey": self.key.get_name() + " " + self.key.get_base64()
        }
        config = deploy_ssh.parse_connection(json.dumps(data))
        self.assertIsNotNone(config.private_key)
        self.assertIsNone(config.password)
        client = MagicMock()
        keys = paramiko.HostKeys()
        client.get_host_keys.return_value = keys
        def authenticate(*args, **kwargs):
            self.assertEqual(config.private_key, kwargs["pkey"])
            self.assertFalse(kwargs["allow_agent"])
            self.assertFalse(kwargs["look_for_keys"])
        client.connect.side_effect = authenticate
        with patch.object(deploy_ssh.paramiko, "SSHClient", return_value=client):
            self.assertIs(client, deploy_ssh.connect(config))

    def test_pinned_key_is_registered_before_password_authentication(self):
        config = deploy_ssh.parse_connection(self.config())
        client = MagicMock()
        keys = paramiko.HostKeys()
        client.get_host_keys.return_value = keys
        def authenticate(*args, **kwargs):
            self.assertEqual(config.host_key, keys.lookup("[cloud.f-li.cn]:6014")[config.host_key.get_name()])
            self.assertFalse(kwargs["allow_agent"])
            self.assertFalse(kwargs["look_for_keys"])
        client.connect.side_effect = authenticate
        with patch.object(deploy_ssh.paramiko, "SSHClient", return_value=client):
            self.assertIs(client, deploy_ssh.connect(config))
        self.assertIsInstance(client.set_missing_host_key_policy.call_args.args[0], paramiko.RejectPolicy)

    def test_failed_connection_is_closed(self):
        client = MagicMock()
        client.connect.side_effect = paramiko.AuthenticationException("authentication failed")
        with patch.object(deploy_ssh.paramiko, "SSHClient", return_value=client):
            with self.assertRaises(paramiko.AuthenticationException):
                deploy_ssh.connect(deploy_ssh.parse_connection(self.config()))
        client.close.assert_called_once()

    def test_package_mismatch_rejected_before_upload(self):
        sha = "a" * 40
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / "app.jar").write_bytes(b"artifact")
            (directory / "service.ps1").write_text("exit 0")
            (directory / "manifest.json").write_text(json.dumps({"version": sha,
                "sha256": hashlib.sha256(b"artifact").hexdigest()}))
            deploy_ssh.validate_package(directory, sha)
            (directory / "app.jar").write_bytes(b"modified")
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                deploy_ssh.validate_package(directory, sha)
        with self.assertRaises(ValueError):
            deploy_ssh.deployment_script("upload-../../other", sha)

    def test_remote_failure_is_not_reported_as_success(self):
        client = MagicMock()
        channel = MagicMock()
        channel.recv_ready.return_value = False
        channel.recv_stderr_ready.return_value = False
        channel.exit_status_ready.return_value = True
        channel.recv_exit_status.return_value = 17
        stream = MagicMock(channel=channel)
        client.exec_command.return_value = (None, stream, MagicMock())
        with self.assertRaisesRegex(RuntimeError, "exit 17"):
            deploy_ssh.powershell(client, "exit 17")

    def test_upload_timeout_propagates_and_closes_sftp(self):
        client = MagicMock()
        context = client.open_sftp.return_value
        sftp = context.__enter__.return_value
        def upload(*args, **kwargs):
            sftp.get_channel().settimeout.assert_called_once_with(120)
            raise TimeoutError("Upload stalled")
        sftp.put.side_effect = upload
        with self.assertRaisesRegex(TimeoutError, "Upload stalled"):
            deploy_ssh.upload_package(client, Path("package"), "upload-" + "a" * 32)
        context.__exit__.assert_called_once()


if __name__ == "__main__":
    unittest.main()

# AnZhi GitHub Actions 配置

日期：2026-10-21。对应 workflow：`Build and deploy rx-mimic`。

## 只配置一个 Secret

打开 [rx-mimic-production 环境设置](https://github.com/RockyLOMO/rx-mimic/settings/environments/24876535130/edit)，在 **Environment secrets** 中新增：

| 项目 | 填写内容 |
| --- | --- |
| Name | `ANZHI_SSH` |
| Secret | 本机 `D:\home\.envKeys\rx-mimic-github-anzhi-ssh.json` 的全部 JSON 内容 |

填写文件内容，不是文件路径。连接地址、账号、密码、固定主机公钥已经生成，无需逐项填写。可在 PowerShell 中复制：

```powershell
Get-Content -LiteralPath 'D:\home\.envKeys\rx-mimic-github-anzhi-ssh.json' -Raw | Set-Clipboard
```

文件结构是 `endpoint/password/hostKey` 三个字段，endpoint 为 `ssh://AnZhi@cloud.f-li.cn:6014`，同时包含账号、host 和 port；主机公钥不重复配置 host/port。

原来的 `DEPLOY_HOST`、`DEPLOY_PORT`、`DEPLOY_USER`、`DEPLOY_KEY`、`DEPLOY_KNOWN_HOSTS` 在新版 workflow 中均不再读取。运行目录仍固定 `D:\rx-mimic`；远端应用凭据继续使用现有受限配置和用户级环境变量，无需复制到 GitHub。

## 生效与运行

1. 将本次 workflow、`deploy/app-crawler/deploy_ssh.py` 和测试改动提交推送到默认分支；改动需在默认分支生效。
2. 配置上述唯一 Secret。
3. 打开 [Build and deploy rx-mimic](https://github.com/RockyLOMO/rx-mimic/actions/workflows/java-ssh-crawler.yml)，commit 选填：留空部署默认分支最新提交；需要指定版本时填写默认分支可达的完整40位commit SHA，再手动运行。

新版在构建前执行只读 SSH 连接与初始化检查。随后构建同一制品、校验本地和远端 SHA256，调用已有发布脚本执行 drain、优雅退出、版本切换和健康检查。主机公钥必须匹配；上传或远端命令失败会使 job 失败。SFTP 空闲超时120秒，远端部署命令超时30分钟；job总超时45分钟。

同一个 commit 对应不可变 release，已经存在的 release 会拒绝覆盖；不要用重复发布绕过该检查。

## 本轮验证

- 失败 run `39532999235`：构建、测试、制品准备通过；上传阶段5个原部署变量均为空，在主机参数检查时退出，尚未连接 SSH。
- 沿用本机已有 AnZhi SSH 密码认证；没有修改远端 SSH 登录配置、应用文件或计划任务。
- 新脚本 `--check` 已真实登录 `anzhi-pc\anzhi`，确认已有服务脚本及运行任务。
- ECDSA 主机公钥指纹与此前部署记录一致：`SHA256:zm9KGOp8b2IRbTjDmlIpMLqNJBGlMszeIZCEvm1omLc`。
- 单元测试覆盖连接解析、错误配置、主机公钥固定、认证失败关闭、制品哈希校验、远端失败及上传超时。Workflow YAML 与嵌入 Python 语法检查通过。
- 新 Secret 文件禁用 ACL 继承，仅授权当前 Windows 用户；密码没有写入仓库、文档或命令参数。用户已将 `ANZHI_SSH` 添加为 Repository Secret；环境未同名覆盖时可以直接读取，无需重复添加Environment Secret。新版完整CI发布按本轮运行记录确认。

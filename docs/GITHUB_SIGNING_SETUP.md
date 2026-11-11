# GitHub 签名凭据保管与接入

更新：2026-10-31。公开证书已核验并[登记](signing/README.md)，master 上的实际凭据读取、证书匹配和私钥签名证明已[通过](SIGNING_CREDENTIAL_VALIDATION.md)。维护者明确取消人工审批；备份恢复、实际 APK 签名及升级仍未完成，当前连接不能独立读取全部环境设置。

## 1. 存放位置

使用当前仓库自己的 `release-signing` environment，不新建存放私钥的 Git 仓库，也不使用可供其它项目复用的组织级 Secret。

| 内容 | 位置 | 说明 |
| --- | --- | --- |
| 公开证书 `miancak-release-cert.pem` | 已核验并提交至 [`docs/signing/`](signing/README.md) | 只含公钥；实际证书 DER 的 SHA-256 已登记，作为后续签名核验的预期值 |
| `certificate-info.json` 中的公开算法、有效期与指纹 | 已整理为[核验记录](signing/certificate-info.json) | 与实际证书一致；密钥库加密、密码和 alias 状态没有仅凭证书认定为已验证 |
| 加密的 `miancak-release.p12`，转为 Base64 | 环境 Secret：`MIANCAK_RELEASE_KEYSTORE_B64` | Base64 不是加密；原 PKCS12 仍须保留强密码保护。上传值必须符合 GitHub 每项 48 KB 的限制 |
| 密钥库密码 | 环境 Secret：`MIANCAK_RELEASE_STORE_PASSWORD` | 独立填写，不写 Git 文件、聊天、日志或产物；获准签名的 job 会同时获得密钥库与密码 |
| 恢复备份 | 维护者保管的至少两份加密副本 | GitHub Actions 凭据存储不代替独立备份和密码管理器 |

Secrets 由 GitHub 接收加密值，再在获准的工作流运行时解密注入。它们提供受控使用，不是不可导出的硬件密钥；获准运行的 job 仍具有完整签名能力。

## 2. 环境范围与当前决定

进入 [仓库环境设置](https://github.com/AuroraLilja8514/miancak/settings/environments)，使用 `release-signing`：

1. Deployment branches and tags 明确选择分支 `master`；不要允许 PR refs、任意开发分支或不受保护的标签。不能只凭“Protected branches only”字样推断已有分支保护。
2. 维护者于 2026-10-31 明确取消人工审批。本项目按此决定运行，不重新设置 Required reviewers；工作流的仓库、master 来源、触发条件和两组自测仍限制正式凭据的使用。
3. 在该环境的 Environment secrets 区录入上表两项，不误填为 Repository variables；当前这两个固定名称的凭据已能被正式检查任务读取。连接不能独立读取管理设置，实际登记层级和保留的环境规则由维护者核对。

当前 GitHub 连接没有环境／Secrets 管理及 workflow dispatch 功能；本次通过已审查签名配置合入 master 的正常 push 事件启动。不要把管理 token 或密码贴到聊天以替代缺失的管理功能。

## 3. Windows 上准备上传值

进入生成器输出的私有 `MIANCAK-keys` 文件夹，运行：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.\miancak-release.p12'))) | Set-Content -LiteralPath '.\miancak-release.p12.b64' -Encoding ASCII -NoNewline
```

该命令只生成加密密钥库的文本编码，不解密私钥，不处理密码。将 `.p12.b64` 的内容填入 `MIANCAK_RELEASE_KEYSTORE_B64`，将原密码直接填入 `MIANCAK_RELEASE_STORE_PASSWORD`。临时编码文件也按凭据保管，使用后清理；不提交 Git、不作为 PR／Release 附件。密码继续保存在独立密码管理器中。

生成包采用 Windows 原生 PKCS12 导出，证书显示名称不等于 Java 内部 alias。文件只有一个私钥条目；后续 `apksigner` 省略 `--ks-key-alias` 自动选择，并核对实际证书指纹。不要把旧 `keytool` 示例中的 `miancak-release` 字符串强行当作此文件的内部 alias。出现多个条目时拒绝自动选择。

## 4. 当前检查与后续 APK 签名

独立[凭据检查](SIGNING_CREDENTIAL_VALIDATION.md)已合入 master 并成功运行：签名配置相关的 master push 自动启动，或从 master 手动触发；PR／开发分支不运行正式步骤。维护者已取消人工审批，现有其余环境规则继续生效。检查器只做内存中的随机挑战证明，不处理 APK。

以下实际 APK 的签名流程仍待实现／验证：

- 受信、来源固定的工作流申请 `release-signing` 环境；PR、fork、普通构建和测试不接触正式凭据，不能签署任意输入 ref／下载产物。
- 在无正式凭据的步骤先核对仓库、可信源码 SHA、构建 run／产物来源和适用验收结果，再校验 APK 包名 `org.miancak.keyboard`、版本、非 debuggable 状态与原生库。
- 有凭据的签名 job 使用已审查、固定版本的签名工具，仅处理上述确定 APK；不运行被签应用的 Gradle、依赖安装或仓库脚本。密码通过官方工具支持的环境变量／受控输入传递，不写入命令行明文。
- 不让正式证书签 debug 或测试执行器。签名前后校验公开证书指纹；保留 API 21 声明所需签名方案及 API 23／35 实际验证。
- 只发布最终签名 APK、文件哈希、证书指纹和必要汇总。私钥、密码、临时目录与未脱敏诊断不能进入日志、缓存或 artifact。
- 用一次性测试密钥先验证错误来源、错误证书指纹、缺失凭据及日志保护；正式身份的 APK、两版本覆盖升级和数据保留另行验收。

用途授权仅限 miancak。工作流与权限负责约束调用范围，证书本身不提供按仓库或包名的密码学限制。若获准工作流或管理账号被控制，Secrets 仍可能泄露；不能因仓库设为 private 就忽略这些边界。

## 5. 下一步与完成状态

已[登记真实指纹](signing/README.md)，Linux 29 项／Windows 31 项一次性密钥自测及 master 上的[实际正式凭据检查](SIGNING_CREDENTIAL_VALIDATION.md)均成功。下一步验证独立加密备份恢复，实现 APK 来源校验与签名，完成正式身份的覆盖升级。连接不能代为管理或审计全部环境／Secrets 设置；现有 debug 到正式包的迁移限制仍按 [稳定签名方案](SIGNING_PLAN.md) 保留。

仓库的 `.gitignore` 防止常规误加入 `.p12`／`.pfx`／`.jks` 及生成目录；它不能阻止强制添加，也不能移除已经进入历史的秘密。出现泄露时停止签名并评估影响，不以删除当前文件声称历史已经清理。

## 官方依据

- [GitHub Secrets](https://docs.github.com/en/actions/concepts/security/secrets)
- [管理部署环境](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
- [部署保护规则与环境 Secrets](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
- [Secrets 大小与读取时机](https://docs.github.com/en/actions/reference/security/secrets)
- [使用 Secrets 与 Base64 二进制内容](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)

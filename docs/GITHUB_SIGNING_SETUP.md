# GitHub 签名凭据保管与接入

更新：2026-10-29。本文是 miancak 的配置方案。**公开证书已核验并[登记](signing/README.md)；受保护环境、正式 Secrets、密钥库／备份验证和正式 APK 签名仍未完成。** 不把文档存在、Secret 名称存在或测试密钥通过当成正式签名已完成。

## 1. 存放位置

使用当前仓库自己的 `release-signing` environment，不新建存放私钥的 Git 仓库，也不使用可供其它项目复用的组织级 Secret。

| 内容 | 位置 | 说明 |
| --- | --- | --- |
| 公开证书 `miancak-release-cert.pem` | 已核验并提交至 [`docs/signing/`](signing/README.md) | 只含公钥；实际证书 DER 的 SHA-256 已登记，作为后续签名核验的预期值 |
| `certificate-info.json` 中的公开算法、有效期与指纹 | 已整理为[核验记录](signing/certificate-info.json) | 与实际证书一致；密钥库加密、密码和 alias 状态没有仅凭证书认定为已验证 |
| 加密的 `miancak-release.p12`，转为 Base64 | 环境 Secret：`MIANCAK_RELEASE_KEYSTORE_B64` | Base64 不是加密；原 PKCS12 仍须保留强密码保护。上传值必须符合 GitHub 每项 48 KB 的限制 |
| 密钥库密码 | 环境 Secret：`MIANCAK_RELEASE_STORE_PASSWORD` | 独立填写，不写 Git 文件、聊天、日志或产物；获准签名的 job 会同时获得密钥库与密码 |
| 恢复备份 | 维护者保管的至少两份加密副本 | GitHub Actions 凭据存储不代替独立备份和密码管理器 |

Secrets 由 GitHub 接收加密值，再在获准的工作流运行时解密注入。它们提供受控使用，不是不可导出的硬件密钥；审批后的 job 仍具有完整签名能力。

## 2. 维护者先配置环境保护

进入 [仓库环境设置](https://github.com/AuroraLilja8514/miancak/settings/environments)，创建 `release-signing`：

1. Deployment branches and tags 使用明确的 Selected branches and tags，初期只允许分支 `master`。不要允许 `refs/pull/*`、任意开发分支或不受保护的标签。
2. 添加维护者为 Required reviewer，使正式凭据在批准前不可获得。核对该账号／仓库实际支持并已启用此功能。
3. 单维护者如果还要批准自己触发的任务，不能开启 Prevent self-review 后再假定自己能批准；这也不能被描述为双人审查。有条件引入第二位审查者时再启用。
4. 在界面提供且实际适用时关闭管理员绕过。不要只凭“Protected branches only”字样推断已有分支保护；无分支保护时该选项可能允许所有分支，初期采用明确的 `master` 规则。
5. 核对这些保护生效后，才在该环境的 Environment secrets 区录入上表两项。不要误填成 Repository variables 或普通仓库级 Secrets。

当前 GitHub 连接未提供环境与 Secrets 管理功能时，由维护者在 GitHub 官方设置页录入。不要把管理 token 或密码贴到聊天以替代缺失的权限。若环境保护不可用，继续本机签名，直到有明确可用的受保护方案。

## 3. Windows 上准备上传值

进入生成器输出的私有 `MIANCAK-keys` 文件夹，运行：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.\miancak-release.p12'))) | Set-Content -LiteralPath '.\miancak-release.p12.b64' -Encoding ASCII -NoNewline
```

该命令只生成加密密钥库的文本编码，不解密私钥，不处理密码。将 `.p12.b64` 的内容填入 `MIANCAK_RELEASE_KEYSTORE_B64`，将原密码直接填入 `MIANCAK_RELEASE_STORE_PASSWORD`。临时编码文件也按凭据保管，使用后清理；不提交 Git、不作为 PR／Release 附件。密码继续保存在独立密码管理器中。

生成包采用 Windows 原生 PKCS12 导出，证书显示名称不等于 Java 内部 alias。文件只有一个私钥条目；后续 `apksigner` 省略 `--ks-key-alias` 自动选择，并核对实际证书指纹。不要把旧 `keytool` 示例中的 `miancak-release` 字符串强行当作此文件的内部 alias。出现多个条目时拒绝自动选择。

## 4. 后续签名工作流的边界

以下仍是待实现／待验证内容，不是当前可用工作流：

- 仅手动触发的受信工作流申请 `release-signing` 环境；PR、fork、普通构建和测试不接触正式凭据。给出人工审批不等于允许任意输入 ref／下载产物。
- 在无正式凭据的步骤先核对仓库、可信源码 SHA、构建 run／产物来源和适用验收结果，再校验 APK 包名 `org.miancak.keyboard`、版本、非 debuggable 状态与原生库。
- 有凭据的签名 job 使用已审查、固定版本的签名工具，仅处理上述确定 APK；不运行被签应用的 Gradle、依赖安装或仓库脚本。密码通过官方工具支持的环境变量／受控输入传递，不写入命令行明文。
- 不让正式证书签 debug 或测试执行器。签名前后校验公开证书指纹；保留 API 21 声明所需签名方案及 API 23／35 实际验证。
- 只发布最终签名 APK、文件哈希、证书指纹和必要汇总。私钥、密码、临时目录与未脱敏诊断不能进入日志、缓存或 artifact。
- 用一次性测试密钥先验证错误来源、错误证书指纹、缺失凭据及日志保护；正式身份的 APK、两版本覆盖升级和数据保留另行验收。

用途授权仅限 miancak。工作流与权限负责约束调用范围，证书本身不提供按仓库或包名的密码学限制。若获准工作流或管理账号被控制，Secrets 仍可能泄露；不能因仓库设为 private 就忽略这些边界。

## 5. 下一步与完成状态

已读取维护者明确提供的公开证书与信息文件，计算并[登记真实指纹](signing/README.md)。下一步由维护者验证密钥库与加密备份、配置并核对环境保护；先用一次性密钥验证签名流程，再安全注入正式凭据，完成正式 APK 签名及覆盖升级。当前连接不能代为管理环境与 Secrets，尚未获得配置完成的证据。现有 debug 到正式包的迁移限制仍按 [稳定签名方案](SIGNING_PLAN.md) 保留。

仓库的 `.gitignore` 防止常规误加入 `.p12`／`.pfx`／`.jks` 及生成目录；它不能阻止强制添加，也不能移除已经进入历史的秘密。出现泄露时停止签名并评估影响，不以删除当前文件声称历史已经清理。

## 官方依据

- [GitHub Secrets](https://docs.github.com/en/actions/concepts/security/secrets)
- [管理部署环境](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
- [部署保护规则与环境 Secrets](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
- [Secrets 大小与读取时机](https://docs.github.com/en/actions/reference/security/secrets)
- [使用 Secrets 与 Base64 二进制内容](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)

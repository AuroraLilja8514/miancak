# MIANCAK 签名凭据测试

本工作流只测试 MIANCAK 专用签名凭据能否使用，不构建、签署或发布 APK。维护者已表示 GitHub 凭据应已录入；当前连接不能管理或读取环境配置，也没有据此认定真实凭据测试已通过。

## 两种测试

| 任务 | 触发／凭据 | 检查 |
| --- | --- | --- |
| disposable credentials | PR、指定测试分支 push、master push 或手动触发；只有一次性测试密钥 | Linux JDK 17，Windows JDK 17 加固定版本原生 Windows 生成器；运行同一份内嵌 Java 检查器，覆盖成功与拒绝路径 |
| protected MIANCAK credential proof | 仅本仓库 master 的手动触发，勾选 test_formal_credentials，并通过 release-signing 环境保护；该步才注入正式 Secrets | PKCS12 在内存中解密，严格只有一个私钥条目，不依赖 alias，证书指纹等于登记值，私钥签名随机挑战并由公开证书验签 |

正式 job 使用权限为空的全新 GitHub 托管 runner，没有 checkout、Gradle、依赖安装、下载、第三方 action、缓存或 artifact 步骤。审核过的 Java 源码内嵌在工作流中，在注入凭据前编译；正式凭据只出现在运行检查器的那一步。

程序不写出密钥库、解密私钥、密码或签名证明，也不输出异常原文。它只接受程序内部的固定 MIANCAK 用途标签及随机挑战，不接受外部待签数据；成功后同时确认篡改的挑战不能通过验签。可清理的字节／字符数组在退出前置零，Java 字符串与运行时内存不作可证明擦除的承诺。runner 结束后销毁，不以此代替环境保护和独立加密备份。

预期证书 DER SHA-256 已固定为：

3883a84d506b029db9d7cb6ddb7953c96ad3aa81a38e3e19290b3d50c4f9a26b

该值同时与 [公开证书](../../docs/signing/miancak-release-cert.pem) 和 [公开记录](../../docs/signing/certificate-info.json) 核对。Windows 原生生成器的测试夹具固定到同仓库提交 fc72646d2a52d8b627cdbd108cd0c9efc17fe6e5；它只在无正式凭据的自测 job 中使用。

## 运行正式凭据测试

1. 先审查并将这个小 PR 合并到 master，无需为此合并应用 PR #32 或生成器 PR #33。
2. 在 Settings → Environments → release-signing 核对：Selected branches and tags 明确仅允许分支 master，Required reviewers 已生效，并按实际账号条件限制绕过。单维护者不要同时开启无法满足的 Prevent self-review。
3. 确认 Environment secrets 中有 MIANCAK_RELEASE_KEYSTORE_B64（加密 P12 的 Base64）与 MIANCAK_RELEASE_STORE_PASSWORD；不是 Repository variables。不要将秘密值粘贴到聊天、Issue 或 PR。
4. Actions → Signing credential checks → Run workflow，选择 master，勾选 test_formal_credentials，运行。
5. 无正式凭据的两个 self-test 先通过；正式 job 等待时由维护者 Review deployments → 批准 release-signing。环境审批不能由这份工作流自行替代。
6. 查看 protected MIANCAK credential proof 的实际结果。只看到两个 disposable credentials 为绿色、正式 job 被 skipped，不表示正式凭据可用。

本工作流使用 workflow_dispatch，文件先进入默认分支才可从正常手动入口运行。当前 GitHub 连接没有工作流 dispatch 或部署审批操作；维护者完成第 4、5 步后，可将不含秘密的运行链接提供给本会话继续核验。

## 结果解释

成功日志只有 PASS: PKCS12_PRIVATE_KEY_CERTIFICATE_PROOF。失败只输出固定代码：

| 代码 | 处理 |
| --- | --- |
| MISSING_KEYSTORE / MISSING_PASSWORD | 检查 Secret 名称、环境位置与授权；不要改为向 PR 公开凭据 |
| BASE64_INVALID | 检查上传的是 P12 的 Base64，而不是路径、PEM、JSON 或带引号的字符串 |
| EXPECTED_FINGERPRINT_INVALID | 这是固定工作流预期值错误，应审查工作流，不能随意替换已登记证书 |
| PKCS12_LOAD_FAILED | 核对密钥库、完整性及密码；不输出异常内容 |
| ENTRY_SELECTION_FAILED | 需要严格一个私钥条目；纯公钥文件或多个条目均不符合本流程 |
| CERTIFICATE_MISMATCH | 实际密钥库证书与已登记身份不同，停止，不能为使测试通过直接改指纹 |
| CERTIFICATE_POLICY_FAILED | 检查有效期、RSA 3072、SHA256withRSA、自签名及密钥用途 |
| PRIVATE_KEY_PROOF_FAILED | 无法用对应私钥完成签名与验签，需要在维护者可信环境核查 |
| INPUT_INVALID | 检查受支持的 JDK 17 与无额外参数的调用方式 |

成功仅证明这次运行能读取密码、加载匹配的私钥并完成签名证明，不证明备份已恢复、不证明所有环境设置正确，也不代替实际 release APK、两版本覆盖升级、数据保留或 ARM 真机验收。实际 APK 签名工作流仍需另行实现并验证产物来源及正式证书；正式私钥用途授权继续仅限 miancak。

## 官方依据

- https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow
- https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
- https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/KeyStore.html

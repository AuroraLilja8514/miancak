# 稳定签名方案与提前实施顺序

更新：2026-10-27。用户确认稳定签名可以提前；从 P4 拆出为 **P0**。本文记录安全方案，尚未生成正式私钥、配置签名 Secrets、签署 APK 或发布版本；密钥初始化和实际升级验收仍待执行。

## 1. 当前源码与目标

核对基线为 PR #32 的 `3a8cbd006a1f4c4fd787f30692a77fd49862adb2`：

- [构建配置](../build.gradle.kts) 的 release 包名为 `org.miancak.keyboard`；debug 使用 `.debug` 后缀。当前声明 `minSdk = 21`，实际完整场景验收已有 API 23／35 记录，不能据签名兼容性验证宣称 API 21 功能已经实测。
- 已有 `RELEASE_KEYSTORE` 等环境变量配置，但 [普通 CI](../.github/workflows/ci.yml) 不注入正式凭据，上传未签名 release APK。没有正式签名身份已初始化的记录。
- debug 缺省生成临时 keystore；已交付 debug APK 不能自动转换成正式包的覆盖升级链。

推荐先采用 **CI 构建未签名 APK，维护者在可信电脑完成正式签名**。这使私钥先保持在维护者控制的设备和备份中，不等待全部功能结束。今后确需自动化时，再做第 4 节的独立受保护签名流程。

| 用途 | 应用身份 | 签名方式 |
| --- | --- | --- |
| PR／普通 CI 调试 | `org.miancak.keyboard.debug` | 保持临时测试身份；不使用正式私钥 |
| 长期试用与后续正式分发 | `org.miancak.keyboard` | 使用维护者持有的同一正式私钥／证书；可以先交付明确标注的预发布版 |

APK 更新需要保持应用身份与兼容的签名身份；正常迭代保持同一私钥／证书，并递增 `versionCode`。不能用重新生成同名 alias 的方式找回原私钥。支持旧 Android 的前提下，暂不依赖密钥轮换来解决签名连续性。

## 2. 密钥生成与保管

由维护者在可信、已更新的个人电脑上，在仓库和聊天附件之外创建专用于 MIANCAK 的 keystore。可使用 Android Studio 或 JDK `keytool`，建议 RSA 3072 位、约 30 年有效期；Android 官方建议证书有效期至少 25 年。证书主体只写项目所需信息，无需填写个人住址。

示例只说明命令形式；`/YOUR_PRIVATE_DIRECTORY/` 必须换成维护者自己的仓库外私有目录，**本轮没有执行此命令**：

```sh
keytool -genkeypair -alias miancak-release -keyalg RSA -keysize 3072 -validity 10950 -storetype PKCS12 -dname "CN=MIANCAK" -keystore /YOUR_PRIVATE_DIRECTORY/miancak-release.p12
```

让工具交互式询问密码；用密码管理器生成并保存高强度随机密码。不在命令参数、Gradle 文件、shell 历史或聊天里填写真实密码。PKCS12 的密钥密码按工具兼容要求与库密码保持一致，不以两个密码字段作为独立防护的假设。

创建后完成：

1. 保留至少两份加密备份，放在不同介质／位置；密码与未解密的私钥备份分开管理，不仅依赖 CI 或单一电脑。
2. 在另一安全位置做一次备份恢复试验，核对证书 SHA-256 一致，再清理试验副本。
3. 导出公开证书，登记其 SHA-256、alias、有效期和用途。公开证书／指纹可入库；keystore、私钥与密码始终不入库、不进日志／产物／聊天。Base64 只是编码，不能代替加密。
4. 明确私钥持有人和备份恢复办法。丢失自持私钥不能从 APK、公开证书或 alias 重建；泄露后也不能仅靠修改 keystore 密码撤销对方已经获得的私钥。

## 3. 第一阶段：本机签名

构建和签名分开进行：

1. 只采用经审查、完成适用验证的确切源码提交。核对仓库、提交 SHA、workflow run 和构建产物来源，再记录未签名 APK 的 SHA-256；仅比对同一来源给出的哈希不等于来源已可信。
2. 普通 CI 继续无正式密钥地构建 release。签名电脑使用已核验版本的 Android SDK Build Tools；只对 APK 做检查和签名，不在私钥可访问的环境运行项目 Gradle、依赖安装或下载的仓库脚本。
3. 核对包名、递增的 `versionCode`、非 debuggable 状态、三 ABI、词包与已有 16 KiB 对齐；需要调整 APK 时先完成调整，最后才签名。使用官方 `apksigner`，由工具交互输入密码，或使用其受控密码文件／环境变量方式；不在命令行内明文传密码。
4. 按 APK 声明的最低版本签名和验证。当前 `minSdk = 21`，必须保留旧系统需要的 v1 签名，并提供现代系统的签名方案；不能只输出 v2／v3。签名后执行 `apksigner verify --verbose --print-certs --min-sdk-version 21`，将证书 SHA-256 与已登记值比较。之后不再改动 APK。
5. 发布物清单记录源码 SHA、版本、包名、APK SHA-256 和证书 SHA-256。APK 文件哈希随版本变化，证书指纹保持预期值；只分发最终签名后且经过验收的那一份文件。

签名本身不等于授权上架或公开发布。先交付长期试用预发布包，再逐步完成 P4 的完整交付验收。

## 4. 第二阶段：需要时接入 GitHub 自动签名

这部分是后续设计，**当前工作流尚未实现**。在私钥备份和本机签名通过后再决定是否采用，最初可以继续本机签名。

- 使用独立 `release-signing` environment；显式限制可运行的可信分支／标签，保留维护者手动批准，并核对实际账户／仓库是否支持及已配置这些限制。只创建一个同名 environment 不代表已有保护。
- 正式凭据只授予签名 job；PR、fork、普通构建与测试 job 均不可获得。拒绝从 `pull_request_target` 执行 PR 代码，也不为任意 `workflow_run` 或用户可控 ref／产物名称自动签名。
- 构建 job 无正式密钥；签名 job 只接收经过来源核验、绑定确切可信提交和 run 的 APK。拿到私钥后不 checkout／执行被签项目代码，不运行 Gradle、包管理器或仓库脚本。
- Secrets 仅是受控存储，获准 job 仍可能读取并泄露它们。对能接触密钥的步骤保持最少权限，审核依赖；使用可核验的 workflow 依赖锁定或固定完整提交 SHA，不依赖可变标签作为唯一信任依据。
- 使用一次性托管 runner。私钥与密码仅在所需步骤短暂出现，关闭命令回显，不缓存／上传含密钥的目录，结束时清理临时文件；清理不被描述为对磁盘的可证明安全擦除。
- 签名 job 只读产物；确需上传 GitHub Release 时由另一个最小权限 job 获得 `contents: write`。发布范围、签名范围及人工批准分别明确，不让发布 token 扩大私钥可访问面。
- 用一次性测试密钥先验证流程、错误密钥／错误指纹拒绝、错误来源拒绝和日志脱敏，确认后才由维护者通过 GitHub 官方 Secrets 设置页面注入正式凭据。真实私钥与密码不经过聊天。

个人项目现阶段采用本机签名是本方案的判断，并非声称 GitHub Secrets 无法安全使用。后续若迁入 HSM／KMS，另行验证 Android 签名工具集成；不把硬件方案设为当前 P0 的前置条件。

## 5. 旧包、数据与验收边界

现有 debug 与正式 release 包名不同，会并存，不共享应用私有数据。若给同一 debug 包名更换证书，而原私钥不可得，也不能直接覆盖安装。先确认旧包的包名／证书和可用数据导出范围，再制定迁移；不得把卸载、清数据或强行重签名当作无损升级办法。当前学习数据有独立存储与备份限制，不能仅凭键位导出就承诺学习也能迁移。

P0 完成标准：

- 维护者确认密钥与备份已建立；公开证书指纹登记完成，没有私钥泄露到代码、日志或产物。
- 有一份使用登记身份签名且验证通过的实际 release APK，包名、版本、来源和签名报告均可核对。
- 完成两个递增版本在 API 23／35 上的真实覆盖安装，核对键位、偏好、词表和学习保留；测试执行器不需要获得正式私钥，也不给测试 APK 签正式身份。仅使用临时密钥的升级夹具继续保留，但不能替代正式身份的结果。
- 从旧 debug 切换到正式包的迁移能力与限制单独说明；不能把它记成同包名覆盖升级成功。
- P4 继续跟踪后续真实版本升级、恢复／回滚边界、ARM 真机及目标编辑器验证；签名 P0 不将这些标记为完成。

## 6. 官方依据

- [Android：应用签名与密钥保管](https://developer.android.com/studio/publish/app-signing)
- [Android：apksigner](https://developer.android.com/tools/apksigner)
- [GitHub：部署环境、分支限制与环境 Secrets](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
- [GitHub：安全使用 Actions](https://docs.github.com/en/actions/reference/security/secure-use)

当前以直接分发 APK 为基线。将来接入 Google Play 时，另行区分 app signing key 与 upload key，确认多渠道最终签名身份一致；不把 upload key 可重置误认为自持 APK 签名私钥可以找回。

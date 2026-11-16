# 正式签名凭据验证记录

验证日期：2026-10-31。维护者明确取消人工审批并授权继续测试。[PR #34](https://github.com/AuroraLilja8514/miancak/pull/34) 已合并；[master 上的运行 41217141373](https://github.com/AuroraLilja8514/miancak/actions/runs/41217141373) 以 push 事件运行并整体成功，确切源码为 `f3f801f9da2b1bded164e33b0c90f21170956e6c`。

## 已通过

| 检查 | 实际结果 |
| --- | --- |
| 一次性密钥自测 | Linux 29／29、Windows 31／31；覆盖原生 Windows PBES2 输出、错误密码／指纹、缺失或损坏输入、多条目、仅证书、过期及 RSA 位数，以及正式触发条件的 9 个正反例 |
| 实际正式凭据 job | `protected MIANCAK credential proof`（job 124584108033）成功；没有被 skipped，也没有只把临时密钥自测当成正式结果 |
| 密钥库读取／条目 | 两个固定名称的凭据可由该任务读取；内存加载 PKCS12，严格一个私钥条目，不假定 Windows 内部 alias |
| 实际证书身份 | 证书 DER SHA-256 与已登记公开证书一致；RSA 3072、SHA256withRSA、自签名、有效期和用途检查通过 |
| 私钥可用性 | 对程序内部 MIANCAK 用途标签和随机挑战完成签名／验签；篡改挑战被拒绝，证明结果不输出 |
| 收尾 | 清理步骤成功；本运行 artifact 列表为空 |

实际成功代码为：

`PASS: PKCS12_PRIVATE_KEY_CERTIFICATE_PROOF`

证书 DER SHA-256：

`3883a84d506b029db9d7cb6ddb7953c96ad3aa81a38e3e19290b3d50c4f9a26b`

公开身份仍对应 `CN=MIANCAK`、有效期至 2056-10-29。用途授权仅限 miancak；证书用途标签不提供按仓库／包名的密码学访问限制。

## 工作流与范围

正式 job 的工作流和源码均来自 master；签名配置相关的 master push 自动触发，master 手动触发时仍需显式勾选。PR、开发分支、其它仓库、错误工作流来源与分支删除不能进入正式 job；两组无正式凭据的自测均成功后才运行它。

正式 job 无 checkout、Gradle、依赖安装、第三方 action、缓存、artifact 或仓库 token 权限；审核过的 Java 检查器在注入正式凭据前编译，凭据只在运行该检查器的一步注入。密钥库在内存中读取，不向工作区写出私钥或密码。源码及操作说明见 [已合并工作流](https://github.com/AuroraLilja8514/miancak/blob/f3f801f9da2b1bded164e33b0c90f21170956e6c/.github/workflows/signing-credentials.yml) 和[使用说明](https://github.com/AuroraLilja8514/miancak/blob/f3f801f9da2b1bded164e33b0c90f21170956e6c/tools/signing/README.md)。

合并前仓库实际要求的 generated、unit、build-debug 均成功，签名自测两组均成功；没有改应用代码或原有应用 CI，也没有通过取消检查或删断言完成合并。旧应用 CI 的 API 35 升级界面失败记录保留，其结果不被凭据验证覆盖。

当前连接不能独立读取环境／Secrets 管理设置，无法凭本结果断言 Secret 登记层级或所有环境规则均已正确配置；这里确认的是上述实际任务能够读取凭据并完成匹配私钥的证明。维护者取消人工审批的决定已记录，工作流不新增审批前置，GitHub 仍执行其余现有环境规则。

## 仍未完成

- 本机／独立加密备份的恢复验证。
- 实际 release APK 的来源校验、正式签名、签名方案与包信息检查。
- 两个递增版本使用正式身份的覆盖升级和数据保留验收，以及旧 debug 迁移边界。
- ARM 真机、完整恢复／回滚与发布。

本次没有生成或签署 APK。P0 已完成公开身份登记和正式凭据可用性验证，尚未整体完成；后续继续按 [稳定签名方案](SIGNING_PLAN.md) 与 [GitHub 配置](GITHUB_SIGNING_SETUP.md) 推进。

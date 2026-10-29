# MIANCAK 公开签名证书

登记日期：2026-10-29。维护者明确提供了公开 PEM 证书和生成器的 `certificate-info.json`。已核验两者一致，并将此证书登记为本项目后续 release 签名的预期身份。这里只保存公开证书和经核验的公开字段，不保存私钥、密钥库或密码。

| 字段 | 已核验值 |
| --- | --- |
| 证书文件 | [miancak-release-cert.pem](miancak-release-cert.pem) |
| 机器可读记录 | [certificate-info.json](certificate-info.json) |
| 适用应用 | `org.miancak.keyboard`，这是本项目的用途登记，不是 X.509 证书内的包名限制 |
| 主体／颁发者 | `CN=MIANCAK`，自签名 |
| 公钥／证书签名 | RSA 3072／SHA256withRSA |
| 有效期（UTC） | 2026-10-29 03:12:59 至 2056-10-29 03:12:59 |
| 证书序列号（十六进制） | `A960B4C02052F7E6` |

证书 DER 的 SHA-256：

```text
3883a84d506b029db9d7cb6ddb7953c96ad3aa81a38e3e19290b3d50c4f9a26b
```

这是证书指纹，不是 PEM 文本文件或 APK 文件的 SHA-256。后续签名必须用实际签名证书与此值比较；登记文档本身没有增加可执行的签名门禁，也不表示签名工作流已完成。

## 核验记录

- 解析文件确认它只含一个公开 X.509 证书，不含私钥。
- 独立计算 DER SHA-256，与维护者提供的 JSON 完全一致；核对主体、颁发者、RSA 位数、签名算法及两端有效时间。
- 使用证书公钥验证其自身签名；核对 `CA=false` 与 `digitalSignature` 用途。OpenSSL 再次独立读取的证书指纹、主体和有效期一致。
- 本目录的 JSON 是经核验字段的整理记录，不是生成器原始 JSON 的原样副本。未将原始 JSON 的 `privateKeyEncryption`、`keystoreFormat`、`passwordSaved` 或 alias 信息作为已验证事实：仅凭公开证书无法验证这些密钥库状态。Windows 原生生成包的实际签名条目仍按单私钥选择方式另行检查。

## 尚待完成

公开证书核验不证明私钥可用、密码可解密、备份可恢复，也不证明私钥只能签本项目。这些仍需在维护者控制的签名环境中验证；用途授权仅限 miancak。

当前尚无已核验的 GitHub 环境保护／正式 Secrets 配置、正式身份签名 APK 或正式身份的两版本覆盖升级结果。不能把现有调试包／临时测试密钥的结果改记为正式签名通过。

下一步按 [GitHub 签名配置](../GITHUB_SIGNING_SETUP.md) 保护环境并安全录入凭据，按 [稳定签名方案](../SIGNING_PLAN.md) 完成密钥库／备份验证、实际 APK 签名和升级验收。公开证书丢失可从备份或 APK 重新提取，但它不能用于恢复丢失的私钥。

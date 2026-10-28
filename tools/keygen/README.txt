MIANCAK 签名证书生成包 1.0.0 — Windows 64 位

使用方法
1. 将整个 ZIP 解压到本地文件夹。不要直接在压缩包预览中运行。
2. 双击 Start-Keygen.cmd。无需 Java、Python、Android Studio 或管理员权限。
3. 在中文窗口中选择保存位置，输入两遍密码，或点击生成随机密码。
4. 先将密码保存到密码管理器，再勾选“我已单独保存密码”。
5. 点击“生成密钥与证书”。完成后在所选位置找到 MIANCAK-keys 文件夹。

系统要求
已更新的 Windows 10/11，Windows PowerShell 5.1，以及系统自带的 .NET Framework 4.7.2 以上。
生成使用 Windows 自带密码学 API 和 PKI 模块。包内没有 Java 运行时或第三方二进制。
若系统组件缺失或不支持所需加密，程序会提示失败，不会降级使用较弱加密。
启动器的 ExecutionPolicy Bypass 仅作用于该次 PowerShell 进程，不修改电脑的持久执行策略。
单位电脑的强制安全策略仍可能阻止脚本；此时遵循管理员策略，不要关闭杀毒软件。

生成内容
miancak-release.p12       含私钥和证书的加密 PKCS12 密钥库，请保密并备份。
miancak-release-cert.pem  不含私钥的公开证书，可以提供给项目核验。
certificate-info.json    用途、alias、算法、有效期与公开证书 SHA-256 指纹。
SHA256SUMS.txt            上述生成文件的 SHA-256 校验值。
README-output.txt        简要保管说明。

固定参数
应用用途：org.miancak.keyboard 的正式包签名。
alias：miancak-release；证书主体：CN=MIANCAK。
RSA 3072 位，SHA256withRSA 自签名证书，约 30 年有效期。
私钥文件采用 AES-256 / SHA-256 的 PKCS12 密码保护。

密码与密钥保管
密码为 20–128 位英文字母、数字和符号，不含空格；建议使用随机密码或密码管理器。
程序不会保存密码，不读取剪贴板，不联网，不自动上传，也不安装证书到 Windows 证书库。
生成目录只向当前 Windows 账户和 SYSTEM 授权；这不能防止管理员或已控制电脑的程序访问。
生成后请将加密密钥库备份到两处不同介质，并把密码单独保存。
若将加密 .p12 放入项目资料空间，不要把明文密码与之一起上传。
“.p12 加密”不代表可以把密码公开；用途文字也无法从密码学上限制密钥只签某个应用。
后续使用前仍需核对应用包名、源码、APK 来源和证书指纹。

避免丢失签名身份
一个长期签名身份只需生成一次。以后每版继续用原来的文件和密码。
如果目标位置已有 MIANCAK-keys，程序会拒绝生成，不覆盖任何已有密钥。
不要为了解决签名冲突重新生成密钥；同名文件或同名 alias 也会是另一份身份。

可核对的源代码
Miancak-Keygen.ps1 为中文界面，Miancak-Keygen.Core.ps1 为生成与自检逻辑。
源代码与许可证包含在本包内；SOURCEINFO.json 记录源码提交及构建来源。
TEST-REPORT.json 记录交付前的 Windows 原生与 Java 17 互操作测试。
发布包没有附带任何已经生成的私钥或演示密钥。

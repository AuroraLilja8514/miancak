# 模拟器环境与失败判定

测试运行在独立 GitHub runner。每个首装任务使用新建、无快照恢复且带 wipe-data 的 API 23／35 x86_64 guest；升级、学习与重启使用各自独立任务。ARM ABI 构建检查与 ARM 真机运行分开报告。

## 固定的配置

| Guest | Emulator 构建 | GPU | 状态 |
| --- | --- | --- | --- |
| Android 6 / API 23 | 36.1.9 / 13823996 | swiftshader_indirect | CI 40229771805 三次环境 reboot、词表及旧版升级的实际 reboot 均通过 |
| Android 15 / API 35 | 37.2.12.0 / 16428233 | swangle | 同轮交互及旧版升级通过；首装初始化与词表 UI 修正后须重跑 |

两种配置都禁用 Vulkan、音频、启动动画和快照。版本由 CI 顶层 `MIANCAK_EMULATOR_BUILD_API23/API35` 指定，五种 APK 测试任务共用。新增独立 API 23 三次重启环境门禁；与原有 17 项合计 18 项检查。门禁不安装应用、不能代替 APK 断言，应用任务各自仍使用全新 guest。构建号固定后不会随 SDK 默认版本悄悄改变；以后升级同样须跑首次安装与重启。

依据：[官方版本说明](https://developer.android.com/studio/releases/emulator)、[官方归档](https://developer.android.com/studio/emulator_archive)、[Debian 官方安装包的 36.1.9／13823996 映射](https://packages.debian.org/sid/google-android-emulator-installer)、[runner 的 emulator-build 参数](https://github.com/ReactiveCircus/android-emulator-runner#configurations)。没有找到可直接确认本项目崩溃的厂商修复声明；上述结果仅是本项目具体运行的实测，不将更换版本本身或一次成功推广为所有环境的保证。

## 已取得的具体证据

CI 40225750332 的 API 23 词表任务完成 7 项原生、导入 UI 和实际覆盖安装，随后 guest reboot 导致宿主 Emulator 37.2.12.0 输出 `DisplaySurfaceGlContextHelper` 上下文冲突／恢复失败，并从 ADB 消失。该条重启未通过，不能用此前通过部分或另一任务代替。API 35 早先一次启动则有 Pixel Launcher 无响应窗口；新 guest 同 APK 重试已通过。这些失败保留在原运行记录。

重试只用于有明确宿主／系统故障证据的单项，记录原失败、重试和 APK 哈希。应用断言失败应修正或说明原因后重新测试，不通过增加重试次数或删断言取得绿灯。APK 驱动有整体及 ADB／instrumentation 局部时限；失败诊断也受限，详见 [SCRIPT_TIMEOUTS.md](SCRIPT_TIMEOUTS.md)。

重启门禁使用真正的 `adb reboot`，每轮同时要求 `sys.boot_completed=1` 和 `/proc/sys/kernel/random/boot_id` 改变，不恢复快照、不复制 app data。仅允许名为 miancak-reboot-smoke 的 emulator，拒绝物理设备或其他 AVD。每个命令、每轮开机和整次执行均有时限，逐轮 JSON 即使失败也保存，避免把设备尚未真正重启误作成功。

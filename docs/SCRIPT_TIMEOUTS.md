# 脚本执行时限

所有项目 Python、Shell、Gradle wrapper 入口均接受 `--timeout-seconds N`，也可设置 `MIANCAK_SCRIPT_TIMEOUT_SECONDS`；默认 3600 秒。必须为有限正数，不能用 0 关闭。命令行值优先于环境变量。嵌套调用继承外层剩余时间，不能重新获得一小时。GitHub Actions 的任务时限仍可更早结束执行。

```sh
python3 scripts/prepare_rime.py --timeout-seconds 1800
bash scripts/emulator_tests.sh --timeout-seconds 3600
./gradlew --timeout-seconds 3600 test assembleDebug
MIANCAK_SCRIPT_TIMEOUT_SECONDS=1800 python3 scripts/check_native_apk.py app.apk
python3 scripts/run_with_timeout.py --timeout-seconds 60 -- command arg
```

Windows `gradlew.bat` 也通过 Python 3 执行 watchdog；本轮未在 Windows 验证。Shell APK 驱动需要 GNU `timeout`。`scripts/adb_timeout.sh` 是供驱动加载的函数库，不单独执行测试。

到期打印原因、返回 124，先 TERM，再在默认 3 秒宽限后 KILL。取消信号向子进程转发，保留 130/143 状态。Linux 同时追踪子孙进程，包括另建会话的子进程；PID 起始时间用于避免误杀重用 PID。嵌套 watchdog 与 ADB watchdog 保持所属进程组。日志收集也计入总时限，不能在退出 trap 中无限等待。超时或失败不会记为测试通过，已有证据仍由 CI 的 always 上传步骤保留。

阶段时限可由环境变量调整：

| 变量 | 默认秒数 | 用途 |
| --- | ---: | --- |
| `MIANCAK_ADB_TIMEOUT_SECONDS` | 30 | 普通 ADB，包括收集诊断 |
| `MIANCAK_ADB_INSTALL_TIMEOUT_SECONDS` | 180 | 安装 APK |
| `MIANCAK_ADB_TRANSFER_TIMEOUT_SECONDS` | 120 | push/pull |
| `MIANCAK_ADB_BOOT_TIMEOUT_SECONDS` | 120 | wait-for-device |
| `MIANCAK_INSTRUMENT_TIMEOUT_SECONDS` | 900 | 一组真实 APK instrumentation |
| `MIANCAK_FETCH_TIMEOUT_SECONDS` | 600 | 每次锁定源码 Git 操作 |
| `MIANCAK_TIMEOUT_GRACE_SECONDS` | 3 | 终止宽限，最大 10 秒 |

重启及少量诊断还有更短的局部上限；不会延长外层时限。网络下载另有 socket 时限。APK 的单次动作／候选等待和性能门槛仍由测试自身限定，不能通过提高脚本预算绕过断言。

`python3 scripts/test_script_timeouts.py --timeout-seconds 120` 实际运行挂起进程，覆盖退出码／输出、参数与环境变量、非法时限、嵌套预算、取消信号，以及真实 APK/Gradle 入口。Linux CI 另验证普通和独立会话的拒绝 TERM 子进程均被清理。无法访问 `/proc` 的环境会明确跳过该项，不能当作已通过。

APK 驱动会输出当前阶段、单组时限及限定标签的实时测试日志；可用 `MIANCAK_PROGRESS=0` 静默。日志读取同样继承剩余预算，退出时结束。Gradle wrapper 保留原有符号链接解析。取消用例分别检查子进程实际收到 INT/TERM，避免只检查外层状态。

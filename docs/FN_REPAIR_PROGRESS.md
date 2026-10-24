# Fn 修复执行记录

更新：2026-10-25。用户已授权开始开发。执行依据为 [布局纠错计划](LAYOUT_REPAIR_PLAN.md) 的 R0–R7；本记录区分源码基准、实际实现和 APK 验收。

## 本次已完成

- 从固定上游提交 `c87d9b8fbd501197286eaafeae88278dfbdd56b7` 提取 8 份原始来源：QWERTY、主底排、希腊／数学、数字及横屏数字、emoji／剪贴板底排、Fn 映射。保留原文件，清单记录来源提交、许可证及逐文件 SHA-256。没有从当前错误实现推导期望值。
- 提取 1,233 个声明槽位（包含空位与 `loc` 隐藏位），保存为 [CSV](UPSTREAM_KEY_SLOTS.csv)。此数值是源码槽位数，不是功能数；不会把空位当作新功能。
- 增加离线核对工具及 4 项完整性／坐标检查，检查快照损坏、正向与斜向、别名、隐藏位、原始数字位置及 Fn 入口。已本地通过。
- 增加 Java 回归用例，直接检查 Fn 求值得到的 Keyevent 类型与 F1–F12 键码，以及导航、编辑、希腊／数学替代字形。它们仅检查现存映射，**不能证明 UI 入口已经修复**。云端 CI 的 debug/release 单元测试已通过：97 个不同用例、合计 194 次执行，零失败；其中新增的 3 个用例分别在两个构建类型通过。已下载 XML 报告复核，不把源代码编译等同于 APK 交互验收。

## 上游功能与改动边界

| 功能 | 上游入口／映射 | 后续验收 |
| --- | --- | --- |
| F1–F10 | QWERTY 第 1 行各键右上 `1…0` → `f1…f10` | 在中／英文自己的 Fn 变体逐一触控，接收编辑器验证 down/up 的实际键码，正文不出现 F1 标签 |
| F11／F12 | 附加键占位符 → `f11`／`f12` | 开启相应附加键、查明配置后位置及真实事件，不声称默认 QWERTY 已显示它们 |
| Home／End／PageUp／PageDown | 上游 Fn＋四方向 | 按最新要求保留四正向为光标移动；在明确替代槽位保留上述导航事件，并记录位置差分 |
| Insert／制表 | Fn＋Esc／Tab | 明确隐藏位可见条件；分别验证 Insert 事件和实际制表文字 |
| NBSP | Fn＋Space／空格字符 | 固定空格中心仍遵循空格／组词确认；NBSP 必须另有明确可达位置，不可直接覆盖中心 |
| 重做／纯文本粘贴 | Fn＋Undo／Paste | 不丢失编辑动作类型；验证正文和编辑器状态 |
| 货币、引号、数学、希腊替代字形 | `srcs/compose/fn.json` 原始映射 | 对照快照逐项核查普通／Shift／Fn 求值和实际输出；分类符号面板不能替代这些变体 |

CSV 记录的是**声明坐标**，不包含配置插入、`loc` 显示条件、响应式重排、Shift/Fn 动态求值和触控可达性。相关运行时矩阵尚未完成，因此 **R0 整体仍在进行中，R1–R7 未完成**。Compose 不列入修复或专门验收；快照中原有声明仅作为来源原样保留。

## 环境与阻塞

当前工作环境已取得仓库、上游提交和 cdict 子模块，并安装 JDK、Gradle、Android SDK 36、构建工具、NDK、CMake、模拟器及 API 35 系统镜像。Java 启动的库路径和临时目录问题已解决，全新 AVD 已创建。

本地 Gradle wrapper 首次下载发行包时，Java 网络连接报 `java.net.SocketException: Network is unreachable`；改用已下载的 Gradle 并配置代理后，构建仍阻塞于 Android Gradle 插件 `com.android.application:8.13.2` 解析失败。没有为绕过环境问题擅自修改项目依赖版本。

本地环境无 `/dev/kvm`，常规模拟器启动器崩溃（exit 139）；改用 headless QEMU、软件模拟后可以进入启动阶段，但尚未确认 guest 完成启动。ADB 即使以 nodaemon server 方式启动，仍因无法读取 `/proc/self/exe` 而失败。当前没有可用的本地 APK 交互通道。

独立分支已提交 [草稿 PR #32](https://github.com/AuroraLilja8514/miancak/pull/32)，并启动[本次 CI](https://github.com/AuroraLilja8514/miancak/actions/runs/40139542771)。`generated` 与 `unit` 已成功；APK 构建和后续模拟器矩阵在该记录更新时仍在进行，不声称其通过。没有交付修复后的 APK，也没有执行 ARM 真机验收。现有 CI 跑的是本次基准分支，生产键盘行为仍为 0.3.0；即使这些历史交互用例通过，也不能据此宣称错误的 Fn 路由已修复。

后续先恢复可构建／可运行 APK 的环境，完成运行时基准与接收编辑器的事件记录，再进入布局状态和 Fn 变体实现。不得跳过真实 APK 阶段验收，把未经验证的功能改动一次性堆到 R7。

## 可重复检查

```sh
python3 scripts/audit_fn_baseline.py
python3 scripts/test_fn_baseline.py
git diff --check
```

`--write` 只从通过哈希核对的快照重新生成 CSV，不会读取或联网更新上游来源。替换上游基准需要单独审查来源与清单差分。

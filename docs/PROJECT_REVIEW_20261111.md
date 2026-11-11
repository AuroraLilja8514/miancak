# 项目现状与实施清单（2026-11-11）

本次审查以 `master` 的 `f3f801f9` 和未合并的 [PR #32](https://github.com/AuroraLilja8514/miancak/pull/32) 的 `05922a8e` 为基线。后者是 0.3.2／versionCode 5 的发布候选，不是已经发布的版本。沿用 [后续开发计划](NEXT_DEVELOPMENT_PLAN.md) 的已确认范围与先发布、后开发顺序；此处将代码落点、依赖和验收拆开，未实施 N1–N4。

## 当前阻塞

- PR #32 仍是草稿。完整 [CI 42804006368](https://github.com/AuroraLilja8514/miancak/actions/runs/42804006368) 首轮 16/18；第二次运行中 API 23 词表检查通过，独立 API 23 三次真实重启检查仍失败，其余 17 项成功。原失败和重跑均保留。
- 真实 release APK 的一次性签名[彩排 42804006590](https://github.com/AuroraLilja8514/miancak/actions/runs/42804006590) 首次 API 23 失败，第二次两种 API 均通过。这是测试密钥彩排，不是正式 APK 发布；仓库当时没有 GitHub Release。
- 独立重启 guest 未安装 MIANCAK。两次失败的 artifact 显示内核 boot ID 已改变，失败后 ADB 已重新连接，但 `sys.boot_completed` 仍为空；`mBooting=true`、`mBooted=false`、`mDisplayEnabled=false`，焦点记录为 `com.android.sdksetup/.DefaultActivity`。早先的 `device offline` 是保留下来的 ADB 错误，不足以描述最终状态。不能据此断定应用无问题，也不能断定启动卡住的根因。
- 2026-11-11 的独立[镜像／启动动画对照](https://github.com/AuroraLilja8514/miancak/actions/runs/42955767116)中，现用 Google API 镜像、开启启动动画的 Google API 镜像、AOSP 镜像各有一次完成三次 reboot 和一次启动未完成。该轮成功的三项随后因诊断工作流的多行 shell 编排错误被标为失败；检查本身的日志与 artifact 保留。修正编排后的[第二轮](https://github.com/AuroraLilja8514/miancak/actions/runs/42956088856)中六个独立 guest 均未完成三次 reboot。三种配置均非已证实的修复；这些实验不更改 PR #32 的验收结果。

独立环境检查只能描述运行它的 guest；它不代替词表、升级和正式 APK 测试各自的真实重启。保留 180 秒的每轮上限、变化的 boot ID 和 `boot_completed=1`，不以延长等待、删检查或一次偶然通过宣布根因已修复。

## 实施顺序

| 顺序 | 工作与代码落点 | 完成条件 |
| --- | --- | --- |
| 0 | 排查 API 23 启动，完成 PR #32 的现有发布范围。`scripts/emulator_reboot_smoke.py` 保存最后成功观测、最后一次轮询错误和历史 ADB 错误；比较新 guest 的窗口、活动和系统日志。 | 保留原失败，明确区分系统与应用证据。任何环境配置改动须重复三次真实重启并重新跑相关 APK 场景；随后以同一源码完成完整 CI、一次性签名彩排、审查和合并。master 成功构建后按 `RELEASING.md` 签名、安装验收并生成 Release，记录 APK、源码和证书。未完成前不声称正式发布。 |
| 1 / N1 | 默认不分体：同步 `Config.get_split_layout()` 的缺省值和 `res/xml/settings.xml`；核对 `MiancakPreferences`、设置优先／IME 优先、恢复默认和 `Config.forPreview()`。 | 未保存字段采用新默认；已有 `split_layout` 值保留，包括无法识别是旧默认自动写入还是用户主动选择的 `wide`。新安装、恢复默认、宽窄屏、横竖屏及折叠状态不自动分体；手动开启／关闭和升级后明确设置保留。若要改变已有的模糊值，先明确迁移规则。 |
| 2 / N2 | Sup/Sub：修改 `prefs/ExtraKeysPreference.default_checked()`，并检查 `LayoutModifier.prepare_layout()`、`KeyboardData.addExtraKeys()`、编辑器覆盖以及普通／Shift／配对 Fn 的实际槽位。 | 新安装和未保存对应字段的配置默认可见；明确关闭仍保留。验证中英文主键盘实际输出及空位冲突，检查符号／emoji／剪贴板不被额外注入；覆盖自定义布局、预览、旋转与升级。仅勾选项或预览显示不足以验收。 |
| 3 / N3 | 设置对齐：先在 `SettingsActivity` 的原生 `PreferenceActivity` 与 `prefs/` 自定义控件中复现并定位 inset／图标占位或布局差异，再做局部修正。 | API 23/35、中文／回退语言、横竖屏及大字体下，同级标题／摘要起点一致，分组层次保留，文本不裁切且点击区域不退化；保留实际 APK 截图和交互结果。 |
| 4 / N4 | 产品身份：盘点 `res/values*/`、启动页与帮助 Intent、`fastlane/metadata/`、README 和现行文档。已知 `launcher_repo_url` 及英／简中商店标题仍指向或写成上游。 | 应用内源码链接到本仓库，反馈入口到本仓库 Issues；实际点击验证目标，中英和其它翻译／回退一致。保留上游版权、许可、来源和历史；不改 applicationId、Java 包名或用户数据目录。 |
| 5 | 中文质量与可持续交付：基于真实词句、多编辑器及设备反馈建立可复现用例；按实际问题分别评估词包、Rime 参数和个人学习。 | 回归字面标点／组词顺序、过期候选、Fn 实际 KeyEvent、符号分页、编辑器、词表导入／部署／回滚、学习和敏感字段。下一正式版递增版本，使用同证书的两个实际版本做覆盖升级；另完成可获得的 ARM 真机、API 21 边界及独立密钥备份恢复验证，分别记录未覆盖项。 |

每项应用改动使用短分支和 Draft PR，相关单元／APK 验证与迁移同步完成；N1–N4 可以按小范围拆分，但不得绕过 0.3.2 发布顺序。新版本的构建输入发生改变时递增版本并更新发布说明。

## 不变的技术边界

- 延续 Unexpected Keyboard 的触摸、方向、Shift 和布局基础；`SemanticKeyAdapter`／`InputRouter` 将字面、引擎和控制动作区分开，`InputController` 保持串行 Rime worker、编辑器代次和候选版本校验。
- 中文经 JNI 直接使用固定版本的 librime；英文和空闲字面输入保留直接路径。词表通过既有校验、独立编译、原子切换和回退管理，学习数据与打包数据分离。输入路径离线；不引入 Fcitx5 核心或通用可执行插件。
- 现有 Fn 配对、中心＋八向编辑、候选展开、分类符号和雾凇基础词包属于回归基线，不作为下一版重新开发的整套功能。Compose 保持现状，H1 高级手势和 [#18](https://github.com/AuroraLilja8514/miancak/issues/18) 的跨语言 cdict 转换后置。
- API 23/35 x86_64 模拟器、三 ABI 构建检查和 ARM 实机体验是不同证据。历史 18/18 或局部重跑不替代当前源码与正式 APK 的验收。

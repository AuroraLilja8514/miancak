# Inherited store asset review

2026-11-17: bounded visual inspection of all eight PNGs under `fastlane/metadata/android/en-US/images`, plus a source comparison with the current application icon and main keyboard. The files inspected at public diagnostic baseline `2bff7d92e4962275eb81586e390f80d5683db373` are byte-identical at N4 source commit `d3dbf7cef6aa9983ba4d42a7aedcb974c8da874c`. This records existing assets; it is not current-APK visual acceptance or permission to distribute them as MIANCAK screenshots. No images or application resources are changed by this inventory.

## File-by-file observations

All six phone screenshots are 1280 × 1290 pixels. They show inherited keyboard layouts and suggestion presentation. None displays the MIANCAK project name, Chinese Pinyin preedit/candidates, or the current main-layout header controls. Missing a product name is not by itself a literal wrong-name defect. Theme variation and inherited functionality do not establish that an image represents the current application journey.

| File, relative to the image directory | Dimensions | Observed pixels / interpretation limit |
| --- | --- | --- |
| [phoneScreenshots/1.png](../fastlane/metadata/android/en-US/images/phoneScreenshots/1.png) | 1280 × 1290 | Light gray keyboard; editor contains `Hello`; suggestion row reads `Cello`, `Hello`, `Hello`. Shows inherited punctuation/corner assignments, not the current MIANCAK main layout. |
| [phoneScreenshots/2.png](../fastlane/metadata/android/en-US/images/phoneScreenshots/2.png) | 1280 × 1290 | Dark keyboard; suggestion row reads `Cello`, `Hello`, `Hello`. The editor text is not visible in this crop. Does not demonstrate the Chinese/Rime journey or current main-layout header. |
| [phoneScreenshots/3.png](../fastlane/metadata/android/en-US/images/phoneScreenshots/3.png) | 1280 × 1290 | Beige keyboard; suggestion row reads `Cello`, `Hello`, `Hello`. The editor text is not visible in this crop. Same inherited layout/presentation limitation. |
| [phoneScreenshots/4.png](../fastlane/metadata/android/en-US/images/phoneScreenshots/4.png) | 1280 × 1290 | Teal keyboard; editor contains `Hello`; suggestion row reads `Cello`, `Hello`, `Hello`. Same inherited layout/presentation limitation. |
| [phoneScreenshots/5.png](../fastlane/metadata/android/en-US/images/phoneScreenshots/5.png) | 1280 × 1290 | Dark keyboard; editor contains `smile`; row shows a smiling emoji, `mile`, `smile`, `smile`. Demonstrates inherited English/emoji suggestions, not current Chinese candidates. |
| [phoneScreenshots/6.png](../fastlane/metadata/android/en-US/images/phoneScreenshots/6.png) | 1280 × 1290 | Dark keyboard; editor contains `Égalit`; row reads `Égalitaire`, `Égalité`, `Égalité`, with accented key legends. Does not establish current MIANCAK language/suggestion support. |
| [featureGraphic.png](../fastlane/metadata/android/en-US/images/featureGraphic.png) | 1024 × 500 | Black-background crop of an inherited keybed, including letter and corner legends; lower controls are cut off. No product name or current-app journey is visible. |
| [icon.png](../fastlane/metadata/android/en-US/images/icon.png) | 512 × 512 | Light gray crop spanning parts of several rows/keys. No product name. It differs visually from the current dark `o`/`p` launcher-icon artwork; see the icon comparison below. |

[video.txt](../fastlane/metadata/android/en-US/video.txt) contains `https://www.youtube.com/watch?v=rwGvWesPFX8`. Only the text destination was inspected. The actual video has **not** been viewed in this review; its visuals, audio, age and relevance remain unassessed. Neither the URL nor the PNG inspection approves the video for reuse.

## Comparison with current source

The mismatch is grounded in the current main-layout implementation rather than the absence of a brand word:

- [RimeCandidatesView](../srcs/juloo.keyboard2/input/RimeCandidatesView.java) creates the `中文` / `EN` language button, layout chooser (`▦`), preedit/status, cancellation and candidate expansion controls. Compact candidates scroll horizontally; expanded candidates scroll vertically with demand-driven loading. The inherited three-word/emoji suggestion rows in these screenshots do not show that presentation.
- [Keyboard2.create_keyboard_view / refresh_candidates_view](../srcs/juloo.keyboard2/Keyboard2.java) attaches the Rime surface and shows the inherited suggestion view only when `_inputController == null` and its other conditions hold. The retained inherited classes/resources are not evidence that these old screenshots represent the normal current main-layout path.
- [miancak_qwerty.xml](../srcs/layouts/miancak_qwerty.xml) explicitly assigns `。` and `，` to the northwest corners of `c` and `v`, and `language_switch` to Shift northeast. The screenshots show inherited Latin corner legends, including northeast `<` / `>`, without the current northwest `。` / `，` assignments. The northeast `<` / `>` actions themselves are retained in the current template. [bottom_row.xml](../res/xml/bottom_row.xml) uses paired `fn_page` and Enter's southeast `layout_picker`. Similar-looking inherited controls must not be mistaken for evidence of these current actions.

These are source-to-image observations, not a fresh APK rendering, device test or guarantee of all runtime states. English remains direct input; the French/English screenshot text is not a claim of a new MIANCAK prediction feature. Released v0.3.2 behavior and unpublished 0.3.3/N4 changes remain distinguished in [PROJECT_IDENTITY.md](PROJECT_IDENTITY.md).

## Does the store icon match the current application icon?

The current manifest's application and activities use [`@mipmap/ic_launcher`](../AndroidManifest.xml). All five raster density variants were inspected: [mdpi](../res/mipmap-mdpi/ic_launcher.png), [hdpi](../res/mipmap-hdpi/ic_launcher.png), [xhdpi](../res/mipmap-xhdpi/ic_launcher.png), [xxhdpi](../res/mipmap-xxhdpi/ic_launcher.png), [xxxhdpi](../res/mipmap-xxxhdpi/ic_launcher.png). They show a dark crop dominated by the `o` and `p` keys (48, 72, 96, 144 and 192 pixels square respectively). The store's light 512-pixel multi-key crop does **not** match that raster artwork; this is a visual comparison as well as a file-identity difference.

For API 26+, [ic_launcher.xml](../res/mipmap-anydpi-v26/ic_launcher.xml) selects [a dark background](../res/drawable/ic_launcher_background.xml) and [a vector foreground](../res/drawable/ic_launcher_foreground.xml). These are separate source assets from the store PNG. No adaptive launcher rendering/mask has been captured here; its rendered fit, device-specific masking, scaling and launcher presentation still need review. The confirmed pixel comparison is the store PNG versus all five current raster variants. Both designs lack a written product name, which alone is not a wrong-name defect. This inventory does not prescribe new icon artwork or remove inherited attribution.

## Engineering handoff and remaining acceptance

- Retain these original files and their history. Do not silently remove, replace, relabel or present the six inherited screenshots as fresh MIANCAK evidence. Text metadata regeneration does not validate imagery.
- Prepare a separately scoped set of fresh screenshots from an identified current APK using synthetic input. It should demonstrate the actual Chinese/Rime journey, current language/chooser controls, candidate scrolling/expansion and relevant current layout/editor/symbol behavior. Record the exact source, APK hash/version, locale and device configuration, and distinguish the released app from unpublished development behavior. No mock UI or such captures are created by this review.
- Review the actual linked video before retaining or replacing it; decide the feature graphic/icon's fitness separately from screenshot coverage. The source icon comparison is not complete visual/store acceptance.
- Follow [PUBLIC_TEST_EVIDENCE.md](PUBLIC_TEST_EVIDENCE.md): existing maintained device outputs still cross only the typed `summary.json` / `heartbeat.jsonl` boundary. Do not repurpose raw guest screenshots/logs or other raw evidence as attachments. Any future distribution-image capture/review needs its own scope; this inventory contains text observations and file references only.
- N3 alignment, launcher animation lifecycle, remaining translations, funding-beneficiary policy and native/device acceptance remain separate. No beneficiary, signing identity, application ID or keyboard behavior is changed here.

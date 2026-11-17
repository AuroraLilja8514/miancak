# Clipboard history and pinning

The unreleased 0.3.3 source fixes an ordering error in the existing clipboard panel. History is newest-first. Pinning an older entry now removes it from recent history and saves it among pinned entries while preserving the newer system clipboard. Pinning the newest entry retains the inherited policy of clearing its current system clipboard. Pinning removes all matching history copies and sends one history-change notification.

History remains optional, disabled by default, limited to six in-memory entries and governed by the existing duration setting. Pinned entries retain the existing private preference file and format. This fix does not rename storage, migrate data, change paste ordering or alter keyboard gestures.

Eight JVM regressions cover oldest/newest/middle/only entries, repeated text, empty history and listener ordering. They exercise the service's removal method with a controlled clipboard-write callback; they do not establish Android clipboard behavior. The normal Context constructor still binds that callback to the same platform clear operation.

The existing full `ImeInteractionTest` additionally prepares an older/newer synthetic pair, presses the shipped history pin button, checks persisted pinned text and reads the real primary clipboard before and after pinning/removing the older item. Its fixture restores the earlier pinned preference and original synthetic clipboard. It runs after the existing composition-paste assertions, under the unchanged prepared-guest interaction driver and instrumentation budget. First-install and restart-only routes are unchanged. Actual API 35 execution remains required; compilation and JVM passes are not runtime acceptance.

Device evidence continues through the existing wrapper and exact typed `summary.json` / `heartbeat.jsonl` staging boundary. No clipboard contents or preference maps become public attachments. This source record does not announce a release or change the historical APK results.

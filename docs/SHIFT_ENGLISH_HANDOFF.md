# Shift and unfinished Chinese input (unreleased 0.3.3)

Priority: high. Previously a language change confirmed the first/highlighted
candidate; a shifted letter could also reach ordinary literal confirmation.
That loses the user's intended English spelling when it was never selected as
Chinese. The reported Shift interaction still requires Android 15 reproduction.

The new behavior is lossless: `nihao` followed by a completed manual Shift action
or the English language control produces `nihao`, not `你好`. If the user explicitly
selected `你好` from `nihaoshijie`, it produces `你好shijie`. Fully committed/selected
text is retained once. Apostrophes and other raw input bytes are not reconstructed
from the formatted preedit or changed to a candidate's spelling/tones.

The JNI command uses the pinned Rime `commit_code` operation's existing boundary:
clear nonselected composition segments, retain selected ones, then commit their
raw remainder. It never calls candidate selection to finish the English switch.
Normal/private policy and native learning callbacks remain in place. The frontend
and serialized controller publish the English layout only after successful engine
handoff and editor delivery. Failed or stale work cannot update another editor's
layout or append its old text.

An idle Shift remains the inherited capitalization latch/lock. During composition,
a completed manual Shift latch/lock requests English; capitalization still follows
that frontend Shift state. The pointer hook runs after the gesture resolves, not
on initial touch, cancellation, automatic/fake caps or editor-preview touches.
Ctrl/Alt/Meta combinations do not request this handoff. Literal punctuation,
Space and Enter retain their existing explicit confirmation paths.

Regression coverage includes raw/no-selection, explicit partial/full selection,
exact apostrophes, queued input/delivery, rejected editor transactions, stale
editors and unchanged idle Shift behavior. Native and pointer/IME instrumentation
must still run on Android 15+ through the existing finite guarded drivers. Host
JVM/JNI results and test compilation are not physical touch, receiving-editor,
rotation/restart, privacy or Android runtime acceptance.

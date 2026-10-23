# MIANCAK

**MIANCAK Is Another New Chinese Android Keyboard.**

MIANCAK is a personal, open-source Android input method project based on [Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard). Its goal is to keep Unexpected Keyboard's compact, swipe-oriented mobile interaction model while adding first-class Chinese composition through Rime.

This project is primarily developed for self use. It is public so that its source, history, licenses, and third-party provenance remain transparent and auditable.

## Project status

MIANCAK has an **experimental offline simplified-pinyin implementation** with a pinned librime runtime, semantic input controller, exact literal output and a dedicated touch candidate strip. Existing automated scenarios pass, but everyday Chinese usability is not accepted and remains tracked in [issue #20](https://github.com/AuroraLilja8514/miancak/issues/20). The inherited gestures and one-finger Shift latch/lock remain in place. See [docs/TESTING.md](docs/TESTING.md) for the actual validation scope.

See [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md) for the current milestones, acceptance gates and next work.

The prototype integrates:

- Unexpected Keyboard's existing key layout and swipe interaction model;
- explicit literal symbols and punctuation;
- Chinese composition powered by librime;
- direct, interference-free English input;
- a touch-oriented Chinese candidate strip;
- offline operation with no network dependency in the input path.

## Trying the prototype

Download the debug APK from a successful [MIANCAK CI run](https://github.com/AuroraLilja8514/miancak/actions/workflows/ci.yml), install it, then enable MIANCAK in Android's input-method settings and select it. The unsigned release artifact is intended for a separate signing step. Debug APKs use `org.miancak.keyboard.debug`, so they coexist with Unexpected Keyboard.

- Tap **中文 / EN** to switch explicitly; key geometry stays the same.
- In Chinese mode, ordinary lowercase letters build pinyin. Tap a candidate or press space to select; use the arrows/scrolling to reach more candidates.
- On the default MIANCAK QWERTY layout, swipe **c northwest** for `。` or **c southwest** for `.`, and **v northwest** for `，` or **v southwest** for `,`. These positions work identically in both languages.
- Digits and other literal symbols confirm current composition before appending exactly what is shown. Shift latch/lock produces direct uppercase ASCII without leaving Chinese mode.
- Enter confirms composition before the editor's newline/action; Escape or × cancels composition. Uncommitted pinyin stays in the IME preedit row and is cancelled when changing fields or moving the cursor.
- Password, numeric and raw-key editors use direct input. No-learning text fields use a schema without a user dictionary. Rime user data is kept locally and excluded from Android backup/transfer rules.

Current Chinese vocabulary limits:

- Rime user learning is enabled in normal fields; it is separate from manual phrase editing or bulk dictionary import.
- The app does not yet import, add or switch external Rime word tables. That independent future feature is tracked in [issue #22](https://github.com/AuroraLilja8514/miancak/issues/22).
- Word-table support will retain MIANCAK's input logic and use compatible vocabulary data, including existing prepared MediaWiki-derived or other Rime tables. It does not require the source project's complete input scheme.
- Manual phrase editing is a separate capability and is not a prerequisite for using an existing word table.

Custom layout authors can use explicit `:engine:'text'`, `:literal:'text'` and `language_switch` actions; see [doc/Possible-key-values.md](doc/Possible-key-values.md).

## Interaction principles

The interaction model is treated as a product contract, not an implementation detail. In particular:

- Chinese and English use the same physical keyboard layout.
- Unexpected Keyboard's one-finger Shift latch/lock behavior is preserved.
- No normal interaction requires simultaneously holding Shift and another key.
- Engine input and literal input are distinct semantics.
- Literal punctuation, digits, and symbols always mean exactly what is shown.
- `.` and `。`, `,` and `，` may coexist as separate explicit inputs.
- Candidate selection is touch-first; digits are not candidate shortcuts by default.
- The keyboard must not guess punctuation from surrounding text.

See [docs/INTERACTION_CONTRACT.md](docs/INTERACTION_CONTRACT.md) for the normative rules.

## Architecture direction

The runtime keeps inherited gestures behind a semantic adapter. The controller sends idle literal/English input directly to the editor and serializes Chinese operations through a persistent Rime worker/JNI session. Immutable results update the candidate strip and ordered editor transactions.

MIANCAK does **not** plan to embed Fcitx5 core as a runtime dependency, and it does not plan to provide a general executable plugin system. Word lists and Rime configuration are treated as data.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Development

Development happens through Issues, short-lived branches, Draft Pull Requests, and required CI checks. Normal project work should not be pushed directly to `master`.

Start with:

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [docs/DEVELOPMENT_WORKFLOW.md](docs/DEVELOPMENT_WORKFLOW.md)
- [docs/CI.md](docs/CI.md)
- [docs/UPSTREAM.md](docs/UPSTREAM.md)
- [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md)

## Upstream

MIANCAK is a fork of [Julow/Unexpected-Keyboard](https://github.com/Julow/Unexpected-Keyboard). The fork relationship, original Git history, copyright notices, and license are intentionally preserved.

Changes that do not need to diverge from upstream should remain easy to rebase or port when practical.

## Licensing

This repository remains licensed under the GNU General Public License v3.0 as inherited from Unexpected Keyboard. See [LICENSE](LICENSE).

The pinned native libraries, dictionary, licenses and packaged provenance are documented. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Acknowledgement

MIANCAK would not exist without Unexpected Keyboard and its contributors. The project deliberately reuses its interaction model and codebase rather than pretending to be an independent clean-room implementation.

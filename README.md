# MIANCAK

**MIANCAK Is Another New Chinese Android Keyboard.**

MIANCAK is a personal, open-source Android input method project based on [Unexpected Keyboard](https://github.com/Julow/Unexpected-Keyboard). Its goal is to keep Unexpected Keyboard's compact, swipe-oriented mobile interaction model while adding first-class Chinese composition through Rime.

This project is primarily developed for self use. It is public so that its source, history, licenses, and third-party provenance remain transparent and auditable.

## Project status

MIANCAK is currently in the **design and bootstrap stage**. The repository still closely follows Unexpected Keyboard, and Chinese input functionality has not yet been implemented.

The first implementation target is a minimal Android IME that combines:

- Unexpected Keyboard's existing key layout and swipe interaction model;
- explicit literal symbols and punctuation;
- Chinese composition powered by librime;
- direct, interference-free English input;
- a touch-oriented Chinese candidate strip;
- offline operation with no network dependency in the input path.

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

The intended runtime architecture is deliberately small:

```text
Unexpected Keyboard frontend / gestures
        -> semantic input layer
        -> direct path OR Rime composition engine
        -> Rime worker / JNI / librime
        -> editor transaction / InputConnection
```

MIANCAK does **not** plan to embed Fcitx5 core as a runtime dependency, and it does not plan to provide a general executable plugin system. Word lists and Rime configuration are treated as data.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Development

Development happens through Issues, short-lived branches, Draft Pull Requests, and required CI checks. Normal project work should not be pushed directly to `master`.

Start with:

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [docs/DEVELOPMENT_WORKFLOW.md](docs/DEVELOPMENT_WORKFLOW.md)
- [docs/CI.md](docs/CI.md)
- [docs/UPSTREAM.md](docs/UPSTREAM.md)

## Upstream

MIANCAK is a fork of [Julow/Unexpected-Keyboard](https://github.com/Julow/Unexpected-Keyboard). The fork relationship, original Git history, copyright notices, and license are intentionally preserved.

Changes that do not need to diverge from upstream should remain easy to rebase or port when practical.

## Licensing

This repository remains licensed under the GNU General Public License v3.0 as inherited from Unexpected Keyboard. See [LICENSE](LICENSE).

Third-party components will be documented when they are actually introduced. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Acknowledgement

MIANCAK would not exist without Unexpected Keyboard and its contributors. The project deliberately reuses its interaction model and codebase rather than pretending to be an independent clean-room implementation.

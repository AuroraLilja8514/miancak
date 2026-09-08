# Interaction Contract

This document defines MIANCAK's interaction invariants. Implementations may evolve, but behavior that conflicts with these rules requires an explicit design decision and documentation update.

## 1. Stable physical layout

Chinese and English use the same physical keyboard geometry. Language switching must not reshuffle ordinary keys or move frequently used symbols.

The purpose is to preserve muscle memory inherited from Unexpected Keyboard.

## 2. No simultaneous Shift chord as a normal mobile interaction

Unexpected Keyboard's Shift behavior is sequential and one-finger friendly:

- tap Shift to latch it for the next applicable key;
- lock behavior may use the existing long-press/double-tap semantics where appropriate;
- users are not expected to hold Shift with one finger while pressing a letter with another.

MIANCAK must preserve this property.

## 3. Engine input and literal input are different semantics

A key action must be able to express at least these two concepts:

- **Engine input**: a code/key intended for the active composition engine;
- **Literal input**: exact text that must be committed as displayed.

They may display the same character while having different semantics.

Example:

```text
Engine(".")  -> the engine may interpret it
Literal(".") -> always an ASCII period
Literal("。") -> always a Chinese full stop
```

## 4. Chinese and English routing

For normal alphabet keys:

```text
Chinese mode: Engine("q") -> Rime
English mode: Engine("q") -> literal "q"
```

English direct input must not require autocorrect, prediction, or composition.

## 5. Literal symbols are exact

Literal punctuation, digits, and symbols must always mean exactly what they show.

MIANCAK must not silently transform punctuation according to:

- language mode;
- the previous character;
- whether the previous character is a digit;
- application type;
- inferred sentence context;
- programmer/non-programmer heuristics.

Users may place both half-width and full-width punctuation on separate swipe positions.

## 6. Literal input while Chinese composition exists

Default behavior:

1. confirm the current selected/highlighted Chinese candidate;
2. append the requested literal;
3. expose the result to the editor as one ordered transaction.

Examples:

```text
nihao + Literal(".")  -> 你好.
nihao + Literal("。") -> 你好。
di + Literal("3")     -> 第3   (assuming 第 is the selected candidate)
```

The literal must not appear before unresolved composition text.

## 7. Digits are not candidate shortcuts by default

Digits reachable from the keyboard are literal digits unless a layout explicitly declares an engine-specific digit action.

Chinese candidates are selected by touch. Desktop-style `1..9` candidate shortcuts are not part of the default touchscreen interaction model.

## 8. Candidate interaction is touch-first

The Chinese candidate strip should:

- rank candidates left to right;
- select a candidate by tapping it;
- allow access to additional candidates with horizontal navigation;
- keep a stable allocated height so the keyboard does not jump vertically when composition starts or ends.

The candidate strip should not become a general-purpose toolbar.

## 9. Language switching is explicit

Switching Chinese/English is an explicit semantic action.

It must:

- be available as a one-finger operation;
- not depend on simultaneous multi-touch;
- not be inferred from text context;
- not require restarting the Rime runtime/session.

The exact gesture may be configurable, but it must remain deterministic.

## 10. Shift and language mode are orthogonal

Shift latch/lock belongs to the keyboard frontend. Language mode belongs to input routing.

They must not be conflated into one hidden state machine.

This permits efficient mixed input, for example using a one-shot Shift for a capital ASCII letter while otherwise remaining in Chinese mode.

## 11. Backspace and space must respect composition state

Expected default routing:

- backspace while composing -> composition engine;
- backspace while not composing -> editor/backspace behavior;
- space while composing -> composition engine confirmation/selection behavior;
- space while not composing -> ordinary space behavior.

Any deviation must be explicit and testable.

## 12. No network dependency in the input path

Typing, composition, candidate generation, and literal output must work offline.

The normal key-processing path must not depend on a remote service.

## 13. No per-key disk I/O

Normal input handling must not perform filesystem deployment, dictionary extraction, or similar disk work on every key event.

Rime data preparation belongs to initialization/update paths, not the key path.

## 14. Determinism over hidden convenience

When there is a conflict between a clever context-sensitive behavior and a predictable explicit gesture, prefer the predictable explicit gesture.

MIANCAK should use Unexpected Keyboard's dense swipe surface to expose more exact actions rather than compensating with hidden heuristics.

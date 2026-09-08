# Upstream Policy

MIANCAK is a public fork of [Julow/Unexpected-Keyboard](https://github.com/Julow/Unexpected-Keyboard). The fork relationship is intentional and should remain useful over time.

## Upstream role

Unexpected Keyboard is the source of MIANCAK's keyboard frontend foundation, including its layout model, swipe interaction, modifier behavior, and much of the Android IME implementation.

MIANCAK is not trying to erase that lineage or present the inherited code as an independent implementation.

## What should stay easy to synchronize

When practical, preserve upstream structure in areas that MIANCAK does not need to own, especially:

- touch/gesture mechanics;
- key geometry/layout parsing;
- generic Android IME fixes;
- crash fixes unrelated to Rime semantics;
- accessibility fixes;
- build/tooling fixes that apply equally to the fork;
- generic modifier behavior.

Avoid large formatting-only or renaming-only changes in inherited code, because they make future upstream comparison unnecessarily difficult.

## Where divergence is expected

MIANCAK is expected to diverge intentionally in areas such as:

- Chinese composition integration;
- semantic distinction between engine and literal input;
- Chinese candidate UI;
- language-mode routing;
- Rime data deployment;
- project documentation and development workflow.

Divergence should still be localized where reasonable.

## Suggested Git remotes

For a local clone:

```sh
git remote -v
```

The MIANCAK repository should normally be `origin`, and the original project can be added as `upstream`:

```sh
git remote add upstream https://github.com/Julow/Unexpected-Keyboard.git
git fetch upstream
```

## Synchronizing upstream

Before importing upstream changes:

1. fetch `upstream/master`;
2. review the upstream commits rather than blindly updating;
3. check whether they touch MIANCAK-owned interaction/runtime areas;
4. merge or rebase in a dedicated branch;
5. run the full relevant test/build suite;
6. resolve semantic conflicts using MIANCAK's interaction contract, not merely textual merge convenience;
7. use a PR so the imported diff is reviewable.

A typical branch name:

```text
chore/sync-upstream-YYYY-MM
```

## Do not automatically overwrite intentional divergence

A clean textual merge is not enough if an upstream change conflicts with MIANCAK's product rules.

Examples that require review rather than automatic adoption:

- punctuation behavior;
- candidate semantics;
- Shift/language-switch behavior;
- direct commit vs composition routing;
- keyboard geometry changes affecting established MIANCAK positions.

`docs/INTERACTION_CONTRACT.md` takes precedence for MIANCAK behavior.

## Contributing fixes upstream

If MIANCAK discovers a bug that is genuinely generic to Unexpected Keyboard and does not depend on MIANCAK-specific architecture, prefer keeping the fix small enough that it could be proposed upstream.

This is not mandatory, but it reduces long-term fork maintenance and respects the value of the upstream project.

## Attribution and license

Do not remove inherited authorship, copyright notices, Git history, or GPL licensing information while synchronizing or refactoring upstream code.

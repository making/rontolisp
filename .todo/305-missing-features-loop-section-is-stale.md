# The `loop` section of the missing-features guide lists supported clauses as out of scope

Difficulty: Low

`doc/{en,ja}/guides/missing-features.md`'s "The `loop` macro" section says:

> Out of scope are destructuring, parallel `and` between `for` clauses, `being`,
> the anaphoric `it`, `named`/`loop-finish`, and `thereis`/`always`/`never`.

Every one of those works, on all four backends, and the reference page
(`doc/{en,ja}/reference/macros/loop.md`) documents them with runnable examples:

| listed as out of scope | actually | pinned by |
| --- | --- | --- |
| destructuring | supported (nested + dotted patterns) | ci-spec `loop-macro-extended-clauses`, `loop-variable-after-termination` |
| parallel `and` | supported (the Fibonacci shape) | ci-spec `loop-macro-extended-clauses` |
| `being` | supported for hash-keys/hash-values; the package form is a documented lite no-op | ci-spec `loop-being-symbols-lite`, `LispEvaluatorTest` |
| the anaphoric `it` | supported | ci-spec `loop-macro-extended-clauses` |
| `named` | supported (wraps the expansion in `(block name ...)`) | `.kb/do-return-block.md` |
| `loop-finish` | supported | ci-spec `loop-macro-extended-clauses` |
| `thereis`/`always`/`never` | supported | ci-spec `loop-macro-extended-clauses` |

So the guide understates the macro by seven clauses and contradicts its own
reference page. Someone reading the guide first would hand-write a `do` loop for
something `loop` already does.

## Work

- Rewrite the section from the reference page's actual clause list: say what a
  bounded subset really excludes now (the real limitations are at the bottom of
  `reference/macros/loop.md` — destructuring patterns not recognizing lambda-list
  keywords, `(loop-finish)` needing statement position, accumulation clauses
  without `into` having to agree in kind, `thereis`/`always`/`never` not combining
  with default accumulation) rather than an out-of-date "out of scope" list.
- Same file set, same headings, byte-identical code fences in `doc/en` and
  `doc/ja` (the two currently agree with each other — they are both stale).
- While there, check the neighbouring sections of the same guide the same way:
  a limitations list that nobody re-reads after the feature lands is a pattern,
  not a one-off, and this one had been wrong for at least seven landings.

## Done when

The guide's `loop` section and `reference/macros/loop.md` agree about what is
supported, in both languages.

# d43. A non-integer `:count` to a sequence operator answers

Difficulty: Low

CLHS 17.2.1 makes `:count` an integer or nil; SBCL signals a `type-error` for anything else. The
remove / delete / substitute families answer a float `:count` as a budget instead:

| call | SBCL | interpreter | JVM / P1 / component |
|---|---|---|---|
| `(remove 2 (list 1 2 3 2) :count 1.5)` | `type-error` 1.5 | `(1 3)` | `(1 3)` |
| `(substitute 0 2 (list 1 2 3 2) :count 1.5)` | `type-error` 1.5 | `(1 0 3 0)` | `(1 0 3 0)` |
| `(funcall #'remove 2 (list 1 2 3 2) :count 1.5)` | `type-error` 1.5 | `type-error`, datum NIL | `(1 3)` |

A symbol `:count` is already refused (`max` in the scaffold's budget). The expansion is
`SeqScanScaffold.addBindings`' budget; the interpreter's twin is `LispEvaluator.requireCount`,
whose refusal carries no datum. Refuse a non-integer once, before the walk, keeping a nil and a
negative count as CLHS reads them (`.kb/sequence-bounding-keywords.md`).

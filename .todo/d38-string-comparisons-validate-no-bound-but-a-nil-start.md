# d38. String comparisons validate no bound but a nil `:start`

Difficulty: Medium

SBCL signals a `type-error` for a negative, non-integer or out-of-range `:start1`/`:end1`/
`:start2`/`:end2` and for a start past its end. A nil start is refused on all four backends
(`.kb/characters-code-points.md`, "String comparison family"); nothing else is, and the backends
disagree (bounds read at run time):

| call | SBCL | interpreter | JVM | P1 / component |
|---|---|---|---|---|
| `(string= "abc" "abc" :start1 -1)`, `:end1 9`, `:start1 2 :end1 1` | `type-error` | non-`type-error` error | `type-error` | `type-error` |
| `(string< "abc" "abd" :start1 -1)` | `type-error` -1 | `type-error` -1 | `-1` | `0` |
| `(string< "abc" "abd" :start1 2 :end1 1)` | `NIL` | `2` | `2` | `2` |
| `(string-not-equal "abc" "abd" :start1 9)` | `type-error` | `9` | `9` | `9` |
| `(funcall #'string< "abc" "abd" :start2 -1)` | `type-error` -1 | `type-error` -1 | `NIL` | `NIL` |

The `string<` family's checks belong in `%string-compare` (one definition, all four backends),
once before the walk; the interpreter's `Environment.boundedStringArg` should refuse with the
`type-error` `subseq` uses (`.kb/subseq-runtime.md`). Keep a keyword-free call byte-identical and
measure the walk's speed (`.kb/string-index-cost.md`). The sequence operators' twin is `.todo/d34`.

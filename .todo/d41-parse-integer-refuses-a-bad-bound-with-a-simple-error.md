# d41. `parse-integer` refuses a bad bound with a `simple-error`, and wasm reads before the string

Difficulty: Medium

SBCL signals a `type-error` for every bad `:start`/`:end`. Here the class differs by backend and
P1 / the component READ BEFORE THE STRING for a negative start (bounds read at run time):

| call | SBCL | interpreter | JVM | P1 / component |
|---|---|---|---|---|
| `(parse-integer "123" :start 9)` | `type-error` | `simple-error` | `simple-error` | `simple-error` |
| `(parse-integer "123" :end 9)` | `type-error` | `type-error` 3 | `simple-error` | `simple-error` |
| `(parse-integer "123" :start -1)` | `type-error` -1 | `type-error` -1 | `simple-error` | **`1123`** |
| `(parse-integer "123" :start 3 :end 1)` | `type-error` | `simple-error` | `simple-error` | `simple-error` |

The compile paths share `LispMacroExpander.expandParseInteger`; the interpreter's builtin reads
the bounds with `asLong`. The bounds check the sequence operators share (`%check-bounds`,
`.kb/sequence-bounding-keywords.md`) refuses exactly these with `subseq`'s `type-error` on all
four backends; the `1123` says the wasm walk indexes the string without one.

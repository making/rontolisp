# wasm `subseq` does not check its bounds; `gensym` mishandles an integer

Difficulty: Low

Found 2026-09-26 while widening the built-in wrappers:

| form | interpreter | JVM | wasm |
|---|---|---|---|
| `(subseq "abc" 2 1)` (computed bounds) | `SUBSEQ: invalid bounds 2, 1 for string of length 3` | `index out of bounds` | `""` |
| `(subseq "abc" 0 5)` | same report | `index out of bounds` | `"abc"` |
| `(symbol-name (gensym 5))` | `GENSYM prefix must be a string, got 5` | `"51"` | `"51"` |

CL: bad bounds are an error on every backend (one text); `(gensym 5)` is `#:G5` -- an integer is
the suffix, and the counter does not advance. `string-upcase` / `string-downcase` /
`string-capitalize` with `:start` / `:end` lower onto `subseq`
(`LispMacroExpander.expandBoundedCaseConversion`), so they inherit the wasm row. Fix and pin the
three backends together.

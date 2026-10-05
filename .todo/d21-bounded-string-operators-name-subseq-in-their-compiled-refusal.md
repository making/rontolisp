# d21. Bounded string operators name `SUBSEQ` in their compiled refusal

Difficulty: Low

`write-string` / `write-line` / `string-upcase` with a bad `:start`/`:end` refuse on every
backend, but the compile paths lower them onto `subseq` and report its text, while the
interpreter names the operator:

| call | interpreter | JVM, wasm P1, component |
|---|---|---|
| `(write-string "hello" s :start 3 :end 1)` | `WRITE-STRING: bad bounding indices 3..1` | `SUBSEQ: invalid bounds 3, 1 for string of length 5` |
| `(string-upcase "hello" :start 3 :end 1)` | `STRING-UPCASE: bad bounding indices 3..1` | same `SUBSEQ` text |
| `(write-line "hello" s :start 1 :end 9)` | `WRITE-STRING: bad bounding indices 1..9` (names the wrong operator) | `SUBSEQ: invalid bounds 1, 9 for string of length 5` |

Pick one text per operator and give it to all four backends (the lowerings are
`LispMacroExpander.expandBoundedCaseConversion` and the `write-string` bound lowering);
`write-line`'s interpreter report should name `WRITE-LINE`. Pin in ci-spec by message.

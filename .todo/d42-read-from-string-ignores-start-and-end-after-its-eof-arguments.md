# d42. `read-from-string` ignores `:start` / `:end` after its eof arguments

Difficulty: Medium

`(read-from-string string &optional eof-error-p eof-value &key start end preserve-whitespace)`:
the keywords follow the two optionals. All four backends answer as if they were absent:

| call | SBCL | rontolisp (all four) |
|---|---|---|
| `(read-from-string "123" t nil :start 1)` | `23` | `123` |
| `(read-from-string "123" t nil :start 9)` | `type-error` | `123` |

(`(read-from-string "123" :start 1)` is `123` in SBCL too: there `:start` is the eof-error-p.)
The interpreter's builtin (`Environment`, `READ_FROM_STRING`) reads only its first argument;
check the compile paths' lowering and the `%read-from-string-end` second value alike, and refuse
a bad window with the `type-error` `subseq` uses (`.kb/subseq-runtime.md`).

# i71. A write to a closed or an input stream on wasm

Difficulty: Medium

Measured 2026-11-05, each row a program of its own, the answer of a `handler-case` for `error`. SBCL 2.2.9 refuses all of
them; the interpreter and the JVM refuse them too, with another condition; wasm writes nothing and says nothing, or traps where
no handler can reach.

| form | SBCL 2.2.9 | interpreter | JVM | Preview 1 | component |
| --- | --- | --- | --- | --- | --- |
| `(write-string "x" closed-file)`, `write-line` | `closed-stream-error` | `simple-error` | `simple-error` | answers `"x"`, no write | trap `index 2 is not a resource` |
| `(write-string "x" input-file)` | `simple-type-error` | `simple-error` | `type-error` | answers `"x"`, no write | answers `"x"`, no write |
| `(fresh-line closed-file)`, `(fresh-line input-file)` | as above | as above | as above | nil | nil |
| `(write-string "x" closed-string-stream)` | `closed-stream-error` | `simple-error` | `simple-error` | trap `out of bounds array access` | the same trap |
| `(write-string "x" input-string-stream)`, `fresh-line` | `simple-type-error` | `simple-error` | `type-error` | trap `unreachable` | the same trap |

`fd_write`'s errno is dropped at every site, and `close` leaves its mark on the stream VALUE
(`.kb/read-load-streams.md`, "Open or closed is a fact about the VALUE on wasm") where no write reads it. The refusal fits the
check every stream operator's argument already takes (`compiler.StreamOperandChecks`, `%check-stream-runtime`) if the output
operators get a direction and an open test there. What a program with no file or string stream pays is the design question: one
writing to `t` must carry none of it. The read family has the same gap on a closed stream; measure it first, and take the three
backends to one condition while there.

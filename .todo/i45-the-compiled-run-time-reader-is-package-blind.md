# i45. The compiled run-time reader is package-blind

Difficulty: High

`.kb/symbol-runtime-api.md` and `.kb/packages.md` state the gap; nothing tracked it. Measured
2026-11-05, in `(defpackage :p (:use :cl)) (in-package :p) (defstruct pt x)`:

| Form | SBCL, interpreter | JVM, wasm P1, component |
|---|---|---|
| `(eq (read-from-string "foo") 'foo)` | T | NIL |
| `(symbol-package (read-from-string "foo"))` | `P` | `CL-USER` |
| `(pt-x (read-from-string "#S(pt :x 8)"))` | 8 | `PT is not a defined structure type` (wasm: trap) |

`(read-from-string "p::foo")` agrees. The interpreter homes each token through
`LispReader.symbolHome` -> `PackageResolver.readSpelling`. The emitted readers
(`JvmReadRuntimeBuilder`, `WasmReadRuntimeBuilder`) spell a token as read, and `_readStruct` /
`_rd_struct` match an unqualified spelling only against an unqualified directory entry.

Needs `*package*` at the emitted reader on both compile backends, gated so a program that stays in
`cl-user` is unchanged. `expandReadFromStringFailure`'s walk over the datum (local nicknames) is
the existing post-read seam; it cannot serve `#S`, whose type is looked up during the read.

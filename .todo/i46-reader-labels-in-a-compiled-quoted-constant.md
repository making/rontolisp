# i46. A reader label's sharing and circle do not reach a compiled quoted constant

Difficulty: Medium

Measured 2026-11-05 (`.kb/reader-features.md`, the `#n=` entry), any package:

| Form | SBCL | Interpreter | JVM, wasm P1, component |
|---|---|---|---|
| `(let ((a '(#1=(x) #1#))) (eq (car a) (cadr a)))` | T | T | NIL |
| `(let ((a '(#1=#(x) #1#))) (eq (car a) (cadr a)))` | T | T | NIL |
| `(let ((a '#1=(x #1#))) (eq a (cadr a)))` | T | T | stack overflow at compile |
| `(let ((a '#1=#(x #1#))) (eq a (aref a 1)))` | T | T | stack overflow at compile |
| `(let ((a '(#1=#S(pt :x 1) #1#))) (eq (car a) (cadr a)))` | T | NIL | NIL |
| `(let ((a '#1=#S(pt :x (y #1#)))) (eq a (cadr (pt-x a))))` | T | NIL | NIL |

Three causes: `JvmQuoteCompiler` / `WasmQuoteCompiler` build a tree, with no memo per node and no way
to close a circle; `StructLiteralFolder` folds per occurrence and rebuilds what it changes; and
`LispReader.patchLabel` does not enter a `LispStructLiteral`, which is immutable, so a label referenced
from inside its own `#S` keeps the placeholder.

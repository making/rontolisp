# i54. Stream operators take a value that is no stream

Difficulty: Medium

Measured 2026-11-05 with `(defstruct bar)`, in a program with no Gray class. SBCL 2.2.9 signals a type error
for every row.

| form | interpreter | JVM | WASM P1, component |
| --- | --- | --- | --- |
| `(write-char #\a (make-bar))`, `write-string`, `princ`, `format` | no-applicable-method error | writes to standard output | same as JVM |
| `(read-char (make-bar))`, `read-line`, `read-byte` | no-applicable-method error | `end-of-file` | same as JVM |
| `(terpri (make-bar))`, `fresh-line`, `finish-output`, `peek-char`, `unread-char` | no-applicable-method error | NIL | same as JVM |
| `(listen (make-bar))` | no-applicable-method error | NIL | `simple-error` |
| `(file-position (make-bar))`, `file-length` | NIL, no-applicable-method error | NIL | same as JVM |
| `(input-stream-p (make-bar))`, `output-stream-p` | NIL | NIL | NIL |
| `(stream-element-type (make-bar))` | CHARACTER | CHARACTER | CHARACTER |
| `(open-stream-p (make-bar))` | `type-error` | T | T |
| `(open-stream-p 3)` | NIL | NIL | T |

`close` already checks (`LispMacroExpander.checkedClose`, ci-spec `close-of-an-instance-that-is-no-stream`), so that
shape is the starting point. The cost to weigh is a test at every stream site: `checkedClose` runs at one
operator, and writing is hot. `.kb/read-load-streams.md` and `.kb/gray-streams.md` cover the two seams to change.

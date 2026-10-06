# d47. A Gray `stream-write-string` method sees a nil `end` where SBCL passes the length

Difficulty: Medium

SBCL's `write-string` / `write-line` / `write-sequence` on a Gray character stream always call
`(stream-write-string stream string start end)` with integer `start` and `end`. Here a call
spelling no bound passes the method the string alone, so a method written for SBCL
(`(loop for i from start below end ...)`) fails on `end` nil. Probe: a class whose method
pushes `(list string start end)`, on all four backends (SBCL in the last column):

| call | rontolisp | SBCL |
|---|---|---|
| `(write-string "hello" o)` | `("hello" 0 NIL)` | `("hello" 0 5)` |
| `(write-line "hello" o)` | `("hello" 0 NIL)`, then the newline | `("hello" 0 5)` |
| `(princ "hello" o)`, `(format o "hello")` | `("hello" 0 NIL)` | `("hello" 0 5)` |
| `(write-sequence "hello" o :start 1)` | one `stream-write-char` per character | `("hello" 1 5)` |

A bounded `write-line` / `write-string` already passes integers
(`.kb/gray-streams.md`, "Bounds on `write-line` / `write-string`"). The plan is the same for
the unbounded helpers (`%gray-write-string-dispatch`, `%gray-write-line-dispatch`, the print
family, the `format` rewrite): pass `0` and `(length s)`. Measure the byte cost on a Gray
program first -- the unbounded helpers were deliberately kept at two arguments so a program
with no bound pays nothing, and a user method with a two-parameter lambda list
(`(defmethod stream-write-string ((s c) str) ...)`, used by ci-spec
`gray-stream-instance-dispatch`) would then fail on arity, as it does in SBCL. If the cost or
the arity break is not worth it, record the numbers in `.kb/gray-streams.md` and close.
`write-sequence` of a string should reach `stream-write-string` for a class that defines it.

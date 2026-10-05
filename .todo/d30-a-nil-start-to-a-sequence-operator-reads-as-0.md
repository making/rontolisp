# d30. A nil `:start` to a sequence operator reads as 0

Difficulty: Medium

SBCL signals a `type-error` (datum `NIL`) for a nil `:start` to every sequence operator
below; a nil `:end` is the sequence's length. Measured with the nil read at run time, on the
interpreter, JVM, wasm P1 and component (all four alike unless noted):

| call | SBCL | rontolisp |
|---|---|---|
| `(count 2 l :start nil)`, `(remove 2 l :start nil)`, `(fill l 0 :start nil)` | `type-error` | answers as `:start 0` |
| `(funcall #'position / #'count / #'find / #'remove / #'fill ... :start nil)` | `type-error` | answers as `:start 0` |
| `(funcall #'read-sequence ... :start nil)`, `(funcall #'write-sequence ... :start nil)` | `type-error` | answers as `:start 0` |
| `(funcall #'reduce f l :start nil)` | `type-error` | interpreter refuses; JVM / P1 / component answer as `:start 0` |

`(position ...)`, `(find ...)`, `(reduce ...)`, `(read-sequence ...)` and `(write-sequence ...)`
in call position already refuse it on all four.

Two causes: the first-class wrappers in `BuiltinFunctionWrappers` default `:start` through
`getfKwOr`, which reads a present nil as the default (the bounded string operators moved to
`getfKwDefault`, `(getf kw :start 0)`); and the interpreter's `count` / `remove` / `fill` (and
its `funcall` paths for the others) default a nil `:start` to 0. A fix changes the interpreter
and the compile paths together with one pinning fixture (`.kb/subseq-runtime.md`, "Bounded
string operators"; `SequenceBoundsFixture`) and a ci-spec case, and measures the size of an
affected program on JVM / P1 / component.

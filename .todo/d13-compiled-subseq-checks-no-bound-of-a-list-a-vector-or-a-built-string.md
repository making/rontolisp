# d13. Compiled `subseq` checks no bound of a list, a vector or a built string

Difficulty: Medium

The interpreter refuses every bad bound (`SUBSEQ: invalid bounds S, E for KIND of length N`);
the compile backends do only for a literal string. Measured 2026-10-05 through
`(defun f (s i &optional e) (if e (subseq s i e) (subseq s i)))` inside `handler-case`:

| call | JVM | wasm P1 / component |
|---|---|---|
| `(f (list 1 2 3) -1)` | `(1 2 3)` | `(1 2 3)` |
| `(f (list 1 2 3) 1 5)` | `(2 3)` | `(2 3)` |
| `(f (list 1 2 3) 2 1)`, `(f (list 1 2 3) 4)` | `NIL` | `NIL` |
| `(f (vector 1 2 3) -1)` | `AREF: The value -1 is not of type (INTEGER 0 (3))` | same |
| `(f (vector 1 2 3) 2 1)` | error `-1` | trap `allocation size too large` |
| `(f (concatenate 'string "ab" "c") -1)` | `the value is not of the expected type`; uncaught `ClassCastException` (`Object[]` to `int[]`) at top level | trap `out of bounds array access` |
| `(f (concatenate 'string "ab" "c") 1 5)` | `index out of bounds` | trap `out of bounds array access` |
| `(f (concatenate 'string "ab" "c") 2 1)` | `""` | trap `allocation size too large` |

The literal-string rows were fixed per backend before (closed item a42 in
`.todo/history/2026-09.md`); the other representations reach their own copy loops unchecked.
The Clojure `.substring` of a built string inherits the crash. Give every representation the
interpreter's check and text, and pin the table in ci-spec on all four backends.

# i62. `format` to a string with a fill pointer

Difficulty: Medium

Measured 2026-11-05 with `(format (make-array 5 :element-type 'character :fill-pointer 0) "x")`. CLHS 22.3 lets a
destination be such a string; `(with-output-to-string (s str) ...)` already writes to one on all four.

| form | SBCL 2.2.9 | all four backends |
| --- | --- | --- |
| a string with a fill pointer | appends, answers NIL | `FORMAT: The value "" is not of type STREAM` |
| any other string | `simple-error` "is not a string with a fill-pointer" | the same `type-error` |

Before i54 the compile paths wrote the text to standard output and the interpreter signalled `not an output stream`.

The write sits in `LispMacroExpander.formatDestinationDispatch`, `GrayStreamsLibrary`'s `format` rewrite and
`BuiltinFunctionWrappers.formatWrapper`; `compiler.StreamOperandChecks` refuses the string in front of all three, and
the interpreter's `format` goes through `checkedCall`. The cost to weigh is the `stringp` arm: every computed `format`
destination would carry it, and a fill-pointer string exists only in a program that can make one, so the arm wants a
gate (`.kb/size-measurement.md`).

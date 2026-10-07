# e05. Computed `typep` of a stream subtype name answers nil

Difficulty: Medium

```lisp
(defun ty (x name) (typep x name))
(ty (make-string-output-stream) 'string-stream)
```

Measured 2026-10-07 (a string output stream `*o*`, a two-way stream, a synonym stream over `*o*`):

| Call | SBCL 2.2.9 | interpreter | JVM, P1, component |
|---|---|---|---|
| `(ty *o* 'string-stream)` | `T` | `NIL` | `NIL` |
| `(ty two-way 'two-way-stream)` | `T` | `NIL` | `NIL` |
| `(ty synonym 'synonym-stream)` | `T` | `T` | `NIL` |
| `(ty *o* 'stream)`, and every literal spelling above | `T` | `T` | `T` |

The literal specifier lowers through `LispMacroExpander.makeTypeTest`'s stream arms; the
run-time dispatch (`RUNTIME_TYPEP_BUILTINS`, `%typep-tag-table%`) knows `STREAM` but none of
`FILE-STREAM`, `STRING-STREAM`, `SYNONYM-STREAM`, `BROADCAST-STREAM`, `TWO-WAY-STREAM`,
`ECHO-STREAM`, `CONCATENATED-STREAM`. A silent wrong answer: `get-output-stream-string`'s
`type-error` carries `(AND STRING-STREAM (SATISFIES OUTPUT-STREAM-P))`, and a handler's
`(typep x (type-error-expected-type c))` answers `NIL` for a string output stream.

## Plan

- Four-backend fixture first (each name, computed, against a member and a non-member;
  `(and string-stream (satisfies output-stream-p))` computed).
- Add the names to the run-time dispatch with the literal arms' tests (both halves, `.kb/clos.md`).
  Mind what the dispatch costs a program that never asks a stream type
  (`WasmLispCompilerTest`'s size levels).

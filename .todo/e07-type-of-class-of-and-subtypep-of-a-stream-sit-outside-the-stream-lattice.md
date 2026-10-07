# e07. `type-of`, `class-of` and `subtypep` of a stream sit outside the stream lattice

Difficulty: Medium

```lisp
(defun ty (x name) (typep x name))
(ty (make-string-output-stream) (type-of (make-string-output-stream)))
(subtypep 'string-stream 'stream)
(class-name (class-of (make-string-output-stream)))
```

Measured 2026-10-07 (`*o*` a string output stream, `*i*` a string input stream):

| Call | SBCL 2.2.9 | interpreter | JVM | P1, component |
|---|---|---|---|---|
| `(type-of *o*)` | `SB-IMPL::STRING-OUTPUT-STREAM` | `%STREAM` | `%STREAM` | `%STREAM` |
| `(type-of two-way)` | `TWO-WAY-STREAM` | `%TWO-WAY-STREAM` | same | same |
| `(ty s (type-of s))` for `*o*`, two-way, synonym | `(T T T)` | `(NIL T NIL)` | same | same |
| `(subtypep 'string-stream 'stream)`, `'file-stream`, `'two-way-stream` (literal) | `T` | `NIL` | `NIL` | `NIL` |
| `(class-name (class-of *o*))` | `SB-IMPL::STRING-OUTPUT-STREAM` | `T` | `FIND-CLASS: there is no class named %STREAM` | trap |

`SUBTYPEP_PARENTS` has no stream edges (ANSI: each of `file-stream` ... `concatenated-stream` is
directly below `stream`). `type-of` answers the internal layout / prelude class name, which no
`typep` arm knows (`(typep x (type-of x))` must be true), and `class-of` of an open stream names a
class `find-class` does not have on the compile paths.

## Plan

- Fixture first, four backends: `type-of` of each stream kind, `(typep s (type-of s))` computed,
  `subtypep` literal and computed of each subtype against `stream`, `class-of`.
- `type-of`: answer the standard name (`string-stream`, `file-stream`, `two-way-stream`, ...).
- `subtypep`: add the stream edges; mind the ancestor-table rows every computed-`subtypep`
  program regenerates (`.kb/declarations-type-checks.md`).
- `class-of`: a built-in class for the stream kinds, or the documented deviation.

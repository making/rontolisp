# d96. load and four stream built-ins signal a different error, or none, per backend

Difficulty: Medium

```lisp
(defmacro try (form) `(print (handler-case ,form (error (c) (type-of c)))))
(try (load "nope.lisp"))
(try (make-string-input-stream "abc" 5))
(try (get-output-stream-string 1))
(try (close 1))
```

Measured in call position; the function values (`#'load`, ...) answer the same.

| form | SBCL | interpreter | JVM | P1 / component |
|---|---|---|---|---|
| `(load "nope.lisp")` | `SIMPLE-FILE-ERROR` | `FILE-ERROR` | `SIMPLE-ERROR` | `NIL` (no signal) |
| `(make-string-input-stream "abc" 5)` | `BOUNDING-INDICES-BAD-ERROR` (a `type-error`) | `SIMPLE-ERROR` | `TYPE-ERROR` | `TYPE-ERROR` |
| `(get-output-stream-string 1)` | `SIMPLE-TYPE-ERROR` | `SIMPLE-ERROR` | `SIMPLE-ERROR` | trap (`cast failure`) |
| `(close 1)` | `TYPE-ERROR` | `T` (no signal) | `SIMPLE-ERROR` | `T` (no signal) |

## Plan

- Four-backend fixture first.
- `load` of a missing file: a `file-error` naming the path on every backend.
- The stream operators on a non-stream: a `type-error` (`.kb/error-handling.md`, "A wrong-type
  argument names its operator"); the bounds: `subseq`'s type-error.

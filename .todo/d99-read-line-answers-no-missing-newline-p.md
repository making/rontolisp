# d99. read-line answers no second value (missing-newline-p) on any backend

Difficulty: Medium

```lisp
(defvar *s* (make-string-input-stream (format nil "ab~%cd")))
(print (multiple-value-list (read-line *s*)))
(print (multiple-value-list (read-line *s*)))
(print (multiple-value-list (read-line *s* nil :eof)))
```

| | SBCL 2.2.9 | interpreter / JVM / P1 / component |
|---|---|---|
| line ending in a newline | `("ab" NIL)` | `("ab")` |
| last line, no newline | `("cd" T)` | `("cd")` |
| at end of file, `eof-error-p` nil | `(:EOF T)` | `(:EOF)` |

CLHS `read-line`: the second value is true when the line was ended by end of file.

## Plan

- Four-backend fixture first (string stream, file stream, standard input, a Gray
  stream's `stream-read-line`, `with-input-from-string`).
- The second value on every backend, through the multiple-value lowering
  (`.kb/multiple-values.md`) so a single-valued use pays nothing.

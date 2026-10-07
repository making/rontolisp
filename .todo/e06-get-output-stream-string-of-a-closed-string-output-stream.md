# e06. `get-output-stream-string` of a closed string output stream differs per backend

Difficulty: Low

```lisp
(defvar *o* (make-string-output-stream))
(write-string "abc" *o*)
(close *o*)
(handler-case (get-output-stream-string *o*) (error (c) (type-of c)))
```

Measured 2026-10-07:

| SBCL 2.2.9 | interpreter | JVM | P1 / component |
|---|---|---|---|
| `"abc"` | `SIMPLE-ERROR` (`%STRING-STREAM-CONTENTS expects a string output stream`) | `SIMPLE-ERROR` (a `NullPointerException`'s text) | trap (out of bounds array access) |

The same for the stream `with-output-to-string` bound, after the form returns (SBCL 2.2.9
there faults: `CORRUPTION WARNING ... Memory fault`). CLHS leaves a closed stream's
consequences undefined. `get-output-stream-string` refuses a stream of another kind by its
KIND, which a closed string output stream keeps, so it reaches `%string-stream-contents`.

## Plan

- Four-backend fixture first.
- One answer on all four: the text written before the close (SBCL's), or one catchable
  condition. The interpreter empties the stream table entry on close; the WASM record goes to
  kind 2 / slot -1.

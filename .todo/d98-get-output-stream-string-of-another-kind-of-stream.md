# d98. get-output-stream-string of a stream that is no string output stream differs per backend

Difficulty: Medium

```lisp
(handler-case (get-output-stream-string (make-string-input-stream "abc"))
  (error (c) (print (type-of c))))
```

| SBCL 2.2.9 | interpreter | JVM | P1 / component |
|---|---|---|---|
| `TYPE-ERROR` (expected `SB-IMPL::STRING-OUTPUT-STREAM`) | `SIMPLE-ERROR` (`%STRING-STREAM-CONTENTS expects a string output stream`) | unnamed `TYPE-ERROR` (`the value is not of the expected type`) | trap (`unreachable`) |

A non-stream is `GET-OUTPUT-STREAM-STRING`'s `STREAM` type-error on all four already
(`.kb/error-handling.md`, "The stream operators that take a stream").

## Plan

- Four-backend fixture first (a string input stream, a file stream, `*error-output*`, a
  synonym stream).
- A `type-error` naming the operator on every backend. No standard type names a string
  output stream; the honest expected type is a compound one, e.g. `(AND STRING-STREAM
  (SATISFIES OUTPUT-STREAM-P))`, which needs the wasm landing's compound-type path
  (`OperandTypes.FILL_POINTER_VECTOR_TYPE` precedent).

# i39. fast-http's own tests fail on CONNECT and on a HEAD response

Difficulty: Medium

Measured 2026-11-05 on the interpreter. `(asdf:test-system :fast-http-test)` runs through; in `t/parser.lisp` 2 of 64
fail, and the `CONNECT request` subtest aborts `t/fast-http.lisp` (1 of 39). Not compared against SBCL: its
quicklisp is not set up here, so whether the HEAD failure is upstream's own is open.

- `CONNECT request`: `Raised an error INVALID-METHOD: invalid HTTP method`. `proc-parse:match-case` alone answers
  `:CONNECT` for `"CONNECT x"`, so the error comes from what follows: `parse-method`'s `(= (current) +space+)`
  or a second parse of the bytes past an upgrade.
- `Head response with Content-Length`: `:COMPLETE NIL` where the test expects a complete message.

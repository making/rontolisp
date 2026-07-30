# A `go` that crosses a lambda boundary is a call-time error on the compile backends

Found while making quri load (2026-07-30). Not caused by that work -- a
pre-existing gap that quri is the first loadable library to walk into.

## Symptom

A `go` whose tag belongs to a `tagbody` in an ENCLOSING FUNCTION works on the
interpreter and fails at CALL TIME on the JVM and both WASM backends:

```lisp
(define-condition my-err (error) ())
(defun f (x)
  (tagbody
   top
     (handler-bind ((my-err (lambda (e) (declare (ignore e)) (go done))))
       (when (> x 0) (error 'my-err))
       (princ "no-error"))
   done)
  :ok)
(print (f 1))   ; interpreter: :OK
                ; JVM: RuntimeException: GO tag DONE has no lexically enclosing
                ;      tagbody: the compilers support go within the same function only
                ; WASM: `unreachable` trap
```

The compilers lower `tagbody`/`go` within one function (a loop plus a
program-counter dispatch, `.kb/do-return-block.md`); a tag reached from inside a
nested `lambda` has no such lowering and gets the loud stub instead.

Note the asymmetry: a **`return-from` that crosses a lambda** IS supported on
every backend -- `compiler/CrossLambdaExitLowering` turns it into a block-exit
throw and the named block into a catch. `go` never got the same treatment.

## What it costs today

quri's `:lenient` percent-decoding is exactly this shape: `url-decode` and
`url-decode-params` install a `handler-bind` for `url-decoding-error` whose
handler does `(go parsing)` / `(go continue)` to skip the malformed escape and
carry on. So on the three compile backends:

- `(quri:url-decode "%ZZ" :lenient t)` and
  `(quri:url-decode-params "a=1&%ZZ=2" :lenient t)` crash instead of skipping;
- and because **`quri:uri-query-params` defaults to `:lenient t`**, a query
  string carrying a malformed escape crashes there too. Well-formed input never
  reaches the handler and works on all four backends (that is what
  `QuriE2e`-style exercises cover).

Documented as a lite limitation in `doc/*/guides/asdf-systems.md` (the quri row)
and `doc/*/guides/missing-features.md`.

## The fix

Extend the cross-lambda exit lowering to `go`, which is harder than
`return-from` and in an interesting way: a block exit LEAVES the block, so a
throw/catch pair is enough, while a `go` RE-ENTERS its tagbody at a label and
keeps going. The shape that should work, since `tagbody` is already a loop plus a
PC dispatch:

1. `CrossLambdaExitLowering` (or a sibling) detects a `go` whose tag is
   established by a `tagbody` outside the innermost enclosing `lambda`, and
   rewrites it to a tag-keyed throw (the `%nlx-throw` machinery `catch`/`throw`
   already uses -- `.kb/do-return-block.md`).
2. The owning `tagbody` wraps its loop in the matching catch. On catching, it
   sets the PC to the thrown tag's index and continues the loop rather than
   returning.
3. The throw must carry the tagbody INSTANCE, not just the tag name, so a
   recursive function's inner activation does not catch an outer one's `go`
   (the same freshness problem `%block` solves for named blocks).
4. Gate it like the `return-from` lowering: the presence of such a `go` forces
   EH mode, so the wasm runs need `-W exceptions=y` (they already do for any
   `handler-bind` program).

Acceptance: the reproduction above prints `:OK` / `no-error:OK` on all four
backends, and `(quri:url-decode-params "a=1&%ZZ=2" :lenient t)` answers
`(("a" . "1") ("%ZZ" . "2"))` on all four. Add a ci-spec case (the shape is
socket-free and needs no asdf load) and drop the lite limitation from the two
guides plus the quri row.

# 407. `(return ...)` in a handler-bind handler is caught by a dynamically active `block nil`

Difficulty: Medium

Found while running a rove suite for a cl-postgres client library on the
interpreter. `return` / `return-from nil` inside a `handler-bind` handler
resolves to the innermost **dynamically active** `nil` block -- the implicit one
a `loop` / `dolist` / `dotimes` / `do` establishes in the function that
SIGNALLED -- instead of the `block nil` that lexically encloses the handler.

```lisp
(define-condition my-error (error) ())
(defun raise () (error 'my-error))

(defun sig-nil (thunk)                      ; rove's SIGNALS, in essence
  (block nil
    (handler-bind ((condition (lambda (c) (return c))))
      (funcall thunk)
      nil)))

(defun sig-named (thunk)                    ; the same thing with a named block
  (block outer
    (handler-bind ((condition (lambda (c) (return-from outer c))))
      (funcall thunk)
      nil)))

(sig-nil   (lambda () (raise)))                                    ; #<MY-ERROR>  ok
(sig-nil   (lambda () (loop :for i :from 1 :to 3 :collect (raise)))) ; NIL         WRONG
(sig-named (lambda () (loop :for i :from 1 :to 3 :collect (raise)))) ; #<MY-ERROR>  ok
```

The handler lambda closes over the `block nil` of `sig-nil`; the `loop` is in a
different function entirely, so no lexical block sits between them. SBCL answers
`#<MY-ERROR>` for all three.

Which enclosing form swallows the exit, all in the SIGNALLING function:

| form around the `error` call | `(return c)` lands where |
| --- | --- |
| `loop ... collect` / `do` / `sum` | the loop -- WRONG |
| `dolist`, `dotimes`, `do` | the iteration -- WRONG |
| `mapcar`, `tagbody`, `catch`, `unwind-protect` | the lexical `block nil` -- ok |

So it is exactly the iteration macros, i.e. exactly the forms CL gives an
implicit `block nil`. A named block is never affected, which is what points at
name-keyed lookup down a dynamic stack rather than lexical block identity.

**Interpreter only.** The same file compiled with `-o Blk.class` (JVM) and with
`-o blk.wasm` (wasm-GC, `-W gc=y -W exceptions=y`) answers `#<MY-ERROR>` in
every row -- the compiled backends already resolve the block lexically.

## Why it matters

rove's `signals` expands to precisely the broken shape:

```lisp
(let ((g 'the-type))
  (typep (block nil
           (handler-bind ((condition (lambda (c) (when (typep c g) (return c)))))
             FORM
             nil))
         g))
```

So `(ok (signals (f ...) 'my-error))` reports a plain assertion FAILURE -- not an
error, not a hint -- whenever `f` raises from inside a `loop`/`dolist`/`dotimes`,
which is where a library validating a list of things naturally raises. Worse than
the false negative: the `(return c)` that lands in the signalling function's loop
makes that loop RETURN THE CONDITION OBJECT as its value, so the caller carries on
with a condition where it expected data. In the sighting that found this, a
parameter list came back as a `parameter-error` instance and died two frames later
inside cl-postgres as `The value of CL-POSTGRES::PARAMETERS is
#<PARAMETER-ERROR ...>, which is not of type LIST` -- a type error naming a
library that had done nothing wrong.

Related but distinct: `.todo/393` (an inner `handler-case` does not shadow an
enclosing `handler-bind`) and `.todo/394` (`#'coerce` and friends are not
first-class) are the other two ways a rove suite over ordinary CL breaks today.
All three surfaced in the same suite.

## Sketch

Give `block` a fresh identity object at evaluation time, capture that identity in
the lexical environment the `block` body (and therefore any `lambda` inside it)
closes over, and have `return-from` resolve the NAME through that environment to
an identity before it unwinds -- rather than searching a dynamic stack for the
nearest frame whose block name is `nil`. The compiled backends already do the
lexical thing, so their behaviour is the oracle.

Watch:
- an escaped exit (the block is no longer active) must still be the current
  error, not a silent no-op;
- `loop`'s own `(return ...)` and `return-from` with an explicit name must stay
  byte-identical -- `.todo/029` owns the loop side;
- the same lookup serves `go`/`tagbody`, whose interpreter-only dynamic form is
  a documented FEATURE (a tag established by the caller) -- do not lose it.

Pin with the three-line repro above in `LispEvaluatorTest` plus the
`compileAndRun` twins, and with a rove-shaped test: `(ok (signals (loop-raiser)
'my-error))`.

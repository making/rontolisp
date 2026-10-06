# d63. WASM symbol-function of an fmakunbound name traps instead of signalling

Difficulty: Medium

`(fmakunbound s)` leaves a tombstone in the function namespace; `(symbol-function s)` on a
computed `s` then answers `undefined-function` on the interpreter and the JVM, but traps
(`unreachable`) on WASM Preview 1 and the component, so no handler can catch it.

```lisp
(defun f () 1)
(defvar *n* (intern "F"))
(fmakunbound *n*)
(print (handler-case (symbol-function *n*) (error (c) (type-of c))))   ; UNDEFINED-FUNCTION in SBCL
```

The tombstone arm of `WasmFunctionFormCompiler.compileSymbolFunction` is the one `unreachable`
left there. The other unbound arms (nil, non-string, registry miss) now report through the
arity-0 dispatcher, which cannot be reused here: the retired name may still be in the
compiled-function registry, so the dispatcher would call it. The arm needs its own
`undefined-function` throw (the `NotFunctionReport` text and condition instance, without the
registry lookup).

## Plan

- Failing cross-backend test first, using the program above in a fixture next to
  `UnboundFunctionDesignatorFixture`.
- Factor the dispatcher's undefined-function throw into something callable from an expression
  with a name already in hand, and use it for the tombstone arm.

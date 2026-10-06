# d79. funcall of a computed name ignores the runtime function namespace on the compilers

Difficulty: Medium

A SYMBOL designator reaching a dispatcher resolves through the compiled-function registry
only (`_lookup`); the eval runtime's function namespace (`_fenv` on the JVM, `GLOBAL_FENV` on
WASM) is never probed, although `symbol-function`, `fboundp` and `_apply` probe it first.

```lisp
(defun f () 1)
(fmakunbound (intern "F"))
(funcall (intern "F"))                                   ; SBCL/interpreter: undefined-function; JVM, P1, component: 1
(eval (read-from-string "(defun zz () 5)"))
(funcall (intern "ZZ"))                                  ; SBCL/interpreter: 5; compilers: undefined-function
(setf (symbol-function (intern "QQ")) (lambda () 7))
(funcall (intern "QQ"))                                  ; SBCL/interpreter: 7; compilers: undefined-function
```

`(apply (intern "F") nil)` after the `fmakunbound` signals on the compilers, but as
`The function NIL is undefined` (the tombstone's nil reaches the dispatcher), against the
interpreter's `The function F is undefined`.

`doc/en/reference/functions/fmakunbound.md` (and the ja mirror) already claims `funcall`
through the symbol sees the retirement on the compiled backends.

## Plan

- Fixture beside `RetiredFunctionDesignatorFixture`, pinned on all four backends.
- The dispatcher prologues (WASM `WasmRuntimeBuilder.emitDispatchPrologue`, the JVM's symbol
  arm) probe the function namespace before the registry where the eval runtime exists; a
  tombstone reports the symbol (WASM: the `$undefined` arm), a binding replaces the symbol
  with its function. Gate it so a program without a namespace writer is unchanged.

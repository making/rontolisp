# h83. `defgeneric` inside a top-level `let` is refused on the compile paths

Difficulty: Medium

```lisp
(let ((k 2))
  (defgeneric g (x))
  (defmethod g ((x integer)) (* k x)))
(print (g 3))
```

Interpreter: `6`. JVM/WASM: `DEFGENERIC is only supported as a top-level form`. `.kb/clos.md`
allows exactly one non-top-level shape, a `defmethod` in a top-level `let`/`let*` body; a
`defgeneric` (and probably `defclass`) beside it is not admitted.

Blocks cl-qrencode on the compiled backends (`rs-ecc/rs-ecc.lisp:17` wraps its methods and
generics in `(let ((gf256 ...)) ...)`). A `defgeneric` captures nothing, so hoisting it out of the
`let` is semantically safe; decide the general rule for the other definers in the same body.

# 479. JVM `aref` on a packed float array ignores the subscript count

Difficulty: Low

Found 2026-08-22 while testing `.todo/478` (a `linalg:to-list` over a rank-3 array
compiled fine on the JVM and signalled on the interpreter):

```lisp
(print (aref (linalg:reshape (linalg:arange 8) '(2 2 2)) 0 1))
```

- interpreter: `error: aref: expected 3 subscripts, got 2` (CL: an error)
- JVM class: prints `1.0` -- the two subscripts are applied as if the array had two
  dimensions (`0 * 2 + 1`), silently.

wasm not checked. The packed float-array `_fvAref*` helpers (`JvmFloatArrayRuntimeBuilder`)
take the subscripts positionally and never compare their count with the rank. Add the
check to the JVM helpers (and wasm if it has the same hole), with a `JvmLispCompilerTest`
case pinning the error on both backends; `linalg:to-list` itself is rank <= 2 by design
and is not the bug.

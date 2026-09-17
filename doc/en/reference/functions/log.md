# log

`(log number)` `(log number base)`

Returns the natural logarithm (base e) of `number` as a float. With a `base` the answer is the logarithm of `number` in that base, which is exactly the quotient `(/ (log number) (log base))` -- no special case makes an exact power exact, and none is needed: `(log 8 2)` is `3.0` and `(log 1024 2)` is `10.0` from the plain quotient on every backend. Every backend computes it with fdlibm (`StrictMath.log` on the interpreter and the JVM, the same algorithm on WASM), so the digits agree everywhere. The IEEE edges match everywhere: `(log 0.0)` is `-Infinity`. A negative argument leaves the real line and answers the principal logarithm in the complex plane, the way `sqrt` roots a negative: `(log -1)` is `#C(0.0 3.141592653589793)`, and `(log -100)` has real part `ln 100` and imaginary part pi. The type of the answer therefore depends on the value, not on how the argument was written -- `(let ((x -1d0)) (log x))` is complex on every backend.

Each logarithm takes that escape on its own, so `(log -8d0 2d0)` answers the plane -- real part `3.0`, imaginary part `pi / ln 2` -- and a complex `number` or `base` is likewise just the quotient of two complex logarithms. The real part is exact there for the same reason `(log 8 2)` is: a real base makes the complex division divide each part on its own (see [`/`](div.md)), so the real part is the one rounded quotient `ln 8 / ln 2`.

```lisp
(log 1) ; => 0.0
```

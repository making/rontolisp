# sqrt

`(sqrt number)`

Returns the square root of `number` as a float, even when the argument is a perfect square (`(sqrt 16)` is `4.0`, not `4`). The result is always a double-precision float. Works in all three backends (interpreter, JVM, WASM).

```lisp
(sqrt 2) ; => 1.4142135623730951
```

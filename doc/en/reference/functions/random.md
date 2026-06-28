# random

`(random limit)`

Returns a random number in the half-open interval `[0, limit)`. The result type follows the limit: an integer limit yields an integer, a float limit yields a float (so `(random 1)` is always `0`). The interpreter and JVM draw from `Math.random`; WASM draws real entropy from the WASI host (`random_get` in Preview 1, `wasi:random` under `--component`), so the sequence differs on every run.

```lisp
(random 1) ; => 0
```

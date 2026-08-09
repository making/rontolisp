# random

`(random limit &optional random-state)`

Returns a random number in the half-open interval `[0, limit)`. The result type follows the limit: an integer limit yields an integer, a float limit yields a float (so `(random 1)` is always `0`). The interpreter and JVM draw from `Math.random`; WASM draws real entropy from the WASI host (`random_get` in Preview 1, `wasi:random` under `--component`), so the sequence differs on every run. A `--no-wasi` module has no host to draw from and carries its own generator instead -- see the [randomness guide](../../guides/random.md). The optional random-state argument is accepted and ignored (evaluated for effect): no random-state objects exist here — [`make-random-state`](make-random-state.md) answers `nil` — and the backend's own entropy always draws.

```lisp
(random 1) ; => 0
```

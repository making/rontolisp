# random

`(random limit &optional random-state)`

Returns a random number in the half-open interval `[0, limit)`. The result type follows the limit: an integer limit yields an integer, a float limit yields a float (so `(random 1)` is always `0`). Every backend draws from a pseudo-random generator inside the program — `ThreadLocalRandom` on the interpreter and JVM, a built-in generator on WASM — rather than from the host once per draw, which is what makes a draw cost a few nanoseconds. Where there is a host, that generator is seeded from its entropy once per run (WASI `random_get` in Preview 1, `wasi:random` under `--component`), so the sequence still differs on every run; a `--no-wasi` module has no host to ask and repeats one sequence unless it is seeded — see the [clock and randomness guide](../../guides/clock-and-random.md). For unpredictable bytes use [`rontolisp:random-bytes`](rontolisp-random-bytes.md), which is the entropy API. The optional random-state argument is accepted and ignored (evaluated for effect): no random-state objects exist here — [`make-random-state`](make-random-state.md) answers `nil`.

```lisp
(random 1) ; => 0
```

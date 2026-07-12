# linalg:seed

`(linalg:seed n)`

Resets the shared linalg random generator deterministically from a non-negative integer seed and returns `n`. The generator is a Wichmann-Hill combination whose draws are exact integer arithmetic plus IEEE double operations, so a seeded [`linalg:rand`](linalg-rand.md) / [`linalg:randn`](linalg-randn.md) / [`linalg:uniform`](linalg-uniform.md) / [`linalg:choice`](linalg-choice.md) / [`linalg:permutation`](linalg-permutation.md) sequence is bit-identical on every backend (interpreter, JVM and WASM).

```lisp
(linalg:seed 42) ; => 42
```

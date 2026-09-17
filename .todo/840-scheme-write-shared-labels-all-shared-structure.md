# Scheme: `write-shared` must label ALL shared structure, not only cycles

Difficulty: Medium

Left by `.todo/835` (2026-09-17). R7RS 6.13.3: `write-shared` uses datum labels for every
pair or vector that occurs more than once, `write` only for cycles. Today both label only
cycles, so

```scheme
(define x (list 1 2))
(write-shared (list x x))   ; R7RS: (#0=(1 2) #0#)   today: ((1 2) (1 2))
```

## To do

1. Failing `scheme-spec.yaml` case (all four backends) for shared-not-cyclic structure,
   plus nested sharing and a vector.
2. Implement in `scheme.lisp` beside `.todo/835`'s cycle walk; `write` and `display`
   output must not change. Keep `.todo/835`'s size/time numbers in `.kb/scheme-frontend.md`
   honest: re-measure the wasm/class size of a program that calls only `display`.

# torch:shuffled-batches

`(torch:shuffled-batches data batch-size &key shuffle drop-last)`

Cuts `data` into mini-batches and returns them as a list of lists, each of `batch-size` elements except possibly the last -- dropped under `:drop-last t`, like a `DataLoader`'s `drop_last`. `data` is a **list** of examples, or a non-negative **integer** `n` standing for the index list `0..n-1`, which is the spelling that batches several parallel arrays at once (the caller selects the same rows out of each).

The order comes from the seeded [`linalg:seed`](linalg-seed.md) generator, so an epoch reproduces on every backend; `:shuffle nil` keeps `data`'s own order, so an evaluation pass uses the same function.

```lisp
(linalg:seed 1)
(torch:shuffled-batches 7 3)                         ; => ((6 0 5) (1 4 3) (2))
(torch:shuffled-batches '(a b c d e) 2 :shuffle nil) ; => ((A B) (C D) (E))
(torch:shuffled-batches '(a b c d e) 2 :shuffle nil :drop-last t) ; => ((A B) (C D))
```

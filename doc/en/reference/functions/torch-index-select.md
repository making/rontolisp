# torch:index-select

`(torch:index-select a idx)`

Differentiable axis-0 slice selection (`linalg:take-rows`) -- the embedding lookup: row `idx[i]` of the table for each `i`, any rank >= 1, and the same index may repeat. The backward pass scatter-adds each output slab's gradient back into its source row, so a row selected twice accumulates both contributions (the shared-embedding case).

```lisp
(torch:data (torch:index-select (torch:tensor '((1.0 2.0) (3.0 4.0))) #(1 0 1)))
; => #f((3.0 4.0) (1.0 2.0) (3.0 4.0))
```

# torch:padding-mask

`(torch:padding-mask tokens &key pad-id)`

Returns the padding mask of a `(batch length)` token matrix: `1.0` at every position holding `pad-id` (`0` by default) and `0.0` elsewhere, with a query axis inserted -- `(batch 1 length)` -- so it broadcasts over an attention score's `(batch query-length key-length)`.

The result is a **raw linalg array**, not a tensor: a mask is a constant, and [`torch:masked-fill`](torch-masked-fill.md) takes it as one. Combine it with [`torch:subsequent-mask`](torch-subsequent-mask.md) using `linalg:add` or `linalg:maximum` -- every non-zero counts as masked.

```lisp
(defparameter *tokens* (torch:pad-sequence '((1 2 3) (4 5))))
(torch:padding-mask *tokens*)                ; => #d(((0.0 0.0 0.0)) ((0.0 0.0 1.0)))
(linalg:shape (torch:padding-mask *tokens*)) ; => (2 1 3)
```

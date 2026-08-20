# torch:subsequent-mask

`(torch:subsequent-mask sequence-length)`

Returns the causal (look-ahead) mask of a sequence: `1.0` strictly **above** the diagonal, shaped `(1 sequence-length sequence-length)` so it broadcasts over the batch. Position `i` may not attend to any `j > i`.

Like [`torch:padding-mask`](torch-padding-mask.md) it is a raw linalg array. Filling the masked scores with `-infinity` before [`torch:softmax`](torch-softmax.md) is the masked-attention idiom, and the masked weight comes out as exactly `0.0`.

```lisp
(torch:subsequent-mask 3) ; => #d(((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0)))
(defparameter *scores* (torch:tensor (linalg:ones '(1 2 2))))
(torch:data (torch:softmax (torch:masked-fill *scores* (torch:subsequent-mask 2) (/ -1.0 0.0))
                           :axis -1))
; => #f(((1.0 0.0) (0.5 0.5)))
```

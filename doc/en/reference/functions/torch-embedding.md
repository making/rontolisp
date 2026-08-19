# torch:embedding

`(torch:embedding num-embeddings embedding-dim)`

Returns an embedding table (PyTorch's `nn.Embedding`): the single field `:weight`, a `(num-embeddings embedding-dim)` parameter drawn from the standard normal like PyTorch's default. The forward takes integer indices of **any** shape and returns them with the embedding axis appended; a row selected twice accumulates both gradients ([`torch:index-select`](torch-index-select.md)'s adjoint).

```lisp
(defparameter *emb* (torch:embedding 4 2))
(torch:set-field *emb* :weight
                 (torch:parameter '((0.0 1.0) (2.0 3.0) (4.0 5.0) (6.0 7.0))))
(torch:data (torch:forward *emb* #(2 0)))            ; => #d((4.0 5.0) (0.0 1.0))
(torch:shape (torch:forward *emb* #2A((1 2) (3 0)))) ; => (2 2 2)
```

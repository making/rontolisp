# torch:pad-sequence

`(torch:pad-sequence sequences &key padding-value)`

Returns a list of variable-length sequences (lists, index vectors or tensors) as one padded rank-2 tensor, **batch first**: `(batch longest)`, every row filled up to the longest one with `padding-value` (`0` by default). This is `torch.nn.utils.rnn.pad_sequence` with `batch_first=True`.

The result is a constant tensor of token indices, ready for [`torch:embedding`](torch-embedding.md) and for [`torch:padding-mask`](torch-padding-mask.md).

```lisp
(torch:data (torch:pad-sequence '((1 2 3) (4 5) (6))))
; => #d((1.0 2.0 3.0) (4.0 5.0 0.0) (6.0 0.0 0.0))
(torch:shape (torch:pad-sequence '((1 2) (3)) :padding-value 9)) ; => (2 2)
```

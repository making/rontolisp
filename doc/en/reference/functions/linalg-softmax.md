# linalg:softmax

`(linalg:softmax array &key axis)`

Returns the softmax of `array`: `exp(x - max)` normalized to sum to 1. With no `:axis` the whole array is one distribution (scipy's `softmax` default); with an integer `:axis` (negative counting from the end) each slice along that axis is normalized on its own, which is the attention-weight form -- torch's `softmax(x, dim)`. The maximum is subtracted first, so a large logit cannot overflow, and an element of `-infinity` (a masked position, see [`linalg:where`](linalg-where.md)) comes out as exactly `0.0`.

Like [`linalg:relu`](linalg-relu.md), `softmax` is not in numpy proper -- it lives here because it is the array-level primitive an activation layer needs. The logarithm is [`linalg:log-softmax`](linalg-log-softmax.md).

```lisp
(linalg:softmax #(1 1 1 1))               ; => #d(0.25 0.25 0.25 0.25)
(linalg:softmax #2A((0 0) (1 1)) :axis 1) ; => #d((0.5 0.5) (0.5 0.5))
```

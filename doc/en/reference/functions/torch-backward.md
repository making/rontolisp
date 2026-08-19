# torch:backward

`(torch:backward tensor)`

Runs reverse-mode automatic differentiation from a scalar (one-element) tensor: seeds its gradient with `1.0`, walks the recorded tape in reverse topological order, and accumulates each operation's input gradients into its parents -- so a tensor reached over more than one path (a residual connection, a reused embedding row) collects the sum. Read the results with [`torch:grad`](torch-grad.md); returns `nil`. A tensor with more than one element signals.

Gradients are retained on intermediate tensors too, and repeated backward calls keep accumulating -- clear parameters with [`torch:zero-grad`](torch-zero-grad.md) between training steps.

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(defparameter *loss* (torch:sum (torch:mul *w* *w*)))
(torch:backward *loss*)
(torch:grad *w*) ; => #d(2.0 4.0)
```

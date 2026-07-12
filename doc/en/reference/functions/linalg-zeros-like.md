# linalg:zeros-like

`(linalg:zeros-like array)`

Creates a zero-filled array with the input's shape *and* element width (numpy's `np.zeros_like`): a packed single-float (`#f`) input gives a `#f` result, anything else a packed double-float one. Unlike [`linalg:zeros`](linalg-zeros.md), which takes a shape designator, it takes the array whose shape is wanted -- the gradient-accumulator idiom.

```lisp
(linalg:zeros-like #2A((1 2) (3 4))) ; => #d((0.0 0.0) (0.0 0.0))
```

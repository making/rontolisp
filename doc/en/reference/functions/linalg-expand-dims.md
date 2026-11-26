# linalg:expand-dims

`(linalg:expand-dims array axis)`

Returns a copy of `array` with a new axis of extent 1 inserted at `axis` (numpy's `np.expand_dims`, torch's `unsqueeze`). A negative `axis` counts from the end of the *result*, so `-1` appends the new axis. The row-major element order is unchanged -- only the shape is -- and the element width follows the input. The inverse is [`linalg:squeeze`](linalg-squeeze.md).

```lisp
(linalg:expand-dims #(1 2 3) 0)  ; => #d((1.0 2.0 3.0))
(linalg:expand-dims #(1 2 3) -1) ; => #d((1.0) (2.0) (3.0))
```

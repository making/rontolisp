# linalg:pad

`(linalg:pad array pads)`

Zero padding (numpy's `np.pad` in its default constant mode): returns a fresh array with `array` copied into the interior and `0.0` everywhere else. `pads` is a list of `(before after)` pairs, one per axis -- or a single non-negative integer applied to both sides of every axis. The result keeps the input's element width (a `#f` array pads to `#f`).

```lisp
(linalg:pad #(1 2) 1)                        ; => #d(0.0 1.0 2.0 0.0)
(linalg:pad #2A((1 2) (3 4)) '((0 0) (1 1))) ; => #d((0.0 1.0 2.0 0.0) (0.0 3.0 4.0 0.0))
```

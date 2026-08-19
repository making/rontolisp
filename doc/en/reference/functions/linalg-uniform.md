# linalg:uniform

`(linalg:uniform lo hi shape &key element-type)`

Returns an array of uniform draws in `[lo, hi)` (numpy's `np.random.uniform`, but with a required shape designator like [`linalg:zeros`](linalg-zeros.md); double by default, `:element-type 'single-float` for `#f`). Each element is `lo + (hi - lo) * u` for a `[0, 1)` draw `u` from the shared generator, so a sequence seeded with [`linalg:seed`](linalg-seed.md) is the same on every backend.

```lisp
(linalg:seed 7) ; => 7
(linalg:emap (lambda (x) (truncate x)) (linalg:uniform 10 20 4)) ; => #d(15.0 15.0 18.0 12.0)
```

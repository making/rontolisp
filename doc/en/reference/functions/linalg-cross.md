# linalg:cross

`(linalg:cross a b)`

The 3-D cross product (numpy `np.cross`, without its `axis`/`axisa`/`axisb`/`axisc` broadcasting keywords). Two length-3 vectors give the length-3 cross product, keeping `a`'s element width, like [`linalg:add`](linalg-add.md). Two length-2 vectors give the scalar z component of the cross product of the vectors extended with a zero third coordinate -- numpy's own 2-D case. Any other rank or length signals a shape error.

```lisp
(linalg:cross #(1 0 0) #(0 1 0)) ; => #d(0.0 0.0 1.0)
(linalg:cross #(1 2 3) #(4 5 6)) ; => #d(-3.0 6.0 -3.0)
(linalg:cross #(1 2) #(3 4))     ; => -2
```

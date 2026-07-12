# linalg:randn

`(linalg:randn shape &optional element-type)`

Returns an array of standard-normal draws (numpy's `np.random.randn`, but taking a shape designator like [`linalg:zeros`](linalg-zeros.md); double by default, `'single-float` for `#f`). The Gaussians are built by Irwin-Hall -- the sum of 12 uniforms minus 6 -- rather than Box-Muller, so a sequence seeded with [`linalg:seed`](linalg-seed.md) stays bit-identical across backends (WASM's `log`/`cos` are polynomial approximations); the tails clip at +/- 6 sigma, which is fine for weight initialization but not a distribution-exact `np.random.randn`.

```lisp
(linalg:seed 42) ; => 42
(linalg:emap (lambda (x) (truncate (* 1024 x))) (linalg:randn 4)) ; => #d(164.0 -469.0 -1782.0 -1292.0)
```

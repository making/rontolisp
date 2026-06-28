# do*

`(do* ((var init step?)...) (end-test result...) body...)`

Like `do`, but the bindings and steps are sequential (`let*`-style) rather than parallel: each `init` form sees the variables bound earlier in the same list, and each `step` form sees the variables already updated this iteration. Otherwise it behaves identically -- `end-test` is checked before each pass, the `result` forms supply the return value, and `return` exits early. In the example below `j` is initialised from the freshly bound `i`, which a plain `do` could not do.

```lisp
(do* ((i 5) (j i)) (t (list i j))) ; => (5 5)
```

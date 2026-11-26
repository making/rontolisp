# +

`(+ &rest numbers)`

Returns the sum of its arguments, or `0` with no arguments. The result is an integer when all arguments are integers, a ratio when ratios are involved, and a float if any argument is a float (contagion). Integer results promote to big integers on overflow, on every backend. The sum runs left to right one pair at a time, so the arguments ahead of the first float add exactly and only their sum turns into a float: `(+ 1/10 1/5 0.0)` is `0.3`, not the `0.30000000000000004` of converting each argument first, as in SBCL. A complex argument folds the same way: `(+ z (- z) 1.5)` over an exact complex `z` is `1.5`.

```lisp
(+ 1 2 3) ; => 6
```

```lisp
(+ 1.5 2.5) ; => 4.0
```

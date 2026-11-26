# *

`(* &rest numbers)`

Returns the product of its arguments, or `1` with no arguments. The result is an integer when all arguments are integers, exact when ratios are involved, and a float if any argument is a float. Integer results promote to big integers on overflow, on every backend. The product runs left to right one pair at a time, so the arguments ahead of the first float multiply exactly and only their product turns into a float: `(* 1/10 3 1.0)` is `0.3`, as in SBCL.

```lisp
(* 3 4) ; => 12
```

```lisp
(* 2.0 3.0) ; => 6.0
```

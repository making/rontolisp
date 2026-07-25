# *

`(* &rest numbers)`

Returns the product of its arguments, or `1` with no arguments. The result is an integer when all arguments are integers, exact when ratios are involved, and a float if any argument is a float. On the interpreter and JVM, integer results promote to big integers on overflow; on WASM integer results are exact within the signed 64-bit range and wrap beyond it.

```lisp
(* 3 4) ; => 12
```

```lisp
(* 2.0 3.0) ; => 6.0
```

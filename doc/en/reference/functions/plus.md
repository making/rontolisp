# +

`(+ &rest numbers)`

Returns the sum of its arguments, or `0` with no arguments. The result is an integer when all arguments are integers, a ratio when ratios are involved, and a float if any argument is a float (contagion). On the interpreter and JVM, integer results promote to big integers on overflow; the WASM backend uses 31-bit integers with no promotion.

```lisp
(+ 1 2 3) ; => 6
```

```lisp
(+ 1.5 2.5) ; => 4.0
```

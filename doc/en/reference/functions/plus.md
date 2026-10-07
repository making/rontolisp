# +

`(+ &rest numbers)`

Returns the sum of its arguments, or `0` with no arguments. The result is an integer when all arguments are integers, a ratio when ratios are involved, and a float if any argument is a float (contagion). Integer results promote to big integers on overflow, on every backend. With a complex argument the sum still runs left to right one pair at a time, so an exact step stays exact whatever float follows: `(+ z (- z) 1.5)` over an exact complex `z` is `1.5`, as in SBCL.

```lisp
(+ 1 2 3) ; => 6
```

```lisp
(+ 1.5 2.5) ; => 4.0
```

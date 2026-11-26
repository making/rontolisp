# lcm

`(lcm &rest integers)`

Returns the least common multiple of its integer arguments. It is variadic; with no arguments it returns `1`, and the result is `0` if any argument is `0`. The result is always a non-negative integer.

```lisp
(lcm 4 6) ; => 12
```

```lisp
(lcm 2 3 4) ; => 12
```

# isqrt

`(isqrt natural)`

Returns the integer square root of a non-negative integer: the largest integer whose square does not exceed the argument (the floor of the real square root). Unlike `sqrt`, the result is an integer rather than a float. Works in all three backends.

```lisp
(isqrt 17) ; => 4
```

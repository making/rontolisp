# expt

`(expt base power)`

Returns `base` raised to `power`. With integer arguments the result is an exact integer (`(expt 2 10)` is `1024`); if either argument is a float the result is a float. Works in all three backends.

```lisp
(expt 2 10) ; => 1024
```

```lisp
(expt 2.0 3) ; => 8.0
```

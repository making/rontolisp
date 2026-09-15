# rationalize

`(rationalize number)`

Returns the simplest rational that approximates a float within half a ulp on either side, so floating the answer reproduces the input. Integers and ratios are already exact, so they are returned unchanged.

```lisp
(rationalize 0.1) ; => 1/10
```

```lisp
(rationalize 1.5) ; => 3/2
```

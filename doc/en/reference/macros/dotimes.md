# dotimes

`(dotimes (var count [result]) body...)`

Evaluates `count` once, then runs the body repeatedly with `var` bound to the successive integers `0` through `count-1`. After the loop finishes, `var` is bound to `count` and the optional `result` form is evaluated and returned (nil if omitted). It expands into a counting loop wrapped in the internal block boundary, so `return` inside the body exits the loop.

```lisp
(dotimes (i 3) (format t "~d~%" i))
```

```
0
1
2
```

# map-into

`(map-into result-sequence function &rest sequences)`

Applies `function` to successive elements of the given `sequences` and stores the results destructively into `result-sequence` -- which may be a list or a vector -- then returns `result-sequence`. The function is called with one element from each sequence in parallel, and iteration stops at the end of the shortest sequence, the result sequence included; any remaining elements of `result-sequence` are left unchanged. With no source sequences the function is called with no arguments to fill each element. A result with a fill pointer is filled up to that fill pointer.

```lisp
(map-into (list 0 0 0 0) #'+ '(1 2 3) '(10 20 30 40)) ; => (11 22 33 0)
```

```lisp
(map-into (make-array 3) #'* #(2 3 4) #(5 6 7)) ; => #(10 18 28)
```

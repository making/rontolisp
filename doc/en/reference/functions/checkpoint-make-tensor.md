# checkpoint:make-tensor

`(checkpoint:make-tensor shape element-type)`

A packed float array of `shape` -- a dimension list, or one integer for a vector -- and `element-type` -- `single-float`, `double-float`, or `bfloat16` on the interpreter and the JVM -- filled with zeros. Unlike a bare `make-array`, it **verifies** that the array it got is packed: `make-array :element-type` answers a boxed general array for an element type it does not recognise instead of signalling, and a checkpoint read into one would only show as slow, wrong output much later. This is the one allocation path the readers use.

```lisp
(checkpoint:make-tensor 3 'single-float)
; => #f(0.0 0.0 0.0)
```

```lisp
(array-dimensions (checkpoint:make-tensor '(2 3) 'double-float))
; => (2 3)
```

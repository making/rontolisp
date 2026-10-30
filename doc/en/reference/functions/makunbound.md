# makunbound

`(makunbound symbol)`

Makes `symbol`'s current value binding unbound, and returns `symbol`. Inside a dynamic binding of the variable (`let`, a parameter, `progv`) that binding loses its value and the previous value comes back when the binding ends; otherwise the global value is removed. [`boundp`](boundp.md) then answers `nil`, and a read of the variable (directly, through [`symbol-value`](symbol-value.md) or `eval`) signals `unbound-variable`. A later assignment gives it a value again.

```lisp
(defvar *level* 1)
(list (makunbound '*level*) (boundp '*level*)
      (handler-case *level* (unbound-variable () :unbound))) ; => (*LEVEL* NIL :UNBOUND)
```

On the compiled backends every special variable takes part. A global that is not a special variable is unbound for `boundp` and `eval` only, and a compiled read of it keeps answering its value.

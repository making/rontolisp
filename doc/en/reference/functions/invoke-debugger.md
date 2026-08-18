# invoke-debugger

`(invoke-debugger condition)`

Signals `condition` and never returns. No backend has an interactive debugger to
enter, so what "entering the debugger and being told to abort" amounts to here is
the condition reaching whatever handler is established outside the caller -- and
the standard-error report plus a non-zero exit when none is.

```lisp
(handler-case (invoke-debugger (make-condition 'simple-error :format-control "boom"))
  (error (e) (princ-to-string e))) ; => "boom"
```

## Backend support

Works on all four backends: one definition in rontolisp source over `error`.

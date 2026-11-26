# invoke-debugger

`(invoke-debugger condition)`

Signals `condition` and never returns. No backend has an interactive debugger to
enter, so what "entering the debugger and being told to abort" amounts to here is
the condition reaching whatever handler is established outside the caller -- and
the standard-error report plus a non-zero exit when none is. Under
[`rontolisp --swank`](../../getting-started/swank.md) a condition no handler takes
opens SLIME's debugger instead.

When `*debugger-hook*` is not `nil`, it is called first with the condition and its own
value, with the variable bound to `nil`. Only `invoke-debugger` consults it: a condition
no handler takes ends the program without calling the hook.

```lisp
(handler-case (invoke-debugger (make-condition 'simple-error :format-control "boom"))
  (error (e) (princ-to-string e))) ; => "boom"

(block found
  (let ((*debugger-hook* (lambda (c hook) (declare (ignore hook)) (return-from found (princ-to-string c)))))
    (invoke-debugger (make-condition 'simple-error :format-control "boom")))) ; => "boom"
```

## Backend support

Works on all four backends: one definition in rontolisp source over `error`.

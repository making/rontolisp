# rontolisp:futurep

`(rontolisp:futurep value)`

Returns `t` if `value` is a future — as returned by calling an
[`rontolisp:async-defun`](../special-forms/rontolisp-async-defun.md) function,
[`rontolisp:fetch`](rontolisp-fetch.md) or
[`rontolisp:stream-read`](rontolisp-stream-read.md) — and `nil` otherwise.

```lisp
(rontolisp:async-defun f () 1)
(rontolisp:futurep (f))    ; => t
(rontolisp:futurep 42)     ; => nil
```

A future is an opaque value: it has no reader syntax and prints as `#<FUTURE>`.
Its settled value is obtained with
[`rontolisp:await`](../special-forms/rontolisp-await.md).

```lisp
(f)   ; => #<FUTURE>
```

# make-condition

`(make-condition type &key format-control format-arguments ...)`

Lite: with no condition system there is no condition object to build, so the expansion yields the `:format-control` value when one is given (or a generic message naming the type otherwise) — which is exactly what `error` needs to signal with the intended message. `:format-arguments` and any other options are discarded.

```lisp
(make-condition 'my-error :format-control "something failed") ; => "something failed"
```

```console
> (error (make-condition 'my-error :format-control "something failed"))
Error: something failed
```

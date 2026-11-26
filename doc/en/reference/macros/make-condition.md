# make-condition

`(make-condition type &key initargs...)`

Constructs a condition object of the given type — a CLOS-subset instance whose slots are filled from the initargs (missing slots take their `:initform`). The type is a symbol naming a type defined by [`define-condition`](define-condition.md) or a seeded built-in like `simple-error`, quoted or computed, and `#'make-condition` (through `funcall` or `apply`) takes it too. Anything else is a `type-error` whose datum is it and whose expected type is `condition`. A compiled program finds the classes [`error`](error.md) finds through a computed datum. The instance can be passed to [`error`](error.md)/[`signal`](signal.md) (the condition-object designator) and tested with `typecase`.

```lisp
(make-condition 'simple-error :format-control "something failed") ; => #<SIMPLE-ERROR :FORMAT-CONTROL "something failed" :FORMAT-ARGUMENTS NIL>
```

```console
CL-USER> (error (make-condition 'simple-error :format-control "something failed"))
Error: something failed
```

# make-condition

`(make-condition type &key initargs...)`

Constructs a condition object of the given type — a CLOS-subset instance whose slots are filled from the initargs (missing slots take their `:initform`). The type must be a literal quoted symbol naming a type defined by [`define-condition`](define-condition.md) or a seeded built-in like `simple-error`. The instance can be passed to [`error`](error.md)/[`signal`](signal.md) (the condition-object designator) and tested with `typecase`.

```lisp
(make-condition 'simple-error :format-control "something failed") ; => #<SIMPLE-ERROR :FORMAT-CONTROL "something failed" :FORMAT-ARGUMENTS NIL>
```

```console
> (error (make-condition 'simple-error :format-control "something failed"))
Error: something failed
```

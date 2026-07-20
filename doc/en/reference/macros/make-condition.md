# make-condition

`(make-condition type &key initargs...)`

Constructs a condition object of the given type — a CLOS-subset instance whose slots are filled from the initargs (missing slots take their `:initform`). The type must be a literal quoted symbol; a type not defined by [`define-condition`](define-condition.md) (or seeded built-in like `simple-error`) yields a raw tagged instance carrying the arguments as given. The instance can be passed to [`error`](error.md)/[`signal`](signal.md) (the condition-object designator) and tested with `typecase`.

```lisp
(make-condition 'simple-error :format-control "something failed") ; => (%class-SIMPLE-ERROR "something failed" NIL)
```

```console
> (error (make-condition 'simple-error :format-control "something failed"))
Error: something failed
```

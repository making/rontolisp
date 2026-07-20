# define-condition

`(define-condition name (parent...) (slot...) option...)`

Defines a condition type as a CLOS-subset class (see [`defclass`](../special-forms/defclass.md)) over the built-in condition hierarchy `condition` > `serious-condition` > `error` (> `simple-error` and the standard error subtypes) and `warning`. The parent defaults to `condition`; with several parents, the **first** provides the slot layout (single inheritance) and the rest join the type hierarchy for `typep`/`typecase`/`handler-case` matching only (their slots are not inherited). Slots use the `defclass` subset (`:initarg`/`:initform`/`:reader`/`:accessor`, plus `:documentation`, which is dropped). Of the class options, `(:report x)` — a literal string or a `(lambda (condition stream) ...)` — is used as the message when the condition is signaled by [`error`](error.md)/[`signal`](signal.md), `(:default-initargs :initarg value ...)` forwards to the generated class (defaults applied by `make-condition`/typed `error` for initargs not supplied), and `(:documentation ...)` is dropped. Returns the type name. On the compile path it is a top-level-only form, like `defclass`.

```lisp
(define-condition my-parse-error (error)
  ((input :initarg :input :reader my-parse-error-input))
  (:report "input did not parse")) ; => MY-PARSE-ERROR
```

```console
> (error 'my-parse-error :input "x")
Error: input did not parse
```

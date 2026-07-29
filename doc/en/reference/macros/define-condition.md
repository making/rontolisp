# define-condition

`(define-condition name (parent...) (slot...) option...)`

Defines a condition type as a CLOS-subset class (see [`defclass`](../special-forms/defclass.md)) over the built-in condition hierarchy `condition` > `serious-condition` > `error` (> `simple-error` and the standard error subtypes) and `warning`. The parent defaults to `condition`; with several parents, the **first** provides the slot layout (single inheritance) and the rest join the type hierarchy for `typep`/`typecase`/`handler-case` matching only (their slots are not inherited). Slots use the `defclass` subset (`:initarg`/`:initform`/`:reader`/`:accessor`, plus `:documentation`, which is dropped). Of the class options, `(:report x)` — a literal string or a `(lambda (condition stream) ...)` — is the condition's REPORT: it is the message when the condition is signaled by [`error`](error.md)/[`signal`](signal.md)/[`warn`](warn.md) **and** the text [`princ`](../functions/princ.md), [`princ-to-string`](../functions/princ-to-string.md) and [`format`](format.md)'s `~A` write for the condition object. [`prin1`](../functions/prin1.md) / `~S` are unaffected and keep the `#<TYPE :SLOT value ...>` instance syntax. A type that defines no `:report` inherits its parent's; a type that inherits none but carries the `simple-condition` slots (`format-control`/`format-arguments`, i.e. any subtype of `simple-error`/`simple-warning`/`simple-condition`) reports through `format` applied to them; a type with neither keeps the `#<...>` rendering under `princ` too. A [`print-object`](../functions/print-object.md) method on the type wins over the report, for both escape modes. `(:default-initargs :initarg value ...)` forwards to the generated class (defaults applied by `make-condition`/typed `error` for initargs not supplied), and `(:documentation ...)` is dropped. Returns the type name. On the compile path it is a top-level-only form, like `defclass`.

```lisp
(define-condition my-parse-error (error)
  ((input :initarg :input :reader my-parse-error-input))
  (:report "input did not parse")) ; => MY-PARSE-ERROR
```

The report is what `princ`/`~A` prints; `prin1`/`~S` still shows the instance:

```lisp
(define-condition dc-report-demo (error)
  ((input :initarg :input :reader dc-report-demo-input))
  (:report (lambda (c s) (format s "did not parse: ~a" (dc-report-demo-input c)))))
(list (princ-to-string (make-condition 'dc-report-demo :input "x"))
      (prin1-to-string (make-condition 'dc-report-demo :input "x")))
; => ("did not parse: x" "#<DC-REPORT-DEMO :INPUT "x">")
```

```console
> (error 'my-parse-error :input "x")
Error: input did not parse
```

# c86. `set` inside an active dynamic binding answers three ways on four backends

Difficulty: Medium

`.kb/symbol-runtime-api.md` says a computed `set` is "deaf to an already-active dynamic binding
everywhere alike". Measured 2026-10-04 (before and after `.todo/c81`, identical), it is not:

```lisp
(defvar *dv* 1)
(defun probe (name) (let ((*dv* 2)) (set name 3) (list *dv* (symbol-value name))))
(print (list (probe '*dv*) *dv*))
```

| backend | output |
|---|---|
| interpreter | `((2 2) 3)` |
| JVM | `((2 3) 3)` |
| wasm Preview 1, component | `((3 3) 1)` |

CL (`set` changes the symbol's CURRENT dynamic value) answers `((3 3) 1)`, which is what wasm's
shallow binding gives by accident: `%global-store-set` writes the module global, which IS the
binding, and the restore puts 1 back. The JVM writes `_g$` beside the `_d$` ThreadLocal cell (a
read of `*dv*` sees the cell, the non-progv `symbol-value` the mirror), the interpreter defines
the global env under its `DynamicBindings`.

Plan: decide the semantics (CL's, presumably: write the active binding, as `setq` does), make the
interpreter, `%global-store-set` (JVM: `_dset`, falling to `_g$`) and the mirror agree, pin it on
all four backends with a ci-spec case, and correct the `.kb` claim. Check the progv mirror path
(a name bound by `progv` without a declaration) the same way.

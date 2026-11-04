# i44. McCLIM's presentation types need built-in classes outside standard-class

Difficulty: High

Measured 2026-11-05, interpreter: `(ql:quickload :clim)` reads `Core/system`, `geometry`, the output
and input layers and part of `clim-core/presentations/standard-presentations.lisp`, then
`record-presentation-type` (`presentation-types.lisp:683`) stops:

```
DEFCLASS: the type NULL cannot be a superclass
```

McCLIM asks `(typep (find-class name nil) 'standard-class)`. Yes means a CLOS class, which gets a
`presentation-type-proxy`; no means it makes a class of its own with `closer-mop:ensure-class`, the
built-in types being the point of that branch. Here every built-in class metaobject is a
`standard-class` instance (`.kb/clos.md`, "MOP boundary"), so `null`, `integer` and the others become
proxies, and a type inheriting one hands the built-in class to `ensure-class` as a superclass.

The essential change is a `built-in-class` that is a real metaclass: `class-of` of a built-in class
answers it and `(typep x 'standard-class)` is nil, with `isClassMetaobject`, the `metaobjectTags`
typep normalizations, `classp` and the compile paths' materializer following on all four backends.
Then re-measure. `forward-referenced-class` has the same shape (seeded below `standard-class`, AMOP
has it beside) and can move in the same change.

Unmeasured beyond the stop: the rest of `Core/clim-core` (panes, frames, commands, gadgets). Known
ahead: exported with no operator yet are `class-default-initargs` (`dialog-views.lisp`),
`compute-effective-method`, `specializer-direct-methods` and
`specializer-direct-generic-functions`; `compute-applicable-methods` is not consulted by a dispatcher,
so McCLIM's `:around` removing the `standard-object` methods for a presentation-type key has no effect.

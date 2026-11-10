# WIT import: `with-<resource>`, a handle given back on any exit

Difficulty: Low

A handle is given back with `<resource>-drop`, and nothing gives it back when a call before the
drop signals. The guide's own example (`doc/en/guides/wit-contracts.md`, "Releasing a resource")
drops after the last call:

```lisp
(let ((bucket (kv:open "")))
  (kv:bucket-set bucket "visits" "41")   ; a store failing signals rontolisp:wit-error here,
  (print (kv:bucket-get bucket "visits"))
  (kv:bucket-drop bucket))               ; and this never runs
```

So each program writes its own `unwind-protect` ([counter-api](https://github.com/categolj/counter-api-lisp):
a `with-bucket` macro over `kv:open` and `kv:bucket-drop`). A handle never given back lives as long
as the component instance, and a host may serve many requests with one instance.

## Goal

`wit-import` binds, beside each resource's `-drop`, a `with-<resource>` macro in `with-open-file`'s
shape: `(kv:with-bucket (bucket (kv:open "")) ...)` drops the handle on any exit, normal or not, on
every backend that binds the drop. Reference-gated like the drop, so a program not naming it
compiles byte-identical. The guide's example uses it.

## Direction

- The drop is bound in `WitImportDirective` (`iface.items()`'s `WitItem.ResourceDef`, through
  `allMembers`) and gated by `WitImportInliner`'s `referencedNames(program)` (`.kb/wit.md`,
  "Resource drops"). The macro is Lisp on every backend:
  `(let ((,var ,form)) (unwind-protect (progn ,@body) (,drop ,var)))`.
- The gate reads the program's text, which names the macro and not the drop it expands to: naming
  `with-<resource>` has to pull the drop in.
- Preview 1's drop is a no-op and the no-gc component refuses a drop (`.kb/wit.md`): the macro
  follows the drop on each.

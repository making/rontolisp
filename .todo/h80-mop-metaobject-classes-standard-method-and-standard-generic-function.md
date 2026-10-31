# h80. `standard-object` as a named superclass; `standard-method` / `standard-generic-function` as classes

Difficulty: High

Measured 2026-10-31 (interpreter):

```lisp
(defclass foo (standard-object) ())          ; unknown superclass STANDARD-OBJECT
(defmethod mn ((m standard-method)) 1)       ; unknown specializer STANDARD-METHOD
(find-class 'standard-method nil)            ; NIL
```

`standard-object` resolves only through `find-class` (`ClosRegistry.FIND_CLASS_ONLY_CLASS_NAMES`,
`.kb/clos.md`), never as a written superclass. The method and generic-function metaobject classes
do not exist.

Blocks:
- **lisp-binary** via moptilities (`dev/moptilities.lisp:153` `(:method ((method
  standard-method)) ...)`, then `:159` a `standard-generic-function` specializer). Needs the
  classes, `class-of` of a method / generic function answering them, and the readers moptilities
  calls (`method-generic-function`, `generic-function-name`, ...).
- **contextl** (`cx-special-class.lisp:3` `(defclass special-object (standard-object) ())`, then
  `cx-layered-function-macros.lisp:57` a class whose superclass is `standard-generic-function`).
  contextl's layered functions are generic functions of a USER generic-function class
  (`:generic-function-class`, `compute-discriminating-function`-level MOP) -- the far end; measure
  how much of closer-mop's generic-function protocol it really calls before committing.

Order: `standard-object` superclass (cheap, likely interpreter + compile paths share the defclass
widening) -> method/gf classes as specializers (moptilities, then re-probe lisp-binary) ->
contextl. Every step on all four backends.

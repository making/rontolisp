# h86. contextl: a layer class made at run time, and the four small gaps in front of it

Difficulty: High

Measured 2026-10-31 on the interpreter, with the method metaobject classes and a written
`standard-object` superclass in place (`.kb/clos.md`, "Out of scope / known gaps", contextl).
Loading `contextl-20241012-git` stops, in order, at:

1. `cx-layer-metaclasses.lisp:9` `(:method ((layer (eql (find-class 't)))) 't)`:
   `parseEqlSpecializerValue` takes `(find-class 'c)` only for a SYMBOL `c`, and the reader's `t`
   is not one.
2. `cx-gc.lisp` (the layer GC, on by default): a COMPUTED eql specializer
   `(eql (lf-definer-name 'adjoin-layer-using-class))` and `add-method`/`remove-method` `:after`
   methods on `layered-function`. contextl's own `:cx-disable-layer-gc` removes the block, but a
   `(push :cx-disable-layer-gc *features*)` ahead of `(load "cx-gc.lisp")` did not reach that
   file's `#-` -- measure why before relying on it.
3. `cx-layer.lisp:2` `(defclass root-specializer () () (:metaclass standard-layer-class)
   (original-name . t))`: `expandDefclass` turns only KEYWORD class options into metaclass
   initargs, and `original-name` (also the slot's `:initarg`) is a plain symbol.
4. closer-mop's `subclassp`, `class-prototype` and `required-args` are not in the shim
   (`src/main/resources/am/ik/rontolisp/eval/closer-mop.lisp`).

With those stood in for (a patched copy plus three defuns), contextl loads and a layered function
with a base-layer method answers. `with-active-layers` then fails: `adjoin-layer-using-class` makes
an ANONYMOUS `standard-layer-class` over (the layer's class, the active context's class) at RUN time
and takes its `class-prototype`, which is `ALLOCATE-INSTANCE: not an allocatable class`; the layered
function dispatches on that prototype next.

The generic-function protocol is not what blocks it: `:generic-function-class layered-function` is
accepted and ignored, and dispatch is static on the layer prototype; nothing on the measured path
calls `compute-discriminating-function` and friends.

Plan: (1)-(4) are small and independent, each on all four backends. The core is a class made at run
time whose superclasses are all registered: it needs a layout (contextl's layer classes are
slot-less), a tag, and an ancestor set the generated dispatchers and `typep` consult at run time,
since a static `%obj-is` tag list cannot name it. Before designing it, measure what contextl's own
`test/` needs beyond `with-active-layers`, and whether the layer classes' `special-class` slot
protocol (`slot-value-using-class` methods, never called here) changes any answer.

# h89. contextl: layered classes, special slots and the rest of its own test suite

Difficulty: High

contextl's layers run on all four backends (`ContextlE2eTest`, `.kb/clos.md` "Classes made at run
time" and the contextl paragraph). Its own `test/` (measured 2026-10-31 on the interpreter, in
package `contextl`) stops at:

1. `demo3`, `special-slots-2`: `define-layered-class` with `:layered-accessor`. `add-layered-accessors`
   makes generic functions and methods at RUN time (`ensure-layered-function` ->
   `ensure-generic-function`, `ensure-layered-method` -> `ensure-method`), and the class itself is a
   `partial-class`: its `initialize-instance :around` makes ANONYMOUS slot-carrying classes
   (`make-instance defining-metaclass`) and puts them in front of the static class's direct
   superclasses. Needs first-class generic functions / methods (`.kb/clos.md`, "Generic functions
   and methods are not first-class metaobjects") and classes made at run time WITH slots, as
   superclasses of a statically defined class.
2. `special-slots-1`, `-3`, `dynenv`: `slot-definition-allocation` (missing from the closer-mop
   shim), then special slots: `slot-value-using-class` / `slot-boundp-using-class` methods on
   `special-class`, `dletf`.
3. `grouped-layers`: a `deflayer` slot (`group-root`) is class-allocated through
   `singleton-direct-slot-definition`'s `initialize-instance :around` (`:allocation :class`), and
   `finalize-inheritance :after` fills the prototype's slots; the static layout ignores both.
4. `layer-gc` passes four of five sections; the fifth needs a later `defmethod` on
   `adjoin-layer-using-class` to run `add-method`'s `:after` (cache clearing).
5. `spx`: `(setf (find-class 'x) nil)` (a run-time error now, `.kb/clos.md`).

Smaller residuals: on the interpreter, a top-level form that calls `map-symbol` (through
`defining-layer`, `find-layer-class`, `adjoin-layer`) prints every symbol of that form qualified
(`CONTEXTL::FOO`) while `*package*` is `contextl` and the symbols stay `eq` -- the compiled
backends print `FOO`; reproduced only with contextl loaded. A layer context's prototype prints as
`#<%DYNAMIC-OBJECT% ...>` (`print-object` is not routed for a dynamic instance).

Plan: measure 2 and 3 first (smallest blast radius: a shim function, then the slot-definition
`:allocation` initarg reaching the static layout). 1 is the large one; decide between first-class
generic functions (a funcallable `standard-generic-function` plus a run-time method table) and a
narrower `ensure-method` over the existing dispatcher before building either.

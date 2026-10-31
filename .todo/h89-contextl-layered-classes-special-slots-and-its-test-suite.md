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
2. `dynenv`, `special-slots-3` (and `-1`): special slots need the AMOP slot-access protocol --
   `slot-value`/`slot-boundp`/`slot-makunbound` and accessors of a `special-class` instance
   going through `slot-value-using-class` / `(setf slot-value-using-class)` /
   `slot-boundp-using-class` / `slot-makunbound-using-class` (closer-mop externals, with system
   default methods: contextl's `slot-unbound` method calls `(setf slot-value-using-class)`
   itself and dies `call-next-method: no next method`, measured 2026-11-01). `special-slots-1`/`-3`
   then LOOP: a `dletf` of a non-special slot `cerror`s and the handler `eval`s a redefinition
   making the slot special, which needs run-time class redefinition with obsolete instances
   (`make-instances-obsolete`, `update-instance-for-redefined-class`) -- interpreter-only at best,
   since a compiled `eval` defines no class.
3. `layer-gc` passes four of five sections; the fifth needs a later `defmethod` on
   `adjoin-layer-using-class` to run `add-method`'s `:after` (cache clearing).
4. `spx`: `(setf (find-class 'x) nil)` (a run-time error now, `.kb/clos.md`).

Smaller residuals: on the interpreter, a top-level form that calls `map-symbol` (through
`defining-layer`, `find-layer-class`, `adjoin-layer`) prints every symbol of that form qualified
(`CONTEXTL::FOO`) while `*package*` is `contextl` and the symbols stay `eq` -- the compiled
backends print `FOO`; reproduced only with contextl loaded. A layer context's prototype prints as
`#<%DYNAMIC-OBJECT% ...>` (`print-object` is not routed for a dynamic instance).

Done 2026-11-01: `grouped-layers` (`closer-mop:slot-definition-allocation`, and a slot-definition
`initialize-instance :around`'s `:allocation :class` reaching the static layout, `.kb/clos.md`)
passes on all four.

Plan: 2 first (the protocol is expansion-level plus a Lisp runtime, so all four backends move
together; gate it on a method on one of the four generics so other programs stay
byte-identical). 1 is the large one; decide between first-class generic functions (a
funcallable `standard-generic-function` plus a run-time method table) and a narrower
`ensure-method` over the existing dispatcher before building either.

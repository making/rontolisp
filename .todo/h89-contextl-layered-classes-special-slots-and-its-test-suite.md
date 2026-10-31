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
2. `special-slots-3` (and `-1`): the `dletf` of a non-special slot `cerror`s and the handler
   `eval`s a redefinition making the slot special, then reads the existing instance: it still
   holds the raw value (`SYMBOL-VALUE expects a symbol, got "Dr. Jekyll"`, 2026-11-01), because
   nothing makes instances obsolete (`make-instances-obsolete` from contextl's
   `finalize-inheritance :after`, then `update-instance-for-redefined-class` ->
   `shared-initialize`, which shifts the slot). Interpreter-only at best: a compiled `eval`
   defines no class. Also open from the slot-access protocol (`.kb/clos.md`): `make-instance`
   fills initargs/initforms through the constructor, not `(setf slot-value-using-class)` (SBCL
   writes each through it), which overwrites a class-allocated SPECIAL slot's special symbol
   (spx's `slot1`).
3. `layer-gc` passes four of five sections; the fifth needs a later `defmethod` on
   `adjoin-layer-using-class` to run `add-method`'s `:after` (cache clearing).
4. `spx`: `(setf (find-class 'x) nil)` (a run-time error now, `.kb/clos.md`).

Smaller residuals: on the interpreter, a top-level form that calls `map-symbol` (through
`defining-layer`, `find-layer-class`, `adjoin-layer`) prints every symbol of that form qualified
(`CONTEXTL::FOO`) while `*package*` is `contextl` and the symbols stay `eq` -- the compiled
backends print `FOO`; reproduced only with contextl loaded (not with a plain
`with-standard-io-syntax` + `intern` in a user package, 2026-11-01).

Done 2026-11-01, on all four: `grouped-layers` (`closer-mop:slot-definition-allocation`, and a
slot-definition `initialize-instance :around`'s `:allocation :class` reaching the static layout)
and `dynenv` (the slot-access protocol, `slot-value-using-class` and its siblings; `assert`'s
message doubling the tildes of the test it quotes), `.kb/clos.md`; a layer-context prototype
prints through contextl's `print-object` (and a class an exported symbol names no longer breaks
the dynamic dispatch arm on the compile paths).

Plan: 1 is the large one; decide between first-class generic functions (a funcallable
`standard-generic-function` plus a run-time method table) and a narrower `ensure-method` over
the existing dispatcher before building either. 2's construction residual is the chain fill
`expandMakeInstance` already does for metaobject classes, extended to a `:metaclass` class under
the slot-access protocol with a protocol-routed `%mop-fill-slots`.

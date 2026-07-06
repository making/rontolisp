# CLOS and `defstruct` (the object system)

**Status:** `defstruct` DONE (2026-07, all four backends; `.kb/defstruct.md`).
CLOS **Stages 1 + 2 DONE** (2026-07-06, all four backends; `.kb/clos.md`):
`defclass` (single inheritance) + `make-instance` + `slot-value` + `defgeneric`
+ `defmethod` (single dispatch on arg 1, `eql`/class/built-in-type specializers).
**Next action: Stage 3** (qualifiers + `call-next-method`) — see below. Full
CLOS (MOP, runtime class ops) is permanently out of scope.

## Out of scope (permanent)

The MOP and runtime class/method operations contradict the static compile model
(`--optimize` tree-shaking): `find-class`/`ensure-class`/`change-class`, class
redefinition + `update-instance-for-*`, `add-method`/`remove-method`,
`compute-applicable-methods`, dynamic EQL specializers, `class-name`/
`slot-boundp`/`slot-makunbound`, direct-super/subclass introspection. Declared
out of scope like the defstruct options — do not attempt. When Stage 3 ships,
mark the remaining operator-table rows "out of scope" in the docs.

## Defstruct — done (remaining gaps deliberate)

Expands into plain defuns over a tagged-list representation
(`LispMacroExpander.expandDefstruct`). Documented gaps (in
`doc/*/reference/special-forms/defstruct.md` + missing-features guide):

- defstruct options (`:conc-name`, `:constructor`, `:include`, ...) — error
- `structurep` — not implemented (per-struct predicates only)
- `#S(...)` print/read syntax — instances print as their tagged list
- `:export` of generated names in `defpackage` — single-colon call sites fail
- runtime `eval` of compiled output: no defstruct, no accessor setf places
- `--no-gc`: rejected (cons-based representation)

## Stage 3 — standard method combination (NEXT)

`:before`/`:after`/`:around` qualifiers + `call-next-method`. The applicable
chain per dispatch branch is statically known, so next-method calls compile to
direct calls folded into the generated dispatcher (`generateDispatcher`).
Combinatorial but mechanical. Follow the defstruct/CLOS wiring pattern: grep
`DEFSTRUCT`/`DEFCLASS` for every site, thread through `ClosRegistry`, expand in
the shared `LispMacroExpander`, no per-backend codegen. Update the 8 pinned
introspection outputs if any new name is added (ci-spec
`rontolisp-package-introspection`, the three backend tests,
`doc/{en,ja}/reference/packages.md` + `rontolisp-list-{macros,special-forms}.md`).
Ship the four usual tests + a ci-spec case + per-operator docs, then run
`-Drontolisp.doc.fix=true`, full DocExamplesTest, and the native-image E2E.

## Related

- `[[39-condition-system]]` (condition types are CLOS classes)
- `[[35-type-system]]` (`typep` on class names)
- `[[31-lambda-list-extensions]]` (`defstruct` accessors use `&key`)

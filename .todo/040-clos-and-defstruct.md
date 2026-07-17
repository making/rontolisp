# CLOS and `defstruct` (the object system)

**Status:** `defstruct` DONE (2026-07, all four backends; `.kb/defstruct.md`).
CLOS **Stages 1 + 2 + 3 DONE** (2026-07-06, all four backends; `.kb/clos.md`):
`defclass` (single inheritance) + `make-instance` + `slot-value` + `defgeneric`
+ `defmethod` (single dispatch on arg 1, `eql`/class/built-in-type specializers)
+ standard method combination (`:before`/`:after`/`:around` qualifiers +
`call-next-method`/`next-method-p`, for class + default methods). **Nothing
actionable remains** — full CLOS (MOP, runtime class ops) is permanently out of
scope (see below). This todo can be closed.

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

## Stage 3 — standard method combination (DONE 2026-07-06)

`:before`/`:after`/`:around` qualifiers + `call-next-method`/`next-method-p`,
shared in `LispMacroExpander` (no per-backend codegen). `generateDispatcher`
splits into `simpleDispatchBody` (unchanged when no qualifier / no
call-next-method) and `combinedDispatchBody` (one branch per specializer, each
value = `effectiveMethod`: `:around` wrap a core of befores → primary chain →
afters, built from nested `lambda`+`funcall` next-method thunks with no
free-variable capture). Every method-body defun gained a leading `%next-method`
parameter; `rewriteNextMethod` lowers `call-next-method`/`next-method-p` against
it. `call-next-method`/`next-method-p` are matched by package-stripped name and
deliberately kept OUT of `CL_SYMBOLS` (no introspection change — the 8 pinned
introspection outputs were untouched). Combination is for class + default
methods; eql/type-qualified methods combine only with same-specializer primaries
+ default (documented). Tests: `LispEvaluatorTest` (5 new), JVM + WASM
`compileAndRun{MethodQualifiersAndCallNextMethod,AroundMethodAndNextMethodP}`,
ci-spec `clos-method-qualifiers-and-call-next-method`, doc examples on
`defmethod.md` (en+ja). Details: `.kb/clos.md`.

## Related

- `[[39-condition-system]]` (condition types are CLOS classes)
- `[[35-type-system]]` (`typep` on class names)
- `[[31-lambda-list-extensions]]` (`defstruct` accessors use `&key`)

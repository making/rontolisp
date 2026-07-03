# CLOS and `defstruct` (the object system)

**Status:** `defstruct` is DONE (2026-07, all four backends; see `.kb/defstruct.md`).
CLOS: a staged *static subset* plan is agreed (see "Implementation approach"
below). **Next action: Stage 1 only** (`defclass` single inheritance +
`make-instance` + slots) — it is specced there down to the wiring checklist;
do not start Stage 2 in the same round. Full CLOS (MOP, runtime class ops) is
permanently out of scope.

## Defstruct — implemented

`defstruct` expands into plain defuns over a tagged-list representation
(`LispMacroExpander.expandDefstruct`): keyword constructor with slot defaults,
predicate, copier, setf-able accessors, package-qualified names, top-level only
on the compile path. Remaining gaps (deliberate, documented in
`doc/*/reference/special-forms/defstruct.md` and the missing-features guide):

- defstruct options (`:conc-name`, `:constructor`, `:include`, ...) — error
- `structurep` — not implemented (not standard CL; per-struct predicates only)
- `#S(...)` print/read syntax — instances print as their tagged list
- `:export` of generated names in `defpackage` — single-colon call sites fail
- runtime `eval` of compiled output: no defstruct, no accessor setf places
- `--no-gc`: rejected (cons-based representation)

## What's missing (CLOS)

The following CL operators are absent:

### CLOS (Common Lisp Object System)

| Operator | Purpose |
|----------|---------|
| `defclass` | Define a class |
| `defmethod` | Define a method |
| `defgeneric` | Define a generic function |
| `make-instance` | Create an instance |
| `slot-value` | Access instance slot |
| `slot-boundp` | Check if slot is bound |
| `slot-makunbound` | Unbind slot |
| `shared-initialize` | Instance initialization |
| `the` (typed) | Type declaration |
| `change-class` | Change instance class |
| `class-name` | Class name |
| `find-class` | Find class by name |
| `ensure-class` | Find or create class |
| `slot-makunbound` | Unbind slot |
| `slot-makunbound` | Unbind slot |
| `standard-method-call` | (Not CL) |
| `call-next-method` | Call next most specific method |
| `compute-applicable-methods` | Compute applicable methods |
| `compute-applicable-methods-using-class` | Compute applicable methods |
| `method-specializers` | Method specializers |
| `generic-function-methods` | Generic function methods |
| `add-method` | Add method to generic |
| `remove-method` | Remove method from generic |
| `method-combination-error` | Method combination error |
| `standard-effective-method-computation` | Standard method combination |
| `eql-specializer` | EQL specializer |
| `make-method` | Create method |
| `initialize-instance` | After method |
| `reinitialize-instance` | After method |
| `update-instance-for-redefined-class` | After method |
| `update-instance-for-different-class` | After method |
| `update-instance-for-lambda-list-change` | After method |
| `direct-superclasses` | Class hierarchy |
| `direct-subclasses` | Class hierarchy |
| `direct-slot-definition-class` | Slot definition |
| `effective-slot-definition-class` | Slot definition |
| `initialize-instance` | After method |
| `instance-access` | (Not CL) |

### Implementation approach — staged static subset (agreed 2026-07)

The defstruct pattern ("literal top-level form -> expand to plain defuns
before Pass 1, registry threaded into the evaluator and both compilers' Ctx")
extends to a *static* CLOS subset. What it can NEVER cover is the MOP and
runtime class operations (`find-class`/`ensure-class`/`change-class`,
class redefinition + `update-instance-for-*`, `add-method`/`remove-method`,
`compute-applicable-methods`, dynamic EQL specializers): those assume a
mutable runtime class/method table, which contradicts the static compile
model (and `--optimize` tree-shaking). Declare them out of scope permanently,
like the defstruct options — do not attempt.

Ship each stage independently, in order:

**Stage 1 — `defclass` (single inheritance) + `make-instance` + slots. START HERE.**

Scope:
- `(defclass name (superclass) ((slot :initarg :k :initform e :accessor a :reader r) ...))`
  — literal, top-level only (compile path), single superclass or none.
  Unsupported class options / slot options (`:documentation`, `:allocation`,
  `:writer`, multiple inheritance, ...) -> `UnsupportedOperationException`,
  mirroring defstruct options.
- `(make-instance 'name :k v ...)` with a LITERAL quoted class name — expands
  to the generated keyword constructor. Non-literal class name = error on all
  backends (keep parity; do not make the interpreter dynamic-only).
- `(slot-value obj 'slot)` / `(setf (slot-value obj 'slot) v)` with a literal
  quoted slot name — expands to the accessor/`nth` position. Non-literal = error.
- `:accessor`/`:reader` generate defuns exactly like defstruct accessors;
  `:accessor` also registers in the setf registry.

Design (all decided, reuse defstruct machinery — read `.kb/defstruct.md` first):
- Representation: tagged proper list `(%class-<name> v1 v2 ...)`; slot layout =
  superclass slots (in inheritance order) + own slots, computed at expansion
  time from the class registry. `:initform` becomes the `&key` default;
  `:initarg` names the keyword (default: slot name).
- New registry beside `structAccessors`: class name -> (ordered slot list,
  ancestor set). Lives per-evaluator (`LispEvaluator` field) and per-compilation
  (thread through `Ctx.Builder` like `structAccessors`). Slot writes reuse the
  SAME `structAccessors` map (accessor -> 1-based position), so setf/incf/push
  work with zero new setf code.
- The ancestor set is stored per class so Stage 2 can generate
  "instance-of C" checks (tag member of C's descendant tags, statically known).

Wiring checklist (identical shape to defstruct; grep `DEFSTRUCT` for every site):
1. `LispNames`: `DEFCLASS`, `MAKE_INSTANCE`, `SLOT_VALUE`.
2. `PackageRegistry`: `defclass` -> `CL_SPECIAL_FORMS`; decide the category of
   `make-instance`/`slot-value` (they expand like `nth`, so likely
   `CL_FUNCTIONS` without wrappers is wrong — probably `CL_MACROS`; whichever
   is chosen, update the 8 pinned introspection outputs: ci-spec
   `rontolisp-package-introspection`, the three backend tests,
   `doc/{en,ja}/reference/packages.md` + `rontolisp-list-{macros,special-forms}.md`).
3. `LispMacroExpander`: `expandDefclass(cons, classRegistry, structAccessors)`
   -> `List<LispVal>`; extend `expandTopLevelDefstructs` into a single
   `expandTopLevelDefinitions` pre-pass handling both defstruct and defclass
   (defclass may reference a superclass defined earlier in the same program —
   process forms in order). `make-instance`/`slot-value` expand at the
   `Jvm/WasmExprCompiler.compileCons` + `evalCons` dispatch (registry needed,
   like the setf overload).
4. `LispEvaluator`: `DEFCLASS` case; `MAKE_INSTANCE`/`SLOT_VALUE` cases.
5. Both compilers: pre-pass call site (already between `PackageResolver` and
   `LambdaLists.desugarProgram` — the constructor uses `&key`, order matters),
   Ctx field + builder setter, ExprCompiler cases (incl. nested-defclass error).
6. `UserMacroExpander`: defclass walker case (class/slot names + option
   keywords stay verbatim, only `:initform` values are expressions).
7. Tests in the four usual places + ci-spec case (inheritance: subclass
   instance passes nothing yet — predicates arrive in Stage 2; test slot
   inheritance order and accessor/`slot-value`/setf round-trips).
8. Docs: `doc/{en,ja}/reference/special-forms/defclass.md` (+ per-operator
   pages for make-instance/slot-value in the matching category, `_catalog.yaml`,
   missing-features table row, eval-limitations bullet), run the
   `-Drontolisp.doc.fix=true` helper, then full DocExamplesTest.
9. Gotchas from the defstruct round: javadoc `<pre>` blocks must escape `&key`
   as `&amp;key`; `web/dist` is generated (don't commit); `--no-gc` rejects via
   its generic top-level error (fine); run the native-image E2E before push.

**Stage 2 — `defgeneric`/`defmethod`, single dispatch on the first argument.**
Literal top-level defmethods only. A compile-time pass collects methods per
generic, sorts by specificity (subclass before superclass; class specializers
before built-in-type specializers before `t`), and generates ONE dispatcher
defun per generic: a `typecase`-style chain — built-in specializers map to
existing predicates (`integerp`, `stringp`, `consp`, ...), class specializers
to "tag in descendant set" checks. No qualifiers, no `call-next-method` yet.

**Stage 3 — standard method combination.** `:before`/`:after`/`:around` and
`call-next-method`: the applicable chain per dispatch branch is statically
known, so next-method calls compile to direct calls folded into the generated
dispatcher. Combinatorial but mechanical.

**Stage 4 — does not exist.** MOP / runtime redefinition stays out of scope
(see above); when Stage 3 ships, mark the remaining operator-table rows as
"out of scope" in the docs.

### Related

- `[[39-condition-system]]` (condition types are CLOS classes)
- `[[35-type-system]]` (`typep` on class names)
- `[[31-lambda-list-extensions]]` (`defstruct` accessors use `&key`)

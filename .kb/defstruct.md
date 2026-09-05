# `defstruct` — expansion into plain defuns, instance-object representation

User behavior: `doc/en/reference/special-forms/defstruct.md`. `#S(...)` print/read and the
six `%obj-*` primitives: [instance-syntax.md](instance-syntax.md).

**Invariant: `LispMacroExpander.expandDefstruct(cons, structAccessors)` is the one expansion
for every backend; no `Jvm`/`Wasm` defstruct compiler exists.** `(defstruct point x (y 10))`
becomes top-level defuns `make-point` (`&key`), `point-p`, `copy-point` and one accessor per
slot, over a registered `LispLayout`: `%obj-new`, `%obj-is`, `%obj-ref`, `%obj-set` as the setf
place, copier a fresh `%obj-new` (shallow). Instances are not `consp`/`listp`; `equal` compares
slot-wise (deliberate CL deviation); `#S(...)` source reads back via `StructLiteralFolder`.

- Options: `:constructor`/`:conc-name`/`:predicate`/`:copier`/`:include`/`:type`/
  `:print-object`/`:print-function`, a dropped docstring, parsed-but-ignored slot
  `:type`/`:read-only`; anything else throws "DEFSTRUCT option is not supported".
- BOA `(:constructor name (lambda-list))` passes the lambda list verbatim; a slot matching a
  parameter (`boaParameterSymbols`) reads it, others evaluate their initform.
- A struct name is a valid `defmethod` specializer (`ClosRegistry.registerStruct` /
  `findStructTag`; `structAncestorCount` ranks it, band 100-199, deeper first).
- `:conc-name` takes a STRING DESIGNATOR, colon stripped. **Trap: keeping the colon yields
  accessors no call site can name, surfacing as "setf does not support place: ...".**

## Hook points
- Interpreter `LispEvaluator.evalDefstruct`.
- Compilers: `LispMacroExpander.expandTopLevelDefstructs`, between `PackageResolver` and
  `LambdaLists.desugarProgram` (the `&key` constructor must precede desugaring). It SPLICES
  into several top-level defuns, not one form, because Pass 1 does not descend into `progn`.
- Non-top-level defstruct errors in `Jvm/WasmExprCompiler.compileCons`; `NoGcWasmCompiler`
  rejects it outright.
- Bundled libraries expand earlier, on `LibraryDefunPruner.prune`, leaving a
  `(%struct-definition (defstruct ...))` marker `expandTopLevelDefinitions` consumes for
  registration only ([library-defun-pruning.md](library-defun-pruning.md)). Explicit option
  names (`(:predicate torch:tensorp)`) are the robust spelling for an exported API.
- `UserMacroExpander`'s walker has a defstruct case, so a user macro may expand into one.

## `:include` (single inheritance)
Parent slots (in ITS layout order) are PREPENDED, so an inherited slot keeps one index in every
descendant and the parent's baked `%obj-ref` accessors read a child. `ClosRegistry.structAncestors`
/ `descendantStructTags(name)` is the tag set `typep`, the predicate and a specializer test.
`(:include parent (slot new-default))` re-defaults in THIS child's layout only, index kept,
matched on the UNQUALIFIED slot name; an unknown slot throws.

- **Predicates are REGENERATED as later children appear**, else `(base-p child)` tests too few
  tags: `LispMacroExpander.structPredicateDefun` rebuilds; the interpreter re-evaluates each
  ancestor's predicate (`ClosRegistry.structAncestorNames`), the compile path calls
  `refreshStructPredicates` in the "registry is complete" phase, replacing ONLY where the body
  is still the generated `(%obj-is __struct ...)` shape. `ClosRegistry.structPredicates` records
  what to rebuild; `(:predicate nil)` and `:type` structs never enter it.
- No such refresh for CLASSES -- a `defmethod` on a `defclass` class bakes the tags known when
  the dispatcher is generated. Gap: a compiled program registering a class via runtime `eval`.

## `:type (vector ...)`
A typed struct IS a plain vector: no tag, NOT registered as a type (no specializer, no `typep`,
not a `structure-object`, as in CL). Constructor `(vector v...)`, copier `copy-seq`, accessor an
`aref`, plus a generated `%setf-<accessor>` writer. `(:type list)` and `:include` are rejected.

- A declared `(vector (unsigned-byte 8|16|32))` element type is KEPT, so the constructor's
  `make-array` is packed on every backend and stores MASK to the declared width.
- Stores ride `LispMacroExpander.TYPED_VECTOR_SLOT_BASE`: the accessor registers in
  `structAccessors` as `TYPED_VECTOR_SLOT_BASE - index` (below the `-1` setf-function marker;
  `expandSetf` is the sole reader) and `(setf (acc obj) v)` becomes `(setf (aref obj i) v)`.

## `(:print-object fn)` / `(:print-function fn)`
Both take a function DESIGNATOR and lower to ONE synthesized
`(defmethod print-object ((obj <struct>) stream) (funcall fn obj stream [0]))`, appended LAST so
its accessor calls read defuns generated above it. `:print-function` is the CLtL1 spelling,
differing only in that third `depth` argument, the literal `0`. Rides the `print-object` seam
([clos.md](clos.md)) on all four backends, firing for a value nested in a printed list or
general rank-1 vector but NOT one in a structure slot.

- Mutually exclusive with `:type`, and both spellings at once is an error -- rejected, not
  ignored.
- **Trap: the forms must go through `addExpandedDefinition`; routed past it, the raw `defmethod`
  reaches Pass 1 as an unknown top-level form.**
- `print-unreadable-object`'s `:type t` spelling follows `*print-escape*`
  (`LispMacroExpander.typeNameOf`); its expansion runs in Pass 2, AFTER `injectMvSpillGlobal`'s
  reference scan, so that scan counts the UN-EXPANDED operator as the declaring reference.

## setf on accessors, and setf-functions
No `defsetf`: `LispMacroExpander.expandSetf`'s place list is a hard-coded switch, and
`expandDefstruct` records accessor -> 1-based slot position in a registry that is per-evaluator
(`LispEvaluator.structAccessors`) and per-compilation (`Ctx.structAccessors`, threaded through
`Ctx.Builder`), passed by the three dispatch sites. `push`/`pop`/`incf`/`decf`/`remf` emit
un-expanded `(setf ...)` that re-dispatches there. Gap: the zero-arg `expandSetf(cons)` used by
`macroexpand-1` sees an EMPTY registry, so macroexpand of a struct-place setf errors.

`(defun (setf name) ...)` reuses that registry with sentinel
`LispMacroExpander.SETF_FUNCTION_MARKER` (`-1`; real positions are `>= 1`). The writer installs
as `setfFunctionName(name)` = `%setf-<name>`, NOT in the ordinary function namespace;
`expandSetf`'s default branch expands `(setf (name arg...) val)` to
`(funcall #'%setf-name val arg...)` -- **new value FIRST**. `#'(setf name)` resolves in
`evalFunction` and both `Jvm/WasmFunctionFormCompiler`s (`setfFunctionPlaceName`); registration
is `evalDefun` / `expandTopLevelDefinitions`, the latter also rewriting the defun name so Pass 1
collects it ordinarily. Non-goal: `symbol-function`/`fboundp` of a `(setf ...)` name.

## Package-qualified names
Expansion is POST-resolution, so `(defstruct foo::point x)` generates
`foo::make-point`/`foo::point-p`/`foo::copy-point`/`foo::point-x`, always the internal
double-colon spelling; constructor keywords use the unqualified slot base name via the explicit
`((:x foo::x) default)` `&key` form. Limitation: `:export`ing generated names in `defpackage`
makes call sites resolve to the single-colon spelling and fail.

## Gaps
No `structurep` (not standard CL). Compiled runtime `eval` cannot define structs or setf
accessor places (`doc/en/guides/eval-limitations.md`).

## Tests
- `LispEvaluatorTest#defstruct*` / `#evalDefstructPrint*` / `#evalDefstructAcceptsAKeywordConcName`
  / `#evalDefstructRejectsBothPrinterOptions` / `#setfFunction*` /
  `#defstructIncludeInheritsSlotsAndTypeTests`
- `JvmLispCompilerTest#compileAndRunDefstruct*` + `compileNestedDefstructFails`;
  `Jvm/WasmLispCompilerIntegrationTest#compileAndRunDefstructIncludePredicateMatchesLaterChildren`
  / `#compileAndRunSetfFunctionDefinition`;
  `Jvm/WasmLispCompilerTest#compileAndRunDefstructPrintObjectAndPrintFunctionOptions`
- `UserMacroExpanderTest#defstructNamesAreNotMistakenForMacroCalls*` /
  `macroExpandingToDefstructIsKept`
- ci-spec `defstruct-constructor-accessors-predicate-copier`,
  `defstruct-setf-places-and-first-class-accessors`, `defstruct-keyword-conc-name`,
  `defstruct-print-object-and-print-function`, `setf-function-definitions`,
  `ironclad-residue-features`; `DocExamplesTest`.

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

## Accessors check their object
**Invariant: an accessor, its `setf` place and the copier given a non-instance signal a
`type-error` reporting `POINT-X: The value 42 is not of type POINT` (`(SETF POINT-X): ...`,
`COPY-POINT: ...`) whose datum is the object and expected-type the struct name -- the same line on
all four backends, wasm-GC in EH mode.** Pinned by ci-spec
`defstruct-accessors-signal-a-type-error-on-a-non-instance` and standalone
`uncaught-struct-accessor-report`, `cli/UncaughtReportParityTest`, and one test per backend
(`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`, the last also
pinning the unchecked module). Until 2026-09-26 the interpreter reported `%OBJ-REF expects an
instance, got 42` (a `simple-error`), the JVM a `ClassCastException` -- and, a cons being an
`Object[]`, READ and WROTE a field of `(cons 1 2)` -- and wasm-GC trapped even in EH mode.

- **The check rides the primitive, as a FAILURE operand**: the accessor body is `(%obj-ref obj i
  (%struct-type-error obj "POINT-X" 'POINT nil))`, the place `(%obj-set tgt i v (%struct-type-error
  tgt "POINT-X" 'POINT t))` (`LispMacroExpander.checkedStructRead` / `checkedStructWrite`), and each
  backend folds the test into the access it makes anyway: interpreter `evalCons` (the operand is
  evaluated only for a non-instance), JVM `JvmObjCompiler.emitChecked` (the instance guard; the
  failure form on its reject arm), wasm-GC `WasmInstanceCompiler.emitChecked` (ONE
  `br_on_cast_fail`, the failure form on its miss). **Not `(if (%obj-p obj) ...)` in the
  expansion**: that was the first cut and cost a second type test per access -- 200M iterations of
  two stores and a read under wasmtime 0.88 s -> 1.57 s; the operand form runs 0.89 s. The JVM JIT
  did not care either way.
- **The store checks after the value** (`let`-bound unless the object is a symbol and the value an
  atom), CL's order: `(incf (point-x "s"))` reports the READ, `POINT-X`.
- **`%struct-type-error` is one generated defun** (`structTypeErrorDefun`, injected when referenced;
  the interpreter defines it on first resolution). The report is rendered into the TEXT CONTROL at
  the signal (`formatMessagePieces` + `textControlForm`), never stored as a control with arguments:
  a runtime control drags the format renderer in (zlib +59 KB). The operator travels as a string (a
  store builds `(SETF ...)` from the reader's), the type as `%unspelled-quote`. The owner a place
  needs is `ClosRegistry.structOfAccessor`, registered only by a checked expansion.
- **Unchecked where nothing reads the report**: under `SignalMessages.LAZY` (wasm-GC outside EH
  mode) `expandTopLevelDefinitions` expands unchecked, so the module is byte-identical; a
  pruner-expanded library struct (`LibraryDefunPruner`, which cannot know the mode) keeps the
  operand and wasm-GC ignores it outside EH mode (`checkedFailure`).
- **ANY instance passes**, not the struct's own tags: a later `:include` child widens the tag set
  after the accessor exists (the predicate regeneration below), so `(point-x <another struct>)`
  still reads a slot. Deliberate; revisit with a per-accessor tag test the predicate refresh also
  rebuilds.
- Cost, wasm-GC EH mode (2026-09-26, `--optimize=size`): zlib 89,252 -> 95,803 B (+7.3%; gzip
  29,776 -> 31,250), `--optimize` 117,000 -> 124,519 -- ~35 B per checked site (133 in zlib: the
  failure call's two literals and the blocks) plus the operator strings. A module already carrying
  the printer pays little (a toy with `ignore-errors`: +88 B). Outside EH mode: 0.

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

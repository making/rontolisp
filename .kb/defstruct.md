# `defstruct` — expansion into plain defuns, instance-object representation

User behavior: `doc/en/reference/special-forms/defstruct.md`. `#S(...)` print/read:
`.kb/instance-syntax.md`.

## Expansion to existing primitives, no backend codegen

- `LispMacroExpander.expandDefstruct(cons, structAccessors)` is the one expansion for all
  backends; no `Jvm/Wasm` defstruct compiler exists.
- `(defstruct point x (y 10))` -> top-level defuns `make-point` (`&key`, slot defaults),
  `point-p`, `copy-point`, one accessor per slot.
- Over the registered `LispLayout`: constructor `(%obj-new '%struct-<name> slot...)`,
  predicate `(%obj-is obj '%struct-<name>)`, accessor `(%obj-ref obj 0)`, setf place
  `%obj-set`, copier a fresh `%obj-new` over `%obj-ref`s (shallow, like `copy-structure`).
  Six `%obj-*` primitives and per-backend shapes: `.kb/instance-syntax.md`.
- Consequences: instances are not `consp`/`listp`; `print` gives `#S(NAME :SLOT value ...)`;
  source `#S(...)` reads back via `StructLiteralFolder` (so the layout carries ordered slot
  names and initforms); `equal` compares slot-wise (deliberate deviation from CL).
- Options: `:constructor` / `:conc-name` / `:predicate` / `:copier` / `:include` / `:type` /
  `:print-object` / `:print-function`; a dropped docstring; slot options `:type`/`:read-only`
  (parsed, ignored). Anything else throws
  `UnsupportedOperationException("DEFSTRUCT option is not supported: ...")`.
- BOA: `(:constructor name (lambda-list))` passes the lambda list through verbatim; a slot
  whose plain name matches a parameter (`boaParameterSymbols`, markers skipped,
  `(name default)` binds `name`) reads it, other slots evaluate their initform inline.
- A struct name is a valid `defmethod` specializer: `expandDefstruct` registers it in
  `ClosRegistry` (`registerStruct` -> `findStructTag`); the dispatcher emits the predicate's
  tag test.
- `:conc-name` takes a STRING DESIGNATOR: `(:conc-name :http-)` gives `http-state`, not
  `:http-state`; `LispMacroExpander`'s `:CONC-NAME` arm strips a leading colon. Trap: keeping
  the colon yields accessors no call site can name, surfacing as "setf does not support
  place: ...". Pins: `LispEvaluatorTest#evalDefstructAcceptsAKeywordConcName`, ci-spec
  `defstruct-keyword-conc-name`.

## Hook points

- Interpreter `LispEvaluator.evalDefstruct` (an `evalCons` case) expands and evaluates each
  generated defun -- REPL, `load`, runtime `eval`, user-macro expansions; returns the name.
- Compilers: `LispMacroExpander.expandTopLevelDefstructs`, inside
  `Jvm/WasmLispCompiler.compile` between `PackageResolver` and `LambdaLists.desugarProgram`
  (the `&key` constructor must precede desugaring), SPLICES one defstruct into several
  top-level defuns for Pass 1. Splicing rather than 1-form expansion is load-bearing: Pass 1
  does not descend into `progn`.
- A non-top-level defstruct errors in `Jvm/WasmExprCompiler.compileCons` ("only supported as
  a top-level form"); `--no-gc` (`NoGcWasmCompiler`) rejects it with "supports only
  (defun ...)".
- Bundled libraries (torch/linalg/vec) expand EARLIER on the pruning path:
  `LibraryDefunPruner.prune` splices the defuns ahead of reachability (each prunes
  individually) and leaves a `(%struct-definition (defstruct ...))` marker that
  `expandTopLevelDefinitions` consumes for registration side effects only
  (`.kb/library-defun-pruning.md`). Explicit option names (`(:predicate torch:tensorp)`) are
  used verbatim -- the robust spelling for an exported API.
- `UserMacroExpander` has a defstruct case in its structure-aware walker (names preserved,
  slot defaults expanded as expressions); a user macro may expand INTO a top-level defstruct.

## `:include` (single inheritance)

- Parent slots (in ITS layout order, so chains nest) are PREPENDED, so an inherited slot
  keeps one index in every descendant and the parent's baked `%obj-ref` accessors read a
  child unchanged.
- `ClosRegistry.structAncestors`; `descendantStructTags(name)` is the tag set tested by
  `typep`, the generated predicate and a struct specializer; `structAncestorCount` ranks a
  struct specializer (band 100-199, between classes and built-in types, deeper first).
- `(:include parent (slot new-default) ...)` re-defaults an inherited slot in THIS child's
  layout only, index kept; an unknown slot throws, naming it. Matched on the UNQUALIFIED slot
  name (source spells them qualified, `(:include quri.uri:uri (quri.uri::scheme "http"))`;
  layouts record base names).
- Predicates are REGENERATED as later children appear, else `(base-p child)` tests too few
  tags. `LispMacroExpander.structPredicateDefun` rebuilds; interpreter `evalDefstruct`
  re-evaluates each ANCESTOR's predicate (`ClosRegistry.structAncestorNames`) after an
  `(:include parent)` struct; compile path `expandTopLevelDefinitions` calls
  `refreshStructPredicates` in the "registry is complete" phase that fills method
  dispatchers, replacing in place ONLY when the name is a registered struct predicate and the
  body is still the generated `(%obj-is __struct ...)` shape (user redefinitions survive).
  `ClosRegistry.structPredicates` (struct name -> predicate defun name) records what to
  rebuild; `(:predicate nil)` and `:type` structs never enter it.
- Pins: `LispEvaluatorTest#defstructIncludeInheritsSlotsAndTypeTests`,
  `Jvm/WasmLispCompilerIntegrationTest#compileAndRunDefstructIncludePredicateMatchesLaterChildren`,
  ci-spec `ironclad-residue-features`.
- No refresh for classes: a `defmethod` specialized on a `defclass` class bakes the tags
  known when the dispatcher is generated (compile path: after the whole program registers;
  interpreter: registry at call time). Gap: a compiled program registering a class via
  runtime `eval`.

## `:type (vector ...)`

- A typed struct IS a plain vector: no instance tag, NOT registered as a type (no
  specializer, no `typep`, not a `structure-object`, as in CL). Constructor `(vector v...)`,
  copier `copy-seq`, accessor an `aref` read, plus a generated `%setf-<accessor>` writer
  defun per accessor (setf-function protocol -- no instance to `%obj-set`). `(:type list)`
  unsupported; `:include` on a typed struct rejected.
- A declared `(vector (unsigned-byte 8|16|32))` element type is KEPT: constructor builds
  `(make-array n :element-type '(unsigned-byte w) :initial-contents (list v...))`, so the
  instance is packed on every backend and each accessor's `aref` takes the packed fast path.
  Other element types (or none) keep plain `(vector ...)`.
- Stores ride `LispMacroExpander.TYPED_VECTOR_SLOT_BASE`: the accessor registers in
  `structAccessors` as `TYPED_VECTOR_SLOT_BASE - index` (below the `-1` setf-function marker;
  sole value-reading consumer is `expandSetf`) and `(setf (acc obj) v)` expands to
  `(setf (aref obj i) v)` -- same subform order and value, no boxed trip through the writer,
  which still exists for first-class uses.
- Consequence: stores MASK to the declared width (`(setf (aref r 0) (expt 2 33))` reads 0).

## `(:print-object fn)` / `(:print-function fn)`

Both take a function DESIGNATOR (symbol or lambda expression) and lower to ONE synthesized

```lisp
(defmethod print-object ((obj <struct>) stream) (funcall fn obj stream [0]))
```

appended LAST to `expandDefstruct`'s forms, so its accessor calls read defuns generated above
it and the struct is registered when the specializer is parsed.

- A symbol designator is taken with `#'` (Lisp-2); anything else is already function-valued.
- `:print-function` is the CLtL1 spelling, differing only in the third `depth` argument, the
  literal `0` (no print level is tracked). No argument, or `nil`, keeps default `#S(...)`.
- No printer machinery of its own: rides the `print-object` seam (`.kb/clos.md`), which
  reaches struct instances on all four backends (`printObjectTags` collects descendant tags;
  printing operators route through `%print-object-str`).
- Compile path: the forms go through `addExpandedDefinition` so the method registers and its
  dispatcher slot is placed. Trap: routed past it, the raw `defmethod` reaches Pass 1 as an
  unknown top-level form.
- A later user `defmethod print-object` on the struct replaces this method.
- Seam reach: fires for a value nested in a printed list or general rank-1 vector, NOT for
  one in a structure slot -- `#S(BOX :ITEM #S(NODE ...))` keeps the inner struct's built-in
  rendering (`.kb/clos.md`).
- Rejected, not ignored: mutually exclusive with `:type` (no instance tag to specialize on);
  both spellings at once is an error, as in CL.
- `print-unreadable-object`'s `:type t` spells the designator as `*print-escape*` decides --
  `prin1` keeps the package qualifier (`#<MAP-SET:MAP-SET of 1 element>`), `princ` writes only
  the name -- so `LispMacroExpander.typeNameOf` reads `*print-escape*`. That expansion runs in
  Pass 2, after `injectMvSpillGlobal`'s reference scan, so the scan counts an UN-EXPANDED
  `print-unreadable-object` operator as the reference declaring the variable.
- Pins: `LispEvaluatorTest#evalDefstructPrint*`, `#evalDefstructRejectsBothPrinterOptions`,
  `Jvm/WasmLispCompilerTest#compileAndRunDefstructPrintObjectAndPrintFunctionOptions`,
  ci-spec `defstruct-print-object-and-print-function`.

## setf on accessors: the registry

- No `defsetf`; `LispMacroExpander.expandSetf`'s place list is a hard-coded switch.
  `expandDefstruct` records accessor -> 1-based slot position, and
  `expandSetf(cons, structAccessors)` resolves an unknown place accessor through it:
  `(setf (point-x p) v)` -> `(%obj-set p 0 v)`.
- The registry is per-evaluator (`LispEvaluator.structAccessors`) and per-compilation
  (`Jvm/WasmLispCompiler.Ctx.structAccessors`, threaded through the shared `Ctx.Builder`),
  passed by the three dispatch sites (`LispEvaluator` SETF case, `Jvm/WasmExprCompiler` SETF
  case).
- `push`/`pop`/`incf`/`decf`/`remf` emit un-expanded `(setf ...)` forms that re-dispatch
  there, so struct places compose for free.
- Known gap: the zero-arg `expandSetf(cons)` (used by `macroexpand-1`) sees an empty
  registry, so macroexpand of a struct-place setf errors.

## setf-functions: `(defun (setf name) ...)` reuse that registry

- Stored with sentinel `LispMacroExpander.SETF_FUNCTION_MARKER` (`-1`; real slot positions
  are `>= 1`), so no new registry threads through the three dispatch sites.
- The writer defun is installed under `LispMacroExpander.setfFunctionName(name)` =
  `%setf-<name>` (JVM/WASM-compilable), NOT in the ordinary function namespace.
- `expandSetf`'s default branch, on the marker, expands `(setf (name arg...) val)` to
  `(funcall #'%setf-name val arg...)` -- new value FIRST, per the CL setf lambda-list
  convention.
- `#'(setf name)` resolves to `#'%setf-name` in `evalFunction` and both
  `Jvm/WasmFunctionFormCompiler`s; `LispMacroExpander.setfFunctionPlaceName` recognizes the
  `(setf name)` designator everywhere.
- Registration: `evalDefun` (interpreter) and `expandTopLevelDefinitions` (compile path,
  which also rewrites the defun name to `%setf-name` so Pass 1 collects it as an ordinary
  defun; its early-return guard also fires on a setf-function defun).
- Non-goal: `symbol-function`/`fboundp` of a `(setf ...)` name. Pins:
  `LispEvaluatorTest#setfFunction*`,
  `Jvm/WasmLispCompilerIntegrationTest#compileAndRunSetfFunctionDefinition`, ci-spec
  `setf-function-definitions`.

## Package-qualified names

Expansion is POST-resolution, so names derive textually from the canonical struct name:
`(defstruct foo::point x)` (or `foo:point`) generates
`foo::make-point`/`foo::point-p`/`foo::copy-point`/`foo::point-x`, always the internal
double-colon spelling `PackageResolver` gives their in-package call sites. Constructor
keywords use the unqualified slot base name via the explicit `((:x foo::x) default)` `&key`
form. Limitation: `:export`ing generated names in `defpackage` makes call sites resolve to
the single-colon spelling and fail.

## Gaps and pins

- No `structurep` (not standard CL); per-struct predicates only.
- Compiled runtime `eval`: generated functions are callable, but eval'd forms cannot define
  structs or setf accessor places (`doc/en/guides/eval-limitations.md`).
- Pins: `LispEvaluatorTest#defstruct*`, `JvmLispCompilerTest#compileAndRunDefstruct*` +
  `compileNestedDefstructFails`, `WasmLispCompilerIntegrationTest#compileAndRunDefstruct*`,
  `UserMacroExpanderTest#defstructNamesAreNotMistakenForMacroCalls*` /
  `macroExpandingToDefstructIsKept`, ci-spec
  `defstruct-constructor-accessors-predicate-copier`,
  `defstruct-setf-places-and-first-class-accessors`, and the
  `doc/*/reference/special-forms/defstruct.md` examples via `DocExamplesTest`.

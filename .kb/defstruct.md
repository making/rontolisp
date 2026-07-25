# `defstruct` — expansion into plain defuns, instance-object representation

User-facing behavior: `doc/en/reference/special-forms/defstruct.md` (and the
missing-features guide for what is out of scope: `:include`). An instance both
prints AND reads as `#S(...)` — `.kb/instance-syntax.md` owns both halves.
The options syntax IS supported: `(:constructor name)` /
`(:conc-name prefix)` / `(:predicate name)` / `(:copier name)` /
`(:include parent)` / `(:type (vector ...))`, a dropped
docstring before the slots, slot options `:type`/`:read-only` (parsed,
ignored), and lite BOA constructors — `(:constructor name (lambda-list))`
passes the lambda list to the generated defun verbatim; a slot whose plain
name matches a lambda-list parameter (collected by `boaParameterSymbols`,
markers skipped, `(name default)` binds `name`) reads that parameter, every
other slot evaluates its initform inline in the constructor body. A struct
name is also usable as a `defmethod` parameter specializer: `expandDefstruct`
registers the type in the `ClosRegistry` (`registerStruct` →
`findStructTag`), and the dispatcher emits the same tag test as the
predicate.

## Design: expand to existing primitives, no backend codegen

`LispMacroExpander.expandDefstruct(cons, structAccessors)` is the single
expansion shared by all backends. `(defstruct point x (y 10))` becomes ordinary
top-level defuns — keyword constructor (`make-point`, `&key` with the slot
defaults), predicate (`point-p`), copier (`copy-point`), and one accessor per
slot (`point-x` = `(%obj-ref obj 0)`) — so no `Jvm/Wasm` defstruct compiler
exists. An instance is a first-class object built by `%obj-new` over the
registered `LispLayout` (see `.kb/instance-syntax.md` for the six
`%obj-*` primitives and the per-backend value shapes): the constructor is
`(%obj-new '%struct-<name> slot...)`, the predicate `(%obj-is obj
'%struct-<name>)`, the copier a fresh `%obj-new` over `%obj-ref`s (shallow, like
`copy-structure`), and an accessor's setf place `%obj-set`. Consequences:
instances do NOT satisfy `consp`/`listp`, `print` shows `#S(NAME :SLOT value
...)` and a source `#S(...)` reads back into an instance (`StructLiteralFolder`,
which is why the layout carries the ordered slot names and initforms), and
`equal` compares slot-wise (a deliberate deviation from CL, where distinct
structures are never `equal` -- see `.kb/instance-syntax.md`).

## Where each path hooks in

- **Interpreter**: `LispEvaluator.evalDefstruct` (an `evalCons` case) expands
  and evaluates each generated defun, so defstruct also works in the REPL,
  `load`, runtime `eval`, and inside user-macro expansions. Returns the struct
  name symbol.
- **Compilers**: `LispMacroExpander.expandTopLevelDefstructs` runs inside
  `Jvm/WasmLispCompiler.compile` between `PackageResolver` and
  `LambdaLists.desugarProgram` (the constructor uses `&key`, so it must precede
  desugaring), splicing each top-level defstruct into multiple top-level defuns
  that Pass 1 collects normally. A non-top-level defstruct hits a
  `Jvm/WasmExprCompiler.compileCons` case that errors ("only supported as a
  top-level form"); Pass 1 does not descend into `progn`, so splicing (not a
  1-form macro expansion) is load-bearing. `--no-gc`
  (`NoGcWasmCompiler`) rejects defstruct via its generic "supports only
  (defun ...)" top-level error.
- **`UserMacroExpander`** has a defstruct case in its structure-aware walker:
  the struct/slot names are preserved, slot defaults are expanded as
  expressions. A user macro may expand INTO a top-level defstruct (the splice
  runs later, inside the compilers).

## `:include` (single struct inheritance) and `:type (vector ...)` — todo-173

**`(:include parent)`**: the parent's slots (in ITS layout order, so a chain
nests) are prepended to the child's, which keeps every inherited slot at the
same index in all descendants — the parent's accessors, its `%obj-ref` indexes
baked, therefore read a child instance unchanged. `ClosRegistry` records the
ancestor chain (`structAncestors`, the struct-side twin of
`ClassInfo.ancestors`); `descendantStructTags(name)` gives the tag set that
`typep`, the generated predicate and a struct method specializer all test, and
`structAncestorCount` ranks a struct specializer for dispatch (band 100-199,
between classes and built-in types, deeper `:include` first). Slot-override
syntax (`(:include parent (slot new-default))`) is NOT supported.

**Known limit — the predicate is emitted before later children exist**: a
predicate defun bakes the descendant tags registered AT ITS defstruct, so
`(base-p child)` is `NIL` when `child`'s defstruct comes after `base`'s (the
normal case). `(typep x 'base)` written after both IS `T` — type tests expand at
their use site, predicates at their definition site. Both compile paths and the
interpreter process forms in order, so all four backends agree. Re-evaluation
trigger: fixing this needs either the parent's predicate REGENERATED on each
child registration (interpreter) plus a pre-scan of `:include` links before any
predicate is emitted (compile path), or a late-bound runtime ancestry test —
neither existed when `:include` landed, and the ironclad slice that motivated it
only needs the `typep`/specializer side. Pinned by
`LispEvaluatorTest#defstructIncludeInheritsSlotsAndTypeTests`, which asserts the
`NIL` deliberately.

**`(:type (vector ...))`**: a typed struct IS a plain vector — no instance tag,
so it is not registered as a type (no specializer, no `typep`, not a
`structure-object`, matching CL). The constructor builds `(vector v...)`, the
copier is `copy-seq`, an accessor is an `aref` read, and each accessor also gets
a generated `%setf-<accessor>` writer defun (the setf-function protocol) since
there is no instance to `%obj-set`. The element type is dropped (rontolisp arrays
are generic). `(:type list)` is not supported; `:include` on a typed struct is
rejected. This is what makes ironclad's `define-digest-registers` — a
`(:type (vector (unsigned-byte 32)))` struct over the digest registers — load
verbatim.

## setf on accessors: the registry

There is no `defsetf`; `LispMacroExpander.expandSetf`'s place list is a
hard-coded switch. `expandDefstruct` therefore records accessor -> 1-based slot
position into a registry map, and the overload
`expandSetf(cons, structAccessors)` resolves an unknown place accessor through
it, expanding `(setf (point-x p) v)` to `(%obj-set p 0 v)`.
The registry lives per-evaluator (`LispEvaluator.structAccessors`) and
per-compilation (`Jvm/WasmLispCompiler.Ctx.structAccessors`, threaded through
the shared `Ctx.Builder`); the three setf dispatch sites (`LispEvaluator`
SETF case, `Jvm/WasmExprCompiler` SETF case) pass it. `push`/`pop`/`incf`/
`decf`/`remf` emit un-expanded `(setf ...)` forms that re-dispatch through
those sites, so struct places compose with them for free. The zero-arg
`expandSetf(cons)` (used by `macroexpand-1`) sees an empty registry, so
macroexpand of a struct-place setf errors — known, minor.

## setf-functions: `(defun (setf name) ...)` reuse the same registry

`(defun (setf name) (args... newval) body)` defines a *setf-function* — the
writer invoked by `(setf (name arg...) val)`. It reuses the `structAccessors`
map as another place kind: the name is stored with the sentinel value
`LispMacroExpander.SETF_FUNCTION_MARKER` (`-1`; real slot positions are always
`>= 1`), so no new registry has to be threaded through the three setf dispatch
sites. The writer defun is installed under the mangled internal name
`LispMacroExpander.setfFunctionName(name)` = `%setf-<name>` (a `%`-prefixed
symbol, already JVM/WASM-compilable), NOT in the ordinary function namespace.
`expandSetf`'s default branch, on seeing the marker, expands
`(setf (name arg...) val)` to `(funcall #'%setf-name val arg...)` — the new
value first, per the CL setf lambda-list convention. `#'(setf name)` resolves to
`#'%setf-name` in `evalFunction` (interpreter) and the two
`Jvm/WasmFunctionFormCompiler`s. `LispMacroExpander.setfFunctionPlaceName`
recognizes the `(setf name)` two-element designator everywhere. Registration
happens in `evalDefun` (interpreter) and, on the compile path, in
`expandTopLevelDefinitions` (which also rewrites the defun name to `%setf-name`
so Pass 1 collects it as an ordinary defun; its early-return guard now also
fires on a setf-function defun). Non-goal: `symbol-function`/`fboundp` of a
`(setf ...)` name. Pinning: `LispEvaluatorTest#setfFunction*`,
`Jvm/WasmLispCompilerIntegrationTest#compileAndRunSetfFunctionDefinition`,
ci-spec `setf-function-definitions`. Roadmap: todo-079.

## Package-qualified names

Expansion happens POST-resolution, so names are derived textually from the
canonical struct name: `(defstruct foo::point x)` (or `foo:point`) generates
`foo::make-point`/`foo::point-p`/`foo::copy-point`/`foo::point-x` — always the
internal double-colon spelling, matching how `PackageResolver` canonicalizes
their in-package call sites (fresh user symbols are internal). Constructor
keywords use the unqualified slot base name via the explicit
`((:x foo::x) default)` `&key` form. Limitation: `:export`ing generated names
in `defpackage` makes call sites resolve to the single-colon spelling and fail
(documented in the reference page).

## Out of scope / known gaps

- defstruct options (`(defstruct (name ...) ...)`) throw
  `UnsupportedOperationException("defstruct options are not supported")`.
- No `structurep` (not standard CL); per-struct predicates only.
- Compiled runtime `eval`: generated functions are callable (normal registry
  defuns), but eval'd forms cannot define structs or setf accessor places
  (doc/en/guides/eval-limitations.md).
- `defstruct` is in `PackageRegistry.CL_SPECIAL_FORMS`, so it appears in
  `list-special-forms` — pinned in ci-spec
  (`rontolisp-package-introspection`), the three backend tests, and the doc
  pages; update all together if that set changes again.

Pinning tests: `LispEvaluatorTest#defstruct*`,
`JvmLispCompilerTest#compileAndRunDefstruct*` + `compileNestedDefstructFails`,
`WasmLispCompilerIntegrationTest#compileAndRunDefstruct*`,
`UserMacroExpanderTest#defstructNamesAreNotMistakenForMacroCalls*` /
`macroExpandingToDefstructIsKept`, ci-spec cases
`defstruct-constructor-accessors-predicate-copier` and
`defstruct-setf-places-and-first-class-accessors` (all four backends), and the
`doc/*/reference/special-forms/defstruct.md` examples via `DocExamplesTest`.

# `defstruct` — expansion into plain defuns, tagged-list representation

User-facing behavior: `doc/en/reference/special-forms/defstruct.md` (and the
missing-features guide for what is out of scope: options syntax, `:include`,
BOA constructors, `#S(...)` print/read, CLOS).

## Design: expand to existing primitives, no backend codegen

`LispMacroExpander.expandDefstruct(cons, structAccessors)` is the single
expansion shared by all backends. `(defstruct point x (y 10))` becomes ordinary
top-level defuns — keyword constructor (`make-point`, `&key` with the slot
defaults), predicate (`point-p`), copier (`copy-point`, `copy-list`), and one
accessor per slot (`point-x` = `(nth 1 obj)`) — so no `Jvm/Wasm` defstruct
compiler exists. An instance is a tagged proper list
`(%struct-<name> v1 v2 ...)`; the predicate compares the tag with `equal`
(content-safe on WASM, where symbols are interned strings). Consequences:
instances satisfy `consp`/`listp`, `print` shows the underlying list, `equal`
compares slot-wise.

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

## setf on accessors: the registry

There is no `defsetf`; `LispMacroExpander.expandSetf`'s place list is a
hard-coded switch. `expandDefstruct` therefore records accessor -> 1-based slot
position into a registry map, and the overload
`expandSetf(cons, structAccessors)` resolves an unknown place accessor through
it, expanding `(setf (point-x p) v)` like `(setf (nth 1 p) v)` (rplaca/nthcdr).
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
ci-spec `setf-function-definitions`. Roadmap: `.todo/79`.

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

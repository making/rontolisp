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
between classes and built-in types, deeper `:include` first).

**Slot overrides** — `(:include parent (slot new-default) ...)` — re-default one
inherited slot in THIS child's layout; the parent's own initform is untouched and
the slot keeps its inherited INDEX, so the parent's baked `%obj-ref` accessors
still read it. Overriding a slot the parent does not define throws, naming the
slot. Overrides are matched on the UNQUALIFIED slot name, because a resolved
source form spells them package-qualified (`(:include quri.uri:uri
(quri.uri::scheme "http"))`) while a layout records base names. quri's
`uri-http`/`uri-https`/`uri-ftp`/`uri-ldap`/`uri-file`/`urn` are all built this
way, which is what made this arrive.

**The predicate is REGENERATED as later children appear**: a predicate defun can
only bake the descendant tags registered at the point it is built, and a child's
defstruct normally comes after its parent's — so `(base-p child)` would test too
few tags. Every generated predicate is therefore rebuilt (
`LispMacroExpander.structPredicateDefun`, from the registry as it then stands)
once the wider tag set is known:

- **interpreter** — `evalDefstruct`, after evaluating the expansion of a
  `(:include parent)` struct, re-evaluates the predicate defun of each ANCESTOR
  (`ClosRegistry.structAncestorNames`), so the redefinition happens form by form
  exactly as a REPL would need it.
- **compile path** — `expandTopLevelDefinitions` calls `refreshStructPredicates`
  in the same "registry is complete" phase that fills the method dispatchers,
  replacing each emitted predicate defun in place. It only replaces a form whose
  name is a registered struct predicate AND whose body is still the generated
  `(%obj-is __struct ...)` shape, so a program that redefines that name itself
  keeps its own defun.

`ClosRegistry.structPredicates` (normalized struct name → predicate defun name)
is what records which defuns to rebuild; `(:predicate nil)` and `:type` structs
never enter it (no predicate to refresh). Pinned by
`LispEvaluatorTest#defstructIncludeInheritsSlotsAndTypeTests`,
`Jvm/WasmLispCompilerIntegrationTest#compileAndRunDefstructIncludePredicateMatchesLaterChildren`
and the ci-spec `ironclad-residue-features` case (all four backends).

Still definition-site-baked, for the same reason but WITHOUT a refresh: a
`defmethod` whose specializer names a `defclass` class tests the descendant tags
known when the dispatcher is generated. On the compile path that is after the
whole program is registered (so subclasses defined later are covered), and the
interpreter dispatches through the registry at call time — the gap is only a
compiled program that registers a class through runtime `eval`.

**`(:type (vector ...))`**: a typed struct IS a plain vector — no instance tag,
so it is not registered as a type (no specializer, no `typep`, not a
`structure-object`, matching CL). The constructor builds `(vector v...)`, the
copier is `copy-seq`, an accessor is an `aref` read, and each accessor also gets
a generated `%setf-<accessor>` writer defun (the setf-function protocol) since
there is no instance to `%obj-set`. `(:type list)` is not supported; `:include`
on a typed struct is rejected. This is what makes ironclad's
`define-digest-registers` — a `(:type (vector (unsigned-byte 32)))` struct over
the digest registers — load verbatim.

Since todo-413 (2026-08-16) a **declared `(vector (unsigned-byte 8|16|32))`
element type is KEPT**: the constructor builds
`(make-array n :element-type '(unsigned-byte w) :initial-contents (list v...))`,
so the instance is a packed integer vector on every backend and each accessor's
`aref` takes the packed fast path (a general vector boxed every 32-bit digest
register read through the generic array dispatch — the wasm hot-path driver).
Any other element type (or none) keeps the plain `(vector ...)` construction.
The store side rides `LispMacroExpander.TYPED_VECTOR_SLOT_BASE`: such an
accessor registers in `structAccessors` as `TYPED_VECTOR_SLOT_BASE - index`
(below the `-1` setf-function marker; the one value-reading consumer is
`expandSetf`), and `(setf (acc obj) v)` expands to the `(setf (aref obj i) v)`
the `%setf-` writer would perform — same subform order, same value, no boxed
trip across the writer's call boundary. The writer defun still exists and still
serves first-class uses. Consequence to remember: stores into such a struct now
MASK to the declared width (`(setf (aref r 0) (expt 2 33))` reads back 0), as
in a real CL packed array — the general-vector behavior kept the wide value.

## `(:print-object fn)` / `(:print-function fn)` -- the printer options

Both name the struct's printer as a function DESIGNATOR (a symbol or a lambda
expression), and both are lowered to ONE thing: a synthesized

```lisp
(defmethod print-object ((obj <struct>) stream) (funcall fn obj stream [0]))
```

appended LAST to `expandDefstruct`'s generated forms, so the method body's accessor
calls read defuns already generated above it and the struct is registered (the
`registerStruct` in the slot loop) by the time the specializer is parsed. A symbol
designator is taken with `#'` -- this is a Lisp-2 -- and anything else (a `(lambda
...)`, an explicit `#'name`) is already the function-valued form.
`(:print-function fn)` is the CLtL1 spelling and differs ONLY in passing a third
`depth` argument; nothing tracks a print level, so it is the literal `0`, which is
what an implementation without a depth counter passes (SBCL passes 0 at top level
too). An option with NO argument, or with `nil`, leaves the default `#S(...)`
printing in place.

**There is no printer machinery of its own**: the option rides the `print-object`
seam (`.kb/clos.md`), which already reaches struct instances on all four backends --
a struct name parses as a TYPE specializer, so `printObjectTags` collects its
descendant tags and every printing operator routes through `%print-object-str`.
Consequences worth knowing:

- On the compile path the generated forms go through `addExpandedDefinition` (like
  `expandDefclass`'s, which also mixes plain defuns with synthesized defmethods), so
  the method registers and its dispatcher slot is placed. Route them past it and the
  raw `defmethod` reaches Pass 1 as an unknown top-level form.
- A later user `defmethod print-object` on the same struct simply replaces this
  method -- same generic, same specializer.
- The seam's reach applies: since todo-437 the printer fires for a value nested inside
  a printed list or general rank-1 vector too, but NOT for one stored in a structure
  slot -- `#S(BOX :ITEM #S(NODE ...))` keeps the built-in rendering of the inner struct
  (`.kb/clos.md` carries the trigger).

**Rejected, not ignored**: the pair is mutually exclusive with `:type` (a typed
struct IS a plain vector -- no instance tag to specialize on, and silently printing
the vector is a divergence a program can see), and giving both spellings at once is
an error, as in CL.

This is what makes map-set (`map-set-20230618-git`, BSD 3-Clause, Robert Smith) load
verbatim -- its one struct carries a `:print-function` whose output nothing in myway
or ningle ever reads -- and its `~:P` plural directive needs nothing new: the static
format expansion declines the directive and falls back to the runtime renderer, which
has had `%fmt-plural` all along (`.kb/format.md`). Pinned by
`LispEvaluatorTest#evalDefstructPrint*` / `#evalDefstructRejectsBothPrinterOptions`,
`Jvm/WasmLispCompilerTest#compileAndRunDefstructPrintObjectAndPrintFunctionOptions`
(the compile-path half, which ci-spec cannot check without the native binary) and
the ci-spec case `defstruct-print-object-and-print-function` (all four backends).

`print-unreadable-object`'s `:type t` spells the type designator the way CL writes
the type SYMBOL at that moment, i.e. as `*print-escape*` decides: `prin1` keeps the
package qualifier (`#<MAP-SET:MAP-SET of 1 element>`), `princ` writes only the
symbol's name (`#<MAP-SET of 1 element>`) -- SBCL-checked, and invisible to any
struct whose name is unqualified. `LispMacroExpander.typeNameOf` therefore reads
`*print-escape*`, and because that expansion runs in Pass 2 -- long after
`injectMvSpillGlobal`'s reference scan -- the scan counts an UN-EXPANDED
`print-unreadable-object` operator as the reference that declares the variable.

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

- An UNSUPPORTED defstruct option throws
  `UnsupportedOperationException("DEFSTRUCT option is not supported: ...")`, naming
  the clause. The supported set is `:constructor` / `:conc-name` / `:predicate` /
  `:copier` / `:include` / `:type` / `:print-object` / `:print-function`.
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

**`:conc-name` takes a STRING DESIGNATOR, so a KEYWORD designates its name without
the colon**: `(defstruct (http (:conc-name :http-)) ... state)` defines
`http-state`, not `:http-state`. Keeping the colon produced accessors no call site
could name, and the failure surfaced far away as "setf does not support place:
FAST-HTTP.HTTP:HTTP-STATE" -- the accessor defuns existed under an unreachable
spelling and the setf registry never learned them. `LispMacroExpander`'s
`:CONC-NAME` arm strips a leading colon. Pinned by
`LispEvaluatorTest#evalDefstructAcceptsAKeywordConcName` and the ci-spec case
`defstruct-keyword-conc-name`.

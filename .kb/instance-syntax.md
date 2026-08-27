# Instances — the object value model, `#S(...)`/`#<...>` printing, the `%obj-*` primitives

User-facing behavior: `doc/*/reference/special-forms/{defstruct,defclass}.md`,
`doc/*/reference/macros/make-condition.md`, `doc/*/guides/missing-features.md`.
This file is the invariant: **a `defstruct` instance, a CLOS instance and a
condition instance are ONE value type, and every read, write, construction and
type test of one goes through the `%obj-*` primitives — nothing else may
touch slot storage.** That seam is the whole point: it is what let the model
move off tagged lists without editing a single expansion, and it is what keeps
the interpreter, the JVM backend and the WASM-GC backend from drifting apart.

## The normative text contract

An instance is self-describing: it carries a `LispLayout` (tag, print name,
`STRUCT`/`CLASS` kind, ordered slot base names, initforms), so the printer needs
no registry lookup and all three backends can render it with one fixed loop.

- A `defstruct` instance prints `#S(NAME :SLOT value ...)`.
- A `defclass`/`define-condition` instance prints `#<NAME :SLOT value ...>`.
- The third layout kind, `PATHNAME`, is the ONE fixed layout of the pathname
  value (`.kb/pathnames.md`): it prints `#P"namestring"` under `prin1` and the
  bare namestring under `princ` -- a per-kind arm in all three printer loops,
  never the slot-name syntax -- is seeded layout-only (never in `classes()`,
  never a struct tag, excluded from the `#S` reader directories), and reads
  back through the `#P` dispatch rather than `#S`.
- The `#S`/`#<` frame and the `:` on each slot key are LITERAL syntax, so they
  are emitted under `princ` too (CLHS 22.1.3.12, matching SBCL); only the slot
  VALUES follow the ambient escape mode. `prin1`, `princ`, `format ~s`/`~a`,
  `print`, `prin1-to-string`, `princ-to-string`, `write-to-string` all agree.
- `NAME` is the type name as spelled, keeping a package qualifier (`GEO::PT`).
- A slot-less type prints `#S(EMPTY)` / `#<EMPTY>`.
- The ONE exception is a CONDITION under `princ`/`~A`: it renders its `:report`
  instead (`prin1`/`~S` keep the `#<...>` form). See
  `.kb/error-handling.md`, "A condition's `:report` is what PRINTS it".

A `#S(...)` literal in SOURCE reads back into the instance it denotes (see
"Reading `#S(...)`" below), and so does one handed to the runtime `read` /
`read-from-string` on EVERY backend, so `(read-from-string (prin1-to-string p))`
round trips everywhere. The compiled readers resolve the type in a baked
directory of every registered layout (JVM: the `_rdStructs` `Object[][]` filled
in `<clinit>`; WASM: a blob after the `WasmInstanceLayouts` records) with
`findStructTag`'s exact-then-member-fallback rule, and apply the fold's slot
rules; an omitted slot takes a `nil` initform, re-reads a baked constant text
(`EmittedReaderInitforms`), or signals -- never a silently wrong value. Error
messages match `StructLiteralFolder`'s verbatim on the JVM; WASM signals static
messages (trap outside EH mode). See `.kb/read-load-streams.md`.

## Reading `#S(...)`

CL builds the structure inside the reader, which works because `load` reads and
evaluates one form at a time, so an earlier `defstruct` is already in effect.
rontolisp reads a whole file up front and `LispReader` knows nothing about the
`ClosRegistry`, so reading is split in two:

- The lexer's `#S(`/`#s(` branch (beside `#(`) emits `Token.StructOpen` and
  `LispReader.readStruct` builds a **`LispStructLiteral`** — the type name and
  the slot name/value pairs exactly as written. Only what the reader can decide
  alone is decided there: a non-symbol type name, a missing type name and an ODD
  slot list are `LispReadException`s.
- `StructLiteralFolder` turns each literal into a `LispInstance` against the
  registry. It runs **per top-level form** on both paths — inside
  `expandTopLevelDefinitions` on the compile path, at the interpreter's two
  top-level `eval` entries (`eval(LispVal)` / `evalResolved`) via
  `LispEvaluator.resolveStructLiterals`, a sibling of `resolveReadTimeEval`.
  Per-form, not whole-program, is the point: it reproduces CL's rule that the
  `defstruct` must PRECEDE the literal, on every backend.
- The interpreter's runtime `read` / `read-from-string` fold their RESULT through
  the same `StructLiteralFolder`. `LispEvaluator.registerEval` rebinds the two
  `Environment` built-ins (which hold no registry) to themselves plus the fold —
  the FUNCTION binding, not the call sites, so `#'read-from-string` and every
  library that funcalls it fold too.

The carrier is a first-class `LispVal` rather than a `(%read-struct ...)` marker
cons on purpose: being neither a symbol nor a cons it rides through `quote`,
backquote templates (both the single-level and the CLtL2 nested expander) and
`#(...)` vector literals with no special case, exactly like the `LispInstance` it
becomes. The fold walks conses AND `LispArray` storage for the same reason.

Fold-time rules (CLHS 2.4.8.13): values are DATA and never evaluated
(`#S(BOX :V (+ 1 2))` stores the list); a repeated slot keeps its LEFTMOST value;
an omitted slot takes its recorded initform, which must be a CONSTANT — the fold
runs at compile time on three of the four backends, so a `(+ 1 2)` initform is a
clear error instead of a per-backend divergence. An unknown type name and a slot
the type does not have are errors too (a class name gets a "`#S` reads defstruct
types only" hint). Slot names match by package-stripped base name with the
keyword marker dropped, so `:X`, `X` and `PKG::X` all name slot `X`.

A `#S` literal under a failing `#+` needs no work: the lexer's skip already
treats `#<symbol-prefix>(` as a prefixed list.

The emit gate needs no new case either — a folded literal IS a `LispInstance` in
the AST and `mayCreateInstance` already answers `true` for one, independent of
whether the `defstruct`'s constructor defun survived. It was extended to look
inside `LispArray` storage, the one container its cons walk could not see
through.

## Consequences of "an instance is not a list"

`consp`, `listp` and `typep 'cons`/`'list`/`'sequence` are nil on an instance,
and `atom` is t. `(nth 1 c)` on a caught condition — once the documented way to
read a `simple-error`'s message — is retired; use
`simple-condition-format-control` or a slot reader. The internal
`%class-designator` returns the instance TAG symbol (`%class-NAME` /
`%struct-NAME`), which is also a designator `%class-slot-defs` accepts, so a
slot-walking serializer works against structs and CLOS instances alike
(json.lisp's `%json-out-instance`). `class-of` answers the class METAOBJECT
(`.kb/clos.md`), and `%class-slot-defs` accepts that too (through its name
slot).

**`eq` and `eql` on two instances are REFERENCE identity, on all four backends**
(CL's rule). The JVM (`Object[]` identity) and both WASM backends (`ref.eq`)
always were; the interpreter fell through to `LispInstance.equals` -- which is
structural, because that is `equal`'s contract below -- until todo-466 put
instances beside conses in `Environment.isIdentityAggregate`. The divergence was
filed as todo-444's Gap 2 and closed there: it stopped being theoretical when
torch's records became defstructs, because `torch::%t-topo`'s visited set and
`torch::%m-collect`'s parameter dedup are `member` (i.e. `eql`) over records and
BOTH mean identity -- two scalar tensors holding the same number were `eql` in
the interpreter, and the tape conflated them (`.kb/torch.md`). Gap 1 of that
todo -- `make-hash-table :test` being ignored, so every table is `EQUAL` -- is
untouched: this is the predicate, not the table.

`equal` on two instances is STRUCTURAL: same layout, every slot recursively
`equal`. **This is a deliberate deviation from CL**, where distinct structures
are never `equal`. Reason for the divergence: before the object model landed an
instance WAS a list, so `equal` already compared slot-wise, and the libraries
loaded through `ql:quickload` were written against a host where that is at least
true of `equalp`; switching to identity would have changed their behavior
silently and invisibly. Re-evaluate if a real program depends on CL's identity
rule — the change is one arm in each of the three `_equal`s. `equalp` descends
slot-wise on top of that: the prelude compares `(%obj-tag a)` with `(%obj-tag b)` and
then hands `(%obj-slots a)`/`(%obj-slots b)` back to `equalp`, so the existing cons arm
does the recursion. That is WHY `%obj-slots` exists -- walking the slots through
`%class-slot-defs` + a runtime-name `slot-value` instead cost +19 KB of registry
dispatch in every `equalp`-using artifact, versus +63 bytes this way.

## The primitives (`LispNames.OBJ_*`, all in `PackageRegistry.CL_INTERNALS`)

| primitive | meaning |
| --- | --- |
| `(%obj-new '<tag> v...)` | build an instance of the registered layout; values past its `capacity` are evaluated and dropped, missing ones are nil |
| `(%obj-ref obj <k>)` | read slot `k` (0-based) |
| `(%obj-set obj <k> v)` | write slot `k`, returning `v` |
| `(%obj-is obj '<tag>...)` | t when `obj` is an instance of any of the tags, nil for every other value |
| `(%obj-tag obj)` | the instance tag symbol, nil for a non-instance |
| `(%obj-p obj)` | t for any instance |
| `(%obj-slots obj)` | a FRESH list of the slot values in layout order, nil for a non-instance |

On the compile path the tag and the index must be LITERAL (a quoted symbol / an
integer); every expansion satisfies that because they all go through the private
`objNew`/`objRef`/`objSet`/`objIs`/`objTag` builders in `LispMacroExpander`
(`%obj-slots` needs neither, so the prelude calls it directly).
`(setf (%obj-ref o k) v)` is a `setf` place expanding to `%obj-set`, which is
why `slot-value`, `with-slots` and a struct accessor all compose with
`setf`/`incf`/`push` without a case of their own.

## Per-backend representation

- **Interpreter**: `LispInstance` (layout + `LispVal[]`), a `LispVal` sealed
  permit, self-evaluating (CLHS 3.1.2.1.3), `equals`/`hashCode` structural.
- **JVM**: `Object[]{ String[] layout, v1..vn }`. The `String[]` in slot 0 is
  both the layout (`{tag, printName, "S"|"C", slot0, ...}`) and the type
  DISCRIMINATOR: no other value this backend produces has a `String[]` there (a
  cons is `Object[2]` of Lisp values, a function value has an `Integer` in slot
  0, a ratio is `BigInteger[]`, a character is `int[]`, and the `java:` bridge
  turns every host array into a list first). `JvmLispCompiler.LayoutPool`
  interns one static field per referenced tag ON DEMAND, filled in a `<clinit>`
  merged with the condition channel's.
- **WASM-GC**: `TYPE_INSTANCE` = `struct {const i32 layoutAddr, mut eqref
  slots}` in its own rec group, appended after the fixed types, the `--simd`
  block and the async block so no existing type or `FUNC_*` index moves. The
  layout records live in a linear-memory blob (`WasmInstanceLayouts`). A record cites
  its tag (`%class-FOO`) and its print name (`FOO`) as two `(offset, length)` pairs,
  and the second is the first minus a fixed prefix, so the print name is interned as a
  VIEW into the tag's own bytes (`StringTable.addTailOf`) rather than a second copy —
  ~600 B on a library with two dozen classes and structs. A shared entry can never be a
  tree-shake candidate (a cut `DroppableDataRange` has to own its bytes outright), so
  the reuse declines when the container is already one; the call site checks
  `LispLayout.printNameOfTag` rather than assuming the relationship, because the record
  carries the two strings independently. Do NOT
  "simplify" the struct to three fields: `{i32, i32, eqref}` with an immutable
  tail canonicalizes equal to `TYPE_VBLOCK` under `--simd` and `ref.test` could
  no longer tell an instance from a packed-array block. `--no-gc` has no
  instances at all (it rejects `defstruct`/`defclass` at the top level).

### The layout sits at index 0 on the JVM, and NOT in the WASM slot array

An instance's JVM array is `{layout, v1..vn}` while its WASM slot array holds the
slot values only. Any per-backend loop over the slots must account for that: the
JVM cursor stops at 1, the WASM one at 0. `%obj-slots` got this wrong on the JVM
first and handed the `String[]` layout back as a list element, which made the
prelude's `equalp` recurse into itself forever -- `compileAndRunInstanceSlotsAndEqualp`
pins it.

### The bake-ALL vs demand-intern asymmetry (record, do not "fix")

WASM bakes EVERY registered layout into the data segment before Pass 2a, while
the JVM interns a layout field only for a tag it actually compiles. The reason
is timing, not taste: `%obj-new` needs the layout ADDRESS as an `i32.const`
inside an ordinary function body, and the tags a program uses only become known
DURING Pass 2 (a `make-instance`/`error` deep in a body expands then), whereas
the JVM can add a constant-pool field at any point during Pass 2. The registry
is complete after `expandTopLevelDefinitions`, so baking all of it is the only
pre-Pass-2 answer that cannot be wrong.

## The emit gate

`LispMacroExpander.mayCreateInstances(program, closRegistry)` answers "can an
instance value EXIST in this artifact". It gates the WASM struct type + layout
blob (`WasmLispCompiler.usesInstances`, which fixes `instanceTypeIndex`) and the
JVM predicates' instance exclusion + `_equal` instance arm
(`Ctx.mayUseInstances`). With the gate off, an instance-free program is
byte-identical to a build that never knew about instances — that is the
invariant the gate exists for.

**It has a sibling, and the pair is the whole exclusion list.** The JVM's
cons-shaped predicates (`consp`/`listp`/`atom`) answer for an `Object[]` that is
not a ratio, not a funcref and not an instance — which left the ASYNC values,
also `Object[]`s: a stream and a stream-read token are `Object[3]`s headed by an
interned marker, and this backend alone answered `(consp a-stream)` = T (the
interpreter and both WASM backends answer nil). `JvmEmitHelper.emitAsyncValueExclusion`
is that exclusion, gated the same way on `Ctx.mayUseAsyncValues` (the compiler's
`usesAsyncRuntime`) so a program without the async runtime stays byte-identical,
and comparing the marker by IDENTITY exactly as `_streamp` / `_futurep` do. Pinned
by the `stream-new-builds-a-pull-stream-on-every-backend` ci-spec case; what it
cost before it was found is in `.kb/clack.md` (a stream response body took
`%http-body-string`'s `consp` arm on the JVM).

Only CONSTRUCTION needs the gate on; the reading primitives compile to a
constant nil when it is off (there is provably nothing to read), so an
over-approximation costs one unused type entry and an under-approximation is a
loud compile error, never wrong output.

Most of the answer is a scan for `%obj-new`, which `expandTopLevelDefinitions`
has already spliced in for every struct/class constructor. The rest are the
condition sites that expand during BODY compilation, long after the gate must be
fixed: `handler-case`/`ignore-errors` (they synthesize a `simple-error`),
`signal` (always a `simple-condition`), and `error`/`warn`/`cerror` with a
quoted type, a literal `(make-condition ...)` datum, or — for `error` — a
runtime datum carrying initargs. **That case split must stay in step with
`expandSignalDesignator`**; they are the same analysis written twice.

Do NOT gate on `closRegistry.classes().isEmpty()` / `layouts().isEmpty()`: the
registry seeds 21 built-in condition classes, so it is never empty. Since
todo-304 the scan also answers true for `read` / `read-from-string` / `load`
heads (and `#'read`/`#'read-from-string`): the emitted runtime reader can
construct a PATHNAME instance from `#P"..."`, so a read-using program is
instance-capable. Since todo-553 it also answers true for
`mayCreateStreamValues` -- an OPEN stream is an instance of the fixed `%STREAM`
layout, and so is the `*error-output*` a program merely NAMES
(`.kb/read-load-streams.md`, "A stream is a VALUE").

## Where the expansions live

Everything is in `LispMacroExpander` (shared by all backends, so the flip had to
be atomic): `expandDefstruct`, `expandDefclass`, `expandSetf`'s struct-accessor
and `%obj-ref` places, `makeClassInstanceTest` / `makeAnyClassInstanceTest` /
`makeAnyStructInstanceTest` (the `standard-object` / `structure-object` type
tests), the struct specializer in `singleSpecializerTest`, `expandClassOf`
(`(%obj-p v)` → `(%obj-tag v)`, no tag enumeration), `expandSlotValue` /
`expandRuntimeSlotValue` / `expandSlotBoundp` / `expandRuntimeSlotBoundp`,
`expandConditionSlotReader`, `buildTypedConstruct`, `expandStringSignal`,
`expandObjectSignal` and `makeHandlerTypeTest`. The only instance construction
outside it is the `simple-error` a `handler-case` synthesizes, built as an AST
by `Jvm/WasmHandlerCaseCompiler` and by
`LispEvaluator.synthesizeSimpleError`.

## Pinning tests

`LispEvaluatorTest#structLiteral*`,
`Jvm/WasmLispCompilerTest#compileAndRunStructLiteral*` +
`#compileStructLiteral*Fails`, the ci-spec case `struct-literal-read-syntax`
(all four backends), and
`LispEvaluatorTest#instancePrimitivesBuildReadWriteAndTestAnInstance` /
`#instanceSlotsListsTheSlotValuesInLayoutOrder` / `#equalpDescendsIntoInstanceSlots` /
`#instancePrintsInStructSyntax` / `#classInstancePrintsInAngleSyntax` / `#defstruct*` /
`#defclass*`, `JvmLispCompilerTest#compileAndRunInstance*` (including
`#compileAndRunInstanceSlotsAndEqualp` and
`#compileAndRunInstancePrimitivesAlongsideHandlerCase`),
`WasmLispCompilerIntegrationTest#compileAndRunInstance*`, and the ci-spec case
`instance-print-syntax-and-identity` -- the only place the prelude `equalp` is
exercised end to end, since the backend harnesses do not splice the prelude (all
four backends, `--component` included). Every new JVM test must RUN the class:
the printer's `max_stack` is hand-written and `StackMapAugmenter` copies it
verbatim, so a `VerifyError` only shows at run time.

# `am.ik.wit` — the WIT parser/printer library, the settled type mapping, `wit-export` and `wit-import`

Everything downstream consumes this model; nothing downstream re-parses text.

## The library (`am.ik.wit`)
Language-independent sibling of `am.ik.jvm` / `am.ik.wasm`: **no rontolisp imports, no external
dependencies** (jspecify only). `WitLexer` is lossless (every character lands in a token's `text`
or the next token's leading trivia; `EOF` carries trailing trivia); `WitParser` is recursive
descent over the grammar the corpus uses, anything outside it a `WitParseException` with
line/column.

- Model: `WitDocument` > `WitItem` (PackageHeader / PackageBlock / World / InterfaceDef / Use /
  TypeAlias / RecordDef / VariantDef / EnumDef / FlagsDef / ResourceDef / FuncDef / ImportRef /
  ImportNamed / ExportRef / ExportNamed / Include), `WitType` (Prim / Named / ListOf / OptionOf /
  ResultOf / TupleOf / StreamOf / FutureOf / BorrowOf / OwnOf), `WitFunc`, `WitMeta`,
  `WitPackageName`, `WitRef`. **Record equality = model equality.** `Wit` is the construction DSL.
- `WitParser.parseLocated` -> document + `WitLocations`. **Positions live BESIDE the model in an
  `IdentityHashMap`, never on the records** — a field would break the structural equality
  `WitRoundTripTest` pins; lookup is BY IDENTITY. This is what lets `wit-export` say
  `world.wit:5: ...`.
- **`%`-escaping is source syntax, not part of the name** (`WitIdentifiers`): the model holds the
  BARE name, `WitPrinter` puts `%` back on exactly the keywords, and in `parseType` an escaped
  word is ALWAYS a `Named` type. Trap: carrying `%flags` verbatim leaked into generated
  `WasiWitDefinitions` and made a world lower to an invalid export label.
- `WitPrinter.printVerbatim(tokens)` = byte-identical reassembly; `print(document)` =
  **canonical**, byte-compatible with `wasm-tools component wit`.
- `WitResolver`: `findInterface(reference)` (fully-qualified, version-less, or bare name),
  `resolveType(scope, name)` (own definitions first, then through `use` clauses, following
  aliases, cycle-guarded), `functions(iface)` (freestanding funcs + every resource's
  constructor/methods/statics, in document order, tagged with the owner).

`WitRoundTripTest` covers the whole in-repo corpus (`src/wasm-component/deps/**`, `uni*.wit`,
`examples/count-vowels/count_vowels_component.wit`, 7 fixtures).

## `--emit-wit`
Wiring, variants, oracles and the three-phase regen: `.kb/wasi-component.md`. Unique here:
`WitEmitter` splices no text (the fixed per-variant document is the GENERATED
`WasiWitDefinitions`, each `rontolisp:wasm-export` Decl appended as a typed `export` world item);
`///` docs are NOT carried (they ARE, as `;;;`, into `--scaffold-wit`); and **the emitted world
is always `package root:component; world root`**.

**Variant vocabulary**: `http` -> `http-client`, `serve` -> `http-server`, `serve-http` ->
`http-server-client`, `sock` -> `sockets`, applied to `WitEmitter.VARIANT_*`, fixtures, blob
artifacts, `src/wasm-component` sources, regen scripts, docs. **Mode vocabulary unchanged**: the
`serve` boolean, `WasmServeComponentBuilder`, `buildServe` still name the `wasmtime serve` MODE.

## The settled type mapping (`compiler/WitTypeMapper`)
`rep(WitType)` / `repOfDefinition(WitItem)` — the ONE vocabulary the binders consult, no codegen,
pinned by `WitTypeMapperTest` (reversing a cell is a breaking change). `record`=keyword plist,
`enum`=keyword, `variant`=tagged list, `flags`=keyword list, `option<T>`=value-or-nil,
`tuple`=list, `resource`/`borrow`/`own`=opaque integer handle (`.kb/read-load-streams.md`),
`stream`/`future`=UNSUPPORTED until language async, scalars per the settled table
(`s64`/`u64` bignum-safe).

**`result<T, E>` = a condition on EVERY backend.** Ok arm = the return value (payload-less ok
returns `nil`); the error arm **signals a condition** carrying the mapped `E`, catchable with
`handler-case` everywhere except `--no-gc` (`.kb/error-handling.md`; needs wasmtime 37+).
Rejected: `(values ok err)` — a bare call silently swallows the error arm, it bypasses the
condition system, `--no-gc` has no spill channel, and migrating later breaks user code.

**`list<u8>` = a string carrying the bytes one-per-char**, NOT a list of ints; distinct from WIT
`string` (canonical-ABI UTF-8).

- **Trap**: a rontolisp string is stored UTF-8 (`.kb/wasm-gc-strings.md`), so staging one for a
  `list<u8>` parameter ENCODES and every byte >= 0x80 doubles. A `list<u8>` / `stream<u8>`
  PARAMETER therefore also takes a packed `(unsigned-byte 8)` vector (`TYPE_I8ARR`), staged raw
  by `WasmComponentImportCompiler.emitStageBytesParam` (a `ref.test TYPE_I8ARR` whose else-arm is
  the string staging verbatim). A `string` parameter is untouched.
- **A `stream<u8>` READ answers a packed byte vector**, lifted through `_bytes_from_mem` (raw
  octets, no decode) in both places a completion is lifted (`emitReadLift`, `_sched_dispatch`'s
  kind-1 settle); `WasmLispCompiler.streamReadsBytes` forces that one helper on, so a module
  without a byte-stream read is byte-identical. **`list<u8>` RESULTS still lift as byte strings**
  — trigger: the day a result consumer wants octets, move it to `_bytes_from_mem`.

## `rontolisp:wit-export` — implementing a world
`(rontolisp:wit-export "count_vowels_component.wit" :world root)`; the path resolves against the
source file's directory (`SourceLoader.resolve`) and `:world` may be omitted when the file
declares exactly one.

**It LOWERS into `wasm-export` — there is no new export path.**
`compiler/WitExportDirective.lower()` returns, per world export in world order, one
`(rontolisp:wasm-export 'name :params ... :param-names ... :returns ... [:async t])`; the
backends never see `wit-export`, and **the emitted component is byte-identical to the
hand-written equivalent** on wasm-GC and `--no-gc` `--component` — the design invariant. It does
no I/O and no codegen; its `Backend` enum (`WASM_GC` / `WASM_NO_GC` / `WASM_COMPONENT` / `OTHER`)
selects backend rules only.

Three call sites: `eval/WitExportInliner` on the compile path (**after** `LoadInliner` /
`UserMacroExpander`, so every `defun` is a literal top-level form, and **before**
`LibraryDefunPruner`, so the directives count as roots); the same inliner from
`RontoPlayground.frontend`, which is why it lives in `eval` not `cli` and reads the WIT through
an injected `SourceLoader` (**do not regress**: without it the compile buttons died with
`Cannot compile: rontolisp:wit-export`); and `LispEvaluator.evalWitExport`, a SPECIAL FORM
checking the functions defined **so far**, so **put the directive at the END of the file**.

**The world is the AUTHORITATIVE export list**: a hand-written `rontolisp:wasm-export` alongside
a `wit-export` is a compile error (`WitExportInliner`), and so is `rontolisp:http-handler` +
`wit-export` (`RontoLispCli`, serve mode). A world's `import` items and type definitions are
IGNORED.

### The contract checks
Each names the WIT file and line (`WitLocations`): no world / several worlds without `:world` / a
missing world / a world with no exports; an export the world declares but the program does not
define; **arity** mismatch, or an exported `defun` with `&optional`/`&rest`/`&key` (REQUIRED
parameters only); a duplicate export; a name that is not a component-model `label`
(lower-kebab-case) or the reserved `run`; an export naming an interface the file does NOT define;
an inline `import name: func` (rejected, not silently dropped); a type outside the export subset
(`s8`…`u64`, `f64`, `bool`, `string` — `compiler/BoundaryType`, from which the message's
"supported: ..." list is DERIVED so they cannot drift); an `async func` under `--no-gc
--component` (conversely an `async func` SETS `:async t`, `.kb/wasi-component.md`). **A
user-facing message carries no `.todo` pointer** — a todo file is deleted when the work lands.

### The integer boundary — the exact-or-trap rule (`compiler/BoundaryType`)
**The vocabulary IS the WIT spelling**: `:s8 :s16 :s32 :s64 :u8 :u16 :u32 :u64`, plus `:float`
(f64 — no internal f32, `.kb/wasm-export-no-wasi.md`), `:bool`, `:string`, and the
rontolisp-only `:s-expr` / `:void`. **`:int` / `:long` are permanent aliases of `:s32` / `:s64`,
normalized at parse time**
(`WitExportInlinerTest.theLegacyIntSpellingCompilesToTheSameBytesAsItsWitSpelling`). **Emitting
`s32` for a `u32` is not benign**: the component model has NO integer subtyping, so
`wasm-tools component targets`, jco and every `bindgen` host REJECT it (`WitOracleE2eTest`).

**The rule: the boundary carries the value exactly, or the wrapper traps** — derived from two
intervals (`BoundaryType.range()` vs the backend's house-integer range), not enumerated per type.
wasm-GC boxes inbound wide integers through `WasmExportCompiler.emitBoxWideInt` and returns them
through `emitWideIntResult` (exact via `_int_val` past 2^53 where f64 would round), with an `i32`
scratch local for the sub-32-bit outbound range check (`WasmExportCompiler.scratchTypes`,
reserved right after the parameter slots); `--no-gc` uses `i64.extend_i32_s/_u` inbound and
`NoGcWasmCompiler.emitBoundaryRangeGuard` + `i32.wrap_i64` outbound. `u64` traps below 0 on both.
`:s64` needs neither check nor narrowing on `--no-gc`, which is why its pass-through wrapper
elision (`isPassThroughExport`) survives.

**Residual asymmetry**: a host-supplied `u64` >= 2^63 has no exact representation — an export
wrapper TRAPS, while a `--component` import lift degrades to the float approximation
(`WasmComponentImportCompiler.boxI64`). **The import side is deliberately NOT widened**:
`WasmImportCompiler`'s set is still `{:s32, :float, :bool, :string, :s-expr}` and
`WitImportDirective.designatorOf` maps the whole `Rep.INT` family to `:s32` (`.todo/169`).

### Interface exports, `:param-names`, `--scaffold-wit`
`wit-export` resolves an `export docs:adder/add;` reference through `WitResolver` and lowers
**each of the interface's functions** like a freestanding export, each carrying
**`:interface "docs:adder/add@0.1.0"`** (`WasmExportCompiler.Decl.iface`); exports sharing an
`iface` are bundled by `appendFuncExports` / `NoGcWasmComponentBuilder` into **one exported
component instance** (`ComponentWriter.componentInstanceFromFuncs` + `exportInstance`). A flat
export stays a top-level `exportFunc`, byte-identical to before; the only `ExportRef` that stays
a no-op is `wasi:cli/run`.

**GOTCHA — an instance EXPORT consumes an instance index.** In the GC builder the run instance is
`11 + userIfaces`, but its `export wasi:cli/run` statement introduces another
(`12 + userIfaces`), so the first free index for an exported interface is **`13 + userIfaces`**.
Getting it wrong points the export at the run instance and *validates* — only the WIT diff
catches it. `--no-gc`'s base is `FIRST_PRINT_INSTANCE (2)` with print, `0` without. Pinned by
`WitOracleE2eTest.{gc,noGc}InterfaceExportWitMatchesWasmToolsByteForByte`.

- `:param-names '(text)` (quoted labels, arity matching `:params`) fills `Decl.paramNames`,
  **defaulting to `p0`, `p1`, ...**; the builders encode them into the lifted function type and
  `WitEmitter` prints them, so pre-existing artifacts stay byte-identical. Ignored on the
  core-export (Preview 1 / `--no-wasi`) path.
- `rontolisp --scaffold-wit world.wit [--world N] [-o impl.lisp]` (`cli/WitScaffolder`,
  short-circuited in `RontoLispCli.run` before the REPL fallback) emits per export a `;;;` doc
  block and a `defun` stub with the WIT's own parameter names over an
  `(error "name is not implemented yet")` body; **the `wit-export` directive comes LAST** so the
  interpreter's check passes.
- **`--emit-wit` on the export side is a FIXPOINT, not a check.** World export -> `lower()` ->
  Decl -> component function type -> `WitEmitter` -> world export is one-to-one both ways, so it
  cannot fail; what catches a drifted program is the contract check. What `--emit-wit` uniquely
  reports is the **IMPORT side**, which comes from the BUILD; under `--optimize` the emitted
  world is filtered from the SAME set the builder prunes the import block to
  (`WasmComponentBuilder.wasiInterfaces` -> `WitEmitter.emit`), which is why `WitOracleE2eTest`
  grew `--optimize` legs. A world's `import` ITEMS are still not the authoritative import list.

### Two standing decisions
- **An `:s-expr` export has NO WIT spelling — never.** `:s-expr` is s-expression TEXT crossing as
  `(ptr, len)` and parsed by the emitted runtime reader, while `designator()` maps WIT `string`
  to `:string`, a DIFFERENT wire format; a blind retrofit **compiles** and breaks at run time.
  Worse, `WasmExportCompiler.componentValType()` lifts BOTH as component `string`, so an
  `:s-expr` export under `--component --emit-wit` prints a valid world which, fed back through
  `wit-export`, yields `:string` designators and a silently different ABI. The browser demos stay
  on hand-written `rontolisp:wasm-export` permanently; a rich-type export LIFT does not exist.
- **`wit-export` grows NO alias mechanism**: `LABEL` enforces lower-kebab-case, right for a
  COMPONENT (jco maps `count-vowels` -> `countVowels`); core-module demos wanting camelCase
  hand-write `rontolisp:wasm-export`.

## `rontolisp:wit-import` — calling a WIT interface
`(rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)`, then
`(kv:bucket-set (kv:open "cache") "hello" "world")` — a handle is the FIRST argument.

Options (`compiler/WitImportDirective.parse`; any name may be a string or bare symbol): the path
(read through `SourceLoader`); **`:interface`** (required: fully-qualified, version-less or bare —
`WitResolver.findInterface` tries them in that order, an ambiguous one resolves to nothing and
the error lists every id the file defines; all three lower to the CANONICAL id, which keys the
provider registry); `:package kv` (synthesizes a `defpackage` and qualifies each binding);
`:from "module"` (Preview 1 only); `:field-style` `:camel` (default) or `:kebab`.

**It LOWERS — no new call path on any backend.** `lower()` returns, in WIT order, the forms the
directive stands for; no I/O, no codegen.

- **Preview 1 WASM**: one `(rontolisp:wasm-import 'name :from M :as FIELD :params ... :returns
  ...)` per function, `:async t` for an `async func` (`.kb/wasm-import.md`). **Measured
  byte-identical** to the hand-written block, and again under `--optimize`.
- **Interpreter / JVM**: the `defpackage`, then one ordinary
  `(defun kv:bucket-get (self key) (rontolisp::%wit-call "<iface>" "bucket-get" self key))` per
  function — so `#'`/`funcall`/`mapcar`/`eval` all work; an `async func` binds as an
  `async-defun`.
- **`--component`**: a component-model instance import, each function `canon lower`ed
  (`(rontolisp::%component-import ...)`) — every variant, `rontolisp:http-handler` included.
- **`--no-gc`**: a clear error — its MVP module imports nothing.

### Pass order — the IMPORT inliner runs BEFORE `UserMacroExpander`
`eval/WitImportInliner` runs straight after `LoadInliner`, BEFORE `UserMacroExpander`. It has to:
the `(defpackage kv ...)` must exist **before anything resolves a `kv:get` call site**, and
`UserMacroExpander` resolves every top-level form through its `PackageResolver`, where an unknown
package is a hard error. It can run that early because a `wit-import` is checked against a WIT
FILE, never against the program. `WitExportInliner` runs AFTER `UserMacroExpander`; then
`WitLibrary.process` splices the runtime and `LibraryDefunPruner` runs last. **Put a `wit-import`
at the TOP of the file** (the opposite end from a `wit-export`); `PackageResolver` exempts its
arguments from resolution and the names it BINDS it qualifies itself.

### The provider and the `wit.lisp` runtime
`(rontolisp:wit-provide "<iface>" #'my-dispatch)`, and **NO built-in provider for anything**. A
provider is an ordinary Lisp callable taking the member name (a STRING) then that function's
arguments — the same value on every backend, so `wit-provide` needs no `#+jvm` guard.
`java:bind-wit` was dropped (`java:` interop is JVM + interpreter only and the native binary
cannot INTERPRET it, `.kb/java-interop.md`); a `ServiceLoader` SPI stays rejected. The rule for
shipping nothing: *rontolisp's core knows the provider MECHANISM; it does not know what
`wasi:keyvalue` is.*

`eval/WitLibrary` + `src/main/resources/am/ik/rontolisp/eval/wit.lisp` (~50 lines, the
`usocket.lisp` pattern): `rontolisp::*wit-providers*` (an `equal` hash table),
`rontolisp:wit-provide`, `rontolisp::%wit-call` (`(apply provider member args)`),
`rontolisp:wit-error` + `wit-error-payload`, `rontolisp::%wit-result`.

- **The key is the CANONICAL id** (`WitResolver.canonicalId`), never the user's spelling:
  lowering as written gave one interface three registry keys and `:interface store` compiled
  clean then died at run time with "No provider is bound".
- No provider bound signals `rontolisp:wit-error`; `wit-provide` REPLACES any provider. **On the
  WASM backends the host IS the provider, so a top-level `wit-provide` is dropped by
  `WitImportInliner`** — inert, not a compile error.
- Splice policy: `WitLibrary.process` prepends when the program REFERENCES one of those names
  (idempotent; a WASM program references none, so its output stays byte-identical); the
  interpreter loads lazily via `ensureWitLoaded()`, so a program may bind a provider BEFORE the
  directive that uses it. **`wit.lisp` is not in `LibraryDefunPruner`'s prunable set**, like
  `usocket`.

### Name mapping and the two type tiers
An interface func `create-shader` binds `create-shader`; `resource bucket`'s method `get` binds
`bucket-get` with the handle FIRST, its `constructor` binds `bucket-new`, a `static func f` binds
`bucket-f`, its RELEASE binds `bucket-drop`. WIT parameter names become the lambda list
**verbatim**, a resource method gaining a leading `self` (a signature already declaring `self`,
or a member name bound twice, is a clear error naming the line); a constructor's result is its
resource (`:int`). The Preview 1 FIELD is `:field-style` applied to the label, **`:camel` by
default** (`.kb/wasm-import.md`).

- **Interpreter / JVM — everything except `stream`/`future`**: an ordinary Lisp call, nothing
  marshalled, so every settled representation crosses by construction and only `Rep.UNSUPPORTED`
  is refused. **Nothing in the lowering inspects the return value** — the `result` error arm is
  **the PROVIDER** calling `(error 'rontolisp:wit-error :payload ...)`, an obligation no one
  enforces.
- **Preview 1 WASM — only the flat set `rontolisp:wasm-import` carries** (`INT`/`HANDLE` ->
  `:int`, `FLOAT` -> `:float`, `BOOLEAN` -> `:bool`, `STRING`/`BYTE_STRING` -> `:string`, no
  result -> `:void`); everything else is a compile error naming the WIT file and LINE, a core
  import having no component type to declare a richer shape with. So **`wasi:keyvalue/store` does
  not cross Preview 1**; the interfaces that DO are the flat host-shaped ones (WebGL).
- A resource handle is an opaque integer allocated **by the provider**. **It is NOT the shared
  stream/socket handle space, and `cl:close` does not apply to a WIT resource.**

### The gl.lisp migration
`examples/browser/webgl-common/gl.wit` is checked in; gl.lisp binds `local:webgl/gl` and
`local:webgl/ui` with two directives, neither naming `:package`. **All six demos' modules are
byte-identical to their pre-migration builds.**

- Four members were a RENAME: a WIT label is ONE name that becomes both the Lisp name and the
  camelCased host field, and **the WIT names win — `wit-import` does NOT grow a per-function
  alias option.**
- **A `type shader = s32` alias is free and worth it**: GL objects cross as named aliases
  resolving to plain s32, so the lowering is untouched, but the NAME distinguishes a table handle
  from an integer value. **`gl-imports.js` is GENERATED from gl.wit** (`GlImportObjectTest`
  regenerates and pins it, reusing `FieldStyle.CAMEL`).
- **Trap: `load` lost the base directory.** A `wit-import` inside a library resolves its `.wit`
  against the file that WROTE it, but the splice flattens the program, so `WitImportInliner` had
  only the ENTRY's directory. `LoadInliner.rebaseWitImport` fixes it (`LoadInlinerTest`);
  `http.lisp` never hit this because `HttpLibrary` lowers off the classpath.

## Component imports — `canon lower`
**A `--component` build is no longer import-locked to the fixed WASI blob surface**: a
`wit-import` becomes a real component-model instance import whose functions are `canon lower`ed
into the core module, so the provider is the HOST and the component composes (`wac plug`).
`rontolisp:fetch` AND `rontolisp:http-handler` are both consumers — one Lisp-source library
(`http.lisp`, spliced by `eval/HttpLibrary`) over a wit-imported `wasi:http@0.3.0` surface; no
WAT http adapter remains (`.kb/fetch-http.md`, `.kb/async-await.md`).

**The reference probe (do this before touching the encoders again).** Everything was derived from
a hand-built probe, NOT the spec. Golden bytes:
`ComponentWriterTest.instanceTypeMatchesWasmToolsReference` (393 bytes); layout facts:
`WitCanonicalAbiTest`. It taught two things: wasmtime's in-memory keyvalue provider recognizes
**only the empty store identifier**, and a **forged handle traps at the boundary** before
reaching the provider — uncatchable.

### The pieces
- **`compiler/WitImportDirective`** lowers to `(rontolisp::%component-import "<iface-id>"
  "<wit text>" ("member" "lisp-name") ...)` — **the WIT TEXT TRAVELS INSIDE THE FORM**, so the
  WASM compiler reads no files and the playground works by construction.
- **`codegen/wasm/WitCanonicalAbi`** — size / alignment / flattening / despecialization (option =
  variant{none,some}, result = variant{ok,err}, enum = payload-less variant, tuple = record).
- **`codegen/wasm/WasmComponentImportCompiler`** — guest-side marshalling on the exact
  `WasmImportCompiler` pattern: one synthetic defun per bound function and a placeholder call
  `1<<27 + ordinal` that **`WasmImportInjector` renumbers — REUSED UNCHANGED**.
- **`codegen/wasm/WitComponentTypeEncoder`** — the imported instance TYPE, grammar pinned by the
  probe: a resource is `export "name" (type (sub resource))`, a named type is a type decl + an
  `eq`-bound export, structural types are unexported decls memoized by shape, and **type decls
  AND type-bound exports append to the instance type's local type index space; function exports
  do not**.
- **`WasmComponentBuilder.appendUserImports`** — type + import + per-function alias +
  `canon lower` (memory 0, realloc = core func 0, utf8, exactly when the call touches linear
  memory) + one synthesized core instance per interface. Every downstream hardcoded index shifts
  by the user-import counts. **Zero imports = zero shift = byte-identical.**
- **`WasmServeComponentBuilder`** — ONE `build` over ONE block, the only extra module being the
  preview1 bridge (`adapter-http-server-p1.wat`), instantiated BEFORE the rontolisp core. Core
  instances: mem(0) / bridge-w(1) / bridge(2) / one per fixed http iface
  (`lowerServeIoFromBlock`) / one per user iface / core, the `ServeIo` cursors + `coreInstanceOf`
  map making every downstream index relative. **State a served handler cannot otherwise have**: a
  `wasi:http` host recreates the instance per request so its globals are not state, but a store
  behind a WIT import is — whether it SURVIVES is the host's.
- **`codegen/wasm/WitImportWorldEmitter`** — the `--emit-wit` import side.

### `result`, member pruning, resource drops
- **`result` = the envelope + a Lisp wrapper (NOT a codegen catch).** A result-returning function
  binds TWO names: the synthetic defun takes the raw name (`kv::%open`) and returns the envelope
  cons `(:ok . V)` / `(:error . E)`; a generated wrapper
  `(defun kv:open (id) (rontolisp::%wit-result (kv::%open id)))` unwraps it, so the error arm
  rides the ORDINARY condition machinery. **The codegen never mentions conditions.**
- Type tiers: `--component` params carry **everything except flags and `list<T>`** (`list<u8>`
  crosses), results **everything except flags**. **Rejected precedent**: lowering rich P1 types
  through `:s-expr` was REVERTED — a rontolisp-specific pseudo-protocol is not a WIT boundary and
  it broke P1 byte identity.
- **Member pruning replaces the tree shaker on this path** (`--component` skips
  `WasmTreeShaker`): `WitImportInliner` passes a member filter (the symbol names the program
  textually references, the `LibraryDefunPruner` convention), disabled by `--no-prune` /
  `--dynamic`. A program calling 2 of `wasi:keyvalue`'s 6 functions imports exactly 2.
- **Resource drops — `<resource>-drop`.** Nothing in `WitResolver.functions` yields one, so
  `WitImportDirective` walks `iface.items()` for `WitItem.ResourceDef` and binds it through the
  same `allMembers` set. Not a nicety: `wasi:http` makes `outgoing-body.finish` TRAP unless the
  child `output-stream` is dropped first. **Reference-gated on every backend, and that is what
  buys byte identity** (`WitImportInliner` passes `referencedNames(program)` as a drop filter).
  Interpreter/JVM get the ordinary provider defun; Preview 1 gets a **no-op defun and NO
  `wasm-import`**; `--component` gets an ordinary core import (field `"[resource-drop]bucket"`,
  `(func (param i32))`) plus a SECOND outer emission kind. **Dropping a handle releases the
  reference, not the store.**
- **The outer side forces TWO counters.** `canon resource.drop` produces a **CORE function with
  NO component function behind it**, and mixing them yields a component that VALIDATES while
  lifting the wrong core function: `userImportFuncs` = decls only -> every **component**-func
  index; `userImportCoreFuncs` = decls **+ drops** -> every **core**-func index;
  `userImportTypes` = interfaces + projected + **dropped** resources -> the first free TYPE index
  (`appendUserImports` REUSES a `use` clause's projection, keyed `ifaceId#resource`, so the count
  must not double it).
- Two traps the drop forced: **a wrapper's first parameter is local slot 1, not 0** (slot 0 is
  the implicit closure environment, `buildDropBody`), and **the encoder declares resources
  LAZILY**, so a resource that is ONLY dropped is force-declared through the `provided` hook
  AFTER the function walk. A drop never appears in `--emit-wit`.

## Rich PARAMETERS across the component boundary
**A param lowers everything a result lifts, except `list<T>` (T != u8)**. The premise "the
blocking variants are all flat-payload" was WRONG: `wasi:http`'s `method` variant carries a
**string** and `response-outparam.set` takes a `result` whose `error-code` cases carry records
and `option<string>`.

**The Lisp shape is the LIFT's shape** — `emitLowerVariantParam` / `emitLowerRecordParam` consume
exactly what `emitVariantCase` / `emitLiftRecordAt` build. **The settled `result` ARGUMENT is the
envelope**; a payload-less arm may be the bare keyword because the tag/payload split is `consp` ?
`car`/`cdr` : the value itself.

- **Keyword identity in wasm is `struct.get $string 0` (the interned id) + `i32.eq` against
  `ctx.stringTable.addString(":case").offset()` — NOT `ref.eq`** (`compileStringLiteral`
  allocates a fresh struct each time; only field 0 is canonical). Idiom:
  `WasmFetchRuntimeBuilder.buildPlistGet` (`FUNC_FETCH_PLIST_GET`), which a `record` param also
  calls. A keyword naming no case traps, deliberately.
- **The joined payload flats** (`WitCanonicalAbi.flatTypes`): each case's flats are coerced to the
  joined type per the canonical ABI and unreached positions zero-filled; a joined FLOAT flat rides
  in an integer scratch local as its bit pattern (`storageOf`). Cursors roll back per case, field
  and ARGUMENT, so the cost is the deepest nesting, not the sum.
- **The scratch pools are SIZED BY MEASUREMENT, not by a constant**: `buildWrapperBody` emits the
  body twice, reads the high-water marks and emits again. **Trap**: fixed pools were blown through
  by `response-outparam.set` — the ONE call this work exists for — while the trimmed test corpus
  stayed green. **Do not re-fix by raising a constant.**
- **`needsMemory` must recurse into variant cases and record fields**, and **`repOf` must resolve
  aliases first** (`type headers = fields` targets a NAME while `WitTypeMapper` classifies
  structurally — a latent `IllegalArgumentException` on EVERY backend, pinned by
  `WasmComponentImportCompilerTest.followsATypeAlias...`).
- Edges: an **empty `record`** is a compile error naming the WIT line; a **`record`/`tuple` RESULT
  that flattens to one core value** lifts through `emitLiftRecordFlat`; `option<bool>` is
  ambiguous (`some(false)` and `none` are both nil) and **accepted, not refused**.

**Verified against real hosts** — E2Es in `WasmLispCompilerIntegrationTest` call wasmtime's own
`wasi:http/types`, `wasi:sockets/types` (variant -> record -> tuple) and `wasi:cli/exit`. **Trap:
trimmed WIT in a test must match the host's real interface EXACTLY** (the subtype check is
structural). **A user import must not collide with the WASI surface** —
`WasmComponentBuilder.rejectAdapterImportCollisions` names it at compile time, in `build` where
the blob variant is known, not in the directive.

**The alignment trap**: `__ronto_alloc` returns `HEAP_PTR` as-is and **`HEAP_PTR` is not always
8-aligned** (`_intern` advances by a symbol's exact byte length), so using it as a canonical-ABI
return area traps with `pointer not aligned`, only after a program interns something. The wrapper
**aligns HEAP_PTR up to 8 on entry** and pops back to `align8(max(mark, intern-high-water))` on
exit. Do not "simplify" either.

## What CANNOT be externalized as a WIT import
- **The base adapter (`adapter.wat`)**: the core module's `wasi_snapshot_preview1` import layout
  is Preview-1-IDENTICAL by design and every `FUNC_*` constant rests on it. What remains is the
  SYNC stdin branch, file I/O, stdout/stderr writes, env/clock/random. **The wall is no longer
  technical** — every mechanism exists — it is the byte-stability/flag-neutrality CONTRACT for
  non-async programs: 0.3 file/stdio I/O is stream-based, so a Lisp-library implementation makes
  every file- or print-using component an async+EH component. **`adapter.wat` STAYS.** Revisit
  trigger: a real need for non-stalling file reads inside async bodies, following the stdin.lisp
  pattern (a wit-imported filesystem library behind the same `%io-*` dispatch, async-gated).
- **The serve preview1 bridge** — the same in miniature. **The `--no-gc` print micro-adapter** —
  a different backend; `wit-import` is rejected there.
- **`wasi:sockets` used to be on this list and is NOT.** The stated wall — "its 0.3 surface is
  `stream`/`future`-based, which has no rontolisp value until language-level async" — has been
  torn down: `sockets.lisp` rides a wit-imported `wasi:sockets/types@0.3.0` and
  `adapter-sockets.wat` is deleted (`.kb/tcp-sockets.md`). **Lesson: a "wall" here meant a
  missing LANGUAGE feature, and the language moved — re-read a wall before trusting it.**

# `am.ik.wit` — the WIT parser/printer library, the settled type mapping, `wit-export` and `wit-import`

Everything downstream consumes this model; nothing downstream re-parses text.

## The library (`am.ik.wit`)
Language-independent sibling of `am.ik.jvm` / `am.ik.wasm`: **no rontolisp imports, no external
dependencies** (jspecify only). `WitLexer` is lossless (every character lands in a token's text or
trivia); `WitParser` covers the grammar the corpus uses, anything else a `WitParseException` with
line/column.

- **Record equality = model equality.** Positions (`WitParser.parseLocated` -> `WitLocations`)
  **live BESIDE the model in an `IdentityHashMap`, never on the records** -- a field would break
  the structural equality `WitRoundTripTest` pins; lookup is BY IDENTITY. This is what lets
  `wit-export` say `world.wit:5: ...`.
- **`%`-escaping is source syntax, not part of the name** (`WitIdentifiers`): the model holds the
  BARE name, `WitPrinter` puts `%` back on exactly the keywords, and an escaped word in a type
  position is ALWAYS a `Named` type. Trap: carrying `%flags` verbatim leaked into generated
  `WasiWitDefinitions` and made a world lower to an invalid export label.
- `WitPrinter.printVerbatim(tokens)` = byte-identical reassembly; `print(document)` = canonical,
  byte-compatible with `wasm-tools component wit`.

`WitRoundTripTest` covers the whole in-repo corpus (`src/wasm-component/deps/**`, `uni*.wit`,
`examples/count-vowels/count_vowels_component.wit`, fixtures).

## `--emit-wit`
Wiring, variants, oracles and the regen: `.kb/wasi-component.md`. Unique here: `WitEmitter` splices
no text (the fixed per-variant document is the GENERATED `WasiWitDefinitions`, each
`rontolisp:wasm-export` appended as a typed `export` item); `///` docs are NOT carried (they are,
as `;;;`, into `--scaffold-wit`); **the emitted world is always `package root:component; world
root`**.

**Variant vocabulary**: `http` -> `http-client`, `serve` -> `http-server`, `serve-http` ->
`http-server-client`, `sock` -> `sockets`. **Mode vocabulary unchanged**: the `serve` boolean,
`WasmServeComponentBuilder`, `buildServe` name the `wasmtime serve` MODE.

## The settled type mapping (`compiler/WitTypeMapper`)
The ONE vocabulary the binders consult, pinned by `WitTypeMapperTest` (the decision record;
reversing a cell is a breaking change). `record`=keyword plist, `enum`=keyword, `variant`=tagged
list, `flags`=keyword list (the labels set), `option<T>`=value-or-nil, `tuple`=list,
`resource`/`borrow`/`own`=opaque integer handle (`.kb/read-load-streams.md`), `s64`/`u64`
bignum-safe.

**`result<T, E>` = a condition on EVERY backend.** Ok arm = the return value (payload-less ok ->
`nil`); the error arm **signals a condition** carrying the mapped `E`, catchable with
`handler-case` everywhere except `--no-gc` (`.kb/error-handling.md`; wasmtime 37+). Rejected:
`(values ok err)` -- a bare call silently swallows the error arm, it bypasses the condition system,
`--no-gc` has no spill channel, and migrating later breaks user code.

**`list<u8>` = a string**, NOT a list of ints. "One character per byte" does NOT hold on the wasm
backends: a `--component` result lifts through the same non-validating UTF-8 decode as a WIT
`string`, so `ff 00 41` comes back as one code point. A text `list<u8>` result is exact only for
valid UTF-8. The Clojure tier crosses it as a byte array (`.kb/clojure-frontend.md`, "Host
boundary").

- **`:octets t`** on `wit-import` is an OPT-IN, because the CL tier reads the lifted text
  (`page-hits.lisp` parses it, `serve.lisp` reads `wasi:http` `field-value`s as header text). With
  it a `list<u8>` lifts through `_bytes_from_mem` (forced on only then, so an `:octets` binding
  lifting none is byte-identical); Preview 1 lowers it to the `:octets` designator
  (`.kb/wasm-import.md`, "`:octets`"); interpreter/JVM pass the provider's value as it is;
  `--no-gc` refuses it (`refuseOctets`). The Clojure lowering passes `:octets t` on every target.
  Pins: `ClojureWitBoundaryTest.aComponentReadsBackOctetsThatAreNoUtf8Exact`,
  `.aListOfOctetsCrossesAPreview1ModuleExactUnderNode`,
  `WitImportDirectiveTest.octetsLeadTheComponentFormAndCrossAPreview1ModuleAsOctets`,
  `WasmComponentImportCompilerTest.anOctetsBindingLiftsAListOfOctetsThroughTheBytesHelperOnly`.
- **Two bindings of one interface** (`mergeByIface`) keep a wrapper each but the component declares
  each member once (`Import.distinctDecls` / `distinctCalls` / `distinctDrops`), else validation
  fails (`export name 'open' conflicts`). Pin: `twoBindingsOfOneMemberDeclareItOnce`.
- **Trap**: a rontolisp string is stored UTF-8 (`.kb/wasm-gc-strings.md`), so staging one for a
  `list<u8>` parameter ENCODES and every byte >= 0x80 doubles. A `list<u8>` / `stream<u8>`
  PARAMETER therefore also takes a packed `(unsigned-byte 8)` vector, staged raw
  (`WasmComponentImportCompiler.emitStageBytesParam`).
- **A `stream<u8>` READ answers a packed byte vector** (raw octets, no decode) in both places a
  completion is lifted; `WasmLispCompiler.streamReadsBytes` gates the helper.
- **A stream alias binds `-cancel-read` too, a future alias does not** (`WitImportDirective
  .asyncOps`): it withdraws a read the async scheduler left in flight, and no future read is ever
  left so. Bound only when named; [async-await.md](async-await.md).
- **A stream of values** (neither `u8` nor a resource handle: `stream<directory-entry>`,
  `stream<row>`) crosses as its handle; its alias's `-read` takes `(handle max)` and answers a
  list of at most `max` values lifted as results are (`emitValueStreamRead`), nil at the end;
  read-only for the guest. The read WAITS inside its wrapper's bracket, in asyncMode too (its own
  waitable-set, as a future read): the host lowers each value's strings and lists through
  `cabi_realloc` when the copy completes, and only a bracket's pop reclaims bump-heap memory, so
  a read left to the scheduler would leave every chunk's lowering behind. A reader holds one
  read's values. Measured 2026-11-26: wasmtime 51 hands a directory's entries over together (a
  read of 2 answers 2); Spin 4.3.0 and 4.4.0-pre0 write `query-async`'s rows one a write, so
  every read answers one row ([jdbc-spin-postgres.md](jdbc-spin-postgres.md)). Pins:
  `WasmLispCompilerIntegrationTest#httpHandlerReadsADirectoryAsAStreamOfValuesUnderWasmtimeServe`,
  `WitImportDirectiveTest.aStreamOfValuesCrossesAsAHandleItsAliasReadsAndNoGuestWrites`.
- **`:aliases (("rows" "stream<row>"))`**: aliases added to the interface's model, as if the WIT
  declared them (the items keep their identity, so positions hold); the lowering carries the
  printed document (`Target.text`). A WIT as a host publishes it writes `stream<row>` where it
  is used, and an alias is what names a stream's or a future's built-ins. A name the interface
  has (a type or a function) is refused.
- **`:blocking t`**: an `async func` binds as a call answering its value -- a component
  lowers it SYNCHRONOUSLY (a `Decl`, the instance type still declaring it `async`: Spin 4.3.0
  refuses a plain function type, "satisfied with `func_new_concurrent`, which is only for
  `async func`-typed imports"), Preview 1 without `:async t`, the interpreter and the JVM a
  plain provider call. Measured: Spin 4.3.0 and wasmtime 51 run a synchronous lowering of an
  async func, the task waiting; its wrapper pops what was lowered, where an async-lowered
  call's start keeps its staging. What a front end with no futures binds (the Java subset).
- Trap: a value type's type index is an s33 (`ComponentWriter.definedFuture` wrote it unsigned
  until 2026-11-26, so a future whose payload type sat past index 63 failed validation).

## `rontolisp:wit-export` — implementing a world
`(rontolisp:wit-export "count_vowels_component.wit" :world root)`; the path resolves against the
source file's directory and `:world` may be omitted when the file declares exactly one.

**It LOWERS into `wasm-export` -- there is no new export path.** `WitExportDirective.lower()`
returns one `(rontolisp:wasm-export ...)` per world export, and for a world of flat functions
that NAMES NO TYPE **the emitted component is byte-identical to the hand-written equivalent** on
wasm-GC and `--no-gc` `--component` -- the design invariant. No I/O, no codegen. An export of
any other type is the CANONICAL form of the same directive, and a flat export of a world that
names a type carries the WIT beside its designators ("Compound types across an export" and
"A world that names types" below).

Three call sites: `eval/WitExportInliner` on the compile path (**after** `UserMacroExpander`, so
every `defun` is a literal top-level form, **before** `LibraryDefunPruner`, so the directives are
roots); the playground runs the same `expand` with its uploads as `Options.loader`, which is why
the inliner lives in `eval` and reads through an injected `SourceLoader` (**do not regress**); and
`LispEvaluator.evalWitExport`, a SPECIAL FORM checking the functions defined **so far** -- **put the
directive at the END of the file**.

**The world is the AUTHORITATIVE export list**: a hand-written `rontolisp:wasm-export` beside a
`wit-export` is a compile error, as is `rontolisp:http-handler` + `wit-export`. A world's `import`
items are IGNORED.

**`:reactor t`**: the exports are the program's entry points -- no `wasi:cli/run`, the first export
call runs the top level once; a WASI command module refuses it
([wasm-export-no-wasi.md](wasm-export-no-wasi.md), "`wit-export :reactor t`"). The Java front end's
`@WitExport` program lowers to it ([java-frontend.md](java-frontend.md), "WIT exports"); Lisp's
default stays the command.

**Two exports of one label** (two interfaces' `get`, or one beside the world's own): inside a
component the core module exports an interface member as `<iface>#<label>`
(`WasmExportCompiler.componentCoreName`, the canonical ABI's spelling), which both component builders
alias; a flat export keeps its bytes. `checkNames` keys by that name, so a core module (Preview 1,
`--no-wasi`), whose host calls the label, still refuses the pair. Cost: +2x the `<iface>#` length per
member (`docs:adder/add@0.1.0#`: 1,037 -> 1,079 B GC, 147 -> 189 B `--no-gc`); measured identical:
flat-export programs, `examples/` worlds without interfaces, the Java counter. A `:names` table keys
a member by `<iface>#<label>` (`WitExportDirective.namesKey`, carried to the front ends as
`Export.key()`), falling back to the label; Java and Clojure always emit the qualified key, so Clojure's
one var serves each export with its own wrapper (a multi-arity `defn`). wasmtime's WAVE spelling of a
member is `docs:adder/add.add@0.1.0(...)`; a bare name finds the world's function first. Pins:
`WitOracleE2eTest.{gcMixedFlatAndInterfaceExports,noGcInterfaceExportWit}...`,
`WasmLispCompilerIntegrationTest.interfaceExportIsCallableAsAComponentInstance`,
`WitNamingHookTest.anExportTableImplementsEachLabelByItsFunctionUnderTheLabel`,
`JavaWitImportTest.aClassImplementsTheWorldAComponentExports`,
`ClojureWitBoundaryTest.aWorldIsImplementedAsAComponent`.

### The contract checks
Each names the WIT file and line: world selection; an export not defined; **arity** mismatch or an
exported `defun` with `&optional`/`&rest`/`&key`; a duplicate; a name that is not a lower-kebab
`label` or is the reserved `run`; an interface the file does not define; an inline `import name:
func` (rejected, not dropped); a type no export carries, or none on this backend ("Compound
types across an export"; the flat list is DERIVED, `BoundaryType.witNames`); an `async func`
under `--no-gc --component`; an exported interface that declares a `resource` (its functions
were skipped without a word until 2026-11-26, a component that was not the world's); a `use`
clause whose type cannot be named in the scope ("A type of another interface").
**A user-facing message carries no `.todo` pointer** -- a todo file is deleted when the work lands.

### Compound types across an export — the canonical form
**Invariant: an export whose signature names a type outside the flat set lowers to
`(rontolisp:wasm-export 'f :as "label" :wit "<document>" :wit-world "w" :wit-at "file:line"
:param-names ...)` -- the WIT text in the form, as a `%component-import`'s -- and its wrapper is
the flat export's (`WasmExportCompiler.emitBody`: entry bookkeeping, call, way out) with the two
halves that are the WIT's swapped in: the parameters LIFTED as an import's result is, the result
LOWERED as an import's argument is, by the one generator (`WasmComponentImportCompiler.Gen`,
`ExportMarshal`).** A flat export beside it keeps its bytes; an alias of a flat type is flat.

- **Shared, measured**: every lowering and every memory lift of the import side is reused as it
  is. New for the direction: a lift out of FLAT values (`emitLiftFlats`, the mirror of
  `emitLowerParam`; a joined variant flat narrows to what the case reads, `pushFlat`), since an
  import's result has at most one flat; a tuple of parameters past 16 flats is lifted from the one
  pointer (`spilledParams`).
- **Exact or trap, as the flat boundary** (`Gen.export`): an `s32` lifts through `_int_new` (an
  import's flat `s32` result is an i31), a `u64` at or past 2^63 traps where an import's degrades
  to a float, a narrow integer answered out of range traps (`lowerNarrow`) where an import's
  argument is masked by the host. The import side's widths are untouched (its bytes are pinned).
- **Memory**: the result's area and what it stages are bump-allocated and stay until the host has
  read them; `cabi_post_*` pops to the mark `cabi_realloc` took. The wrapper allocates the area
  THROUGH `cabi_realloc(0, 0, 8, size)` after the call, which takes the mark when the host lowered
  nothing and leaves the heap top 8-aligned (an intern leaves it anywhere: "The alignment trap").
  Measured 2026-11-26, wasmtime 51: 9,000,000 calls of list / record / string exports from a
  composed Lisp command under `-W max-memory-size=48MiB`, no growth.
  A `--no-wasi` module has no `cabi_realloc`: the area comes from `__ronto_alloc` behind the
  staging prologue's 8-aligned heap top, and the glue's mark/reset bracket pops it after the
  lift (`exportMarshal(..., -1)`).
- **A `result` at the top of the answer is the provider's mapping**: the function returns the ok
  arm and signals `rontolisp:wit-error` for the error arm. `lower` writes a defun
  `<f>%wit-export` making the envelope (`handler-case` only in a program that holds wit.lisp, so
  one that names no `wit-error` pays no EH mode); the codegen lowers the envelope and learns no
  condition. Anywhere else a `result` is the envelope both ways. So the functions a world exports
  are, unchanged, a `wit-provide` provider of the interface (`examples/wit/shapes/app.lisp`).
- **Types in the component** (`WitExportTypes`, the shape `wasm-tools component new` gives, read
  off a probe): a world's own named type is a type IMPORT; an exported interface's are defined
  bare, its functions lifted over those, then a nested SHIM component imports each used type and
  function and exports every type and each function ascribed over the exported types (an inline
  instance cannot name a type). Every type the scope defines is declared, used or not -- a
  component importing the interface names them --, in the tool's order: definition order, a type
  waiting for the ones it mentions (the earliest that can go, goes). `--emit-wit` prints them
  back, byte-identical to the tool ("A world that names types" for which scopes).
- **Flags** = the list of the keywords of the labels set, both ways and on the import side
  too (`emitLowerFlags` / `emitLiftFlags`): one i32, label i at bit i, 1 / 2 / 4 bytes in memory
  for up to 8 / 16 / 32 labels (`WitCanonicalAbi.flagsSize`, the canonical ABI's
  `elem_size_flags`). A keyword naming no label traps, as one naming no case; a label named
  twice is set once; a lift answers label order (so a function that conses a label onto the
  front answers another order in process than through the boundary -- `examples/wit/shapes`'
  `embolden` appends). The Clojure and Java tiers convert already (a set, a `Set` of a member
  enum) and cross a component with nothing added. Not a `--no-wasi` core module, either way:
  its JavaScript glue has no value (`WitGlueLayouts.layout` refuses, `.todo/m92`).
- **An `async func`** is the same form with `:async t`, and nothing else: its lift is the
  synchronous one over an async function type, so parameters, result area and post-return are
  a sync export's ([wasi-component.md](wasi-component.md), "`:async t` I/O exports", with what
  the spec says and which calls of an instance overlap). The refusal it had ("an async export
  carries the flat types only") rested on a lift with no post-return and four flat parameters,
  which is an async-OPTION lift's and an async lower's. Measured 2026-11-26: the integration
  test's world with every function async answers what the sync world answers on both
  component shapes, a call that sleeps answers on `--component` (the same function as a plain
  func traps, `cannot block a synchronous task before returning`; the reactor, which imports no
  clock, traps either way); `wasm-tools component targets` accepts it; 600,000 calls from a
  `bindgen!` host under a 48 MiB store limit, 35 MB peak RSS; a `--no-wasi` module's glue calls
  it as any export (`:async` is a component's). `flip: async func(point) -> point` 1,938 B /
  1,126 gzip against the sync 1,881 / 1,098 (the held mark, wasi-component.md).
- **Refused at the WIT line** (`requireCanonical`): a resource handle, a stream, a future, an
  interface declaring a resource
  (`.todo/m91`); `--no-gc`, which has no value a record could be (no cons). In the compiler,
  which knows the output's shape: a WASI Preview 1 module (its host, wasmtime, calls a core
  export with core values) and `--reentrant` (the answer waits on the bump heap for the glue).
  A `--no-wasi` module exports them through `--emit-js-glue` ([wasm-import.md](wasm-import.md),
  "A WIT interface beyond the flat set"). `describe` (the front ends) answers a compound type's
  whole shape (`WitTypeMapper.shape`, as an import member's) after these refusals -- all but
  `--no-gc`'s and a Preview 1 module's, which need the output; a flat type keeps its primitive
  shape, the primitive its `wit` and its `definition`, so a front end tells a canonical export
  by a `wit` that is no flat primitive's (`JavaHostBoundary.flat`).
- `:envelope t` on the directive: a top-level `result`'s function answers the envelope itself,
  so `lower` writes no `<f>%wit-export`. The Clojure lowering passes it: its wrapper makes the
  envelope from an `ex-info` (one catch, no wit.lisp; [clojure-frontend.md](clojure-frontend.md),
  "Host boundary"). So does the Java lowering, whose wrappers convert a `WitResult` or catch a
  `WitException` ([java-frontend.md](java-frontend.md), "WIT exports"). Pin:
  `WitExportDirectiveTest.lowersATypeBeyondTheFlatSetIntoTheCanonicalFormOrRefusesItAgainstTheWitLine`.
- **A `u64` RESULT past 2^63 - 1 is not exact-or-trap** (measured 2026-11-26, wasmtime 51, flat
  and canonical alike): the house integer is a float there, so `2^63 + 1` answers
  9223372036854775808 and `2^64 - 1` traps; a NEGATIVE integer traps flat, and in a canonical
  compound value (a list's element, a record's field) crosses as its 64 bits (-1 ->
  18446744073709551615). A `u32` of -1 traps in both.
- `:octets t` on the directive: a `list<u8>` parameter is the `(unsigned-byte 8)` vector, not
  text. A `list<u8>` answered is a string or a packed vector, anything else traps (`reverse` of a
  packed vector is a general one).
- **Fixed on the way**: a `:string` parameter with an `:s64` / `:u64` result failed validation on
  the wasm-GC component (the post-return's parameter was declared `i32`;
  `WasmExportCompiler.postReturnParam`). `WitPrinter` printed a world's consecutive type
  definitions without the blank line the tool puts between them.
- Sizes (2026-11-26, default `--optimize`, raw / gzip): an interface of `flip: func(p: point) ->
  point` 1,814 / 1,073 B (`--no-wasi` 1,659 / 985) against 1,036 / 695 for an `s32` adder;
  `longest: func(list<string>) -> option<string>` 1,905 / 1,086; `examples/wit/shapes` 69,764 /
  26,645 (its `format` and `loop`). Byte-identical before and after, 34 outputs: hello, string and
  interface exports, the `examples/wit` and count-vowels worlds, mandelbrot, the Java counter and
  `PageHits`, two Clojure worlds, each on the shapes it compiles to.
- Hosts measured (2026-11-26): wasmtime 51's WAVE `--invoke` (every type, both shapes of
  component); a composed Lisp importer; by hand, a Rust importer of `examples/wit/shapes`'
  interface (wit-bindgen 0.62, `wasm-tools component new`, `wasm-tools compose`), every function
  and 600,000 calls under `-W max-memory-size=48MiB` -- its import type matched the shim's
  exports, type names included. By hand on the integration test's world (`use` clauses, flags,
  every other type; `--component --no-wasi`): **jco 1.42.0** under node 24.22.3 (`jco
  transpile`, then each export called; 600,000 calls) and a **wasmtime 51 `bindgen!` host** in
  Rust generated from the SOURCE WIT (600,000 calls, 36.8 MB peak RSS) -- both answer what
  `--invoke` answers, a trap a trap, the error arm of a `result` jco's thrown `payload` and
  Rust's `Err`. jco gives a world's types as TypeScript interfaces and flags as an object of
  booleans. Traps: after a trap the instance is poisoned (jco: "cannot enter component
  instance"), so each trap is a run of its own; jco refuses an EH-mode module ("exceptions
  proposal not enabled"), which a program naming `rontolisp:wit-error` or `handler-case` is --
  a world for jco answers its `result`s under `:envelope t`; `wasm-tools compose` 1.263.0
  refuses a dependency whose WORLD types name each other ("type not valid to be used as
  import"), the tool's own component included (`wac` instead), and wires the composed
  component's own imports alone (a dependency's dependency is composed into it first). The
  same world with its functions async, by hand: the `bindgen!` host (wasmtime's
  `component-model-async`, WASI p3 in the linker; an async func is `call_concurrent` under
  `run_concurrent`) and jco (each async export a `Promise`; 1,200,000 calls, RSS level at 279
  MB) answer what `--invoke` answers.
- Pins: `WasmLispCompilerIntegrationTest.aWorldsCompoundTypesCrossItsExportsThroughTheCanonicalAbi`
  (async funcs among them: in an interface, with a `result`, past sixteen flats, one that waits),
  `WitOracleE2eTest.gcMixedFlatAndInterfaceExportsMatchWasmToolsByteForByte` /
  `.aComponentCallsWhatAnotherExportsOverCompoundTypes`,
  `WitExportDirectiveTest.lowersATypeBeyondTheFlatSetIntoTheCanonicalFormOrRefusesItAgainstTheWitLine`,
  `WitExportInlinerTest.aWorldIsRefusedAnOutputThatCannotExportIt`, `examples.yaml`'s `wit/shapes`.

### A world that names types — the component is the world's
**Invariant: a component of a world that names a type declares the types of every scope the
world exports from, and types every export from the WIT.** "Names a type" = an exporting scope
(the world, an exported interface) defines one, an alias of a flat type included, or an export
is canonical (`WitExportDirective.namesTypes`). Every export form of such a world carries
`:wit` / `:wit-world` / `:wit-at`; a FLAT one keeps `:params` / `:returns` beside them
(`Decl.typedBy`): its wrapper is the flat one, its core module byte-identical, and only the
component wiring differs (`ComponentExportWiring`, shared by both component builders; an
interface with a typed member, or of types alone, is a shim). Component backends only: a core
module's forms are the hand-written ones.

- **Why** (measured 2026-11-26, wasm-tools 1.263.0): `wasm-tools component targets` refused the
  old output of `interface ops { type id = u32; next: func(x: id) -> id; }` on all three
  component shapes ("missing expected export `id`"), and a component importing the interface
  names `id`. A world's own types are imports, which a component may leave out, but they are
  declared too: one rule, and `--emit-wit` prints `c: count`, not `c: u32`.
- **An interface of types alone** is exported as those types (it was "declares no functions to
  export"); no export form stands for it, so the builder reads it off the world any typed
  export carries (`WitExportSignature.exportedInterfaces`, world order). A world exporting
  nothing but such an interface still "declares no exports".
- Cost: `ops` + a world-level alias, 989 -> 1,191 B (`--component --no-wasi`), 1,186 -> 1,388
  (`--component`), 237 -> 439 (`--no-gc --component`). Byte-identical before and after: 22
  outputs of worlds that name no type or were canonical already (count-vowels on six shapes,
  `examples/wit` worlds, the integration test's world). Moved, same size: a world whose flat
  export precedes a canonical one (`examples/clojure/greeter`) declares the world's types
  before that export's function type.
- Pins: `WitOracleE2eTest.gcMixedFlatAndInterfaceExportsMatchWasmToolsByteForByte` (`targets`
  against the source WIT, three shapes), `WitExportDirectiveTest.lowersATypeBeyondTheFlatSet...`.

### A type of another interface — written out, not imported
**A `use`d value type is declared as the using scope's OWN, under the clause's name**
(`am.ik.wit.WitUses.inline`, applied to every exporting scope in `WitExportDirective.implemented`
/ `Implemented.scope`): nothing downstream of the directive sees a `use` of a value type, so
the marshalling, `WitExportTypes` and `WitEmitter` needed no change.

- **Rejected: the tool's shape.** `wasm-tools component new` makes the other interface an
  instance IMPORT (types only) and aliases the type out, or aliases it out of the exported
  instance when the world exports that interface (the probe's shapes: `.todo/m91`, which needs
  them for a resource). A value type is structural: `targets` accepts the structural copy
  against the world that says `use` (measured, both cases), a `bindgen!` host generated from
  the source WIT type-checks it, and the import side has always inlined a used record. The
  import would break `--component --no-wasi`'s zero-import contract and collide with a
  `wit-import` of the same interface.
- What the copied definition mentions: the scope's name for it when it has one (a clause's
  name wins, wherever the type is reached from; a second name is an alias of the first); an
  alias with no name here is written as what it names; a record / variant / enum / flags with
  none comes under its OWN name in an interface (an extra export, which `targets` allows) and
  is REFUSED in a world, whose types are imports the world of the WIT does not have (measured:
  "missing import named `coord`") -- the message says to name it in the clause. A name taken
  by another type is refused at the clause's line, with `as`.
- **A resource is nominal**: a name whose definition is one, or holds a handle, stays a `use`
  clause (path rewritten to the full id, since the scope answered is in no package) and the
  export refuses it as a handle.
- `--emit-wit` prints the scope with the copies (the tool's reading of the component), not the
  `use`.
- Pins: `WitResolverTest.writesAUseClauseOutAsTheDefinitionsItNames`,
  `WasmLispCompilerIntegrationTest.aWorldsCompoundTypesCrossItsExportsThroughTheCanonicalAbi`,
  `WitOracleE2eTest.gcMixedFlatAndInterfaceExports...`, `WitExportDirectiveTest.lowersATypeBeyondTheFlatSet...`.

### The integer boundary — the exact-or-trap rule (`compiler/BoundaryType`)
**The vocabulary IS the WIT spelling**: `:s8`..`:u64`, plus `:float` (f64 -- no internal f32,
`.kb/wasm-export-no-wasi.md`), `:bool`, `:string`, and the rontolisp-only `:s-expr` / `:void`.
**`:int` / `:long` are permanent aliases of `:s32` / `:s64`, normalized at parse time**
(`WitExportInlinerTest.theLegacyIntSpellingCompilesToTheSameBytesAsItsWitSpelling`). **Emitting
`s32` for a `u32` is not benign**: the component model has NO integer subtyping, so `wasm-tools
component targets`, jco and every `bindgen` host REJECT it (`WitOracleE2eTest`).

**The rule: the boundary carries the value exactly, or the wrapper traps** -- derived from two
intervals (`BoundaryType.range()` vs the backend's house-integer range), not enumerated per type.
wasm-GC: wide results exact via `_int_val` past 2^53 where f64 would round; narrow results combine
an exact lane and an f64 lane (float/ratio) in ONE `if` so the type-test fold can drop the f64 lane
(`.kb/wasm-ref-type-fold.md`), then `v != canon(v)` traps. `--no-gc`:
`NoGcWasmCompiler.emitBoundaryRangeGuard` + `i32.wrap_i64`. `u64` traps below 0 on both. `:s64`
needs no check on `--no-gc`, which is why its pass-through wrapper elision (`isPassThroughExport`)
survives.

**Residual asymmetry**: a host-supplied `u64` >= 2^63 has no exact representation -- an export
wrapper TRAPS, while a `--component` import lift degrades to the float approximation
(`WasmComponentImportCompiler.boxI64`). **The import side is deliberately NOT widened**:
`WasmImportCompiler`'s set is `{:s32, :float, :bool, :string, :s-expr}` and
`WitImportDirective.designatorOf` maps the whole `Rep.INT` family to `:s32` (`.todo/169`).

### Interface exports, `:param-names`, `--scaffold-wit`
An `export docs:adder/add;` lowers each of the interface's functions with `:interface
"docs:adder/add@0.1.0"`; exports sharing an interface are bundled into **one exported component
instance**. A flat export stays a top-level `exportFunc`; the only no-op `ExportRef` is
`wasi:cli/run`.

**GOTCHA -- an instance EXPORT consumes an instance index.** In the GC builder the run instance is
`11 + userIfaces`, its `export wasi:cli/run` introduces `12 + userIfaces`, so the first free index
for an exported interface is **`13 + userIfaces`**. Getting it wrong points the export at the run
instance and *validates* -- only the WIT diff catches it. `--no-gc`'s base is `FIRST_PRINT_INSTANCE
(2)` with print, `0` without. Pinned by
`WitOracleE2eTest.{gc,noGc}InterfaceExportWitMatchesWasmToolsByteForByte`.

- `:param-names` default `p0`, `p1`, ... (so pre-existing artifacts stay byte-identical); ignored on
  the core-export path.
- `rontolisp --scaffold-wit world.wit` (`cli/WitScaffolder`) emits `defun` stubs with the WIT's
  parameter names; **the `wit-export` directive comes LAST** so the interpreter's check passes.
- **`--emit-wit` on the export side is a FIXPOINT, not a check**: world export -> `lower()` -> Decl
  -> component type -> `WitEmitter` is one-to-one both ways; the contract check is what catches a
  drifted program. What `--emit-wit` uniquely reports is the IMPORT side, from the BUILD; under
  `--optimize` it is filtered from the SAME set the builder prunes to
  (`WasmComponentBuilder.wasiMembers`), hence `WitOracleE2eTest`'s `--optimize` legs.

### Two standing decisions
- **An `:s-expr` export has NO WIT spelling -- never.** `:s-expr` is s-expression TEXT crossing as
  `(ptr, len)`, while WIT `string` maps to `:string`, a DIFFERENT wire format; a blind retrofit
  **compiles** and breaks at run time. `WasmExportCompiler.componentValType()` lifts BOTH as
  component `string`, so an `:s-expr` export's `--emit-wit` world fed back through `wit-export`
  yields a silently different ABI. The browser demos stay on hand-written `rontolisp:wasm-export`.
- **`wit-export` grows NO alias mechanism**: `LABEL` enforces lower-kebab-case, right for a
  COMPONENT (jco maps `count-vowels` -> `countVowels`); core-module demos wanting camelCase
  hand-write `rontolisp:wasm-export`.

## `rontolisp:wit-import` — calling a WIT interface
`(rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)`, then
`(kv:bucket-set (kv:open "cache") "hello" "world")` -- a handle is the FIRST argument.

`:interface` may be fully-qualified, version-less or bare (`WitResolver.findInterface`, in that
order; an ambiguous one resolves to nothing and the error lists every id); all lower to the
CANONICAL id, which keys the provider registry. `:canonical t`: a `--no-wasi` module imports
through the canonical ABI though every bound member is flat.

**It LOWERS -- no new call path on any backend.**

- **Preview 1 WASM**: one `rontolisp:wasm-import` per function, `:async t` for an `async func`
  (`.kb/wasm-import.md`). **Measured byte-identical** to the hand-written block, also under
  `--optimize`.
- **Interpreter / JVM**: the `defpackage`, then one ordinary defun per function calling
  `rontolisp::%wit-call`, so `#'`/`funcall`/`eval` all work; an `async func` binds as an
  `async-defun`.
- **`--component`**: a component-model instance import, each function `canon lower`ed. An `async
  func`'s result lifts like a sync one's from its return area; only a `stream<T>` element type
  needs its own read lift.
- **`--no-gc`**: the SAME Preview 1 lowering, so one world serves both, differing in two places
  because the vocabulary follows the HOUSE INTEGER: the designator is read straight off the prim
  name (`BoundaryType.forWitName`, as the export side does), so `s64`/`u64` cross and a `u32` keeps
  its width; and an `async func` is a compile error (no futures in that value model). An import and
  an export of ONE world must not disagree about which backend can bind it. Pins:
  `WitImportDirectiveTest.theNoGcBackendGetsThePreview1LoweringWithItsOwnWidths`,
  `NoGcWasmImportE2eTest.aWitImportedInterfaceIsTheHandWrittenImportBlock`.
- **`--no-gc --component`**: the same block, named as the wrap's canon-lowered instance import is
  typed -- the CANONICAL id as `:from`, the WIT label verbatim as `:as`, the WIT parameter names --
  so a composed provider type-checks down to the names. Resources, non-primitive types and drops
  are refused at the WIT line (`validateNoGcComponentFunc`). Pins:
  `WitImportDirectiveTest.theNoGcComponentLowersToTheCanonicalImportNames` /
  `.theNoGcComponentRefusesWhatItsInstanceTypeCannotName`,
  `NoGcWasmComponentImportE2eTest.theWitImportLoweringIsTheHandWrittenBlock`. The wrap:
  `.kb/no-gc-scalar-wasm.md`, "Host imports".

### Pass order — the IMPORT inliner runs BEFORE `UserMacroExpander`
`eval/WitImportInliner` runs straight after `LoadInliner`: the `(defpackage kv ...)` must exist
**before anything resolves a `kv:get` call site**, and `UserMacroExpander`'s `PackageResolver`
treats an unknown package as a hard error. It can run that early because a `wit-import` is checked
against a WIT FILE, never against the program. **Put a `wit-import` at the TOP of the file** (the
opposite end from a `wit-export`).

### The provider and the `wit.lisp` runtime
`(rontolisp:wit-provide "<iface>" #'my-dispatch)`, and **NO built-in provider for anything**. A
provider is an ordinary Lisp callable taking the member name (a STRING) then the arguments -- the
same value on every backend. `java:bind-wit` was dropped (the native binary cannot INTERPRET
`java:`, `.kb/java-interop.md`); a `ServiceLoader` SPI stays rejected. *rontolisp's core knows the
provider MECHANISM; it does not know what `wasi:keyvalue` is.*

Runtime: `src/main/resources/am/ik/rontolisp/eval/wit.lisp` via `eval/WitLibrary`.
- **The key is the CANONICAL id** (`WitResolver.canonicalId`), never the user's spelling (else one
  interface gets three keys and "No provider is bound" at run time).
- No provider signals `rontolisp:wit-error`; `wit-provide` REPLACES. **On the WASM backends the
  host IS the provider, so a top-level `wit-provide` is dropped** -- inert, not a compile error.
- Spliced only when the program REFERENCES its names (WASM output stays byte-identical); the
  interpreter loads lazily, so a provider may be bound BEFORE the directive. **`wit.lisp` is not in
  `LibraryDefunPruner`'s prunable set**, like `usocket`.

### Name mapping and the two type tiers
A resource `bucket`'s method `get` binds `bucket-get` with the handle FIRST; `constructor` ->
`bucket-new`, `static func f` -> `bucket-f`, the release -> `bucket-drop`.

- **WITH `:package` the label is kept verbatim and qualified** (`kv:|bucket-get|`): a call site meets
  it through the export table, which matches case-insensitively. **WITHOUT `:package` the binding is
  named the READER's spelling** (upcased `ADD-INTS`, internal names with it): with no export table
  in between, definition and call site must produce the same string themselves. A verbatim binding
  is reachable by NOTHING -- it failed on every backend and silently shook gl.lisp's whole error
  path out of the browser demos. Pins:
  `WitImportDirectiveTest.{bindsTheNamesInTheCurrentPackageWithoutThePackageOption,aPackageLessBindingIsNamedWhatACallSiteReadsTo}`,
  `WitImportInlinerTest.aPackageLessImportIsTheHandWrittenImportBlockToo`,
  `LispEvaluatorTest.aPackageLessWitImportBindsNamesTheProgramCanCall`,
  `NoGcWasmImportE2eTest.aPackageLessWitImportIsCallableFromTheProgramThatDeclaredIt`. The member
  string a provider gets, the Preview 1 field and the component import name are the WIT's names.
  `PackageResolver`'s lower-kebab retry is only a safety net for hand-written code.
- WIT parameter names become the lambda list **verbatim**, a resource method gaining a leading
  `self` (a signature already declaring `self`, or a member bound twice, is an error naming the
  line). The Preview 1 FIELD is `:field-style` applied to the label, **`:camel` by default**.
- **Interpreter / JVM -- everything except `stream`/`future`**: an ordinary call, nothing marshalled.
  **Nothing in the lowering inspects the return value** -- the `result` error arm is **the PROVIDER**
  calling `(error 'rontolisp:wit-error :payload ...)`, an obligation no one enforces.
- **Preview 1 WASM -- only the flat set `rontolisp:wasm-import` carries**; everything else is a
  compile error naming the WIT LINE (a core import has no component type for a richer shape). So
  **`wasi:keyvalue/store` does not cross a WASI Preview 1 module**; the flat host-shaped interfaces
  (WebGL) do. A `--no-wasi` module's glue lifts the canonical ABI, so there a non-flat interface
  takes the `--component` lowering (`WitImportDirective.CanonicalCore`; [wasm-import.md](wasm-import.md),
  "A WIT interface beyond the flat set").
- A resource handle is an opaque integer allocated **by the provider**. **It is NOT the shared
  stream/socket handle space, and `cl:close` does not apply to a WIT resource.**

### The naming hook (`:names`) — a front end's own names
`:names (("label" "lisp-name") ...)` on both directives (`compiler/WitNamingHook`; strings, so no
package resolution touches either side). For the Clojure lowering, whose `c%ns/name` vars neither
`:package` nor the reader spelling produce (`.kb/clojure-frontend.md`, "Host boundary"). It names
the Lisp side only.

- **`wit-import`, table mode**: only the listed members are bound, verbatim; an unlisted one is
  neither bound nor tier-checked. Internal names follow behind a lone `%` (`<name>%raw`, `%start`,
  `%lift`), which no Clojure identifier spells. A front end reading a result's envelope itself
  names `<name>%raw` alone, and the member binds without its `%wit-result` wrapper (`rawOnly`). A
  table directive is filtered by reference on Preview 1 too (`--no-prune` binds the whole table),
  so a member the core import cannot carry fails only where the program calls it. `:package` beside
  it is an error.
- **`wit-export`**: each world export is implemented by the function the table names, emitted with
  `:as "label"`.
- **`describe`** (`WitImportDirective.describe`, `WitExportDirective.describe`): the members / exports
  each typed as a `WitTypeMapper.Shape` with its line, after the same checks as `lower` but before
  any program exists, so a front end binds its names first. A shape is the whole type, aliases
  followed and nested types resolved in the interface that writes them (`use` crossed). A NESTED
  type naming no definition, or a self-containing one, describes as `UNSUPPORTED` (the
  interpreter's `lower` never looks inside a record, so only the member reaching it is the front
  end's to refuse).
- **The Java front end** uses the same hook (`.kb/java-frontend.md`, "WIT imports"), only on a WASM
  target; for it `describe` also gives a member's `resource`, WIT `function` name and `kind`, and a
  shape's `definition` and `borrow`.
- Pins: `WitNamingHookTest`, `WitImportInlinerTest.aNamesTableBindsOnPreview1TheMembersTheProgramNames`.

### The gl.lisp migration
`examples/browser/webgl-common/gl.wit` is checked in; gl.lisp binds `local:webgl/gl` and
`local:webgl/ui` package-less. Byte identity with the pre-migration builds **was hiding the
package-less naming bug above** (`gl::fail` never met its call site, so the shaker took the error
path) -- byte identity is not proof of a correct migration.

- A WIT label is ONE name that becomes both the Lisp name and the camelCased host field; **the WIT
  names win -- `wit-import` does NOT grow a per-function alias option.**
- **A `type shader = s32` alias is free and worth it**: the lowering is untouched, but the NAME
  distinguishes a table handle from an integer. **`gl-imports.js` is GENERATED from gl.wit**
  (`GlImportObjectTest` regenerates and pins it).
- **Trap: `load` loses the base directory.** A `wit-import` inside a library resolves against the
  file that WROTE it, but the splice flattens the program; `LoadInliner.rebaseWitImport` fixes it
  (`LoadInlinerTest`).

### A package directory
The path may name a directory: a WIT package as published (wasi-keyvalue's `wit/`, only
`world.wit` declaring the package). `eval/WitFiles.read` is the ONE reader (every directive, the
interpreter's special forms, `ClojureHostBoundary`); `am.ik.wit.WitPackageAssembler` parses each
file ON ITS OWN and assembles the document a single file with nested `package` blocks would parse
to (each `deps/` entry a `PackageBlock`). Duplicate names, a dep without a package and a parse error
each name `file:line`.

- **Positions stay per file**: every message goes through `WitSource.at(path, locations, item)` and
  carries `path:line`.
- **The `--component` form carries `WitPrinter.print` of the assembled document**; the component is
  BYTE-IDENTICAL to the hand-concatenated file
  (`WasmLispCompilerIntegrationTest.componentImportLowersListParametersAsTheInterpreterAndTheJvmPassThem`).
- No top-level `use` exists in the parser, so no file-scoped name leaks across files; an
  interface-level `use` reaches a sibling file, which is the WIT rule.
- `wit-export` on a directory searches only the package's own worlds, not its deps'.
- A loader that lists no directories is refused a package directory (`WitImportInlinerTest`); the
  playground's lists the directories its uploads' names make (`eval/UploadedFiles`).
- Not done: rontolisp's own libraries (`http.wit`, `sockets.wit`, ...) read concatenated copies,
  because a resource directory is not listed in a native image.
- Pins: `WitPackageAssemblerTest`, `WitImportInlinerTest.aPackageDirectoryBindsWhatItsHandConcatenationBinds`,
  `ClojureHostBoundaryTest`; fixture `testsupport/WasiKeyvalueWit`.

## Component imports — `canon lower`
A `wit-import` under `--component` is a real component-model instance import whose functions are
`canon lower`ed into the core module, so the provider is the HOST and the component composes (`wac
plug`). `rontolisp:fetch` and `rontolisp:http-handler` are consumers: `http.lisp` over a wit-imported
`wasi:http@0.3.0`, no WAT http adapter (`.kb/fetch-http.md`, `.kb/async-await.md`).

**The reference probe (do this before touching the encoders again).** Everything was derived from
a hand-built probe, NOT the spec. Golden bytes:
`ComponentWriterTest.instanceTypeMatchesWasmToolsReference`; layout facts: `WitCanonicalAbiTest`.
wasmtime's in-memory keyvalue provider recognizes **only the empty store identifier**, and a
**forged handle traps at the boundary** before reaching the provider -- uncatchable.

### The pieces
- **`compiler/WitImportDirective`** lowers to `(rontolisp::%component-import "<iface-id>" "<wit
  text>" ...)` -- **the WIT TEXT TRAVELS INSIDE THE FORM**, so the WASM compiler reads no files and
  the playground works by construction.
- **`codegen/wasm/WasmComponentImportCompiler`**: one synthetic defun per bound function and a
  placeholder call that **`WasmImportInjector` renumbers -- REUSED UNCHANGED**.
- **`codegen/wasm/WitComponentTypeEncoder`**, grammar pinned by the probe: **type decls AND
  type-bound exports append to the instance type's local type index space; function exports do
  not**.
- **`WasmComponentBuilder.appendUserImports`**: every downstream hardcoded index shifts by the
  user-import counts. **Zero imports = zero shift = byte-identical.**
- **`WasmServeComponentBuilder`**: the preview1 bridge is instantiated BEFORE the rontolisp core;
  `ServeIo` cursors + `coreInstanceOf` make every downstream index relative. **State a served
  handler cannot otherwise have**: a `wasi:http` host recreates the instance per request, but a
  store behind a WIT import survives -- whether it SURVIVES is the host's.

### `result`, member pruning, resource drops
- **`result` = the envelope + a Lisp wrapper (NOT a codegen catch).** The synthetic defun takes the
  raw name (`kv::%open`) and returns `(:ok . V)` / `(:error . E)`; a generated wrapper unwraps it
  through `rontolisp::%wit-result`, so the error arm rides the ORDINARY condition machinery. **The
  codegen never mentions conditions.**
- `--component` params and results carry **everything** (flags: "Compound types across an
  export"). **Rejected precedent**:
  lowering rich P1 types through `:s-expr` was REVERTED -- a rontolisp-specific pseudo-protocol is
  not a WIT boundary and it broke P1 byte identity.
- **Member pruning replaces the tree shaker on this path** (`--component` skips `WasmTreeShaker`):
  the member filter is the names the program textually references, disabled by `--no-prune` /
  `--dynamic`.
- **Resource drops -- `<resource>-drop`.** Not a nicety: `wasi:http` makes `outgoing-body.finish`
  TRAP unless the child `output-stream` is dropped first. **Reference-gated on every backend, and
  that is what buys byte identity.** Preview 1 gets a **no-op defun and NO `wasm-import`**;
  `--component` a core import (`"[resource-drop]bucket"`). **Dropping a handle releases the
  reference, not the store.**
- **`with-<resource>`**: a macro in `with-open-file`'s shape (`let` + `unwind-protect` + the drop),
  bound when named (naming it binds the drop too); not under a `:names` table. Pinned by
  `WitImportDirectiveTest.bindsAResourceDropOnlyWhenTheProgramNamesIt`.
- **The outer side forces TWO counters.** `canon resource.drop` produces a **CORE function with NO
  component function behind it**, and mixing them yields a component that VALIDATES while lifting
  the wrong core function: `userImportFuncs` (decls) -> component-func indices;
  `userImportCoreFuncs` (decls + drops) -> core-func indices; `userImportTypes` (interfaces +
  projected + dropped resources; a `use` projection is REUSED, keyed `ifaceId#resource`, so it must
  not be counted twice).
- Two traps: **a wrapper's first parameter is local slot 1, not 0** (slot 0 is the closure
  environment), and **the encoder declares resources LAZILY**, so a resource ONLY dropped is
  force-declared AFTER the function walk. A drop never appears in `--emit-wit`.

## Rich PARAMETERS across the component boundary
**A param lowers everything a result lifts**: `wasi:http`'s `method` variant carries a string and
`response-outparam.set` takes a `result` whose cases carry records and `option<string>`.

**The Lisp shape is the LIFT's shape** (`emitLowerVariantParam` / `emitLowerRecordParam` consume
what `emitVariantCase` / `emitLiftRecordAt` build). **The `result` ARGUMENT is the envelope**; a
payload-less arm may be the bare keyword (`consp` ? `car`/`cdr` : the value).

- **Keyword identity in wasm is `struct.get $string 0` (the interned id) + `i32.eq` -- NOT
  `ref.eq`** (`compileStringLiteral` allocates a fresh struct each time; only field 0 is
  canonical). Idiom: `WasmFetchRuntimeBuilder.buildPlistGet`. A keyword naming no case traps,
  deliberately.
- **Joined payload flats** (`WitCanonicalAbi.flatTypes`): coerced to the joined type, unreached
  positions zero-filled; a joined FLOAT flat rides in an integer scratch local as its bits. Cursors
  roll back per case, field and argument, so the cost is the deepest nesting, not the sum.
- **The scratch pools are SIZED BY MEASUREMENT, not by a constant**: `buildWrapperBody` emits twice,
  reads the high-water marks and emits again. Fixed pools were blown by `response-outparam.set` --
  the ONE call this exists for -- while a trimmed test corpus stayed green. **Do not re-fix by
  raising a constant.**
- **`needsMemory` must recurse into variant cases and record fields**, and **`repOf` must resolve
  aliases first** (`type headers = fields`; pinned by
  `WasmComponentImportCompilerTest.followsATypeAlias...`).
- **`list<T>` (T != u8)** (`emitStageListParam`, the mirror of `emitLiftList`): count, allocate,
  store each element through `emitLowerAt` (strings and nested lists stage ABOVE the array).
  **A vector is taken, in Lisp, on every backend**: `WitImportDirective.listDescriptor` quotes where
  a `list<T>` sits and the binding passes the argument through `rontolisp::%wit-coerce` (a vector
  becomes its list, anything else signals `type-error` before the call). Driven by
  `wasi:keyvalue`'s `batch` (774 keys: `list-keys` + a `get` each 7.1-7.7 s vs 16 calls with
  `get-many`). Pinned by
  `WasmLispCompilerIntegrationTest.componentImportLowersListParametersAsTheInterpreterAndTheJvmPassThem`,
  `LispEvaluatorTest.aListArgumentReachesTheProviderAsAProperListWhereverItSits`,
  `WasmComponentImportCompilerTest.aListParameterStagesACanonicalArrayThatValidates`.
- Edges: an **empty `record`** is a compile error; a `record`/`tuple` RESULT flattening to one core
  value lifts through `emitLiftRecordFlat`; `option<bool>` is ambiguous (`some(false)` and `none`
  both nil) and **accepted, not refused**.

**Verified against real hosts** (wasmtime's `wasi:http/types`, `wasi:sockets/types`,
`wasi:cli/exit`, E2Es in `WasmLispCompilerIntegrationTest`). **Trap: trimmed WIT in a test must
match the host's real interface EXACTLY** (the subtype check is structural). **A user import must
not collide with the WASI surface** -- `WasmComponentBuilder.rejectAdapterImportCollisions`, in
`build` where the blob variant is known.

**The alignment trap**: `HEAP_PTR` is not always 8-aligned (`_intern` advances by a symbol's exact
byte length), so using it as a canonical-ABI return area traps with `pointer not aligned` only after
a program interns something. The wrapper **aligns HEAP_PTR up to 8 on entry** and pops back to
`align8(max(mark, intern-high-water))` on exit. Do not "simplify" either.

## What CANNOT be externalized as a WIT import
- **The base adapter (`adapter.wat`)**: the core module's `wasi_snapshot_preview1` import layout is
  Preview-1-IDENTICAL by design and every `FUNC_*` constant rests on it. **The wall is no longer
  technical** -- it is the byte-stability/flag-neutrality CONTRACT for non-async programs: 0.3
  file/stdio I/O is stream-based, so a Lisp-library implementation makes every file- or
  print-using component an async+EH component. **`adapter.wat` STAYS.** Revisit trigger: a real
  need for non-stalling file reads inside async bodies (the stdin.lisp pattern).
- **The serve preview1 bridge** -- the same in miniature. **The `--no-gc` print micro-adapter** -- a
  different backend.
- **`wasi:sockets` is NOT on this list**: `sockets.lisp` rides a wit-imported
  `wasi:sockets/types@0.3.0` (`.kb/tcp-sockets.md`). **Lesson: a "wall" here meant a missing
  LANGUAGE feature, and the language moved -- re-read a wall before trusting it.**

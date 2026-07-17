# `am.ik.wit` — the WIT parser/printer library, the settled type mapping, `wit-export` and `wit-import`

Todo 125 (step 1 of the todo-124 "WIT as universal IDL" roadmap), 2026-07-13, plus
todo 126 (`rontolisp:wit-export`, 2026-07-14) and todo 127 (`rontolisp:wit-import`,
2026-07-14). Everything downstream (component imports, todo-128) consumes this model;
nothing downstream re-parses text.

## The library (`am.ik.wit`)

A language-independent sibling of `am.ik.jvm` / `am.ik.wasm`: **no rontolisp imports,
no external dependencies** (jspecify annotations only, like its siblings).

- `WitLexer` — lossless: every input character lands either in a token's `text` or in
  the **leading trivia** (whitespace + comments, verbatim) of the following token; the
  `EOF` token carries the trailing trivia. Keywords are not distinguished lexically
  (contextual in the parser), so `record`/`type`/... work as names where WIT allows.
  Version tokens (`0.3.0`, `0.2.0-rc-...`) stop before `.{` so
  `use wasi:io/streams@0.2.0.{...}` lexes correctly.
- `WitParser` — recursive descent over the corpus grammar: package headers AND explicit
  `package ... { }` blocks in one file, worlds (`import`/`export` by path or inline
  extern, `include`, `use`), interfaces, funcs (`async`/`static`/`constructor`),
  `record`/`variant`/`enum`/`flags`/`resource`/`type`, all type uses incl.
  `stream`/`future`/`borrow`/`own`/`result<_, E>`, `%`-escaped ids, `@since`/`@unstable`/
  `@deprecated` gates (parsed and preserved as `WitMeta.Gate`), and `///` doc comments
  (extracted from trivia into `WitMeta.docs`, which is how `--scaffold-wit` copies them
  into the generated Lisp).
  Out-of-corpus constructs (nested namespaces, `include ... with`, fixed-size lists) are
  clear `WitParseException`s with line/column.
- Model: records/sealed interfaces — `WitDocument` > `WitItem` (PackageHeader /
  PackageBlock / World / InterfaceDef / Use / TypeAlias / RecordDef / VariantDef /
  EnumDef / FlagsDef / ResourceDef / FuncDef / ImportRef / ImportNamed / ExportRef /
  ExportNamed / Include), `WitType` (Prim / Named / ListOf / OptionOf / ResultOf /
  TupleOf / StreamOf / FutureOf / BorrowOf / OwnOf), `WitFunc`, `WitMeta`,
  `WitPackageName`, `WitRef`. Record equality = model equality (the idempotence tests
  rely on it). `Wit` is the static-factory construction DSL (meta-less; construct
  records directly to attach docs/gates) — generated code and future binders build
  models through it.
- `WitParser.parseLocated(source)` -> `WitParseResult` = the document PLUS a
  `WitLocations` (todo 126), the start offset / 1-based line / column of every item.
  The positions live **beside** the model in an `IdentityHashMap`, never on the
  `WitItem` records: the records are pure values and `WitRoundTripTest` pins their
  structural equality (two structurally equal documents must stay `equals`), which a
  position field would break. Lookup is therefore BY IDENTITY — the item must be the
  very instance this parse produced, not a copy. `WitLocations.none()` for a document
  built in memory. This is what lets `wit-export` say `world.wit:5: ...`.
- **`%`-escaping is source syntax, not part of the name** (`WitIdentifiers`, todo 126).
  An identifier colliding with a WIT keyword is written `%type` / `%flags` / `%stream`
  — all three occur in the vendored WASI WIT — but the identifier, and the
  component-model `label` it becomes, is the bare word. So the **model holds the bare
  name**: `WitParser` strips the `%` at every identifier position and `WitPrinter`
  puts it back on exactly the keywords, which keeps the corpus round-trip and the
  `wasm-tools` byte-diff green while every consumer (a name check, an export label, a
  future binder) sees the real name. In `parseType` an escaped word is ALWAYS a
  `Named` type — `%list` is a user type called `list`, never the built-in. Before the
  fix the model carried `%flags` verbatim (it had leaked into the generated
  `WasiWitDefinitions` too), and a world using one lowered to an invalid export label.
- `WitPrinter`, two modes:
  - `printVerbatim(tokens)` — byte-identical reassembly of a lexed source.
  - `print(document)` — **canonical**, byte-compatible with `wasm-tools component wit`
    output: 2-space indent, blank line between interface members EXCEPT consecutive
    `use` clauses (they group), blank line between a world's import and export blocks,
    NO blank between the world and the first package block, TWO blanks between package
    blocks, trailing commas on all members, docs as `///` lines, gates as
    `@name(key = value)`.

Tests (`WitRoundTripTest`): the whole in-repo corpus — `src/wasm-component/deps/**`
(34 files incl. the hard ones, `wasi:filesystem/types` and `wasi:http/types`),
`uni*.wit`, `examples/count-vowels/count_vowels_component.wit`, and the 7 fixtures —
(1) verbatim round-trips byte-identically and parses, (2) canonical print reproduces
the wasm-tools-formatted fixtures byte-for-byte, (3) on the hand-written files the
canonical print re-parses to the identical model and is print-stable.

## `--emit-wit` emission after the migration (todo 125 refactor)

`WitEmitter` no longer splices text into classpath templates. The fixed per-variant
document (world imports + fixed export + reachability-pruned package definitions) is
**`WasiWitDefinitions`** — Java code building the model through `Wit`, GENERATED by
`WasiWitDefinitionsGenerator` (test sources) from per-variant fixtures under
`src/test/resources/am/ik/rontolisp/codegen/wasm/component/wit/`; byte-identical
package blocks shared by several variants are emitted once (the 0.3 GC trio shares
cli/clocks/filesystem/random), per-variant projections (wasm-tools prunes each
package to what the component reaches) get suffixed methods. `WitEmitter`
appends each `rontolisp:wasm-export` Decl as a typed `export` world item and prints
canonically. Oracles, strongest first:

- `WitOracleE2eTest` — live byte-diff against `wasm-tools component wit` (local only).
- `WasiWitDefinitionsTest` — always-on byte pin of every variant against its fixture.
- `WitEmitterTest` — line pins of the export shapes.
- New in the round-trip suite: the emitted WIT re-parses through our own parser.

The old `component/wit/*.wit` main resources are DELETED (the resource-config
`WitEmitter` entry too); the fixtures are test resources. Regen is now three-phase
(blobs -> jar -> fixtures -> generator); see `src/wasm-component/README.md`. The
deliberate deviation is kept: the http-server fixture restores the handler
interface's `use types.{request, response, error-code};` clause that `wasm-tools`
drops (its own output does not re-parse); ours must stay consumable.

## Variant renaming (same change, user-requested)

The blob/variant vocabulary was rebuilt around what each surface IS (the old names
grew by accretion: "http" meant fetch because fetch came first, then the server got
"serve"):

| old | new |
|---|---|
| `http` | `http-client` (fetch, `wasi:http/outgoing-handler`) |
| `serve` | `http-server` (`rontolisp:http-handler`, `wasi:http/incoming-handler`) |
| `serve-http` | `http-server-client` |
| `sock` | `sockets` |

Applied to `WitEmitter.VARIANT_*`, fixture names, ALL blob artifacts
(`import-block-http-server.bin`, `adapter-http-server-p1.wasm`,
`mem-http-client.wasm`, ...), the `src/wasm-component` sources
(`core-http-server.wat`, `core-sockets.wat`, ...), regen scripts and docs.
**Mode vocabulary is unchanged**: the `serve` boolean on `WasmLispCompiler`,
`WasmServeComponentBuilder`, `buildServe` still name the `wasmtime serve` MODE.
(HISTORICAL NOTE: the `http-client` variant died when fetch became a Lisp
library over user imports, and the `http-server-client` variant died with the
todo-002 wasi:http@0.3 cutover -- serve and serve+fetch are ONE `http-server`
variant now; mode `serve` -> variant `http-server`.)

## The settled type mapping (`compiler/WitTypeMapper`, todo 124's table)

`WitTypeMapper.rep(WitType)` / `repOfDefinition(WitItem)` — the ONE vocabulary the
future binders consult; no codegen. Pinned by `WitTypeMapperTest` (reversing a cell
after 126+ ship = breaking change). Highlights: scalars per the todo-124 table
(`s64`/`u64` bignum-safe), `record`=keyword plist, `enum`=keyword, `variant`=tagged
list, `flags`=keyword list, `option<T>`=value-or-nil, `tuple`=list,
`resource`/`borrow`/`own`=opaque integer handle (one handle space,
`.kb/read-load-streams.md`), `stream`/`future`=UNSUPPORTED until language async.

### Decision record: `result<T, E>` = option (c) — condition on EVERY backend

Chosen 2026-07-13 (user decision after weighing all three todo-124 options):

- The ok arm is the return value; a payload-less ok arm (`result` / `result<_, E>`)
  returns `nil`. The error arm **signals a condition** carrying the mapped `E`
  payload, catchable with `handler-case` — on every backend, as the contract.
- The WASM catch mechanism this presupposed landed with todo-129
  (2026-07-14): the wasm-GC backends compile `handler-case` through the
  exception-handling proposal (`.kb/error-handling.md`, "WASM (todo-129)"), so
  the error arm is catchable on every backend except `--no-gc` and the
  todo-128 prerequisite is SATISFIED. Programs that catch need
  `wasmtime -W exceptions=y` (37+).
- Why not (a) multiple values `(values ok err)`: it is implementable today with zero
  new machinery (a `(values ...)` tail in the synthesized stub rides the `%mv-spill`
  channel, `.kb/multiple-values.md`) and would even give wasm-GC recoverability now —
  but a bare call silently swallows the error arm (nil-ok vs error is ambiguous, e.g.
  `kv:get`'s `result<option<list<u8>>, error>`), it bypasses the todo-116 condition
  system the rest of the tree uses (`usocket:socket-error`), `--no-gc` has no spill
  channel at all, and migrating (a)->(c) later breaks user code. (b)->(c) and
  (c)-now differ only in documentation stance; (a) is the one trap.
- Why the divergence is safe: user code written for (c) on interpreter/JVM uses
  `handler-case`, which today does not COMPILE on WASM — so there is no working WASM
  program whose behavior changes when catching lands; it starts compiling, unchanged.

### Decision record: `list<u8>` = string

A rontolisp string carrying the bytes one-per-char — the existing fetch/socket
marshalling convention — NOT a list of ints (consing per byte would make large
wasi:keyvalue values pathological). Distinct from WIT `string` (canonical-ABI UTF-8).
An explicit bytes<->string helper can be added later if a real divergence shows up.

## What todo 125 deliberately did NOT do

No Lisp-facing surface (that arrived with todo 126 below, for the export side only).
Zero user-visible behavior change — emitted `.wasm` and `.wit` bytes are identical
before/after (pinned by `WitOracleE2eTest` + the integration suite).

## `rontolisp:wit-export` — implementing a world (todo 126, 2026-07-14)

Step 2 of todo-124. "This program implements this WIT world": the `.wit` becomes the
single source of truth for the export surface, so the `:params`/`:returns` lists stop
being maintained next to a separately-generated `.wit` that can drift until
`wasmtime --invoke` fails at run time.

```lisp
(defun count-vowels (text) ...)          ; WIT: count-vowels: func(text: string) -> s32

(rontolisp:wit-export "count_vowels_component.wit" :world root)
```

The path resolves against the source file's directory (`SourceLoader.resolve`, like
`load`); `:world` takes a bare symbol or a string and may be omitted when the file
declares exactly one world.

### It LOWERS into `wasm-export` — there is no new export path

`compiler/WitExportDirective.lower()` returns, per world export and in world order, one
`(rontolisp:wasm-export 'name :params '(...) :param-names '(...) :returns ... [:async t])`
form — exactly what a hand-written export list would have carried. The backends never
see `wit-export`; `WasmComponentBuilder` / `NoGcWasmComponentBuilder` / `WasmExportCompiler`
gained no export path. **The emitted component is byte-identical to the hand-written
equivalent** on both wasm-GC `--component` and `--no-gc --component` (verified by hand;
count-vowels("Hello, World!") = 3 under wasmtime 46 on both). That byte-identity is the
whole point of the design: a front-end for machinery that already exists.

`WitExportDirective` itself does no I/O and no codegen — the caller hands it the WIT
*text* (so the interpreter and the browser playground can source it their own way) and
splices the returned forms. Its `Backend` enum (`WASM_GC` / `WASM_NO_GC` / `OTHER`)
selects the backend-specific rules only.

Three call sites:

- **Compile path**: `eval/WitExportInliner`, run in `RontoLispCli.compileToFile` between
  the library splices and `LibraryDefunPruner` — after `LoadInliner` /
  `UserMacroExpander` (so every `defun`, including a load-spliced or macro-produced one,
  is a literal top-level form and can be checked) and before the pruner (so the
  synthesized `wasm-export` directives still count as pruning roots). Backend from
  `RontoLispCli.witBackend` (`.wasm` + `--no-gc` -> `WASM_NO_GC`, `.wasm` -> `WASM_GC`,
  else `OTHER`).
- **Browser playground**: `RontoPlayground.frontend` runs the SAME inliner. It lives in
  `eval` (not `cli`) and reads the WIT through an injected `SourceLoader` precisely so
  that it can: the playground has no filesystem and backs the loader with its map of
  uploaded files, the same way `(load "x.lisp")` works there. Without this the compile
  buttons met the directive itself and died with `Cannot compile: rontolisp:wit-export`
  while the REPL on the same page happily checked it — do not regress that asymmetry.
  (`.wit` is in the upload picker's `accept` list.)
- **Interpreter**: `LispEvaluator.evalWitExport` — a SPECIAL FORM, so it runs the same
  contract check against the functions defined **so far**. Put the directive at the END
  of the file (where the scaffold puts it). It exports nothing and returns `nil`;
  `Backend.OTHER`, so the WASM-only rules (`s64`, `async`) are not imposed. A plain
  `rontolisp prog.lisp` therefore already catches a drifted world.

### The world is the AUTHORITATIVE export list (two strict rules)

- A hand-written `rontolisp:wasm-export` in a program that ALSO has a `wit-export` is a
  **compile error** (`WitExportInliner`). Declare the export in the world, or drop the
  `wit-export` directive. There is no merge — the component's exports and the `.wit` can
  never disagree.
- `rontolisp:http-handler` + `wit-export` is a **compile error** (`RontoLispCli`, serve
  mode): a serve-mode component's only export is `wasi:http/handler@0.3.0`, so a world
  of function exports could not be honored. Say so rather than drop it.

### The contract checks

Every one names the WIT file and line (`WitLocations`), e.g.
`count-vowels.wit:5: export 'count-vowels' has no matching (defun count-vowels ...) in the program`:

- no world / several worlds and no `:world` / `:world` names a world the file lacks; a
  world with no exports at all
- an export the world declares but the program does not define
- **arity** mismatch; an exported `defun` with `&optional` / `&rest` / `&key` / ... — an
  exported function takes REQUIRED parameters only
- a duplicate export
- an export name that is not a component-model `label` (lower-kebab-case), or the
  reserved `run` (the component's `wasi:cli/run` entry point) — the WIT makes the
  *correct* name authoritative, instead of the ad-hoc `:as` fix-up
- an export that names an interface (`export foo/bar;` or an inline interface):
  `wit-export` implements plain function exports only (a program's
  `wasi:http/handler@0.3.0` export comes from `rontolisp:http-handler`)
- an inline `import name: func(...)` in the world — rejected, not silently dropped; it is
  the one import shape `rontolisp:wit-import` cannot bind either (it is not an interface),
  and the message says to declare the interface to call instead
- a WIT type outside the export boundary's subset (`s32` / `s64` / `f64` / `bool` /
  `string`). The error names the type's SETTLED house representation via
  `WitTypeMapper.rep` and says the component boundary cannot marshal it yet (todo-128)
  — "your `record` is a keyword plist, marshalling it is not built yet", not a bare
  refusal. The message itself carries no `.todo` pointer: a todo file is deleted the
  moment the work lands, so a user-facing string must never name one
- `s64` on the wasm-GC backend (its integers are `i31ref`; only `--no-gc --component`
  lifts an `s64`) — the pre-existing rule, now reported against the WIT line that asked
  for it
- an `async func` under `--no-gc --component` (the adapter-free reactor has no async
  machinery). Conversely, an `async func` in the world SETS `:async t` on the lowered
  export: the stackful-async lift an I/O-bearing export needs is now **stated by the
  WIT** instead of remembered by hand, so "cannot block a synchronous task" stops being
  a runtime trap you discover late (`.kb/wasi-component.md`, Tier 3).

A world's `import` items and type definitions are IGNORED by the check (a component's
imports come from the fixed WASI adapter surface, and the type definitions only spell
out the signatures). Only the export side is a contract today.

### `:param-names` on `wasm-export`, and what it makes true

`rontolisp:wasm-export` gained `:param-names '(text)` (a quoted list of symbols or
strings, each a component-model label; must match the `:params` arity). It fills
`WasmExportCompiler.Decl.paramNames`, **defaulting to `p0`, `p1`, ...** exactly as
before. `WasmComponentBuilder.FuncExport` and `NoGcWasmComponentBuilder` now encode
those labels into the lifted component function type instead of synthesizing `p<i>`
themselves, and `WitEmitter` prints them.

Consequence: a world implemented with `wit-export` (which fills `:param-names` from the
WIT) **round-trips through `--emit-wit` with its own parameter names**. Because the default is
unchanged, every pre-existing artifact — and every `--emit-wit` output of a program that uses
neither `wit-export` nor `:param-names` — is byte-identical. Ignored on the core-export
(Preview 1 / `--no-wasi`) path, where a WASM parameter has no name.

### The `--emit-wit` round-trip: the export side is a FIXPOINT, not a check

Do NOT describe (or "harden", or add a test for) `--emit-wit` on a `wit-export` program
as a consistency check of the export side. **It cannot fail.** The pipeline is

```
world export item -> WitExportDirective.lower() -> wasm-export Decl
                  -> component function type -> WitEmitter -> emitted world export item
```

and the accepted boundary type set (`s32`/`s64`/`f64`/`bool`/`string`, plus `:async t`
<-> `async func`, plus `:param-names` since todo 126) is EXACTLY the set that maps
one-to-one in both directions. Names, parameter names, types and async-ness therefore
come back out identical by construction — an export line that disagreed with the input
world would be a bug in `WitTypeMapper`/`WitEmitter`, never a drift in the user's
program. Re-emitting and diffing (as `examples/count-vowels/README.md` does with
`git diff --exit-code`) is a **regression test on OUR type mapping**; it is worth having
and it is worth saying so honestly, but it is not checking the user. The thing that
catches a drifted program is the `wit-export` contract check itself, which runs on every
backend (interpreter included).

What `--emit-wit` uniquely and genuinely reports is the **IMPORT side**, which
`wit-export` never looks at (a component's WASI surface is the fixed adapter blob's, per
variant). Measured on the greeter example: a **6-line** hand-written world compiles to a
component whose real type is **149 lines** — 10 `wasi:*` imports + `export wasi:cli/run`
around the single declared export; adding a `rontolisp:fetch` call makes it **216 lines**
/ 12 imports (+ `wasi:http/{types,client}@0.3.0`),
and `rontolisp:tcp-*` adds `wasi:sockets`. Short of `wasm-tools`, `--emit-wit` is the
only way to see it, and it is what a host / `jco` must consume. For a program WITHOUT a
world (hand-written `wasm-export`, or an `:s-expr` export, which has no WIT spelling)
`--emit-wit` remains the sole generator, as before todo 126.

Since component imports landed (todo 128), the import side is REAL: a `wit-import` under
`--component` becomes a component-level instance import, and `--emit-wit` prints it into
the world (pruned to the bound members) — byte-diffed against `wasm-tools component wit`
by the oracle. A world's `import` ITEMS are still not the authoritative import list (the
program declares what it calls with a separate `wit-import` directive); `--emit-wit` now
reports both sides of the component's real type.

The world's payoff on the **browser** is real TODAY, not a promise: a
`--no-gc --component` world has no imports, so `jco transpile` turns it into a single
self-contained ESM with zero `import` statements that a page runs with no shim / import
map / polyfill — the world's export names (kebab-case, camelCased by jco) ARE the
JavaScript API. Measured on Chrome 149; the GC path's browser limits and the two upstream
jco gaps are in `.kb/wasi-component.md` ("Components in a browser (jco)").

Two deliberate differences from the input file, in any case:

1. **`///` doc comments are not carried.** A component's *type* does not store them —
   `wasm-tools component wit` cannot recover them either, and `WitOracleE2eTest`
   byte-diffs our `--emit-wit` output against that tool, so carrying them would break the
   oracle. (The docs ARE carried into `--scaffold-wit`'s output as `;;;` comments: that
   path reads the WIT text, not a component.)
2. **The emitted world is always `package root:component; world root`**, whatever the
   input `.wit` called its package and world. That is what a component's type *is*.

### `--scaffold-wit`

`rontolisp --scaffold-wit world.wit [--world N] [-o impl.lisp]` (stdout without `-o`).
It short-circuits in `RontoLispCli.run` before the no-positional-argument REPL fallback,
because it generates a program instead of running one. `cli/WitScaffolder` emits: a
header, then per export a `;;;` block carrying the WIT `///` docs plus a `;;; WIT:`
signature line, a `defun` stub whose parameters are the WIT's own names, and a body
`(error "name is not implemented yet")`; the `wit-export` directive comes LAST, so the
interpreter's check (which sees only what precedes it) passes. The output **compiles
unchanged** — the stubs signal at run time, not at compile time, so a world can be filled
in one export at a time.

### What `wit-export` does NOT do: the import side

A world's `import` items still bind nothing, and the contract check still ignores them.
Since todo 127 a program declares what it CALLS in a separate `rontolisp:wit-import`
directive naming the interface (next section) — the two directives do not talk to each
other. An inline `import name: func(...)` in an implemented world stays an error: it is
the one import shape `wit-import` cannot bind (it is not an interface), so it must not
read as supported. Making a world's import LIST authoritative the way its export list is
needs the component boundary — todo-128 (canon lower, the wasi:keyvalue unblocker) —
and only then does `--emit-wit` become a two-sided check.

### Decision record: an `:s-expr` export has NO WIT spelling — (a) never (todo 130)

**Decision: (a).** `:s-expr` is a rontolisp-private wire format, not a WIT boundary. The
two demos that use it — `examples/browser/minesweeper/minesweeper-wasm.lisp:86-92` (SEVEN
exports, every one taking an `:s-expr` and four returning one: the game state in five of
them, a host-supplied cell ORDER in `place-mines` and mine LAYOUT in `new-game`, because
the entropy-free reactor cannot call `random`) and
`examples/browser/hiragana/recognize.lisp:34` (`recognize(:s-expr) -> :string`, the
576-pixel bitmap as a list) — stay on hand-written `rontolisp:wasm-export`
**permanently**, and that directive is the documented escape hatch for this shape. "Not
supported" is the record; silence was the bug.

`:s-expr` is s-expression TEXT crossing as `(ptr, len)` and parsed by the emitted runtime
reader. No WIT type lowers to it: `designator()` maps WIT `string` to `:string`, a
*different* wire format (an ordinary Lisp string; nothing parses it). So a blind retrofit
does not fail — it **compiles**, and the page breaks at run time.

Worse, the two already collide in the emitted WIT:
`WasmExportCompiler.componentValType()` (`:645-656`, the `:string`/`:s-expr` arm at
`:651`) lifts **both** as component `string`, so an `:s-expr` export under
`--component --emit-wit` prints `export head: func(p0: string) -> string;` — a valid
world which, fed back through `wit-export`, yields `:string` designators and a silently
different ABI. Do not "fix" that by teaching the world about `:s-expr`; fix it by not
putting `:s-expr` in a world.

- **(b) rewrite the data model** remains the honest long-term home (minesweeper's state is
  a plist of ints, the bitmap is 576 numbers; both are a WIT `record` / `list<u8>` that
  `WitTypeMapper` already names). It is a **rewrite of the demos, not a retrofit**, and it
  is NOT unblocked: todo-130 assumed todo-128 was its gate, which is **wrong**. Todo-128
  and todo-133 landed rich types on the **import** side (`canon lower`). The **export**
  boundary still accepts exactly `s32`/`s64`/`f64`/`bool`/`string`
  (`WitExportDirective.designator()`, whose own error says "the component boundary cannot
  marshal it yet"). A rich-type export LIFT is the real gate and does not exist.
- **(c) annotate a WIT `string`** as "s-expression text": rejected on sight — it makes our
  WIT unportable to `wasm-tools` / `wit-bindgen`, which is the whole point of emitting WIT.

Precedent, and why (a) is not a retreat: an earlier round of the import work already
lowered rich P1 types through `:s-expr` (prin1 out, embedded reader back) and it was
REVERTED for the same reason (see "Type tiers, restated" below) — a rontolisp-specific
pseudo-protocol is not a WIT boundary. Rich types need the component boundary; on the
export side, that boundary is not built yet.

### Decision record: `:as` camelCase vs. the kebab label — `wit-export` is component-facing (todo 130)

**Decision: (ii).** `wit-export` grows **no** alias mechanism. It emits no `:as` —
`exportForm()` builds the lowered `wasm-export` from the world's label and `LABEL`
enforces lower-kebab-case, which for a COMPONENT is simply right (jco maps `count-vowels`
-> `countVowels`). Core-module demos that want camelCase JS names keep hand-writing
`rontolisp:wasm-export`. `wit-export` is **component-facing**; `:as` is the core-module
fix-up it replaces, not a gap it must close.

The twelve aliased exports are `webgl-heat3d/heat3d.lisp:242` (`totalHeat`),
`webgl-robot-arm/robot-arm.lisp:939-940` (`setSolver`, `ikError`) and
`webgl-platformer/platformer.lisp:928,932-939` (`setKey`, `getCoins`, `coinTotal`,
`getDeaths`, `getState`, `getTime`, `getPx`, `getPy`, `getPz`).

**The cheap third path was measured, and it works: those demos MAY migrate.** A kebab
export IS callable from JS as `exports["set-key"](...)`, and the tree already does it —
`examples/browser/rainbow/rainbow.html:109` is `exports["rainbow-html"](ptr, bytes.length)`.
So this was never a mechanism gap; it is ~12 lines of `index.html`.

**They are left alone anyway**, deliberately: all three pages call by dot-property
camelCase (`lisp.totalHeat()` at `webgl-heat3d/index.html:372,393`; `lisp.setKey(...)`,
`lisp.getPx()`, `lisp.ikError()` &c), and `webgl-platformer/index.html:375` advertises
`lisp.getPx()` as the DevTools-console handle on the running game. On a raw core module
loaded with `WebAssembly.instantiate` the export name is literal, so migrating buys the
contract check and pays for it in `lisp["get-px"]()` at every call site and in that
affordance. The trade is not worth it while the pages load core modules; if one ever
becomes a component (the `browser/wit-component` route), the world comes with it and the
camelCase names come back from jco for free.

**(i) an alias mechanism is rejected**, not deferred: a `:as`-carrying option or a
WIT-level annotation re-introduces exactly the two-places drift the directive was built to
kill, on the one field the component model says must be canonical. If it is ever wanted,
it is its own todo, not a line in this one.

## `rontolisp:wit-import` — calling a WIT interface (todo 127, 2026-07-14)

Step 3 of todo-124, the mirror of `wit-export`: *this program CALLS this WIT
interface*. One WIT file, a different implementation behind it per backend, zero source
changes — a `wasi:keyvalue` program is developed against an in-memory bucket (a Lisp file
it `require`s, see the provider decision below), swapped onto a real store by binding one
provider, and (once todo-128 lands) compiled to a component that talks to a real host.

```lisp
;; kv.wit: interface store {
;;           open: func(identifier: string) -> result<bucket, error>;
;;           resource bucket { get: ...; set: ...; delete: ...; }
;;         }
(rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)

;; ...and, on the backends that dispatch a provider, WHO implements it -- ordinary user
;; code, ending in (rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'memory-store).
(require :kv-memory "memory-store.lisp")

(let ((b (kv:open "cache")))         ; a resource = an opaque integer handle
  (kv:bucket-set b "hello" "world")  ; a method takes its handle as the FIRST argument
  (print (kv:bucket-get b "hello"))) ; => "world"
```

Options (`compiler/WitImportDirective.parse`; every name may be written as a string or as
a bare symbol in the WIT's own spelling):

| option | meaning |
|---|---|
| (the path) | the WIT file, resolved against the source file's directory (`SourceLoader.resolve`, like `load`) and READ through `SourceLoader` — the playground has no filesystem |
| `:interface` | **required**: `wasi:keyvalue/store@0.2.0`, or the version-less `wasi:keyvalue/store`, or the bare `store`. `WitResolver.findInterface` tries those spellings in that order; an ambiguous one resolves to nothing and the error lists every id the file defines. All three lower to the CANONICAL id, which is what the provider registry is keyed by (see below) |
| `:package kv` | the bindings land in package `kv`: the directive synthesizes `(defpackage kv (:use cl) (:export ...))` and qualifies each binding `kv:member`. Omitted = the current package |
| `:from "module"` | Preview 1 only: the WASM import module. Defaults to the interface's BARE name (`store`, `gl`) |
| `:field-style` | `:camel` (default) or `:kebab` — how a WIT label is spelled as the Preview 1 import FIELD |

### It LOWERS — there is no new call path on any backend

`WitImportDirective.lower()` returns, in WIT order, the forms the directive stands for.
Like `WitExportDirective` it does no I/O and no codegen (the caller hands it the WIT
*text*), and its `Backend` enum is shared with the export side.

| backend | the directive becomes |
|---|---|
| Preview 1 WASM (`-o out.wasm`) | one `(rontolisp:wasm-import 'name :from M :as FIELD :params '(...) :returns T)` per WIT function — literally what a hand-written import block carries |
| interpreter, JVM (`-o Prog.class`) | the `defpackage`, then one ordinary `(defun kv:bucket-get (self key) (rontolisp::%wit-call "wasi:keyvalue/store@0.2.0" "bucket-get" self key))` per WIT function |
| `--component` | a component-model **instance import** of the interface, each bound function `canon lower`ed (`(rontolisp::%component-import ...)`, below) — on every variant, `rontolisp:http-handler` (serve) included |
| `--no-gc` | clear error (`WitImportDirective.lower`) — its MVP module imports nothing |

The Preview 1 output is **measured byte-identical** to the hand-written `wasm-import`
block it lowers to, and identical again under `--optimize` with a never-called import
shaken out — the same "front-end for machinery that already exists" property `wit-export`
has on the export side. The interpreter/JVM binding is an **ordinary defun**, so
`#'kv:bucket-get` / `funcall` / `mapcar` / `eval` work with no extra wiring (the property
todo 127 flagged as the one most likely to regress); `%wit-call` is itself an ordinary
defun, from `wit.lisp` below.

### Where the passes run — and why the IMPORT inliner runs BEFORE `UserMacroExpander`

The asymmetry a future reader will get backwards. Both inliners live in `eval` and read
through `SourceLoader` (CLAUDE.md's rule — the playground has its own front-end and no
filesystem), but they sit on opposite sides of macro expansion:

- **`eval/WitImportInliner` runs straight after `LoadInliner`, BEFORE `UserMacroExpander`**
  (`RontoLispCli.compileToFile`; `RontoPlayground.frontend` runs it first too). It has to:
  the names it binds live in a package the WIT names, so the `(defpackage kv ...)` it
  synthesizes must exist **before anything resolves a `kv:get` call site** — and
  `UserMacroExpander` resolves every top-level form through its own `PackageResolver`,
  where an unknown package is a hard error. It can afford to run that early because it
  needs nothing macro expansion produces: a `wit-import` is checked against a WIT FILE,
  never against the program.
- **`eval/WitExportInliner` runs AFTER `UserMacroExpander`**, for the exactly opposite
  reason: its contract check must see every `defun`, including one a macro or a `load`
  produced.

After the import inliner, `WitLibrary.process` splices the runtime alongside the other
Lisp-source library splices, and `LibraryDefunPruner` runs last (as ever).

Because the directive is replaced **in place**, put a `wit-import` at the TOP of the file
— the opposite end from a `wit-export`, which goes last. On the interpreter both are
special forms evaluated in source order, so the rule is the same there:
`LispEvaluator.evalWitImport` reads the WIT through its `SourceLoader`, lowers with
`Backend.OTHER`, calls `ensureWitLoaded()`, and evaluates each lowered form through
`packageResolver.resolve` so the synthesized `defpackage` registers exactly as a
hand-written one would.

`PackageResolver` exempts a `wit-import`'s arguments from resolution (they are WIT data,
like `wit-export`'s). The names the directive BINDS need no resolution either — it
qualifies them itself.

### The provider: decision record — the ESCAPE HATCH ONLY, and it is a Lisp callable

Todo 127 recommended (c) built-in providers for the WASI interfaces rontolisp already
implements + (a) an escape hatch spelled
`(java:bind-wit "iface" (java:new "com.example.RedisStore" url))`. Shipped: **the escape
hatch, as `(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-dispatch)`, and NO
built-in provider for anything.** Two separate decisions; keep them apart.

**1. The escape hatch is a Lisp callable, not `java:bind-wit`.** A provider is an ordinary
Lisp callable taking the bound function's Lisp member name (a STRING: `"open"`,
`"bucket-get"`) followed by that function's arguments. Why (a) was dropped:

- `java:` interop is JVM + interpreter only, and **the native binary cannot INTERPRET it**
  (no reflection metadata, `.kb/java-interop.md`) — the escape hatch would be dead in the
  configuration most users actually run.
- it would drag the hand-synced `JavaInterop`/`JavaBridgeTemplate` pair (overload
  selection by cost, sequence marshalling, callback proxying) into the WIT boundary for a
  dispatch that is one `apply`.
- a Lisp callable is the same value on every backend that has a provider at all, so
  `wit-provide` needs no `#+jvm` guard around it.

A user who wants a Java-backed store loses nothing: they write a few lines of Lisp over
the existing `java:` interop — a function dispatching on the member string into
`java:call` — and hand THAT to `wit-provide`. `examples/wit/keyvalue/java-store.lisp` is
exactly that, ~35 lines over a `java.util.LinkedHashMap`. (b), a `ServiceLoader` SPI,
stays rejected: invisible magic plus a native-image reflection problem.

**2. NO built-in provider ships — (c) is REVERSED (user decision, 2026-07-14).** An
earlier round of this work did ship an in-memory `wasi:keyvalue/store@0.2.0` provider
inside `wit.lisp`; it was **removed**. The rule now:

> rontolisp's core knows the provider **mechanism**. It does not know what
> `wasi:keyvalue` is, and it ships no provider for any concrete interface.

The reasoning, so nobody re-adds one:

- a built-in store hardcodes ONE third-party spec's interface id, its member names AND its
  version (`wasi:keyvalue/store@0.2.0`) into the core of a Lisp. It is version-pinned and
  name-pinned to something we do not own, and it privileges one spec over every other.
- it contradicts the bet of todo-124: **a new host interface should cost a `.wit` file,
  not core code.** An implementation of a WIT interface is ordinary USER code — that is
  what the mechanism is FOR, and shipping one implementation in the core says the opposite.
- the cost is honest and small: a `wasi:keyvalue` program cannot run bare, it needs one
  `(require :kv-memory "memory-store.lisp")` of a ~40-line portable Lisp store. That store
  is `examples/wit/keyvalue/memory-store.lisp`, and having it be READABLE user code is
  worth more than having it be invisible.

So `wit.lisp` (below) is now only the registry + `wit-provide` + `%wit-call` + the
`wit-error` condition — ~50 lines. Calling a wit-imported function with no provider bound
signals `rontolisp:wit-error`: *"No provider is bound for the WIT interface `<id>` — bind
one with rontolisp:wit-provide"*. `wit-provide` REPLACES any provider (it is a hash-table
put), which is how `examples/wit/keyvalue` swaps its memory store for its Java one by
loading a second file.

On the WASM backends the host IS the provider, so a top-level `rontolisp:wit-provide` is
**dropped** by `WitImportInliner` — inert, not a compile error. One source runs
everywhere.

### The runtime is `wit.lisp` — so neither backend gained a codegen case

`eval/WitLibrary` + `src/main/resources/am/ik/rontolisp/eval/wit.lisp`, the `usocket.lisp`
pattern. Everything the runtime does is expressible in core primitives — a hash table of
callables, `apply`, a `define-condition` — so ONE implementation in Lisp serves both
backends that need it and no `Jvm*Compiler`/`Wasm*Compiler` case exists at all:

- `rontolisp::*wit-providers*` — an `equal` hash table, interface id -> callable. The key
  is the interface's CANONICAL id (`WitResolver.canonicalId`), never the reference as the
  user spelled it: `findInterface` accepts three spellings of one interface, so lowering
  the spelling as written gave one interface three registry keys — and a `wit-provide`
  writes only one of them, so `:interface store` compiled clean and then died at runtime
  with "No provider is bound" against a store the program had plainly bound.
  `WitImportDirective.lower` canonicalizes; a `wit-provide` key is therefore always the
  fully-qualified id, whatever the `:interface` spelling was.
- `rontolisp:wit-provide` — `(setf (gethash interface *wit-providers*) provider)`.
- `rontolisp::%wit-call` — `(apply provider member args)`; an interface with no provider
  bound signals `rontolisp:wit-error` telling you to call `wit-provide`.
- `rontolisp:wit-error` (a `define-condition` over the CLOS subset,
  `.kb/error-handling.md`) + its `rontolisp:wit-error-payload` reader — what the error arm
  of a WIT `result` signals.

That is ALL of it — no provider for any concrete interface lives here (the decision record
above).

Splice policy, exactly like the other Lisp-source libraries:

- **compile path**: `WitLibrary.process` prepends the forms when the program REFERENCES
  one of the runtime's names (`%wit-call` / `wit-provide` / `wit-error` /
  `wit-error-payload`) — which is precisely when `WitImportInliner` lowered a `wit-import`
  for the interpreter/JVM boundary, or the program binds a provider itself. It is
  idempotent (a program that already defines `%wit-call` is left alone). A WASM program
  references none of those names — its bindings ARE `wasm-import` directives — so its
  output stays byte-identical to a build that never knew the library.
- **interpreter**: lazy, `ensureWitLoaded()` — on the `wit-import` special form, and on the
  first resolution of one of the runtime's own names, so a program may bind a provider
  BEFORE the directive that uses it.
- `wit.lisp` is **not in `LibraryDefunPruner`'s prunable set**, like `usocket`
  (`.kb/library-defun-pruning.md`): the whole runtime is two defuns, a defvar and a
  condition whose surface is reached indirectly — the provider comes out of a hash table
  through `apply`,
  its members are dispatched by STRING, and the condition's reader is named only inside its
  own `:report` — so a textual reachability shake could only go wrong here and has nothing
  to win. Spliced, it survives whole.

`(rontolisp:list-functions :rontolisp)` therefore grew two names, `wit-provide` and
`wit-error-payload` (`wit-error` is a condition, not a function) — pinned by `ci-spec.yaml`
and the `list-functions` tests on every backend.

### Name mapping (user-facing, pinned)

`WitImportDirective.memberName`:

| WIT | Lisp |
|---|---|
| interface func `create-shader` | `create-shader` (`gl:create-shader` with `:package gl`) |
| `resource bucket { get: func(...) }` | `bucket-get`, handle FIRST: `(kv:bucket-get b "k")` |
| `resource bucket { constructor(...) }` | `bucket-new` |
| `resource bucket { f: static func(...) }` | `bucket-f` |
| `resource bucket` — its RELEASE, which WIT declares no func for | `bucket-drop`, handle its only argument — bound only when the program NAMES it ("Resource drops" below) |

- The resource prefix is what keeps the flat, Lisp-2 function namespace unambiguous when
  two resources declare the same method name.
- WIT parameter names become the Lisp lambda list **verbatim**; a resource method gets a
  leading `self` (the receiver the WIT model leaves implicit). A method whose WIT signature
  already declares a parameter called `self` is a clear error naming the line.
- A constructor's result is its resource (`:int`) — also implicit in the model.
- An interface that binds the same member name twice is an error naming the line.
- The Preview 1 import FIELD is `:field-style` applied to the label: `create-shader` ->
  `createShader`. **`:camel` is the default** because it is the JavaScript convention, what
  `jco` emits when it transpiles a component, and what the browser demos' hand-written
  import objects already spell (`.kb/wasm-import.md`). `:kebab` keeps the label verbatim
  for a host that wants it.

### Two type tiers, and why there are two

`WitTypeMapper` says what a WIT type IS in rontolisp; what a BOUNDARY can carry is a
second question, and the two backends answer it differently. `WitImportDirective` is where
a WIT type is judged, and its `wasm` flag is the whole difference:

- **Interpreter / JVM — everything except `stream`/`future`.** The boundary is an ordinary
  Lisp call: the synthesized defun hands its arguments to `%wit-call`, which `apply`s the
  provider. Nothing is marshalled, so every settled representation crosses by construction
  — `record` = keyword plist, `variant` = tagged list, `enum` = keyword, `flags` = keyword
  list, `option<T>` = value-or-nil, `tuple` = list, `list<u8>` = byte string, `s64` =
  bignum-safe int. The type designator is computed and then thrown away; only
  `Rep.UNSUPPORTED` (`stream`/`future`, which have no rontolisp value on ANY backend until
  language-level async) is refused.
- **Preview 1 WASM — only the flat set `rontolisp:wasm-import` can carry.** `INT`/`HANDLE`
  -> `:int`, `FLOAT` -> `:float`, `BOOLEAN` -> `:bool`, `STRING`/`BYTE_STRING` ->
  `:string`, no result -> `:void`. Everything else — `s64`/`u64`, `char`, `list<T>`,
  `tuple`, `option`, `result`, `record`, `variant`, `enum`, `flags` — is a compile error
  naming the WIT file and LINE, and the message says so honestly: the representation IS
  settled and the other two backends bind it today; it is the WASM import boundary that
  cannot marshal it yet.

Say the consequence out loud: `wasi:keyvalue/store` itself does **not** cross the Preview 1
boundary (`open` returns `result<bucket, error>`, `bucket.get` returns
`result<option<list<u8>>, error>`). kv is an interpreter/JVM story until todo-128 lifts
it onto the canonical ABI. The interfaces that DO cross Preview 1 today are the flat,
host-shaped ones — WebGL, see the spike below.

`result<T, E>` on the interpreter/JVM: **nothing in the lowering inspects the return
value.** The ok arm is simply what the provider returned; the error arm is **the PROVIDER**
calling `(error 'rontolisp:wit-error :payload ...)` with the mapped `E` (a `variant` = a
tagged list, so `wasi:keyvalue`'s payload-less `no-such-store` arm is the keyword
`:no-such-store`). Signaling is a provider-side obligation that no one enforces — which is
exactly the settled mapping above (a condition on every backend), and what both stores in
`examples/wit/keyvalue` do on a bad handle. `handler-case` on `rontolisp:wit-error` plus
`wit-error-payload` is the caller's half of it.

### Resource handles: allocated BY THE PROVIDER, and not in the stream handle space

A WIT `resource` is an opaque integer (`Rep.HANDLE`). Who allocates it is **the provider** —
the host on Preview 1 (the WebGL demos' handle-table JS bindings are exactly this shape),
the Lisp callable on the interpreter/JVM. `bucket-new` / `open` returns one; each method
takes it as the leading `self`. Nothing in rontolisp interprets the integer, so any counter
a provider likes will do (`examples/wit/keyvalue/memory-store.lisp` counts from 1, its Java
sibling from 500 — and neither number means anything).

Be honest about what this is NOT: it is **not** the shared stream/socket handle space
todo 127 sketched (`.kb/read-load-streams.md`), and **`cl:close` does not apply to a WIT
resource**. A resource is released by its own interface's `drop`, which `wasi:keyvalue`'s
store does not even expose — there is nothing for `close` to mean here, and a provider's
handles are its own private numbering, not a slot in a rontolisp table. The shared space
becomes relevant only when the component ABI needs it (todo-128, where the handles are
the host's anyway).

### New in `am.ik.wit`: `WitResolver`

The parser's model is deliberately syntactic and lossless: a `use` clause is a
`WitItem.Use` record and a type reference is a `WitType.Named(name)` with no link to its
definition. That is right for a round-tripping printer and useless for a binder — you
cannot classify a type you have not resolved. `WitResolver` is that missing link
(language-independent, no rontolisp imports, like the rest of the library):

- `findInterface(reference)` — indexed over package headers AND `package foo:bar { }`
  blocks; accepts the fully-qualified id, the version-less id, or the bare name.
- `resolveType(scope, name)` — the interface's own definitions first, then transitively
  through its `use` clauses, following aliases, cycle-guarded.
- `functions(iface)` (static) — the interface's freestanding funcs plus every resource's
  constructor / methods / statics, in document order, each tagged with its owning resource.

### The `gl.lisp` spike (the todo asked for it; the answer is "yes, and the WIT names win")

Todo 127's definition of done demanded a spike on
`examples/browser/webgl-common/gl.lisp` (30 `wasm-import`s: 29 into module `gl` plus the
one `ui`-module `fail`) — explicitly NOT a migration. Result:

- **Yes on the mechanism.** A hand-written `local:webgl/gl.wit` reproduces the Preview 1
  boundary **byte-for-byte** (name the interface `gl` and `:from` needs no override;
  `:field-style :camel` regenerates `createShader` and friends exactly), and `--optimize`
  still shakes the imports a demo never calls — so declaring the whole WebGL2 union stays
  free, which is the entire reason gl.lisp exists. todo-124's follow-on prize (one WIT
  describing the browser boundary) is reachable.
- **All but four of them are an exact kebab -> camel match** (`create-shader` ->
  `createShader`, ... , and `ui`'s `fail`), so they migrate untouched.
- **The four that are not are a RENAME, not a case problem.** They are the places gl.lisp
  chose different WORDS on the Lisp side: `shader-compiled-p`/`getShaderParameter`,
  `shader-info-log`/`getShaderInfoLog`, `program-linked-p`/`getProgramParameter`,
  `program-info-log`/`getProgramInfoLog`. `wasm-import` lets the two names differ
  (`:as "getShaderParameter"` beside the Lisp name `shader-compiled-p`); a **WIT label is
  ONE name** that becomes both the Lisp name and (camelCased) the host field, so it cannot
  serve both.

**USER DECISION (2026-07-14): the WIT-generated names win, and `wit-import` does NOT grow a
per-function alias option.** An alias would restore exactly the two-places-to-maintain
drift the directive exists to kill — the same argument that makes a `wit-export` world the
authoritative export list. So the four call sites get renamed instead
(`get-shader-parameter` &c).

### The migration (todo 132, done 2026-07-17)

`examples/browser/webgl-common/gl.wit` is checked in and gl.lisp binds it with TWO
directives — `local:webgl/gl` (the 29 WebGL2 entries) and `local:webgl/ui` (`fail`), the
module split the spike predicted, since one directive binds one interface into one module.
Neither names `:package`: they bind into the CURRENT package, so the bindings land in the
hand-written `(defpackage gl ...)` beside the enum constants and the two shader helpers,
which no directive knows about. **All six demos' modules are byte-identical to their
pre-migration builds** (measured; the spike held). Notes the spike did not have:

- **A `type shader = s32` alias is free and worth it.** GL objects cross as named aliases
  (`shader`, `program`, `buffer`, `vertex-array`, `uniform-location`) that resolve to plain
  s32, so the lowering is untouched — but the NAME is the only thing that distinguishes a
  table handle from an integer value, which is what lets the JS import object be generated
  rather than hand-written.
- **The prize landed: `gl-imports.js` is GENERATED from gl.wit** and each page imports it
  instead of hand-writing the WebGL2 half of its import object.
  `GlImportObjectTest` regenerates + pins it, and reuses
  `WitImportDirective.FieldStyle.CAMEL`, so a page can only be handed the field names the
  lowering actually imports. This is the only part of the migration that buys something a
  WIT-free build cannot have; the rest is a byte-identical rewrite.
- **`load` lost the base directory, and that was a real bug.** A `wit-import` inside a
  library resolves its `.wit` against the file that WROTE it, like `load` — but the splice
  flattens the program, so `WitImportInliner` had only the ENTRY's directory left and
  looked for gl.wit beside each demo. `LoadInliner` now rebases a spliced directive's path
  (`rebaseWitImport`, pinned in `LoadInlinerTest`); a directive in the entry source is
  passed through untouched. gl.lisp is the first user library to carry a `wit-import`, so
  it was the first to hit this; `http.lisp` never did because `HttpLibrary` lowers its
  directives itself off the classpath.

## Component imports — `canon lower` (todo 128, 2026-07-14)

Step 4 of todo-124, and the payoff: **a `--component` build is no longer import-locked
to the fixed WASI blob surface.** A `rontolisp:wit-import` under `--component` becomes a
real component-model instance import whose functions are `canon lower`ed into the core
module — so the provider is the HOST, and the component composes (`wac plug`) with anyone
who exports the interface. `examples/wit/keyvalue/page-hits.lisp` runs against
**wasmtime's own `wasi:keyvalue` implementation** (`-S keyvalue=y`), printing exactly what
the interpreter's Lisp store and the JVM's `java.util.LinkedHashMap` store print.

`rontolisp:fetch` AND `rontolisp:http-handler` are now both consumers of this path:
both are ONE Lisp-source library (`http.lisp`, spliced by `eval/HttpLibrary` following
the reachable half) over a wit-imported `wasi:http@0.3.0` surface — fetch calls
`wasi:http/client.send` (an async-lowered `async func` member), serve implements
`wasi:http/handler.handle` (a stackful async lift delivering its result via `canon
task.return`), and both drive `wasi:http/types`' stream/future bodies through the
alias-derived built-ins. In asyncMode the `<alias>-read` of a stream alias answers a
chunk the host has IN FLIGHT as a **pending future** (registered with the module
scheduler; `.kb/async-await.md`) — so user code reads a stream inside an async
function and awaits the result (an immediate chunk passes through `await`); the
write-side built-ins keep the blocking waitable-set park. No WAT http adapter
remains on any path (the serve builder
lowers http.lisp's imports FROM the block and lifts the core's `handle` wasm-export
directly). That is the **self-hosting proof of the whole IDL bet** — the core HTTP
built-ins re-implemented over the same WIT pipeline any user interface arrives
through, so a new host interface costs a `.wit` file rather than core code. Details in
`.kb/fetch-http.md`.

### The reference probe (do this before touching the encoders again)

Everything here was derived from, and validated against, a hand-built probe in a
scratchpad, NOT from reading the spec: a WAT core module with hand-computed flat
signatures + `wasm-tools component embed/new` + `wasmtime -S keyvalue=y`. A checksum over
all six `wasi:keyvalue` functions came back exactly right, which is what pinned the
flattening and every load offset. The golden bytes of the resulting instance type are
`ComponentWriterTest.instanceTypeMatchesWasmToolsReference` (393 bytes); the layout facts
are `WitCanonicalAbiTest`. Two things that probe taught, which no document says:

- wasmtime's in-memory keyvalue provider recognizes **only the empty store identifier**
  (`open ""`); anything else is `no-such-store`. Both Lisp stores in the example follow
  that rule, which is why all three backends print the same `no-such-store` line.
- a **forged handle traps at the boundary** (`unknown handle index`) before it ever
  reaches the provider — uncatchable. So a program must not stunt with a made-up handle;
  the example demonstrates the error arm with a bad store identifier instead.

### The pieces

- **`compiler/WitImportDirective`** gained the `WASM_COMPONENT` backend (a new
  `WitExportDirective.Backend` value; `wit-export` treats it exactly like `WASM_GC`). It
  lowers to an internal `(rontolisp::%component-import "<canonical-iface-id>" "<wit text>"
  ("member" "lisp-name") ...)` form — **the WIT TEXT TRAVELS INSIDE THE FORM**, so the WASM
  compiler reads no files and the browser playground (no filesystem) works by construction.
- **`codegen/wasm/WitCanonicalAbi`** — size / alignment / flattening / despecialization
  over the `am.ik.wit` model (option = variant{none,some}, result = variant{ok,err}, enum =
  payload-less variant, tuple = record). No codegen.
- **`codegen/wasm/WasmComponentImportCompiler`** — the guest-side marshalling, the exact
  `WasmImportCompiler` pattern: one synthetic defun per bound function (so `#'kv:open` /
  `funcall` / `mapcar` / `eval` work), a placeholder call `1<<27 + ordinal` that
  **`WasmImportInjector` renumbers — REUSED UNCHANGED**, and the body deferred until the
  memory-helper indices are known. Params lower to flat values; results lift recursively
  from the return area.
- **`codegen/wasm/WitComponentTypeEncoder`** — the imported instance TYPE. Declaration
  grammar pinned by the probe: a resource is `export "name" (type (sub resource))`, a named
  type is a type decl + an `eq`-bound export of the same name, structural types are
  unexported type decls memoized by shape, and **type decls AND type-bound exports append to
  the instance type's local type index space; function exports do not**.
- **`codegen/wasm/WasmComponentBuilder.appendUserImports`** — type + import + per-function
  alias + `canon lower` (memory 0, realloc = the shared mem module's `cabi_realloc` = core
  func 0, utf8 — exactly when the call touches linear memory) + one synthesized core
  instance per interface, passed as an extra instantiation arg named by the canonical id.
  Every downstream hardcoded index (the `run` alias / lift / export, `appendFuncExports`)
  shifts by the user-import counts. **Zero imports = zero shift = byte-identical**
  (stash-dance proven on base / sockets, with and without a `:string`
  wasm-export — and on both serve variants).
- **`codegen/wasm/WasmServeComponentBuilder`** (todos 134 + 135 + todo-002 Phase 2) —
  ONE `build` over ONE block (`import-block-http-server.bin`, from the 0.3
  `uni-http-server` world; the NARROW/WIDE `ServeBlock` split died with the
  `http-server-client` variant): serve and serve+fetch are the same shape, http.lisp's
  two halves sharing the merged `wasi:http` bindings, and the ONLY extra module is the
  preview1 bridge (`adapter-http-server-p1.wat`, rewritten over the 0.3 service
  interfaces), instantiated BEFORE the rontolisp core to satisfy its
  `wasi_snapshot_preview1` imports. Core instances are mem(0) / bridge-w(1) /
  bridge(2) / one per fixed http iface (via `lowerServeIoFromBlock`, which emits every
  `appendUserImports` member kind — sync decls, async calls, drops, alias built-ins,
  task-returns, waitable builtins — against the block's instances and dedups by field)
  / one per user iface / rontolisp core; the `ServeIo` cursors + `coreInstanceOf` map
  make every downstream index (the handle functype, the async lift, the exported
  instance, `appendUserImports`) relative, so nothing is hardcoded per shape. The
  serve core module exports no `cabi_realloc` (`componentStringAbi` is
  `component && !serve`) and needs none — the lower's realloc is the shared memory
  module's, core func 0, aliased there as everywhere.
  `rejectAdapterImportCollisions` runs against the ADDITIONAL imports.
  **The state a served handler cannot otherwise have**: a
  `wasi:http` host recreates the instance per request, so its globals are not state; a
  store behind a WIT import is (`examples/wit/keyvalue/page-hits-server.lisp`). Whether
  that store SURVIVES is the host's, not ours: wasmtime's `-S keyvalue=y` provider is an
  in-memory store **rebuilt per instance** (measured: a preset `-S
  keyvalue-in-memory-data=k=41` reads back 41 on every request, so a counter answers 42
  forever), while wasmCloud's `wash dev` links an out-of-process provider and the same
  component counts 1, 2, 3.
- **`codegen/wasm/WitImportWorldEmitter`** — the `--emit-wit` import side.

### `result` = the envelope + a Lisp wrapper (NOT a codegen catch)

A result-returning function binds TWO names: the synthetic defun takes the internal raw
name (`kv::%open`) and returns the envelope cons `(:ok . V)` / `(:error . E)`; a generated
public wrapper `(defun kv:open (identifier) (rontolisp::%wit-result (kv::%open identifier)))`
unwraps it. `rontolisp::%wit-result` is a new **`wit.lisp`** defun (added to `WitLibrary`'s
trigger names) that yields the ok value or `(error 'rontolisp:wit-error :payload ...)`.

Why this and not a throw emitted by the marshalling codegen: the error arm then rides the
ORDINARY condition machinery, so EH-mode gating, `handler-case`, the uncaught-trap shape
and the `:report` all come for free and stay identical to the interpreter/JVM. The
codegen never mentions conditions at all.

### Type tiers, restated (the doc pages carry the user-facing version)

| | Preview 1 core import | `--component` |
|---|---|---|
| params | flat set only | **everything except flags and `list<T>`** (`list<u8>` crosses) |
| results | flat set only | **everything except flags** — scalars, bool, string, char, list<T>, list<u8>, tuple, option, result, record, variant, enum, handles |

Preview 1's limit is not laziness: a core import is a bare host function with **no
component type to declare a richer shape with**. An earlier round of this work lowered rich
P1 types through `:s-expr` (prin1 out, embedded reader back) — it was REVERTED: that is a
rontolisp-specific pseudo-protocol, not a WIT boundary, and it would have broken the P1
byte-identity property. The honest line is the one shipped: rich types need the component
boundary. (`flags` is unimplemented in both directions; `stream`/`future` are refused
everywhere, as ever.)

### Member pruning replaces the tree shaker on this path

`--component` skips `WasmTreeShaker` by design, so an unused interface function would
otherwise cost a real import. `WitImportInliner` therefore passes a **member filter** (the
symbol names the program textually references, the `LibraryDefunPruner` convention) and the
directive binds only those; `--no-prune` / `--dynamic` disable it. Measured: a program
calling 2 of `wasi:keyvalue`'s 6 functions imports exactly 2, and the now-unreachable
`key-response` record vanishes from the component's type too.

### Resource drops — `<resource>-drop` (step D of todo 136, 2026-07-14)

A `resource` is released by a canonical built-in, not by a WIT function: nothing in
`WitResolver.functions` yields one, so a program that RECEIVED a handle had no way to give
it back. `WitImportDirective` therefore walks `iface.items()` for `WitItem.ResourceDef`
itself and binds **`<resource>-drop`** (`kv:bucket-drop`, one parameter — the handle),
symmetric with the `<resource>-new` a constructor binds; the synthetic name goes through
the same `allMembers` set, so an interface that really declares a method called `drop` is
still the existing "binds 'x' twice" error. Not a nicety: `wasi:http` makes
`outgoing-body.finish` TRAP unless the child `output-stream` is dropped first
(`types.wit:518-521`), so without drops a Lisp `fetch` could not send a request BODY at
all.

**Reference-gated, on every backend — and that is what buys byte identity.** A drop is not
a WIT function, so it is deliberately OUTSIDE the "Preview 1 binds every function"
convention: `WitImportInliner` passes `referencedNames(program)` as a **drop filter**
alongside the component `memberFilter` (`WitImportDirective.lower`'s 6-arg overload), so a
program that never writes a `-drop` name emits nothing new. Verified: a 0-import component,
the keyvalue component, a serve component and the Lisp-fetch component all hash the same
before and after. `--no-prune` / `--dynamic` bind them all; so does the interpreter
(`LispEvaluator.evalWitImport` passes a null drop filter — it produces no artifact to keep
identical, and a program may reach a drop through `funcall` / `eval`).

Per backend:

| backend | `(kv:bucket-drop b)` |
|---|---|
| interpreter / JVM | the ordinary `providerDefun` — the provider gets the member string `"bucket-drop"`. The core does NOT decide what a drop MEANS; the provider does (a Java store closes a connection, a Lisp store forgets a handle, a provider with nothing to release answers nil) |
| Preview 1 WASM | a **no-op defun** `(defun kv:bucket-drop (self) self nil)` and **NO `wasm-import`**: importing a `[resource-drop]` field would invent a host function the interface never declared, breaking the byte-identity-with-a-hand-written-import-block property and the browser demos' hand-written JS import objects. A P1 handle is an opaque integer the host handed over; the guest holds nothing |
| `--component` | core side = an ORDINARY core import (module = the canonical iface id, field = `"[resource-drop]bucket"`, type `(func (param i32))`), so `PLACEHOLDER_FUNC_BASE + ordinal` / `WasmImportInjector` are reused unchanged. Outer side = a SECOND emission kind (below) |
| `--no-gc` | unchanged — `wit-import` is already rejected there |

**Why the outer side is a second emission kind, and the TWO counters it forces.**
`canon resource.drop` (`ComponentWriter.canonResourceDrop`, fed by an
`aliasInstanceType(ownerInstance, resource)`) produces a **CORE function with NO component
function behind it** — unlike a bound function, which costs one component-func alias AND
one `canon lower`ed core func. So `WasmComponentBuilder` now has two numbers where it had
one, and mixing them yields a component that VALIDATES while lifting the wrong core
function:

- `userImportFuncs` = decls only → every **component**-func index (`componentInstanceFromFunc("run", ...)`, `appendFuncExports`'s component cursor)
- `userImportCoreFuncs` = decls **+ drops** → every **core**-func index (`canonLift`'s operand in `WasmComponentBuilder` and in both `WasmServeComponentBuilder` variants, `appendFuncExports`'s core cursor)
- `userImportTypes` = interfaces + projected + **dropped** resources → the first free TYPE index. A dropped resource must be projected out of its instance for `canon resource.drop` to name it, and `appendUserImports` REUSES the projection a `use` clause already made (keyed `ifaceId#resource`), so the count must not double it.

Two more things the drop forced, both of which will bite again:

- **A wrapper's first parameter is local slot 1, not 0.** Every compiled function carries an
  implicit closure environment in slot 0 and starts its parameters at 1
  (`WasmComponentImportCompiler.buildDropBody`). The first drop wrapper read slot 0 and
  trapped casting the null env to an `i31`.
- **The encoder declares resources LAZILY** (only when a bound function's signature reaches
  one), so a resource that is ONLY dropped is never declared. `WasmComponentBuilder` /
  `WitImportWorldEmitter` force-declare it through the `provided` hook, after the function
  walk — which keeps the bytes unchanged when the resource was already reached (the
  keyvalue shape). `--emit-wit` prints it into the world the same way; a drop itself never
  appears there (it is a canonical built-in, not a WIT function), so the emitted world is
  unchanged.

**The example teaches the semantics, and had to be fixed to do it.**
`examples/wit/keyvalue/memory-store.lisp` hung its DATA off the handle, so a naive
`(remhash handle ...)` would have deleted the STORE. It now keys the data by store
IDENTIFIER with the handle table as a separate indirection (the Java store likewise), so
**dropping a handle releases the reference, not the store** — the next `open` sees every
key. That is the line the doc pages carry too.

## Rich PARAMETERS across the component boundary (todo 133, 2026-07-14)

The v1 cut lowered params flat (scalar / bool / string / `list<u8>` / handle / `option` of
those) while results lifted recursively. That asymmetry is gone: **a param now lowers
everything a result lifts, except `list<T>` (T != u8)**. What made it worth doing is that
serve's and fetch's move onto wit-imported `wasi:http` was blocked by exactly this — and the
todo's own premise ("the blocking variants are all flat-payload") was WRONG, which is the
thing to remember:

- `wasi:http`'s `method` variant carries a **string** (`other(string)`), and
  `response-outparam.set` takes `result<outgoing-response, error-code>` whose `error-code`
  cases carry **records** (`DNS-error-payload`) and `option<string>`. Cut string/record
  payloads and the keystone unblocks nothing. So the line is drawn at `list<T>` instead:
  writing a canonical ARRAY into linear memory is a different mechanism (a memory `store`
  recursion mirroring `emitLiftAt`), and nothing in sight needs it. `fields.append`
  (`list<u8>`) is the reason the HTTP libraries never needed it either.

### The Lisp shape is the LIFT's shape, and the codegen is the mirror image

No new representation, no new runtime, no Lisp-side helper: `emitLowerVariantParam` /
`emitLowerRecordParam` consume exactly what `emitVariantCase` / `emitLiftRecordAt` build.
A lifted value therefore goes straight back into another call (`(http:set-method r
(http:method r))`), and **the settled `result` ARGUMENT is the envelope** `(:ok . V)` /
`(:error . E)` — the same cons `%wit-result` unwraps, so the mapping cell that todo 133
left open is closed by construction rather than by decree. A payload-less arm may also be
written as the bare keyword (`(cli:exit :ok)`), because the tag/payload split is
`consp` ? `car`/`cdr` : the value itself.

Keyword identity in wasm is **`struct.get $string 0` (the interned id) + `i32.eq` against
`ctx.stringTable.addString(":case").offset()`** — NOT `ref.eq`: `compileStringLiteral`
allocates a fresh struct each time (`_str_build`), and only field 0 is canonical. The
idiom is `WasmFetchRuntimeBuilder.buildPlistGet`, which is also what a `record` param calls
(`FUNC_FETCH_PLIST_GET`, always emitted) to pull a field out of the keyword plist.

A keyword naming no case traps (`unreachable`). That is deliberate and consistent: a type
error on this backend is a `ref.cast` trap already (`(+ 1 "a")`), and the alternative — a
Lisp-side normalizing wrapper that could signal — would have put a second, divergent copy
of the shape rules next to the codegen's.

### Three things the encoders needed

- **The joined payload flats** (`WitCanonicalAbi.flatTypes` already computed them): each
  case's flats are coerced to the joined type per the canonical ABI (`i32`->`i64` =
  `extend_u`, `f32`->`i32` = `reinterpret`, `f64`->`i64` = `reinterpret`) and the positions
  a case does not reach are zero-filled. A joined FLOAT flat rides in an integer scratch
  local as its bit pattern (`storageOf`), which keeps the scratch pools to two. Per-case,
  per-field and per-ARGUMENT cursors are rolled back (a lowered argument's scratch dies the
  moment its flats are on the stack), so the cost is the deepest nesting, not the sum.
- **The scratch pools are SIZED BY MEASUREMENT, not by a constant** -- `buildWrapperBody`
  emits the body twice, reads the cursors' high-water marks off the first pass, and emits
  again with pools of exactly that size (the locals declaration is written after the body
  either way; what pass 1 buys is the local INDICES, which must already be right while the
  body is written). This is not a nicety: the first cut used fixed pools (i32 x 24, i64 x
  5) and **`wasi:http`'s `response-outparam.set` -- the ONE call this whole line of work
  exists for -- blew through them** (result -> variant -> option -> record ->
  option<string>, five levels), while the test corpus, whose trimmed WIT dropped
  `error-code`, stayed green. Any constant is walked past by a deeper WIT; do not re-fix
  this by raising one.
- **`needsMemory` must recurse into variant cases and record fields** — a string inside a
  case payload stages memory, and missing that would emit a `canon lower` with no memory
  options.
- **`repOf` must resolve aliases first.** `type headers = fields` (every wasi:http-shaped
  interface writes it) is an alias whose target is itself a NAME, and `WitTypeMapper`
  classifies structurally, so it threw `IllegalArgumentException`. A latent crash on EVERY
  backend, not just this one; `WasmComponentImportCompilerTest.followsATypeAlias...` pins it.

### Two shapes the gate must refuse, and one the lift had never seen

- An **empty `record`** is not encodable in the component model at all ("record type must
  have at least one field"), so it is a compile error naming the WIT line rather than an
  unreadable component.
- A **`record` / `tuple` RESULT that flattens to one core value** (a single-field record)
  never reaches the return area -- it comes back IN the flat -- and `emitLiftFlat` had no
  case for it, so the gate accepted what the codegen then refused. `emitLiftRecordFlat`
  lifts it into the same plist / list the memory path would have.
- `option<bool>` remains representationally ambiguous (`some(false)` and `none` are both
  nil). It is accepted, not refused: a program can still pass `t` or `nil`-as-none, and
  refusing it would lock out interfaces that only ever use those. Known hole, both
  directions.

### Verified against real hosts (nothing else can check a lowered param)

A unit test can only say the bytes contain the import name. What proves a param is right is
a host that ANSWERS with what it received, so the E2Es (in `WasmLispCompilerIntegrationTest`,
container wasmtime) call wasmtime's own implementations:

- `wasi:http/types` — `set-method :post` / `'(:other . "PATCH")`, read back with `method`
  (round trip); an invalid method makes the host answer the error arm -> `wit-error`.
- `wasi:sockets/types` — `tcp-socket-create :ipv4` (enum), `bind '(:ipv4 :port 0 :address
  (127 0 0 1))` (variant -> record -> tuple), read back with `get-local-address`. The host
  binds the address it was handed, which is the actual proof.
- `wasi:cli/exit` — `exit: func(status: result)`, the cheapest result PARAM: the arm the
  host received IS the process exit code (`:ok` -> 0, `'(:error)` -> 1).

Trimmed WIT in a test must match the host's real interface EXACTLY (the component-model
subtype check is structural): a hand-written `enum error-code` for wasi:sockets failed with
"expected variant found enum" until it was copied from the vendored `.wit`.

### A user import must not collide with the WASI surface (fixed here)

Nothing compared a user's interface id against the fixed adapter blob's imports, so
`(wit-import "wasi:sockets/types@0.3.0")` in a program that also calls `rontolisp:tcp-*`
emitted a component with the SAME instance import name twice — invalid, and only wasmtime
said so, at a byte offset. `WasmComponentBuilder.rejectAdapterImportCollisions` now names
it at compile time. The surface grows with what the program uses, which is why the check
lives where the blob variant is finally known (`build`), not in the directive.

### The alignment trap (this cost the only real debugging round)

`__ronto_alloc` returns `HEAP_PTR` as-is, and **`HEAP_PTR` is not always 8-aligned**:
`_intern` copies a first-seen symbol's bytes into the permanent low region and advances the
pointer by their exact length. Hand that pointer to the canonical ABI as a return area and
wasmtime traps with `pointer not aligned` — nondeterministically, only after a program
happens to intern something. So the wrapper **aligns HEAP_PTR up to 8 on entry** (that is
also the staging floor) and pops back to `align8(max(mark, intern-high-water))` on exit.
Do not "simplify" either of those.

## What CANNOT be externalized as a WIT import (so nobody re-proposes it)

The roadmap that produced all of the above (the todo-124 anchor, closed 2026-07-17 once
the WebGL demos adopted `gl.wit`) left one list worth keeping: the blobs that stay
hand-written WAT on purpose, and why. Everything else the anchor recorded is here already
-- the type mapping in `compiler/WitTypeMapper` + `WitTypeMapperTest`, the `result<T,E>`
option (c) decision above.

- **The base adapter.** The core module's `wasi_snapshot_preview1` import layout is
  Preview-1-IDENTICAL by design, and every `FUNC_*` constant in the WASM backend rests on
  that layout. It is not a boundary a program chose; it is the one every program has.
  **Re-evaluated from zero 2026-07-17** (the deferred final item of the sockets/stdin
  migration), per the "re-read a wall" lesson below: after sockets and async stdin moved
  off, what remains on `adapter.wat` is the SYNC stdin branch, file I/O
  (`path_open`/`fd_read`/`fd_close` + the `fd_write` append cycle), stdout/stderr writes,
  and env/clock/random. The wall is NOT technical anymore -- every migration mechanism
  exists (wit-import canon lower, the async promotion rewrite, `%future-force`,
  `TYPE_WASI_STREAM` handles) -- it is the byte-stability/flag-neutrality CONTRACT for
  non-async programs: 0.3 file/stdio I/O is stream-based, so a Lisp-library
  implementation makes every file- or print-using component an async+EH component
  (`-W exceptions=y` for hello-world), exactly the flip the stdin migration deliberately
  avoided by keeping the adapter's sync branch. Per surface: env/clock/random are
  sync-lowerable TODAY but buy nothing (no promotion goal, no blob deletion since the
  `fd_*` surface stays, per-program WIT-world churn) -- declined; file I/O migration's
  only functional gain is "file reads don't stall async bodies", with no recorded demand
  -- deferred until that promotion goal is real; stdout writes virtually never block
  (the sync-export finding) -- declined. So `adapter.wat` STAYS, deliberately.
  Revisit trigger: a real need for non-stalling file reads inside async bodies (that
  migration would follow the stdin.lisp pattern: a wit-imported filesystem library
  behind the same `%io-*` dispatch, async-gated so sync programs stay byte-identical).
- **The serve preview1 bridge** -- the same thing in miniature.
- **The `--no-gc` print micro-adapter.** A different backend, whose value model is unboxed
  i64/f64 over linear memory; `wit-import` is rejected there by design.

**`wasi:sockets` used to be on this list and is NOT (2026-07-17).** The stated wall -- "its
0.3 surface is fundamentally `stream`/`future`-based, which has no rontolisp value on ANY
backend until language-level async" -- was true when written and has since been torn down:
async/await landed, `stream`/`future` ARE first-class rontolisp values, and
`stream<u8>`/`future<T>` already cross `canon lower` (it is what `http.lisp` rides).
`sockets.lisp` now rides a wit-imported `wasi:sockets/types@0.3.0` and
`adapter-sockets.wat` is deleted (`.kb/tcp-sockets.md`). The lesson worth keeping: a "wall"
here meant a missing LANGUAGE feature, and the language moved -- so re-read a wall before
trusting it.

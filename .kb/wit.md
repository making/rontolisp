# `am.ik.wit` — the WIT parser/printer library, the settled type mapping, and `wit-export`

Todo 125 (step 1 of the `.todo/124` "WIT as universal IDL" roadmap), 2026-07-13, plus
todo 126 (`rontolisp:wit-export`, 2026-07-14). Everything downstream (`wit-import`
`.todo/127`, component imports `.todo/128`) consumes this model; nothing downstream
re-parses text.

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
cli/clocks/filesystem/random), per-variant projections (`wasi:http@0.2.0` /
`wasi:io@0.2.0` — wasm-tools prunes to what each component reaches, so http-client=
outgoing-only, http-server=incoming-only differ) get suffixed methods. `WitEmitter`
appends each `rontolisp:wasm-export` Decl as a typed `export` world item and prints
canonically. Oracles, strongest first:

- `WitOracleE2eTest` — live byte-diff against `wasm-tools component wit` (local only).
- `WasiWitDefinitionsTest` — always-on byte pin of every variant against its fixture.
- `WitEmitterTest` — line pins of the export shapes.
- New in the round-trip suite: the emitted WIT re-parses through our own parser.

The old `component/wit/*.wit` main resources are DELETED (the resource-config
`WitEmitter` entry too); the fixtures are test resources. Regen is now three-phase
(blobs -> jar -> fixtures -> generator); see `src/wasm-component/README.md`. The
deliberate deviation is kept: the http-server fixtures restore incoming-handler's
`use types.{incoming-request, response-outparam};` clause that `wasm-tools` drops
(its own output does not re-parse); ours must stay consumable.

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
(`adapter-http-client.wasm`, `import-block-http-server.bin`,
`adapter-http-server-client-p1.wasm`, `mem-http-client.wasm`, ...), the
`src/wasm-component` sources (`uni-http-client.wit`, `core-sockets.wat`, ...), regen
scripts and docs. **Mode vocabulary is unchanged**: the `serve` boolean on
`WasmLispCompiler`, `WasmServeComponentBuilder`, `buildServe`/`buildHttp` still name
the `wasmtime serve` MODE; the kb rule is mode `serve` -> variant `http-server`.
Component output bytes are unchanged (integration suite + oracle re-run green).

## The settled type mapping (`compiler/WitTypeMapper`, todo 124's table)

`WitTypeMapper.rep(WitType)` / `repOfDefinition(WitItem)` — the ONE vocabulary the
future binders consult; no codegen. Pinned by `WitTypeMapperTest` (reversing a cell
after 126+ ship = breaking change). Highlights: scalars per the todo-124 table
(`s64`/`u64` bignum-safe), `record`=keyword plist, `enum`=keyword, `variant`=tagged
list, `flags`=keyword list, `option<T>`=value-or-nil, `tuple`=list,
`resource`/`borrow`/`own`=opaque integer handle (one handle space,
`.kb/read-load-streams.md`), `stream`/`future`=UNSUPPORTED until language async.

### Decision record: `result<T, E>` = option (c) — condition on EVERY backend

Chosen 2026-07-13 (user decision after weighing all three `.todo/124` options):

- The ok arm is the return value; a payload-less ok arm (`result` / `result<_, E>`)
  returns `nil`. The error arm **signals a condition** carrying the mapped `E`
  payload, catchable with `handler-case` — on every backend, as the contract.
- The WASM catch mechanism this presupposed landed with `.todo/129`
  (2026-07-14): the wasm-GC backends compile `handler-case` through the
  exception-handling proposal (`.kb/error-handling.md`, "WASM (todo-129)"), so
  the error arm is catchable on every backend except `--no-gc` and the
  `.todo/128` prerequisite is SATISFIED. Programs that catch need
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

Step 2 of `.todo/124`. "This program implements this WIT world": the `.wit` becomes the
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
  mode): a serve-mode component's only export is `wasi:http/incoming-handler`, so a world
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
  `wasi:http/incoming-handler` export comes from `rontolisp:http-handler`)
- an inline `import name: func(...)` in the world — rejected, not silently dropped
  (`.todo/127`)
- a WIT type outside the export boundary's subset (`s32` / `s64` / `f64` / `bool` /
  `string`). The error names the type's SETTLED house representation via
  `WitTypeMapper.rep` and points at `.todo/128` — "your `record` is a keyword plist,
  marshalling it is not built yet", not a bare refusal
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
around the single declared export; adding a `rontolisp:fetch` call makes it **325 lines**
/ 15 imports (`wasi:io/{poll,error,streams}` + `wasi:http/{types,outgoing-handler}`),
and `rontolisp:tcp-*` adds `wasi:sockets`. Short of `wasm-tools`, `--emit-wit` is the
only way to see it, and it is what a host / `jco` must consume. For a program WITHOUT a
world (hand-written `wasm-export`, or an `:s-expr` export, which has no WIT spelling)
`--emit-wit` remains the sole generator, as before todo 126.

Transitional: when `.todo/128` (component imports) lands, the import side becomes
something the user declares in the world too — and only THEN does `--emit-wit` become a
real two-sided consistency check.

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

A world's imports are not bound to anything. Binding them is `.todo/127` (interpreter +
JVM: `wit-import` against a provider) and `.todo/128` (component imports via canon lower,
the wasi:keyvalue unblocker). `rontolisp:wit-import` does not exist yet; an inline
function import in an implemented world is an error precisely so that it does not read as
supported.

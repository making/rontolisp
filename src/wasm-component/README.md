# WASI 0.3 (Preview 3) component sources

This directory holds the **sources** for the fixed byte blobs that `WasmComponentBuilder`
embeds when wrapping a rontolisp core module into a WASI 0.3 (Preview 3) component (the
`--component` flag). They are independent of the compiled program.

In WASI 0.3 the `wasi:io` package is gone: byte I/O flows through the built-in
component-model `stream<u8>` / `future<T>` types and the async canonical ABI. rontolisp
keeps its Preview 1 core module unchanged and an **adapter** core module implements the
eight `wasi_snapshot_preview1` functions over WASI 0.3, driving the `stream.*` / `future.*`
canon built-ins. The component's `wasi:cli/run@0.3.0` export (an `async func`) is lifted as
an async-typed export, and the adapters call the ASYNC (non-blocking) stream/future
built-ins, parking on a blocking `waitable-set.wait` when one reports BLOCKED -- all of
base `component-model-async`, so no gated wasmtime feature is needed
and the adapter stays straight-line code.

This directory is **not** under `src/main/resources`, so nothing here is packaged into the
runtime jar or the native image. Running `regen.sh` regenerates the runtime artifacts into
the resources tree:

```
src/wasm-component/                 (this directory: editable sources, dev-only)
  mem.wat  adapter.wat              core-module sources
  uni.wit  deps/  core.wat          inputs for the unified import block
  regen.sh                          regenerates the blobs below, fully offline
  regen-wit.sh                      regenerates the --emit-wit fixtures (needs the jar)

src/main/resources/am/ik/rontolisp/codegen/wasm/component/   (generated, packaged)
  mem.wasm  adapter.wasm  import-block.bin

src/test/resources/am/ik/rontolisp/codegen/wasm/component/wit/   (generated, test-only)
  *.wit                             --emit-wit fixtures, one per blob variant
```

The generated `.bin` / `.wasm` are loaded at runtime via the classpath and registered for
GraalVM native image (wildcard patterns) in
`META-INF/native-image/am.ik.rontolisp/rontolisp/resource-config.json`.

```bash
src/wasm-component/regen.sh     # regenerate all three artifacts (needs wasm-tools + python3)
```

## The `--emit-wit` fixtures and `WasiWitDefinitions`

The CLI's `--emit-wit` option writes the component's WIT world next to the `.wasm`. The fixed
part of that text (world imports, the fixed `wasi:cli/run` / `wasi:http/handler`
export, the referenced package definitions) is the per-variant document model in
`WasiWitDefinitions.java` — `base`/`sockets`/`http-server` (GC) and
`nogc`/`nogc-print` — **generated** by
`WasiWitDefinitionsGenerator` (test sources) from the fixtures under
`src/test/resources/.../component/wit/`, which are captured verbatim from
`wasm-tools component wit` on a minimal reference component per variant. `WitEmitter`
appends the per-program `rontolisp:wasm-export` items to the world and prints the model
with `am.ik.wit.WitPrinter`; `WasiWitDefinitionsTest` pins each variant byte-for-byte
against its fixture, always-on. Two deliberate fixture edits over the raw tool output,
both encoded in `regen-wit.sh`: the reference program's own export lines are stripped,
and the http-server variant restores the handler interface's
`use types.{request, response, error-code};` clause that `wasm-tools component wit`
omits (its output does not parse without it; the upstream wasi:http worlds.wit has it).

Regeneration is three-phase because the fixed exports are wired by `WasmComponentBuilder`
(they are not in the `uni*.wit` reference worlds), so the reference components must be
built by the full pipeline:

```bash
./regen.sh                                # 1. blobs (after editing the sources)
(cd ../.. && ./mvnw package -DskipTests)  # 2. rebuild the exec jar on the new blobs
./regen-wit.sh                            # 3. fixtures (uses the jar + wasm-tools)
# 4. Java definitions from the fixtures:
(cd ../.. && ./mvnw test-compile -DskipTests && \
  java -cp target/classes:target/test-classes \
    am.ik.rontolisp.codegen.wasm.WasiWitDefinitionsGenerator && \
  ./mvnw spring-javaformat:apply)
```

Then re-run `WasiWitDefinitionsTest` (always-on byte pins), `WitEmitterTest` (line pins)
and `WitOracleE2eTest` (live `wasm-tools` diff; skipped when the binary is not on
`PATH`).

Run a generated component with:

```bash
wasmtime run -W gc=y --dir . prog.wasm
```

## What the blobs declare, and how to see it

Compile anything with `--component` and inspect it:

```bash
wasm-tools component wit prog.wasm     # shows every imported WASI 0.3 interface
wasm-tools print prog.wasm | less      # full disassembly (core modules + canon section)
```

`uni.wit` imports exactly these interfaces (in this order, which fixes the component import
instance indices `WasmComponentBuilder.build` assumes):

```
import wasi:cli/stdout@0.3.0;          // write-via-stream(stream<u8>) -> future<result>
import wasi:cli/stdin@0.3.0;           // read-via-stream() -> (stream<u8>, future<result>)
import wasi:cli/environment@0.3.0;     // get-environment -> list<tuple<string,string>>
import wasi:clocks/system-clock@0.3.0; // now -> instant{seconds s64, nanoseconds u32}
import wasi:clocks/monotonic-clock@0.3.0; // now -> u64
import wasi:filesystem/types@0.3.0;    // descriptor.open-at / read-via-stream / append-via-stream
import wasi:filesystem/preopens@0.3.0; // get-directories
import wasi:random/random@0.3.0;       // get-random-u64
import wasi:cli/stderr@0.3.0;          // write-via-stream (fd 2, for warn); appended last
```

`uni.wit` declares **imports only**. The `wasi:cli/run@0.3.0` export is an `async func`,
which `wasm-tools component new` cannot wire from a core module, so the export (the
async-typed lift of the rontolisp core's `run`) is emitted programmatically by
`WasmComponentBuilder.build`.

## Helper core modules (`.wat` is the source, `.wasm` is generated)

`regen.sh` runs `wasm-tools parse` on each `.wat` to produce the `.wasm` the build loads.

- `mem.wat` -> `mem.wasm` — the shared memory module (6 pages). Exports a linear `memory`
  and a bump-allocator `cabi_realloc`. Instantiated first so the canonical lowering, the
  stream/future built-ins and the main modules can all import an already-existing memory
  (avoids the instantiate-before-memory cycle without a lazy funcref trampoline).
- `adapter.wat` -> `adapter.wasm` — the preview1-to-0.3 adapter. Imports the shared memory,
  the lowered WASI 0.3 functions and the async canonical built-ins (under `"w"`), and
  exports the eight `wasi_snapshot_preview1` functions rontolisp imports. Writes use
  `append-via-stream` + await; reads cache a readable stream per fd. wasi:cli and
  wasi:filesystem expose **distinct** `error-code` enums, so their `future<result<_,
  error-code>>` are distinct types with separate built-ins (`future-read-cli`/`-fs`); the
  filesystem error-code is a string-bearing variant, so `future-read-fs` needs realloc. The
  header comment in `adapter.wat` documents the page-5 scratch / fd-table layout.

## The unified import block (`import-block.bin`)

`import-block.bin` is the raw component-model **type + import section bytes** for the 10
imported WASI 0.3 interfaces (component import instances 0-9, component types 0-13). It is
written verbatim by `ComponentWriter.writeRaw`, after which `WasmComponentBuilder.build`
does all remaining wiring programmatically (alias the cli/fs error-code + descriptor types
and the WASI funcs incl. the stderr write-via-stream, define the
`stream<u8>`/`future`/`result` types as component types 17-23, lower the WASI funcs + emit
the `stream.*`/`future.*` canon built-ins as core funcs 1-21, group them as the adapter's
`"w"` import, instantiate mem/adapter/rontolisp, lift `run` against an async function type,
and export `wasi:cli/run@0.3.0`).

### How it is generated (what `regen.sh` does)

- `uni.wit` — the imports-only WIT world (order is significant; append new interfaces LAST).
- `deps/` — the vendored official WASI 0.3.0 WIT (cli / clocks / filesystem / random /
  sockets) so regeneration is fully offline.
- `core.wat` — a stub core module importing every lowered 0.3 function (this is what makes
  `wasm-tools component new` emit the imports) plus a `memory` + `cabi_realloc` + `run`.

`regen.sh` runs `wasm-tools component embed . core.wasm --world uni` + `component new`, then
slices out the component's type/import/alias sections (everything between the 8-byte
preamble and the first core-module section) as `import-block.bin`.

The wiring constants in `WasmComponentBuilder.build` were derived from `wasm-tools dump` /
`wasm-tools print` of the generated reference: the import instance index of each interface,
the component type indices of the resource/enum types aliased out (cli error-code, fs
error-code, descriptor), and the canonical options (memory / realloc / utf8) per lowered
function. If `uni.wit` / `core.wat` change, re-run `regen.sh`, re-derive those constants
from a fresh dump, and re-run the test suite. Validate end to end with:

```bash
wasm-tools validate -f component-model -f cm-async prog.wasm
wasmtime run -W gc=y --dir . prog.wasm
```

## Sockets variant (`rontolisp:tcp-*`) — pure WASI 0.3

A program using a `rontolisp:tcp-*` built-in compiles to the sockets variant:
unlike fetch, `wasi:sockets@0.3.0` exists upstream and wasmtime 46 hosts it, so
the variant stays pure WASI 0.3 (the tcp send/receive plumbing flows through the
same built-in `stream<u8>` machinery as the base I/O). The parallel blob set is:

```
src/wasm-component/
  uni-sockets.wit  core-sockets.wat  adapter-sockets.wat   (sockets sources; mem.wat is shared)

src/main/resources/.../component/
  import-block-sockets.bin  adapter-sockets.wasm
```

`uni-sockets.wit` (world `uni-sockets`) is the base 0.3 surface plus
`import wasi:sockets/types@0.3.0;` appended last (import instance 9; next free
component type 13). `WasmComponentBuilder.buildSock` wires the variant:
alongside the shared `stream<u8>`/future built-ins it defines `own<tcp-socket>`
and the element-typed `stream<own tcp-socket>` accept stream (its own
`stream.read`/`drop-readable` built-ins) plus a drop-only sockets-error-code
future. `adapter-sockets.wat` is `adapter.wat` plus a 32-slot socket table (fd =
200 + slot; `fd_write`/`fd_read`/`fd_close` dispatch on fd >= 200, so the
rontolisp core's stream built-ins work on sockets unchanged) and the
`tcp-connect` / `tcp-listen` / `tcp-accept` / `tcp-local-port` exports (the
core's `"sock"` import seam). Run a socket component with
`-S tcp=y -S inherit-network=y` in addition to the async flags; IPv4 literals
only (`wasi:sockets/ip-name-lookup` is not wired yet). Combining `rontolisp:fetch`
and the tcp functions in one component is a compile error. Details:
`../../.kb/tcp-sockets.md`.

## HTTP client (`rontolisp:fetch`) — the `http.lisp` library, no adapter blob

`rontolisp:fetch` on the plain `wasmtime run` (`--component`, non-serve) path has **no
hand-written adapter and no blob variant of its own**. It is a spliced Lisp-source library,
`src/main/resources/am/ik/rontolisp/eval/http.lisp` (spliced by `eval/HttpLibrary`, which
follows the reachable half — a fetch-only program binds none of the serve members), over a
`rontolisp:wit-import`ed `wasi:http@0.3.0` surface (`http.wit`, a classpath resource carried
inline) — the same canon-lower machinery any `rontolisp:wit-import` uses. So a non-serve
fetch selects the **base** blob set and pulls `wasi:http@0.3` in through canon-lowered user
imports; its `--emit-wit` world shows `import wasi:http/types@0.3.0;` +
`import wasi:http/client@0.3.0;`. `client.send` is an `async func`, so its binding is
async-lowered (the `async` canonical option) and awaited through a waitable-set — which is
exactly what the promise `fetch` returns drives; the request/response bodies flow through
the `stream<u8>` / `future<T>` built-ins bound off `http.wit`'s transparent type aliases.
The user-facing API is unchanged (`fetch` returns an `await`-able future, same options,
same response plist) except that a transport failure now SIGNALS `rontolisp:wit-error` at
await time, like the interpreter and the JVM. Run a fetch component with
`wasmtime run -W gc=y -W exceptions=y -S http=y` -- everything is base
`component-model-async` (default-on in wasmtime 46+), so no gated feature flag is needed.
Mechanics: `../../.kb/fetch-http.md`.

What stays from the old 0.2-era set:

```
src/wasm-component/
  mem-http-client.wat            (16-page memory source; the serve variant reuses it)
  deps/http                      (the vendored wasi:http@0.3.0 WIT; serve regen source)

src/main/resources/.../component/
  mem-http-client.wasm           (16-page memory module; kept for serve)
```

The `deps/clocks-0.2`, `deps/random-0.2`, `deps/io-0.2` and `deps/cli-0.2` packages are
all deleted — since the `--no-gc` print micro-adapter moved to 0.3 (below), the repo
carries **no WASI-0.2-era surface** at all (the vendored `wasi:keyvalue@0.2.0-draft`
example WIT is a host-defined draft interface, not 0.2-era io).

## `--no-gc --component` print micro-adapter — WASI 0.3

A **printing** program under `--no-gc --component` gets a fourth, minimal blob set. A
print-free `--no-gc` component embeds nothing from this directory (the adapter-free
reactor wrap); a printing one needs exactly `wasi:cli/stdout@0.3.0` so a tiny bridge
can implement the core's single `wasi_snapshot_preview1.fd_write` import:

```
src/wasm-component/
  uni-nogc-print.wit  core-nogc-print.wat            (import block sources)
  shim-nogc-print.wat  bridge-nogc-print.wat  fixup-nogc-print.wat

src/main/resources/.../component/
  import-block-nogc-print.bin
  shim-nogc-print.wasm  bridge-nogc-print.wasm  fixup-nogc-print.wasm
```

`uni-nogc-print.wit` (world `uni-nogc-print`) imports only `wasi:cli/stdout@0.3.0`
(`wasi:cli/types` is dependency-hoisted first, so the block declares import instances
0-1 and component types 0-2, the aliased `error-code` at type 1).
`bridge-nogc-print.wat` is the `adapter.wat` cli path in miniature: fd 1 only
(`--no-gc` rejects every other I/O at compile time, so fd 2 / `wasi:cli/stderr` would
be dead weight), one full stream cycle per `fd_write` — `stream.new`,
`write-via-stream(readable)`, the ASYNC `stream.write` built-in, drop the writable end,
await + drop the operation future — parking on a blocking `waitable-set.wait` when a
built-in reports BLOCKED. Only an async-typed task may block, so
`NoGcWasmComponentBuilder` lifts every export of a printing program against an async
function type (the GC `:async t` shape; same flat core signature, post-return intact).
All of that is base component-model-async, so a printing component still runs with
**zero flags** on wasmtime 46+ (where cm-async is default-on — the printing
component's wasmtime floor; jco cannot call async-lifted exports, so keep programs
print-free for jco targets). The bridge reads the iovec out of the CORE's own exported
memory while the core imports `fd_write` from the bridge; `shim-nogc-print.wat` (a
funcref table + a forwarding `fd_write`, instantiated first) and
`fixup-nogc-print.wat` (an element segment patching the real `fd_write` into the
table, instantiated last) break that cycle — the same shim/fixup shape wit-component
generates for the analogous lowering cycle — keeping the printing core module
byte-identical to the plain `--no-gc` output. `NoGcWasmComponentBuilder` wires
everything programmatically.

## HTTP server variant (`rontolisp:http-handler`) — async wasi:http@0.3.0

A program using `rontolisp:http-handler` compiles to the http-server variant: a
`wasi:http/handler@0.3.0` component for `wasmtime serve` (wasmtime 46+). ONE shape serves
plain serve AND serve+fetch — the 0.3 `service` world always imports `client`, so the
block declares it either way and a handler that never fetches simply binds no `send`. Run
with `wasmtime serve -W gc=y -W exceptions=y` (the handle export is a CALLBACK async
lift against a stub callback; the task's blocking is the parked `waitable-set.wait`
inside the wrappers, so neither gated wasmtime feature is needed). The blob set is:

```
src/wasm-component/
  uni-http-server.wit  core-http-server.wat  adapter-http-server-p1.wat
  deps/http  deps/cli  deps/clocks  deps/random   (0.3 deps, shared with the base set)

src/main/resources/.../component/
  import-block-http-server.bin  adapter-http-server-p1.wasm
```

`uni-http-server.wit` (world `uni-http-server`) imports `wasi:http/{types,client}@0.3.0`
plus the service world's `wasi:random/random`, `wasi:clocks/{system,monotonic}-clock` and
`wasi:cli/{stdout,stderr}` (`wasi:cli/types` and `wasi:clocks/types` come in implicitly
through `use` clauses). There is NO serve adapter: the HTTP glue is `http.lisp` (spliced
by `eval/HttpLibrary`), whose `wasi:http` calls the core `canon lower`s — `client.send`
async-lowered, the body machinery through the alias-derived stream/future built-ins —
and whose `%serve-handle` the core exports as `handle`. The one helper module is
`adapter-http-server-p1.wat`, the preview1 bridge, instantiated BEFORE the core to
satisfy its `wasi_snapshot_preview1` imports: `random_get` over `get-random-u64`,
`clock_time_get` over the 0.3 clocks, `fd_write` (fd 1/2) over `write-via-stream` + the
stream/future built-ins (the base `adapter.wat`'s cli path), a zero environment for
`environ_*` (`getenv` returns nil), EOF for `fd_read`, errno 76 for `path_open` (no
filesystem in the service world).

`WasmServeComponentBuilder.build` wires mem -> bridge -> core, lowers the fixed
`wasi:http` surface FROM the import block (sync decls, the async-lowered `send`, drops,
the alias-derived built-ins, `canon task.return`, the waitable-set builtins), and lifts
the core's `handle` export as a **callback async** function
(`async func(request: own<request>) -> result<own<response>, error-code>`, canonical
options `memory`/`utf8`/`async`/`callback` against the bridge's stub callback): the core
signature is `[i32] -> [i32 packed-code]` (always `EXIT`) and the response is
delivered mid-task through the task-return built-in — 0.3's replacement for 0.2's
`response-outparam.set`-before-body — after which the body write rendezvouses with the
host's eager read. The exported function type is built over the block's NAMED
request/response/error-code aliases (component types 1/2/3): the component-model export
rule forbids anonymous structural types in an exported signature. The block declares
import instances 0-7 (`http/types`, `http/client`, `random`, `system-clock`,
`monotonic-clock`, `cli/types`, `stdout`, `stderr`) and the first free component type is
13; re-derive from a fresh `wasm-tools dump` after changing `uni-http-server.wit` or
`core-http-server.wat`. Details: `../../.kb/fetch-http.md`.

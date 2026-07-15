# WASI 0.3 (Preview 3) component sources

This directory holds the **sources** for the fixed byte blobs that `WasmComponentBuilder`
embeds when wrapping a rontolisp core module into a WASI 0.3 (Preview 3) component (the
`--component` flag). They are independent of the compiled program.

In WASI 0.3 the `wasi:io` package is gone: byte I/O flows through the built-in
component-model `stream<u8>` / `future<T>` types and the async canonical ABI. rontolisp
keeps its Preview 1 core module unchanged and an **adapter** core module implements the
eight `wasi_snapshot_preview1` functions over WASI 0.3, driving the `stream.*` / `future.*`
canon built-ins. The component's `wasi:cli/run@0.3.0` export (an `async func`) is lifted as
a **stackful** async export, so the synchronous stream/future built-ins block cooperatively
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
part of that text (world imports, the fixed `wasi:cli/run` / `wasi:http/incoming-handler`
export, the referenced package definitions) is the per-variant document model in
`WasiWitDefinitions.java` — `base`/`sockets`/`http-server`/
`http-server-client` (GC) and `nogc`/`nogc-print` — **generated** by
`WasiWitDefinitionsGenerator` (test sources) from the fixtures under
`src/test/resources/.../component/wit/`, which are captured verbatim from
`wasm-tools component wit` on a minimal reference component per variant. `WitEmitter`
appends the per-program `rontolisp:wasm-export` items to the world and prints the model
with `am.ik.wit.WitPrinter`; `WasiWitDefinitionsTest` pins each variant byte-for-byte
against its fixture, always-on. Two deliberate fixture edits over the raw tool output,
both encoded in `regen-wit.sh`: the reference program's own export lines are stripped,
and the http-server variants restore incoming-handler's
`use types.{incoming-request, response-outparam};` clause that `wasm-tools component wit`
omits (its output does not parse without it; the upstream `deps/http/handler.wit` has it).

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
wasmtime run -W gc=y -W component-model-more-async-builtins=y --dir . prog.wasm
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
which `wasm-tools component new` cannot wire from a core module, so the export (the async
stackful lift of the rontolisp core's `run`) is emitted programmatically by
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
wasm-tools validate -f component-model -f cm-async -f cm-async-stackful -f cm-more-async-builtins prog.wasm
wasmtime run -W gc=y -W component-model-more-async-builtins=y --dir . prog.wasm
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

## HTTP client (`rontolisp:fetch`) — the `fetch.lisp` library, no adapter blob

`rontolisp:fetch` on the plain `wasmtime run` (`--component`, non-serve) path has **no
hand-written adapter and no blob variant of its own**. It is a spliced Lisp-source library,
`src/main/resources/am/ik/rontolisp/eval/fetch.lisp` (spliced by `eval/FetchLibrary`), over a
`rontolisp:wit-import`ed `wasi:http` / `wasi:io` surface (`fetch.wit`, a classpath resource
carried inline) — the same canon-lower machinery any `rontolisp:wit-import` uses. So a
non-serve fetch selects the **base** blob set and pulls `wasi:http@0.2` + `wasi:io@0.2` in
through canon-lowered user imports; its `--emit-wit` world shows
`import wasi:http/types@0.2.0;` + `import wasi:http/outgoing-handler@0.2.0;` + the
`wasi:io/*` imports. The user-facing API is unchanged: `fetch` still returns an `await`-able
promise (via `rontolisp:then`, non-blocking), same options, same response plist. Run a fetch
component with `-S http=y` in addition to the async flags. Mechanics: `../../.kb/fetch-http.md`.

The `adapter-http-client.wat` / `core-http-client.wat` / `uni-http-client.wit` sources and the
`adapter-http-client.wasm` / `import-block-http-client.bin` blobs are **deleted**. What stays
from the old set:

```
src/wasm-component/
  mem-http-client.wat            (16-page memory source; the serve+fetch variant reuses it)
  deps/clocks-0.2  deps/io-0.2  deps/http   (0.2 deps; still used by the serve variants)

src/main/resources/.../component/
  mem-http-client.wasm           (16-page memory module; kept for serve+fetch)
```

`mem-http-client.wasm` (the 16-page memory module) is kept because the serve+fetch
(`http-server-client`) variant below still needs it; `regen.sh` still regenerates it. Async
`wasi:http@0.3` does not exist upstream yet (the wasi-http repo's `v0.3.0-rc` tags and `main`
are still `wasi:http@0.2.x`, and wasmtime 46 hosts only `wasi:http@0.2`), which is why the
imported surface is the 0.2 http interfaces on every fetch path.

## `--no-gc --component` print micro-adapter — pure WASI 0.2

A **printing** program under `--no-gc --component` (todo 93 remaining task 1) gets a
fourth, minimal blob set. A print-free `--no-gc` component embeds nothing from this
directory (the adapter-free reactor wrap); a printing one needs exactly the two WASI
0.2 stdio interfaces so a tiny bridge can implement the core's single
`wasi_snapshot_preview1.fd_write` import:

```
src/wasm-component/
  uni-nogc-print.wit  core-nogc-print.wat            (import block sources)
  shim-nogc-print.wat  bridge-nogc-print.wat  fixup-nogc-print.wat

src/main/resources/.../component/
  import-block-nogc-print.bin
  shim-nogc-print.wasm  bridge-nogc-print.wasm  fixup-nogc-print.wasm
```

`uni-nogc-print.wit` (world `uni-nogc-print`) imports only `wasi:cli/stdout@0.2.0` and
`wasi:io/streams@0.2.0` (`wasi:io/error` is dependency-hoisted first, so the block
declares import instances 0-2 and component types 0-4). `bridge-nogc-print.wat` is the
adapter-http-server-p1 `fd_write` pattern in miniature: fd 1 only (`--no-gc` rejects every
other I/O at compile time, so fd 2 / `wasi:cli/stderr` would be dead weight), chunked
through the *synchronous* `blocking-write-and-flush` — so the component's exports stay
sync lifts and the zero-flag property is preserved (0.2 stdio is default-provided by
wasmtime and jco). The bridge reads the iovec out of the CORE's own exported memory
while the core imports `fd_write` from the bridge; `shim-nogc-print.wat` (a funcref
table + a forwarding `fd_write`, instantiated first) and `fixup-nogc-print.wat` (an
element segment patching the real `fd_write` into the table, instantiated last) break
that cycle — the same shim/fixup shape wit-component generates for the analogous
lowering cycle — keeping the printing core module byte-identical to the plain
`--no-gc` output. `NoGcWasmComponentBuilder` wires everything programmatically.

## HTTP server variant (`rontolisp:http-handler`) — pure WASI 0.2

A program using `rontolisp:http-handler` compiles to the http-server variant: a
`wasi:http/incoming-handler@0.2.0` component for `wasmtime serve` (or any
`wasi:http` 0.2 host with wasm-GC). It is pure WASI 0.2 — no async canon
built-ins, so none of the `component-model-async` flags. The parallel blob set
is:

```
src/wasm-component/
  uni-http-server.wit  core-http-server.wat  adapter-http-server.wat  adapter-http-server-p1.wat
  deps/random-0.2  deps/cli-0.2   (0.2 deps; io-0.2 / clocks-0.2 / http shared)

src/main/resources/.../component/
  import-block-http-server.bin  adapter-http-server.wasm  adapter-http-server-p1.wasm
```

`uni-http-server.wit` (world `uni-http-server`) imports the 0.2 http/io incoming-handler
machinery plus the proxy world's `wasi:random/random`, `wasi:clocks/{wall,
monotonic-}clock` and `wasi:cli/{stdout,stderr}`. There is NO serve adapter: the HTTP
glue is `serve.lisp` (spliced by `eval/ServeLibrary`), whose `wasi:io` / `wasi:http/types`
calls the core `canon lower`s and whose `%serve-handle` the core exports as `handle`. The
one helper module is `adapter-http-server-p1.wat`, the preview1 bridge, instantiated
BEFORE the core to satisfy its `wasi_snapshot_preview1` imports: `random_get` over
`get-random-u64`, `clock_time_get` over the 0.2 clocks, `fd_write` (fd 1/2) over the cli
stdout/stderr streams, a zero environment for `environ_*` (`getenv` returns nil), EOF for
`fd_read`, errno 76 for `path_open` (no filesystem in the proxy world).
`WasmServeComponentBuilder.build` wires mem -> bridge -> core, lowers the fixed
`wasi:io`/`wasi:http/types` surface FROM the import block, and lifts the core's `handle`
export synchronously into `wasi:http/incoming-handler@0.2.0`. Note
`wasi:clocks/monotonic-clock` lands as import instance 0 (wasm-tools hoists it first as a
dependency of `wasi:http/types`), before `wasi:io/error`. Details: `../../.kb/fetch-http.md`.

### HTTP server+client variant (`rontolisp:http-handler` + `rontolisp:fetch`)

A handler program that also uses `rontolisp:fetch` (a proxy-style server making
outgoing requests) compiles with the SAME builder over a wider block — no extra adapter:

```
src/wasm-component/
  uni-http-server-client.wit  core-http-server-client.wat

src/main/resources/.../component/
  import-block-http-server-client.bin
```

`uni-http-server-client.wit` (world `uni-http-server-client`) is the serve surface plus
`wasi:io/poll` and `wasi:http/outgoing-handler` appended last, plus the outgoing-request /
future / incoming-response members of `wasi:http/types` — still entirely inside the
wasi:http proxy world, so any host that serves the plain variant can serve this one (grant
outbound HTTP: `wasmtime serve -W gc=y -W exceptions=y -S http=y`). Both halves are Lisp:
`serve.lisp` handles the incoming side, `fetch.lisp` the outgoing side, spliced together and
their overlapping `wasi:http`/`wasi:io` bindings merged + deduplicated. The preview1 bridge
is the SAME `adapter-http-server-p1.wat` (once fetch is fetch.lisp the core imports no `http`
function, so there is no extended bridge and no serve adapter). `WasmServeComponentBuilder.build`
picks this block when a fetch-only interface is present; note `wasi:io/poll` is
dependency-hoisted to import instance 0, so every instance index shifts by one relative to
the plain serve block and the `WIDE` constants were re-derived from a fresh `wasm-tools dump`.

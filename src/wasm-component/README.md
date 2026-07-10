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

src/main/resources/am/ik/rontolisp/codegen/wasm/component/   (generated, packaged)
  mem.wasm  adapter.wasm  import-block.bin
```

The generated `.bin` / `.wasm` are loaded at runtime via the classpath and registered for
GraalVM native image (wildcard patterns) in
`META-INF/native-image/am.ik.rontolisp/rontolisp/resource-config.json`.

```bash
src/wasm-component/regen.sh     # regenerate all three artifacts (needs wasm-tools + python3)
```

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
  uni-sock.wit  core-sock.wat  adapter-sock.wat   (sockets sources; mem.wat is shared)

src/main/resources/.../component/
  import-block-sock.bin  adapter-sock.wasm
```

`uni-sock.wit` (world `uni-sock`) is the base 0.3 surface plus
`import wasi:sockets/types@0.3.0;` appended last (import instance 9; next free
component type 13). `WasmComponentBuilder.buildSock` wires the variant:
alongside the shared `stream<u8>`/future built-ins it defines `own<tcp-socket>`
and the element-typed `stream<own tcp-socket>` accept stream (its own
`stream.read`/`drop-readable` built-ins) plus a drop-only sockets-error-code
future. `adapter-sock.wat` is `adapter.wat` plus a 32-slot socket table (fd =
200 + slot; `fd_write`/`fd_read`/`fd_close` dispatch on fd >= 200, so the
rontolisp core's stream built-ins work on sockets unchanged) and the
`tcp-connect` / `tcp-listen` / `tcp-accept` / `tcp-local-port` exports (the
core's `"sock"` import seam). Run a socket component with
`-S tcp=y -S inherit-network=y` in addition to the async flags; IPv4 literals
only (`wasi:sockets/ip-name-lookup` is not wired yet). Because imports precede
defined functions and the sock slots (core function indices 8-11) sit before
the http slots (12-13), `adapter-http.wat` exports four errno-returning tcp
stubs that satisfy the sock imports of a fetch component; combining fetch and
tcp in one component is a compile error (see
`../../.todo/49-combine-fetch-and-sockets-component.md`). Details:
`../../.kb/tcp-sockets.md`.

## HTTP variant (`rontolisp:fetch`) — hybrid

A `fetch` program compiles to a **hybrid** component: the base I/O stays WASI 0.3, but
fetch imports `wasi:http@0.2` + `wasi:io@0.2` (async `wasi:http@0.3` does not exist upstream
yet — the wasi-http repo's `v0.3.0-rc` tags and `main` are still `wasi:http@0.2.x`, and
wasmtime 46 hosts only `wasi:http@0.2`; see `../../.todo/02-upgrade-fetch-to-wasi-http-0.3.md`). The parallel blob set is:

```
src/wasm-component/
  uni-http.wit  core-http.wat  mem-http.wat  adapter-http.wat   (http sources)
  deps/clocks-0.2  deps/io-0.2  deps/http   (0.2 deps; http's proxy world is trimmed)

src/main/resources/.../component/
  import-block-http.bin  mem-http.wasm  adapter-http.wasm
```

`uni-http.wit` (world `uni-http`) is the base 0.3 surface plus the 0.2 http interfaces; the
0.2 deps live alongside the 0.3 ones in `deps/` under version-suffixed directories.
`regen.sh` regenerates both variants. `WasmComponentBuilder.buildHttp` wires the http
variant (next free component type index 25; 31 lowered WASI funcs + 10 resource drops + the
0.3 stream/future built-ins; `run` lifted async as in the base). `adapter-http.wat` is
`adapter.wat` plus `fetch-start` / `fetch-await` exports driving the asynchronous outgoing
request (the `rontolisp:fetch` promise API) over `wasi:http@0.2`: `fetch-start` sends the
request and returns the `future-incoming-response` handle immediately, `fetch-await`
blocks on its pollable and reads the response. Run a fetch component with `-S http=y` in
addition to the async flags.

When async `wasi:http@0.3` ships upstream, rewrite the http portion of `adapter-http.wat`
over `stream`/`future`, drop the `wasi:io@0.2` imports from `uni-http.wit`, regenerate, and
re-derive the `buildHttp` constants — the rontolisp core's `http.fetch` seam stays unchanged.

## Serve variant (`rontolisp:http-handler`) — pure WASI 0.2

A program using `rontolisp:http-handler` compiles to the serve variant: a
`wasi:http/incoming-handler@0.2.0` component for `wasmtime serve` (or any
`wasi:http` 0.2 host with wasm-GC). It is pure WASI 0.2 — no async canon
built-ins, so none of the `component-model-async` flags. The parallel blob set
is:

```
src/wasm-component/
  uni-serve.wit  core-serve.wat  adapter-serve.wat  adapter-serve-p1.wat
  deps/random-0.2  deps/cli-0.2   (0.2 deps; io-0.2 / clocks-0.2 / http shared)

src/main/resources/.../component/
  import-block-serve.bin  adapter-serve.wasm  adapter-serve-p1.wasm
```

`uni-serve.wit` (world `uni-serve`) imports the 0.2 http/io incoming-handler
machinery plus the proxy world's `wasi:random/random`, `wasi:clocks/{wall,
monotonic-}clock` and `wasi:cli/{stdout,stderr}`. Two adapters sandwich the
rontolisp core because instantiation order forces it: `adapter-serve.wat`
(reads the incoming request, calls the core's exported `%http-dispatch`,
writes the outgoing response) imports the core, so it comes AFTER — which
means it cannot also provide the core's `wasi_snapshot_preview1` imports the
way `adapter.wat` does for `wasmtime run` components. `adapter-serve-p1.wat`
is that missing preview1 bridge, instantiated BEFORE the core: `random_get`
over `get-random-u64`, `clock_time_get` over the 0.2 clocks, `fd_write` (fd
1/2) over the cli stdout/stderr streams, a zero environment for `environ_*`
(`getenv` returns nil), EOF for `fd_read`, errno 76 for `path_open` (no
filesystem in the proxy world). `WasmServeComponentBuilder.build` wires
mem -> bridge -> core -> serve adapter and lifts the adapter's `serve` export
synchronously as `handle`. Note `wasi:clocks/monotonic-clock` lands as import
instance 0 (wasm-tools hoists it first as a dependency of `wasi:http/types`),
before `wasi:io/error`. Details: `../../.kb/fetch-http.md`.

### Serve+fetch variant (`rontolisp:http-handler` + `rontolisp:fetch`)

A handler program that also uses `rontolisp:fetch` (a proxy-style server making
outgoing requests) compiles to a third parallel serve blob set:

```
src/wasm-component/
  uni-serve-http.wit  core-serve-http.wat  adapter-serve-p1-http.wat

src/main/resources/.../component/
  import-block-serve-http.bin  adapter-serve-p1-http.wasm
```

`uni-serve-http.wit` (world `uni-serve-http`) is the serve surface plus
`wasi:io/poll` and `wasi:http/outgoing-handler` appended last — still entirely
inside the wasi:http proxy world, so any host that serves the plain variant can
serve this one (grant outbound HTTP: `wasmtime serve -W gc=y -S http=y`).
`adapter-serve-p1-http.wat` is `adapter-serve-p1.wat` plus the
`fetch-start`/`fetch-await` bodies of `adapter-http.wat` and the
errno-returning tcp stubs: the bridge (instantiated BEFORE the core, unlike the
serve adapter) is what satisfies the rontolisp core's `http` and `sock`
imports when the program uses fetch. `WasmServeComponentBuilder.buildHttp`
wires it; note `wasi:io/poll` is dependency-hoisted to import instance 0, so
every instance index shifts by one relative to the plain serve block and the
constants were re-derived from a fresh `wasm-tools dump`. The fetch
response-body scratch (0x70000) overlaps the serve adapter's request-body
scratch, which is safe: `%http-dispatch` marshals the request into GC strings
before the Lisp handler (and therefore any fetch) runs.

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
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y \
             -W component-model-more-async-builtins=y --dir . prog.wasm
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

`import-block.bin` is the raw component-model **type + import section bytes** for the 9
imported WASI 0.3 interfaces (component import instances 0-8, component types 0-11). It is
written verbatim by `ComponentWriter.writeRaw`, after which `WasmComponentBuilder.build`
does all remaining wiring programmatically (alias the cli/fs error-code + descriptor types
and the WASI funcs, define the `stream<u8>`/`future`/`result` types as component types
12-21, lower the WASI funcs + emit the `stream.*`/`future.*` canon built-ins as core funcs
1-20, group them as the adapter's `"w"` import, instantiate mem/adapter/rontolisp, lift
`run` against an async function type, and export `wasi:cli/run@0.3.0`).

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
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y --dir . prog.wasm
```

## HTTP variant (`rontolisp:fetch`) — hybrid

A `fetch` program compiles to a **hybrid** component: the base I/O stays WASI 0.3, but
fetch imports `wasi:http@0.2` + `wasi:io@0.2` (async `wasi:http@0.3` does not exist upstream
yet — the wasi-http repo's `v0.3.0-rc` tags and `main` are still `wasi:http@0.2.x`, and
wasmtime 46 hosts only `wasi:http@0.2`; see `../../TODO.md`). The parallel blob set is:

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
`adapter.wat` plus a `fetch` export driving the outgoing request over `wasi:http@0.2` /
`pollable.block`. Run a fetch component with `-S http=y` in addition to the async flags.

When async `wasi:http@0.3` ships upstream, rewrite the http portion of `adapter-http.wat`
over `stream`/`future`, drop the `wasi:io@0.2` imports from `uni-http.wit`, regenerate, and
re-derive the `buildHttp` constants — the rontolisp core's `http.fetch` seam stays unchanged.

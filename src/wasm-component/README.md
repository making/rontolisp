# WASI 0.2 component sources

This directory holds the **sources** for the fixed byte blobs that `WasmComponentBuilder`
embeds when wrapping a rontolisp core module into a WASI 0.2 (Preview 2) component (the
`--component` flag). They are independent of the compiled program.

This directory is **not** under `src/main/resources`, so nothing here is packaged into
the runtime jar or the native image. Running `regen.sh` regenerates the three runtime
artifacts into the resources tree:

```
src/wasm-component/                 (this directory: editable sources, dev-only)
  mem.wat  adapter.wat              core-module sources
  uni.wit  deps/  core.wat          inputs for the unified import block
  regen.sh                          regenerates the blobs below, fully offline

src/main/resources/am/ik/rontolisp/codegen/wasm/component/   (generated, packaged)
  mem.wasm  adapter.wasm  import-block.bin
```

The generated `.bin` / `.wasm` are loaded at runtime via the classpath and registered for
GraalVM native image in
`META-INF/native-image/am.ik.rontolisp/rontolisp/resource-config.json`.

```bash
src/wasm-component/regen.sh     # regenerate all three artifacts (needs wasm-tools + python3)
```

## What the blobs declare, and how to see it

The authoritative meaning of all the blobs together is recoverable from any component
this builder emits. Compile anything with `--component` and inspect it:

```bash
java -jar rontolisp-*-exec.jar prog.lisp --component -o prog.wasm
wasm-tools component wit prog.wasm     # shows every imported WASI interface
wasm-tools print prog.wasm | less      # full disassembly (core modules + canon section)
```

`wasm-tools component wit` prints exactly the interfaces these blobs declare:

```
world root {
  import wasi:io/error@0.2.0;
  import wasi:io/streams@0.2.0;       // input-stream.blocking-read + output-stream.blocking-write-and-flush
  import wasi:cli/stdout@0.2.0;       // get-stdout
  import wasi:random/random@0.2.0;    // get-random-u64
  import wasi:clocks/wall-clock@0.2.0;       // now -> datetime{seconds,nanoseconds}
  import wasi:clocks/monotonic-clock@0.2.0;  // now -> u64
  import wasi:cli/environment@0.2.0;  // get-environment -> list<tuple<string,string>>
  import wasi:filesystem/types@0.2.0;    // descriptor.open-at / read-via-stream / write-via-stream
  import wasi:filesystem/preopens@0.2.0; // get-directories
  import wasi:cli/stdin@0.2.0;        // get-stdin
  export wasi:cli/run@0.2.0;
}
```

## Helper core modules (`.wat` is the source, `.wasm` is generated)

These two are real WebAssembly core modules, hand-authored here as text (`.wat`); `regen.sh`
runs `wasm-tools parse` on each to produce the `.wasm` the build loads at runtime, so the
`.wat` is the editable source of truth.

- `mem.wat` -> `mem.wasm` — the shared memory module (6 pages). Exports a linear `memory`
  and a bump-allocator `cabi_realloc`. Instantiated first so the canonical lowering and
  the rontolisp module can both import an already-existing memory (avoids the
  instantiate-before-memory cycle without a lazy funcref trampoline).
- `adapter.wat` -> `adapter.wasm` — the preview1-to-0.2 adapter. Imports the shared memory
  and the lowered WASI 0.2 functions, and exports the eight `wasi_snapshot_preview1`
  functions rontolisp imports:
  - `fd_write` / `fd_read` / `path_open` / `fd_close` — file I/O over `wasi:filesystem`
    (`get-directories` for the preopened dir, `descriptor.open-at`,
    `read-via-stream` / `write-via-stream`) and `wasi:io/streams`
    (`input-stream.blocking-read`, `output-stream.blocking-write-and-flush`), using a
    small fd table (a preview1 file fd is `100 + slotIndex`). `fd_write` to fd 1 is stdout;
    `fd_read` from fd 0 is stdin via a cached `wasi:cli/stdin` input-stream.
  - `random_get` (over `wasi:random`), `clock_time_get` (over `wasi:clocks`
    wall/monotonic), `environ_sizes_get` / `environ_get` (over `wasi:cli/environment`).

  All adapter scratch and the fd table live in page 5 of the shared memory, clear of the
  rontolisp layout (data/heap in pages 0-3, environ scratch in page 3, the canonical
  realloc heap from 65536). The header comment in `adapter.wat` documents the exact
  offsets.

## The unified import block (`import-block.bin`)

`import-block.bin` is the raw component-model **type + import section bytes** for all ten
imported WASI interfaces (component instances 0-9, types 0-15). It is not a standalone
module and cannot be disassembled on its own; it is written verbatim by
`ComponentWriter.writeRaw`, after which `WasmComponentBuilder.build` does all the
remaining wiring (aliases, canonical lowering with the mem module's `cabi_realloc`,
resource drops, instantiation, lifting `run`, exporting `wasi:cli/run`) programmatically.

The single `wasi:io/streams` instance declares **both** `input-stream` and
`output-stream`, and `wasi:cli/stdout` / `wasi:cli/stdin` / `wasi:filesystem/types`
cross-reference that same instance (a component may import `wasi:io/streams@0.2.0` only
once). That cross-instance type reference is why the block is captured whole from a
`wasm-tools`-generated reference rather than assembled per interface.

### How it is generated (what `regen.sh` does)

The sources for the block are all in this directory:

- `uni.wit` — the WIT world importing exactly these interfaces. The **order is
  significant**: it fixes the component instance indices that `WasmComponentBuilder.build`
  assumes, so a new interface must be **appended last** (stdin was added last, keeping the
  earlier indices stable).
- `deps/` — the vendored official WASI 0.2.0 WIT for io / cli / random / clocks /
  filesystem (so regeneration is fully offline).
- `core.wat` — a stub core module: `(memory)` + `(cabi_realloc)` + `(run)` that imports
  every lowered function (this is what makes `wasm-tools component new` emit the imports).

`regen.sh` then runs `wasm-tools component embed . core.wasm --world uni` +
`component new`, and slices out the component's type/import/alias sections (everything
between the 8-byte preamble and the first core-module section) as `import-block.bin`.

The wiring constants in `WasmComponentBuilder.build` were derived from
`wasm-tools dump uni.wasm`: the instance index of each imported function, the component
type indices of the `output-stream` / `input-stream` / `descriptor` resources (aliased out
for `canon resource.drop`), and the canonical options (`memory` / `realloc` / `utf8`) per
lowered function. If `uni.wit` / `core.wat` change, re-run `regen.sh`, then re-derive those
constants from a fresh `wasm-tools dump uni.wasm` and re-run the test suite. Validate end
to end with:

```bash
wasm-tools validate -f component-model prog.wasm
wasmtime run -W gc=y -S inherit-env=y --dir . prog.wasm
```

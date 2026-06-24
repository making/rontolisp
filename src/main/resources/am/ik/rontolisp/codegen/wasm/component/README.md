# WASI 0.2 component blobs

Fixed byte blobs that `WasmComponentBuilder` embeds when wrapping a rontolisp core
module into a WASI 0.2 (Preview 2) component (the `--component` flag). They are
independent of the compiled program, so they are stored here as files instead of hex
string literals.

The build loads these at runtime via the classpath
(`am/ik/rontolisp/codegen/wasm/component/`); the `.bin` and `.wasm` files are registered
for GraalVM native image in
`META-INF/native-image/am.ik.rontolisp/rontolisp/resource-config.json`. The `.wat` files
are documentation/source for reading and editing (not loaded at runtime, not registered).

## What each blob is, and how to see it

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
  import wasi:io/streams@0.2.0;       // output-stream.blocking-write-and-flush
  import wasi:cli/stdout@0.2.0;       // get-stdout
  import wasi:random/random@0.2.0;    // get-random-u64
  import wasi:clocks/wall-clock@0.2.0;       // now -> datetime{seconds,nanoseconds}
  import wasi:clocks/monotonic-clock@0.2.0;  // now -> u64
  import wasi:cli/environment@0.2.0;  // get-environment -> list<tuple<string,string>>
  export wasi:cli/run@0.2.0;
}
```

## Helper core modules (`.wat` is the source, `.wasm` is generated)

These two are real WebAssembly core modules, hand-authored as text (`.wat`). The `.wasm`
is produced from the `.wat` and is what the build loads at runtime. They round-trip
exactly, so the `.wat` is the editable source of truth:

```bash
wasm-tools parse mem.wat     -o mem.wasm       # regenerate after editing the .wat
wasm-tools parse adapter.wat -o adapter.wasm
wasm-tools validate mem.wasm
wasm-tools validate adapter.wasm
# sanity: print mem.wasm and confirm it matches mem.wat
wasm-tools print mem.wasm | diff - mem.wat
```

- `mem.wasm` / `mem.wat` — the shared memory module. Exports a linear `memory` and a
  bump-allocator `cabi_realloc`. Instantiated first so the canonical lowering and the
  rontolisp module can both import an already-existing memory (avoids the
  instantiate-before-memory cycle without a lazy funcref trampoline).
- `adapter.wasm` / `adapter.wat` — the preview1-to-0.2 adapter. Imports the shared memory
  and the lowered WASI 0.2 functions, and exports the `wasi_snapshot_preview1` functions
  rontolisp imports: `fd_write` (over `wasi:io/streams`), `random_get` (over
  `wasi:random`), `clock_time_get` (over `wasi:clocks` wall/monotonic),
  `environ_sizes_get`/`environ_get` (over `wasi:cli/environment`), plus stub
  `fd_read`/`path_open`/`fd_close` (file I/O is not yet supported in component mode).

## Component type / import section fragments (`.bin`)

These are raw component-model **section bytes** — not standalone modules, so they cannot
be disassembled on their own. They encode WASI 0.2 interface type and import declarations
(resources, records, variants, results, lists) whose binary encoding is impractical to
build programmatically. The rest of the component (the `wasi:random` import, all the
core-instance wiring, lowering, lifting and the `wasi:cli/run` export) is built
programmatically by `ComponentWriter` from `WasmComponentBuilder.build`; only these
type-heavy declarations are embedded verbatim.

How they were produced: a reference component declaring exactly these imports was built
with `wasm-tools` from the WASI 0.2 WIT, and the bytes of its component **type** and
**import** sections were captured. To recover or regenerate them:

```bash
# 1. Take any component this builder emits (it already contains the same declarations):
java -jar rontolisp-*-exec.jar prog.lisp --component -o prog.wasm
# 2. Inspect the component sections and their byte offsets:
wasm-tools dump prog.wasm | less        # locate the type/import section byte ranges
# 3. The encodings are also documented inline in ComponentWriter / WasmComponentBuilder.
```

- `import-block.bin` — component **types 0-4** and **instances 0-2**: imports
  `wasi:io/error` (resource `error`), `wasi:io/streams` (resource `output-stream`,
  variant `stream-error`, method `blocking-write-and-flush`) and `wasi:cli/stdout`
  (`get-stdout`). Written raw via `ComponentWriter.writeRaw`. The downstream code in
  `WasmComponentBuilder.build` depends on these exact indices (e.g. component instance 1
  = io/streams, instance 2 = stdout, type 3 = the `output-stream` resource that
  `canon resource.drop` targets).
- `wall-clock-type.bin` — the `wasi:clocks/wall-clock` instance type. Its `now` returns a
  `datetime` record `{ seconds: u64, nanoseconds: u32 }`.
- `monotonic-clock-type.bin` — the `wasi:clocks/monotonic-clock` instance type. Its `now`
  returns a `u64` instant.
- `environment-type.bin` — the `wasi:cli/environment` instance type. Its
  `get-environment` returns `list<tuple<string, string>>`.

## Will these need customizing later?

Yes — file I/O in component mode is the known next step, and it touches both kinds of
blob:

1. **`adapter.wat`** — the `path_open` / `fd_read` / `fd_close` exports are currently
   stubs. They must be implemented over `wasi:filesystem` (`get-directories` /
   `descriptor.open-at` / `read-via-stream` / `write-via-stream`) and `wasi:io/streams`
   (`input-stream.blocking-read`), with an fd table mapping a preview1 fd to the 0.2
   resource handles. Edit the `.wat`, then regenerate `adapter.wasm` (see above). No
   rontolisp-side change is needed: rontolisp already emits these preview1 calls.
2. **`import-block.bin`** — must grow a `wasi:filesystem` import. The catch: filesystem
   `use`s `wasi:io/streams`, which the block already imports for stdout, and a component
   may import `wasi:io/streams@0.2.0` only once. The single `io/streams` instance must
   declare both `output-stream` and `input-stream`, and filesystem must cross-reference
   it. That cannot be expressed by the current per-interface fragments and needs the
   import block regenerated as one unit from a single WIT world.

Whenever a `.bin` is regenerated, the component **type and instance indices** assumed in
`WasmComponentBuilder.build` (the `aliasInstanceFunc` / `canonResourceDrop` / type-index
arguments) must be re-derived to match — `wasm-tools dump` on the new reference shows the
assignment. After any change, re-validate end to end:

```bash
wasm-tools validate -f component-model prog.wasm
wasmtime run -W gc=y -S inherit-env=y prog.wasm
```

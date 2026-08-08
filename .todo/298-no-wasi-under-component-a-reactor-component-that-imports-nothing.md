# `--no-wasi` under `--component`: a reactor component that imports nothing

Difficulty: High

`--component` silently drops `--no-wasi` today (`WasmLispCompiler`:
`this.noWasi = noWasi && !component`, and `RontoLispCli`'s
`reactor = (noWasi && !component) || noGc` follows it). The reason recorded in the
Javadoc -- "a component has its own (lowered) import story" -- describes the WASI
0.3 adapter, and it stops holding the moment the core module imports no
`wasi_snapshot_preview1` function at all: there is then nothing for the adapter to
adapt.

A **spike (2026-08-09, ~70 lines, reverted)** carried the flag through and the
result is a component that **imports nothing**, is **smaller**, and -- the part
that is not just a size win -- **runs its top-level forms**, which the
`--component` path cannot do at all today.

## What the spike measured

`examples/cloudflare-workers/httpbin/worker.lisp`, `--optimize=size`, jar built
from `develop` @ 9329a8ad. wasmtime 47.0.3, wasm-tools 1.252.0, node 24.18,
`@bytecodealliance/jco` via `npx -y`.

| build | bytes | component imports | component exports |
| --- | --- | --- | --- |
| `--no-wasi` (Preview 1 core module) | 182,767 | none | `_initialize`, `handle-request`, `memory`, `__ronto_alloc*` |
| `--component` (today) | 197,239 | `wasi:cli/{types,stdout,stdin}`, `wasi:filesystem/types` | `wasi:cli/run@0.3.0`, `handle-request` |
| `--component --no-wasi` (spike) | **185,582** | **none** | **`handle-request`** |

`jco transpile <c> --instantiation sync -b 0 --bindgen-enable-wasm-exnref`:

| | today | spike |
| --- | --- | --- |
| glue `.js` | 300,216 B | **98,093 B** |
| core `.wasm` | 158 + 840 + 193,787 B | 25 + 25 + 185,329 B |
| `interfaces/*.d.ts` | 5 files | none |
| `ImportObject` | 3 hand-written WASI stubs | `{}` (the generated `.d.ts` says `interface ImportObject {}`) |
| `WebAssembly.promising` at instantiate | reached -- plain `node` fails with `TypeError: WebAssembly.promising is not a function`, needs `--experimental-wasm-jspi` | not reached; plain `node` runs it |

Both the wasmtime `--invoke` and the node/jco runs answered byte-identically to
the `--component` build for `/get` and `/post`.

**Top-level forms run.** This is the headline, not the bytes. The
`httpbin-component` README's third "not obvious" thing -- *"Top-level forms cannot
be run... every top-level definition is silently `nil`"* -- is a consequence of
init living in the `wasi:cli/run` export, which jco cannot drive. A reactor has no
`run`: the spike put the top level in the **core module's `start` section**, which
the engine runs at instantiation, after the data segments install. Probe:

```lisp
(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
(defparameter *greeting* "hello, ")
(defun greet (name) (concatenate 'string *greeting* name))
```

```console
$ wasmtime run -W gc=y --invoke 'greet("world")' tl-reactor.wasm   # --component --no-wasi
"hello, world"
$ wasmtime run -W gc=y --invoke 'greet("world")' tl-base.wasm      # --component
1: wasm trap: cast failure        # *greeting* was never assigned
```

`(print ...)` on the reactor keeps the Preview 1 `--no-wasi` contract exactly:
the `fd_write` sink discards it, at the top level and inside an export, without
trapping (`.kb/wasm-export-no-wasi.md`).

**The clack path comes along.** `examples/net/httpbin-clack.lisp` -- the whole
`ql:quickload`ed clack stack -- compiled and served correctly as
`--component --no-wasi` in the spike (421,298 B), because `reactor` now selects
the `#+rontolisp-reactor` branch of the clack handler shim. So
`examples/cloudflare-workers/httpbin-clack` gets a component build for free, not
just `httpbin-component`.

**No regression in the two existing shapes**, both verified byte-for-byte with the
spike applied: plain `--component` output unchanged, and plain `--no-wasi` output
identical to the checked-in `examples/cloudflare-workers/httpbin/src/worker.wasm`.
`./mvnw test` was green (6316 tests) -- see gap 8 for why that is not as
reassuring as it sounds.

## What the spike changed (the shape of the real work)

1. `WasmLispCompiler` ctor: stop masking `noWasi` with `component`.
2. `RontoLispCli`: `reactor = noWasi || noGc`.
3. `TYPE_START` is `() -> ()` (not `() -> i32`) and the start body drops its
   trailing `i32.const 0` when `component && noWasi`.
4. `run` is not core-exported; a new `WasmWriter.writeStartSection(int)` writes
   section 8 pointing at `FUNC_START`, between the export and code sections.
   `WasmTreeShaker` already treats the start section as a root and already
   renumbers it (`SEC_START`, `rebuildStartSection`), so nothing there changes.
5. `WasmComponentBuilder.buildBase` takes a `reactor` flag and skips the four run
   sections (alias / lift / instance / `export wasi:cli/run@0.3.0`); the
   `appendFuncExports` cursors shift by 0 core funcs, 0 component funcs and 0
   instances instead of 1/1/2.

## The gaps the spike deliberately left (this is the actual todo)

1. **The zero-import property rides on `--optimize`.** The adapter is only
   narrowed under `Narrowing.shake()`, so an unoptimized
   `--component --no-wasi` still imports the full eleven-interface world
   (measured on a one-defun print+export program: 131,715 B / 11 interfaces
   without `--optimize`, 5,179 B / 0 interfaces with it). The adapter core
   module, the import block and the `mem` instantiation must be dropped
   **because `noWasi` says so**, not because narrowing happened to reach zero.

2. **The `wasi:*`-binding library splices are not gated on reactor.** They are
   what is left of the imports after the adapter goes:
   - `StdinLibrary` gave httpbin its `wasi:cli/stdin` (the spike suppressed it by
     passing `serve || reactor`, which reuses the serve stub shape);
   - `SocketsLibrary` + `WaitForLibrary` gave the clack build
     `wasi:sockets/types` and `wasi:clocks/{types,monotonic-clock}`;
   - `EnvironmentLibrary` (`uiop:getenv`) and `HttpLibrary` (`fetch`) are the
     same class, untested in the spike.

   Under `--no-wasi` none of them may splice: the Preview 1 contract for those
   primitives is already the `unreachable` stub, and a reactor must not quietly
   acquire a WASI import the flag says it does not have. Gate them the way
   `NoWasiFilesystemStubs` gates `open`/`with-open-file` -- one shared decision,
   not five call-site conditions.

3. **`--emit-wit` diverges from the bytes.** For a reactor it still prints
   `export wasi:cli/run@0.3.0` while the component has no such export.
   `.kb/wasi-component.md` makes "the emitted WIT and the emitted bytes have to
   say the same thing" an explicit rule; `WitEmitter.emit(VARIANT_BASE, ...)`
   needs the reactor fact.

4. **Drop the `mem` and adapter core modules.** They survive as empty 25-byte
   stubs (jco emits three `.wasm` files, two of them empty). A reactor has ONE
   writer of linear memory, so the core can declare and export its own memory --
   exactly what the Preview 1 build does and what `NoGcWasmComponentBuilder`
   does -- and the component aliases `memory` (and the core's own
   `cabi_realloc`, which the component branch already exports when a `:string`
   boundary is present) off the rontolisp instance. This is the change that
   makes `examples/cloudflare-workers/httpbin-component/src/index.js` genuinely
   simpler: its three-entry `CORE_MODULES` map and the "the count can change
   with the program" warning both exist because of these modules.

5. **`COMPONENT_DATA_BASE_OFFSET` can go back to the Preview 1 base.** The
   0x60000 start exists to keep the core's static data clear of the adapter's
   page-5 scratch. No adapter, no collision -- so a reactor stops reserving
   384 KB of address space per instance, which is worth having on a Worker.

6. **`--no-wasi` + `rontolisp:http-handler` must be a hard error.** A serve
   component's entire surface is `wasi:http`. The spike silently ignored the flag
   there (`noWasi && !serve`); the real thing should name both flags and refuse.

7. **`--no-gc --component`** already is a reactor and must keep `--no-wasi` a
   no-op (it goes through `NoGcWasmCompiler`, so nothing here touches it) --
   worth an assertion so the two reactor paths cannot drift.

8. **`WasmExportCompilerTest.noWasiIsIgnoredInComponentMode` passes vacuously.**
   It asserts the ASCII `wasi_snapshot_preview1` appears somewhere in the
   component -- and it still does with the spike applied, because the bundled
   adapter module carries that string in its own export names. It was green for
   the whole spike. Replace it with an assertion on the component's *imports*
   (`ComponentImportBlock` / `wasm-tools component wit`), and add the reactor
   cases: no import instance, no `wasi:cli/run` export, a start section, and a
   top-level `defparameter` an export can read. `WasmLispCompilerIntegrationTest`
   already has the `--no-wasi` invoke harness (`compileOptimizedAndInvoke`) to
   model the component variant on.

9. **A trap at the top level now kills instantiation, not a host-made
   `_initialize` call.** Same failure class, moved earlier; it makes
   `.todo/284` (`random` traps at `_initialize` on a `--no-wasi` reactor) show up
   as "the component will not instantiate", so the two should be read together.

10. **Docs and examples.** `doc/{en,ja}/compiling/wasm.md` say `--no-wasi` is
    Preview 1 only, and so does the CLI `--help` line. The payoff to write up is
    in `examples/cloudflare-workers/httpbin-component/README.md`: "WASI imports
    to satisfy: 3 interfaces, stubbed by hand" becomes none, the `WASI_STUBS`
    const and its section disappear from `src/index.js`, and the "Top-level forms
    cannot be run" section is retired rather than restated. `--instantiation sync
    -b 0` and `--bindgen-enable-wasm-exnref` are all still required, for the
    reasons that README already gives.

## Acceptance

- `--component` (no `--no-wasi`) and `--no-wasi` (no `--component`) outputs stay
  byte-identical -- both were verified in the spike and are the cheap regression
  gate.
- `wasm-tools component wit` on a `--component --no-wasi` build shows no `import`
  line, **with and without `--optimize`**, for: a scalar export, a `:string`
  export, the httpbin worker, and `examples/net/httpbin-clack.lisp`.
- A top-level `defparameter` is readable from an export on the reactor.
- The `httpbin-component` example builds, runs under `npx wrangler dev`, and its
  `index.js` instantiates with `{}`.

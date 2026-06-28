# `--optimize`: in-house output optimization (dead-code elimination) for WASM and JVM

**Status:** open. Raised in the `claude-opus` session 2026-06-28 while finishing
the `wasm:export` / `--no-wasi` reactor mode. The user wants an opt-in
`--optimize` flag that performs **our own** optimization (primarily dead-code
elimination) for both the WASM and JVM backends, falling back to an external tool
(`wasm-opt`) only as a last-resort compromise if the in-house pass proves too hard.

## Motivation (measured)

A compiled module/class embeds the **entire runtime** (print / ratio / string /
reader / eval helpers, etc.) unconditionally, even when the program uses almost
none of it. Measured on a pure-compute `fact` exported via `wasm:export`:

```
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(wasm:export 'fact :params '(:int) :returns :int)
```

| build              | size       | functions |
|--------------------|------------|-----------|
| default (WASI)     | 26683 B    | 203       |
| `--no-wasi`        | 26430 B    | 211 (+8 trap stubs) |
| `--no-wasi` + `wasm-opt -O3` | **1149 B** | **4** |

`fact` truly needs ~2 functions (the export wrapper + `fact`); the other ~200 are
dead. The `--no-wasi` index-stability trap stubs add only ~40 bytes — they are NOT
the bloat; the always-emitted runtime is. The same waste exists in the default
WASI build and on the JVM backend (every helper method is emitted regardless of
use). So `--optimize` is a general win, not specific to reactor mode.

`wasm-opt -O3 --enable-gc` already does the full DCE + renumber for us (26 KB → 1.1 KB,
still `fact(5) => 120`), which is the target quality bar for the in-house pass.

## Why the current design fights DCE (the hard part)

Function bodies are generated as **raw byte arrays** by the various
`Wasm*Compiler` / `*RuntimeBuilder` classes, each hard-coding `call <fixed index>`
against the fixed `FUNC_*` constants (and the JVM side calls helper methods by
fixed mangled name). There is **no relocation table and no instruction decoder**.
To drop unreachable functions and compact the index space you must renumber every
`call` / `ref.func` / type-index immediate in every remaining body — effectively a
small WASM linker. The `FUNC_*` fixed-offset scheme (see CLAUDE.md "Index
stability") is what makes naive removal shift every index and break every call.

## Approaches (in-house first, per user preference)

1. **Record call edges at codegen time, then DCE + renumber (in-house, preferred).**
   - The compiler already *knows* every `call` it emits (it resolves the callee in
     `WasmExprCompiler.compileCons` / the JVM equivalent). Have it record, per
     emitted function, the set of callee indices — building a call graph for free,
     without parsing bytes. For the hand-rolled `*RuntimeBuilder` helpers, author a
     small **static dependency table** (one entry per `FUNC_*`: which other `FUNC_*`
     it calls) and keep it in sync (a maintenance cost to weigh).
   - Compute reachability from the export roots: `wasm:export` wrappers, plus
     `_start`/`main` (unless a future reactor variant drops it), plus anything an
     embedded `eval`/`load`/`apply`/dispatch can reach dynamically (be conservative:
     when `usesEval`/`indirectCallArities` is in play, keep the dispatch set).
   - Two sub-options for emission:
     a. **Stub-out** unreachable bodies with `unreachable` and KEEP indices — no
        renumbering, low risk; shrinks code (~25 KB → ~1 KB) but leaves the function
        *entries* (≈211). "No dead code" but not visually minimal.
     b. **True compaction**: drop unreachable functions and renumber. Needs to patch
        every function-index / type-index immediate in remaining bodies. Cleanest
        output (~4 functions) but requires either recorded immediate offsets
        (relocations captured at emit time) or a full instruction-length decoder for
        the GC opcode set (struct.new / ref.cast / i31 / array.* are 0xFB-prefixed
        with type-index immediates — easy to get wrong, high risk).
   - Recommended path: start with (a) stub-out behind `--optimize` to bank the size
     win safely, then graduate to (b) compaction if the relocation capture is clean.

2. **JVM side.** The class embeds every runtime helper method + thousands of baked
   constants. DCE here = drop unreferenced methods (reachability over the constant
   pool / method-ref edges) before writing the class. Interacts with
   `.todo/17-jvm-baked-constant-limit.md` (the v50 verifier ceiling) — fewer
   methods/constants also relieves that ceiling, so consider co-designing. Method
   removal is simpler than WASM renumbering (methods are referenced by name, not by
   positional index), so the JVM DCE may land first.

3. **External fallback (`wasm-opt`), compromise only.** If the in-house WASM pass
   stalls, `--optimize` could shell out to `wasm-opt -O3 --enable-gc` when it is on
   `PATH` (ProcessBuilder works from the native image too). Industry standard
   (emscripten / wasm-pack do this). Downsides: external dependency, and it only
   covers WASM (no JVM equivalent in the same tool). Keep as a documented fallback,
   not the primary design, per the user's preference for self-contained output.

## Scope / flag

- New opt-in CLI flag `--optimize` (add to `CliOptions.noValueKeys`, thread through
  `RontoLispCli.compileToFile` into both `WasmLispCompiler` and `JvmLispCompiler`,
  mirroring how `--dynamic` / `--component` / `--no-wasi` are threaded).
- Off by default (no regression to the current deterministic output, which the
  cross-backend E2E and the `--component` blob wiring depend on).
- Especially valuable combined with `--no-wasi` (a pure reactor library should be
  tiny) but should apply to every output mode.

## Verification

- Re-run the four-backend matrix (interpreter unaffected; JVM / WASM Preview 1 /
  WASM component) on representative programs WITH and WITHOUT `--optimize`, asserting
  identical observable behavior and strictly smaller output.
- For WASM: `wasm-tools validate` the optimized module; confirm scalar and memory
  `wasm:export` round-trips still work (reuse the `--no-wasi` Node/wasmtime checks);
  confirm `usesEval`/`load`/`apply` programs still resolve dynamically (conservative
  reachability did not drop a dispatch target).
- For JVM: load + run the optimized class; confirm a large baked program still loads
  (and ideally that DCE raises the `.todo/17` ceiling in practice).

## Touch points

- `codegen/wasm/WasmLispCompiler.java` (call-edge recording, reachability, the
  stub/compaction emission, `FUNC_*` handling), the `Wasm*RuntimeBuilder` static
  dependency table, `am.ik.wasm` (if a relocation/decoder layer is added).
- `codegen/jvm/JvmLispCompiler.java` + `am.ik.jvm` (method-level DCE).
- `cli/CliOptions.java`, `cli/RontoLispCli.java` (the `--optimize` flag).
- README (document the flag + the size win) and CLAUDE.md "Index stability" /
  "JVM Class Version 50" notes (how DCE interacts with the fixed-index invariant).
- Related: `.todo/17-jvm-baked-constant-limit.md` (JVM DCE relieves the ceiling),
  `.todo/21-wasm-export-memory-abi-ci-coverage.md` (reuse its host harness to verify
  optimized memory exports).

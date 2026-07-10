# 110 — `--no-gc` print / stdout (fd_write) + `with-arena` (executes todo 104)

## DONE 2026-07-10 (develop, on top of 6108b34; uncommitted)

Implemented exactly per the design sketch below. Record of the open decisions
and outcomes:

- **Open decisions settled:** float printer INCLUDED in release 1 (`__ftoa`, a
  string-producing port of the GC `FUNC_PRINT_F64_NO_NL` with the todo-108
  hardening — NaN/Infinity/-Infinity via static literal headers, `-0.0` by sign
  bit, >= 2^63 E-notation; it also un-errors `princ-to-string` of a float);
  `prin1` SKIPPED, `format` deferred (print/princ/terpri only; `print` uses
  prin1 semantics for strings — quoted — matching the interpreter); `_start`
  stretch OUT of scope -> **`.todo/111`** (which also carries the examples.yaml
  RUN-token + printing-mandelbrot residuals); `print` newline semantics = the
  interpreter's exactly (`Environment`: print = prin1 text + TRAILING newline,
  princ = display text no newline, terpri = newline; print/princ return their
  argument).
- **Mechanics:** `Mem.printUsed` (syntactic `usesPrintOp` scan) gates the ONE
  `wasi_snapshot_preview1.fd_write` import (type+function index 0; every local
  function index shifts by `Mem.funcBase()` = 1, all behind the
  `Mem.funcIndex()`/`*Index()` accessors — nothing hardcodes the shift) + the
  `__write_stdout(ptr,len)` funnel, the SOLE caller of the import (the todo-93
  seam). `Mem.ftoaUsed` (typed `rendersFloat` scan against the frozen inference)
  gates `__ftoa`. Print literals + the fd_write iov scratch (16B between static
  data and heapBase) exist only when printing. print/princ of INT/FLOAT bracket
  the transient digits in a heap-pointer mark/reset.
- **Value-model limits (documented):** literal `t`/`nil` print by name, a
  COMPUTED boolean prints its 0/1 integer; stream args and packed-array
  printing are compile errors.
- **with-arena (todo 104's `--no-gc` half):** `rontolisp:with-arena` added to
  `LispNames`/`PackageRegistry` (rontolisp package external) +
  `LispMacroExpander.expandWithArena` -> `progn` dispatched by the
  interpreter/JVM/wasm-GC; `NoGcWasmCompiler.compileWithArena` = mark / body /
  reset with copy-down of a reference result (string/F64VEC/F32VEC/F64MAT/
  F32MAT; `v >= mark` -> memcpy down + heap just past the copy, else plain
  reset), plain progn on a memoryless module.
- **Verified:** print-free byte identity via stash dance (4 programs x
  scalar/--simd/--simd --optimize/--optimize: SHA-identical); import bytes
  pinned BOTH directions (`printGatesTheFdWriteImportOnAndOff`); wasmtime
  stdout parity incl. IEEE edges (`noGcPrintWritesToStdoutMatchingTheInterpreter`,
  also under --optimize); heap flat under a 2-page `-W max-memory-size` cap for
  a 20000-print loop AND a 100000-iteration with-arena loop where the bare loop
  traps; arena value escape (copy-down) checked via --invoke and a Node host
  (pages 1->1, heap 64->64). ci-spec: `with-arena-is-observationally-a-progn`
  (4 standard backends).
- **Docs:** doc/{en,ja}/compiling/wasm.md (Printing + with-arena sections,
  embedding caveat: a printing module no longer instantiates with `{}` — supply
  `{ wasi_snapshot_preview1: { fd_write } }` or use node:wasi), new
  reference/macros/rontolisp-with-arena.md (+ _catalog.yaml + macros.md row),
  rontolisp-wasm-export.md limitation bullet, `.kb/no-gc-scalar-wasm.md`,
  CLAUDE.md `--no-gc` bullet, `.todo/104` (--no-gc half marked done),
  `.todo/93` (fd_write/print decision + stale `ScalarWasmCompiler` name fixed).

Everything below is the original investigation, kept for reference.

---

**Question this answers:** does todo-104 (`with-arena`) unlock strings and `print` on
`--no-gc`? Investigated 2026-07-10 on develop @ 18e72a1.

**Short answer:** strings already mostly work on `--no-gc`; `print` is blocked by the
zero-import design, NOT by memory. `with-arena` alone would not add `print`. But the
two belong in one session: `print` needs a transient rendered string per call, so
without the todo-104 mark/reset primitive a print loop grows the bump heap forever.
Implement the fd_write import + printing ops + the arena together.

## Findings (grounded 2026-07-10; re-grep line numbers before relying on them)

What `--no-gc` ALREADY has for strings:
- String type = `[len:i32][bytes]` linear-memory headers; literals, `concatenate
  'string`, `subseq`, `string=`, `char`/`char-code`/`code-char`/`char=`,
  `princ-to-string` (int via the `__itoa` helper, string passthrough), `:string`
  params/returns across the wasm-export ABI (`NoGcWasmCompiler`; index chain
  memcpy/streq/itoa/mark/reset around lines 785-853, `itoaBody` ~1128).
- `princ-to-string` of a FLOAT is a clear compile error: "no float printer in scalar
  mode" (~3828-3845).
- Heap: bump global 0, `__alloc`, auto-reset on scalar-return exports (todo 88),
  host `__ronto_alloc_mark`/`_reset` (todo 89). Nothing freed WITHIN a call — that
  intra-call hole is exactly todo-104's remaining scope.

What blocks `print`:
- `--no-gc` implies `--no-wasi` and emits ZERO imports (`RontoLispCli.java` ~238-246;
  measured in `.todo/93`: scalar exports have 0 imports — that is the todo-93
  compact-component selling point).
- `collectCallsCons` rejects `print` outright: "unsupported operation 'print' ...
  (not a numeric primitive or an eligible function)".
- The GC backend's porting references: full f64 printer `FUNC_PRINT_F64_NO_NL` in
  `WasmRuntimeBuilder` (hardened by todo 108: NaN/Inf/-0.0/2^63) and the
  fd_write-over-IOV-scratch shape in `WasmIoRuntimeBuilder` (~143-240).

## Design sketch

1. **Conditional WASI Preview-1 import.** Emit
   `(import "wasi_snapshot_preview1" "fd_write")` ONLY when the program uses a
   printing op. Print-free programs keep 0 imports — preserves todo-93's story and
   any-MVP-runtime portability, and their output must stay **byte-identical** to
   before (stash-dance proof like todo 99). `NoGcWasmCompiler` assigns every function
   index itself, so the import can occupy index 0 natively with a conditional base
   offset threaded through the `fn.mem.*Index()` accessors — no `WasmImportInjector`
   post-pass needed.
2. **`print`/`princ`/`terpri`** (decide on `prin1`; recommend deferring `format`) for
   int/float/string/bool: render via the princ-to-string machinery, write
   `[ptr+4, len]` through an IOV scratch (port the `WasmIoRuntimeBuilder` shape).
   Output must match the other backends exactly (newline conventions) — pin with
   integration tests capturing wasmtime stdout.
   **The todo-93 seam:** funnel ALL output through ONE internal helper
   `__write_stdout(ptr, len)` — the sole caller of the fd_write import. The print
   ops never touch the import directly, so a later todo-93 component variant can
   swap the implementation (micro-adapter, different import, buffer) in one place.
3. **Float printer port** so `(print 3.14)` and `princ-to-string` of a float work:
   port `FUNC_PRINT_F64_NO_NL`'s algorithm into a `--no-gc` string-producing helper.
   Recommend doing it in release 1 (floats are `--no-gc`'s bread and butter; todo
   46/108 already hardened the algorithm). If it turns out large, ship int/string
   print first and leave the float printer as the recorded residual.
4. **`print`'s internal bracket:** mark before rendering the transient string, write,
   reset after (a string argument is passthrough — nothing allocated). A print loop
   keeps the heap flat by construction.
5. **`with-arena` (todo 104 proper):** `rontolisp:with-arena` as a
   `LispMacroExpander` lowering — plain `progn` on interpreter/JVM/wasm-GC (a real GC
   already reclaims); on `--no-gc` mark / body / reset with a copy-down of a
   string / packed-array result value (dst < src, forward memmove is safe). Escape
   contract per `.todo/104`: nothing allocated inside may be reachable after, except
   the body's value. On `--no-gc` there is no intern registry, so 104's
   intern-count-guard question does not arise (it is wasm-GC-only; defer that half).
6. **STRETCH — `_start` command-module mode.** With `print`, `--no-gc` could accept
   top-level non-defun forms compiled into `_start`, making `wasmtime run prog.wasm`
   work directly; `examples/examples.yaml` could gain a RUN token for `--no-gc` and
   console examples (mandelbrot!) could run and be output-checked. Bigger surface
   (top-level compilation, no global `setq` on this backend) — assess early in the
   session and either scope it in or file a follow-up todo.

## Alignment with todo 93 (compact `--no-gc --component`)

Todo 93 wraps the `--no-gc` module as a tiny adapter-free component precisely
BECAUSE it has 0 imports ("reactor with only exports and no imports"). 110 must
preserve that foundation and leave 93 easier, not harder:

- **0-import property stays conditional, not lost.** A print-free program keeps 0
  imports and byte-identical output; only a printing program gains the single
  fd_write import. 93's adapter-free wrap continues to apply verbatim to print-free
  programs.
- **Print + `--component` decision.** A printing core module has a
  `wasi_snapshot_preview1.fd_write` import the adapter-free wrap cannot satisfy.
  Release 1 of 93 should make `print` under `--no-gc --component` a clear compile
  error; a later phase can add a micro-adapter (a minuscule core module implementing
  fd_write over `wasi:cli/stdout`, the adapter-serve-p1 pattern in miniature). The
  `__write_stdout` seam above is what makes that swap local. `--component` is
  rejected under `--no-gc` today anyway, so 110 itself only needs the seam — record
  the decision, no component work.
- **Shared plumbing 93 will reuse:** the exported `__ronto_alloc`/`_mark`/`_reset`
  (93's realloc-shim + arena story), the `fn.mem.*Index()` accessor-driven index
  chain (110's conditional import shifts every function index by a base offset when
  print is used — keep ALL index math behind the accessors so 93's per-export
  `aliasCoreFunc` wiring never hardcodes indices), and the `:string` export ABI.
- **Stale name in 93:** `.todo/93` (and `.todo/92`) still say `ScalarWasmCompiler` /
  cite old RontoLispCli line numbers; the class is `NoGcWasmCompiler` since todo
  100. Fix the references when 93 starts (or opportunistically during 110).
- Ordering: 110 has no dependency on 92/93. Doing 110 first with the seam keeps 93
  unblocked; 93 still depends on 92 (canonLift + type mapping) per its own header.
- **Considered and REJECTED (2026-07-10): allowing print only under `--component`.**
  It does not simplify — the core module needs the render machinery and an import
  either way, and the component variant ADDS canonLower glue or a micro-adapter blob
  for `wasi:cli/stdout` (the byte-identical-blob maintenance world) while breaking
  93's adapter-free selling point and sequencing print behind 92 -> 93. The plain
  Preview-1 fd_write import is the minimal build: the HOST implements fd_write for
  free (wasmtime, Node's built-in `wasi` module, any P1 host), and it is what makes
  the `_start` stretch possible. Note the raw-embedding caveat for docs: a printing
  module no longer instantiates with `{}` imports (the mandelbrot-nogc Node snippet
  style) — the embedder must supply a P1 `fd_write` or use `node:wasi`.
- **with-arena stays necessary regardless of the print decision.** print's own
  transient string is covered by its internal mark/reset bracket, but user-level
  allocation inside one export call is not: `concatenate 'string` in a loop (strings
  have no `-into`) and `vec:zeros`/`from-list` in a loop — the exact todo-104 gap.
  A string built in a loop and THEN printed is allocated before print's mark, so
  only a with-arena around the loop body reclaims it; print makes that pattern more
  common, not less.

## Constraints

- Rank-1/rank-2 packed layouts, existing `--no-gc` output for print-free programs,
  and all other backends stay byte-identical.
- No new imports unless the program prints (pin with a unit test on the import
  section bytes, both directions).
- Docs: `doc/{en,ja}` mirrored in the same commit (compiling/wasm.md `--no-gc`
  section + wherever print support is tabled), `.kb/no-gc-scalar-wasm.md`, CLAUDE.md
  `--no-gc` bullet, `.todo/104` (mark the `--no-gc` half executed here, leave the
  wasm-GC half), this file's completion record.
- ci-spec is the 4 standard backends and does not cover `--no-gc`; coverage lives in
  `NoGcWasmCompilerTest` (byte pins) + `WasmLispCompilerIntegrationTest` (wasmtime
  stdout) + examples harness if the `_start` stretch lands.

## Verify

- Unit: import section empty without print / exactly fd_write with; itoa/float
  printer byte pins; with-arena expands to progn on the other backends (existing
  macro-expander test style).
- Integration (wasmtime): `--invoke` an export that prints — stdout matches the
  interpreter for the same forms (int, float incl. -0.0/NaN/Inf if the float printer
  lands, string, bool/nil).
- Heap flat: N prints in a loop and `(with-arena () (vec:zeros 1000))` in a loop
  leave `memory.size` unchanged (the `.kb/no-gc-scalar-wasm.md` count-vowels
  measurement shape).
- Byte-identity stash dance for print-free programs (scalar / --simd / --simd
  --optimize).
- Full suite `./mvnw spring-javaformat:apply test` (baseline 3151/0, 2 skip; count
  may drift a few with env-dependent example dynamic tests), DocExamplesTest 436/0,
  `-Pweb compile`, javadoc (Version-only error OK), native E2E 772/0.
- Note: main repo `target/` may hold a stale pre-rename jar — rebuild before probing.

## Open decisions (settle at session start)

- Float printer in release 1? (recommend yes)
- `prin1` yes/no; `format` deferred (recommend defer; todo 01 territory)
- `_start` stretch in scope or follow-up todo
- Exact newline semantics of `print` — copy the interpreter, pin cross-backend

## Related

- `.todo/104` (with-arena design — the `--no-gc` half is executed by this todo),
  `.todo/93` (compact `--no-gc --component`; keep the 0-import property conditional),
  `.todo/46`/`108` (float printer provenance), `.kb/no-gc-scalar-wasm.md`,
  `.kb/wasm-gc-strings.md`.

# `--no-gc`: a non-GC WASM lowering for pure-numeric exports

**Status:** Open (design captured 2026-06-28). Raised in the `claude-opus` session
right after `--optimize` + the `--no-wasi` `_initialize` rename, while discussing how
practical the optimized no-wasi reactor already is. The optimized no-wasi module is
import-free and tiny, but it still requires a **wasm-GC-capable** runtime (`wasmtime -W
gc`, a GC-enabled browser) because every value is a GC heap type (`i31ref`/`struct`/
GC `array`/`eqref`). This task adds an opt-in lowering that emits a **plain (non-GC)**
module for a restricted subset, so the output runs on any MVP-class runtime with no
`-W gc` and no import object.

## Why this is viable (and why it is NOT "rip GC out of the current backend")

You cannot strip GC from the existing output: the value model *is* wasm-GC (ints are
`i31ref`, cons is a `struct`, strings/arrays/hash are GC arrays, the runtime is written
against `eqref`). There is no GC->MVP post-pass (wasm-opt has none either). So this is a
**separate lowering**, not a flag on the current codegen.

The reason it is still tractable: if an exported function's **entire transitive call
graph touches only numbers** (no cons/list/string/symbol/char/hash/eval/apply), then the
whole computation closes over `i32`/`i64`/`f64` and never needs a heap at all. That is
exactly the "pure-compute scalar export" sweet spot we already target with `--no-wasi`.
`fact`, numeric kernels, validators, math/finance functions all fit. So the new backend
is essentially "compile the numeric core directly on unboxed locals" — a thin parallel
lowering, not a full second runtime.

Bonus: unboxing widens the integer range. Today fixnums are `i31` (~+-10^9); a non-GC
path can use `i32` (full 32-bit) or `i64`. Floats stay `f64`. So for pure numerics the
non-GC output can be numerically *better*, not just smaller.

## Scope

### In scope (Phase 1 — scalar)
- Exports declared with scalar designators only: `:int`, `:float`, `:bool`
  (and `:void`/omitted returns).
- A function is **eligible** iff its transitive call graph (the exported `defun` plus
  every `defun` it reaches) uses ONLY:
  - integer / float / boolean literals and `t`/`nil` as booleans,
  - arithmetic: `+ - * / mod rem 1+ 1- abs min max` (numeric only),
  - comparison/logic: `= /= < <= > >= and or not zerop plusp minusp evenp oddp`,
  - control: `if when unless cond progn let let* and-or, recursion, calls to other
    eligible functions`,
  - float<->int: `float truncate floor ceiling round` (map to wasm conversions).
  - NO cons/list/car/cdr, string/char, symbol, vector, hash-table, `eval`/`apply`/
    `funcall` with a computed function, `read`/`print`/I/O, or anything heap-allocating.
- Ineligible function used by a `--no-gc` export => **compile error** naming the
  offending op (so the boundary is explicit, never a silent miscompile).

### Phase 2 (optional, later) — strings/sexpr via linear memory
- `:string`/`:sexpr` do need memory, but **linear memory is an MVP feature** — no GC
  required. Represent strings as `(ptr,len)` in linear memory with a bump allocator
  (reuse the `__ronto_alloc` idea), independent of the GC `array` string repr. This is
  more work (a second string runtime) and should only follow if Phase 1 proves useful.

### Out of scope (this is backend "B", a different project — see `.todo` note below)
- Full language (cons/list/closures/eval) on MVP wasm. That is a linear-memory value
  representation + allocator (NaN-boxing or tagged words, cons = address, mark-sweep or
  bump GC in linear memory) — essentially the pre-wasm-GC approach, a whole second
  runtime. Do not scope-creep Phase 1 into this.

## Module shape produced
- No rec group, no `struct`/`array`/`i31` types, no `eqref`. Function params/results and
  locals are `i32`/`i64`/`f64` only.
- No `wasi_snapshot_preview1` imports (this mode pairs with / implies `--no-wasi`; it is a
  pure-compute reactor). Import-free: instantiate with no import object.
- Reactor init entry exported as `_initialize` (same convention as `--no-wasi`, see
  CLAUDE.md). For pure-scalar exports there is usually no top-level init at all, so it can
  often be dropped by `--optimize`.
- Feature level: core MVP plus, if used, `sign-extension` and `nontrapping-float-to-int`
  (both ubiquitous since ~2020; decide whether to require or avoid them). Validate with
  `wasm-tools validate` *without* `-f gc`, and run with plain `wasmtime run` (no `-W gc`).

## Flag / wiring design
- New opt-in CLI flag `--no-gc`. Pairs with `--no-wasi` (both pure-compute reactor); decide
  whether `--no-gc` implies `--no-wasi` or requires it explicitly (recommend: implies, and
  error if combined with `--component`, since the component path is GC-bound).
- Thread a `noGc` boolean the same way `dynamic`/`component`/`noWasi`/`optimize` are
  threaded (CliOptions.noValueKeys, RontoLispCli.compileToFile, WasmLispCompiler ctor).
- **Recommended structure:** a **separate backend class** (e.g.
  `codegen.wasm.scalar.ScalarWasmCompiler`) rather than branching the GC `WasmLispCompiler`
  everywhere. The GC compiler's fixed-`FUNC_*`-index invariant and 200-function runtime are
  irrelevant here (no runtime is emitted), so a clean small compiler that only knows numeric
  ops is far simpler and keeps the GC path untouched (zero regression risk). `RontoLispCli`
  dispatches to it when `--no-gc` is set.

## Interaction with `--optimize`
- `WasmTreeShaker` is GC-agnostic (it only renumbers function indices; it copies type/
  memory/global/data verbatim and already enumerates the `0xFB` GC opcodes but a non-GC
  module simply won't contain them). So `--no-gc --optimize` should compose for free, and
  the decoder is actually *simpler* on a non-GC module. Add a corpus case.
- Likely the scalar lowering already emits near-minimal code (few functions), so the win
  from `--optimize` is smaller here, but it still drops anything unreachable.

## Open decisions (resolve before implementing)
1. Integer width & overflow: `i32` (wrap? trap on overflow?) vs `i64`. No bignum either
   way — document the range. Recommend `i64` for headroom, `i32.const`-boxing-free.
   `/` on integers: Lisp `/` returns a ratio (`1/3`) — but ratios are heap values, so
   under `--no-gc` integer `/` with a non-dividing result must either be a compile error
   or be restricted to `truncate`/float division. Decide the `/` semantics explicitly.
2. Does `--no-gc` imply `--no-wasi`, or require it? (Recommend: imply.)
3. Eligibility analysis location: a pre-pass over the reader AST (before the GC compiler
   would run), walking the call graph from each `--no-gc`-relevant export. Reuse
   `compiler.FunctionDesignators` / the existing Pass-1 defun collection if convenient.
4. Error message shape for an ineligible op (name the op + the function + why).
5. Phase 2 string ABI: linear-memory `(ptr,len)` + bump allocator, or defer entirely.

## Verification
- Structural (no Docker): module has no rec group / no GC types; `wasm-tools validate`
  (no `-f gc`) passes; only `i32`/`i64`/`f64` in signatures.
- E2E: `wasmtime run --invoke fact fact.wasm 5` **without `-W gc`** => `120`; same in a
  non-GC browser context (the web playground harness) to prove the "runs anywhere" claim.
- Parity: the `--no-gc` result must equal the GC backend + interpreter for the same pure
  function across a range of inputs (add to the cross-backend matrix or a dedicated test).
- Ineligibility: a `--no-gc` export that (transitively) conses / uses a string must fail
  to compile with a clear message, not miscompile.

## Touch points
- `cli/CliOptions.java`, `cli/RontoLispCli.java` (the `--no-gc` flag + dispatch).
- New `codegen/wasm/scalar/ScalarWasmCompiler.java` (the lowering) over `am.ik.wasm`
  (reuse the section/encoder writers; emit no rec group).
- An eligibility analyzer (call-graph closure over the numeric-op allow-list).
- `am.ik.wasm.WasmTreeShaker` corpus/test additions for the non-GC shape.
- README ("No-WASI (reactor) mode" / "Optimize" neighbourhood) + CLAUDE.md (a new
  design-constraint bullet describing the non-GC scalar backend and its eligibility rule).
- Related: `.todo/22` (`--optimize`, composes), `.todo/21` (`--no-wasi` memory ABI, the
  Phase-2 string path reuses its host harness), `.todo/09` (function arity cap).

## Recommendation
Land **Phase 1 (scalar int/float/bool) only** first as a self-contained backend behind
`--no-gc`. It is the high-value, low-risk slice: it makes the "import-free, runs on any
MVP runtime, ~1 KB pure function" story real without a wasm-GC dependency, and it does not
touch the GC backend at all. Treat Phase 2 (linear-memory strings) and the full
linear-memory runtime as separate, later decisions.

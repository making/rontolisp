# 97 — linalg single-float (`#f`) input/output support

**Motivation (surfaced by `examples/ml/nn-vec.lisp`, todo-95 Part 2, 2026-07-08):** the
`vec:` + `linalg:` neural-network example had to be **double-float throughout**, purely
because `linalg` always PRODUCES double. Mixing a single-float (`#f`) `vec:` vector with a
`linalg` result (always `#d`) works on the scalar path (both widen to f64) but is a hard
**mixed-width error under JVM `--simd`** — and, worse, a functional weight update like
`(linalg:sub W2 ...)` would silently flip a single-float `W2` to `#d`, so the next
`(vec:matvec W2 x)` sees a `#d` matrix and an `#f` `x` → `--simd` mixed-width error. So a
fully single-float NN is impossible while `linalg` is double-only. Making `linalg`
width-polymorphic unlocks an all-`#f` transformer/NN (½ memory, 2× SIMD lanes — the llama2
payoff), and would let `nn-vec.lisp` (or the todo-98 SIMD demo) run in true f32.

**This was ALWAYS the plan** — todo-95 approved decision #4: "linalg = accepts single inputs
FOR FREE (aref widens) but ALWAYS produces double — with a **forward-compat funnel**: route
every linalg result-array alloc through ONE helper `linalg::%la-make(dims init &optional
element-type)` (introduced NOW, always `'double-float`) so later 'produce single too' = thread
a **literal** `:element-type` through it + the public constructors (localized, not 33-fn
churn)." That seam already exists and is waiting.

## The seam (already built, Part 1 Phase 1)

`src/main/resources/am/ik/rontolisp/eval/linalg.lisp`:
- `linalg::%la-make(dims init)` (~L26) is the SINGLE allocation funnel; **19 constructor call
  sites already route through it** (verified 2026-07-08). Its comment already says it is the
  forward-compat seam for exactly this change.
- Today it hardcodes `(make-array dims :element-type 'double-float :initial-element init)`.
- **Hard constraint (compile path):** the `:element-type` MUST stay a **compile-time literal**
  — the JVM/WASM backends pick the `double[]`/`float[]` (or `TYPE_F64ARR`/`TYPE_F32ARR`,
  `F64VEC`/`F32VEC`) repr from a literal element-type; a runtime-computed element-type cannot
  select the packed repr statically. So single-float production = an explicit literal
  `:element-type 'single-float` at the call site / a literal branch in `%la-make`, NOT
  automatic input-type promotion.

## Approach (sketch — re-ground before starting)

1. **`%la-make(dims init &optional element-type)`**: add the optional param; branch on a
   LITERAL `'single-float` vs `'double-float` (both `make-array` calls literal, so each is
   statically compilable — mirror `vec::%make-like`'s two-literal-branch pattern in
   `vec.lisp`).
2. **Public constructors that should be able to produce single**: thread a literal
   `:element-type` keyword through `zeros`/`ones`/`full`/`eye`/`arange`/`linspace`/`from-list`
   /`reshape`/`transpose`/`add`/`sub`/`mul`/`div`/`emap`/`dot`/`matmul`/`outer`/... down to
   `%la-make`. Decide the policy: (a) a default (stay double for back-comat) + opt-in
   `:element-type 'single-float`, or (b) **width-following** (a single-float INPUT →
   single-float OUTPUT, like `vec:`), which is the more useful "産み分け" but needs the input
   width known at COMPILE time (an `#f` literal or a `'single-float` make-array is statically
   known; a runtime-passed array is not — so width-following only compiles when the width is
   statically inferable, else default double). Lean toward (a) explicit opt-in first (always
   compilable), consider (b) later.
3. **Reads already widen** (aref f32→f64), so consuming `#f` inputs is already free — only
   PRODUCTION is the work.
4. **Per-backend**: no new repr needed — the single-float packed array already exists on
   interpreter/JVM/wasm-GC (todo-95 Part 1). `--no-gc` linalg is already unsupported (arrays),
   so no change there. The work is entirely in `linalg.lisp` + threading literals.

## Verify

- `LinalgLibrary` / interpreter lazy-load + compile-path splice unchanged.
- Cross-backend: a `linalg` single-float result prints `#f(...)` on interpreter/JVM but (like
  `vec:`) **`#d(...)` on wasm-GC** (the `#+rontolisp-wasm` double-only branch of the shared
  seam — same constraint as `vec::%make-like`). So a ci-spec case pinning single-float linalg
  output must respect the wasm-GC width divergence (or stay double); mirror the todo-95
  `packed-single-float-cross-backend` constraints (f32-exact printed values, reductions safe).
- Update `.kb/linalg.md` (the "always double" / `%la-make` paragraphs) + the linalg guide.
- **Payoff demo:** flip `examples/ml/nn-vec.lisp` (or the todo-98 demo) to all-`#f` and confirm
  it stays `--simd`-clean (no mixed-width) end to end.

## Pointers

- Seam: `linalg.lisp` `%la-make` (~L26), 19 call sites. Pattern to mirror: `vec.lisp`
  `vec::%make-like` (the two-literal-branch, `#+/#-rontolisp-wasm` split).
- Design decision #4 + the funnel rationale: `.todo/95` "linalg = accepts both, produces
  double (forward-compat for later 産み分け)".
- Repr per backend: `.kb/linalg.md`, `.kb/vec.md` (single-float reprs), todo-95 Part 1.
- The mixed-width `--simd` error that forced this: `JvmSimdVectorTemplate.mixedWidth()`.

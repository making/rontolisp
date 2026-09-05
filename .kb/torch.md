# The `torch` package (tensor + reverse-mode autograd over linalg)

One Lisp library `src/main/resources/am/ik/rontolisp/eval/torch.lisp` (driver `eval.TorchLibrary`,
a `LinalgLibrary` mirror), so one implementation runs on all four backends. **torch NEVER
reimplements a kernel — it wraps a `linalg:` one and adds its adjoint** (`.kb/linalg.md`).

## Records

Three `defstruct`s (tensor, module, optimizer); everything else a plain defun, no CLOS. A bundled
defstruct expands into its generated defuns AHEAD of reachability (`BundledStructs` in
`LibraryDefunPruner`, `.kb/library-defun-pruning.md`), so each accessor prunes INDIVIDUALLY. Each
carries `(:print-object ...)` (`.kb/defstruct.md`), printing identically on all four backends;
**the printers NEVER spell the tape slots** (`backward-fn` is a closure).

- `torch::%tensor` — `torch::%t-new`, conc-name `torch::%t-`, predicate `torch:tensorp`, no copier,
  no `:type`. Fields `store` (linalg array / NUMBER for the rank-0 scalar / `torch::%view`; read it
  through the defun `torch::%t-data`, which materializes — the accessor `%t-store` is written by
  `torch:set-data` and the optimizers only), `grad` (nil or a RAW linalg value, not a tensor),
  `requires-grad` (the LEAF flag), `parents`, `backward-fn` (`(lambda (grad-out) ...)` -> per-parent
  gradient list, nil for untracked parents).
- `torch::%t-wrap` wraps operands; `torch::%t-result` records a tape edge only while
  `torch::*grad-enabled*` AND some parent tracks (`torch:requires-grad-p`).
- **`eq`/`eql` on a record instance is REFERENCE identity on every backend**
  (`Environment.isIdentityAggregate`); `%t-topo`'s visited marking and `%m-collect`'s dedup use
  `member` and mean IDENTITY. `equal` stays structural (`.kb/instance-syntax.md`).
- **The interpreter must load torch BEFORE it decides a print's routing** — the route is picked at
  FORM expansion, before the lazily-loading argument is evaluated, so
  `LispEvaluator.referencesTorch` triggers `ensureTorchLoaded()` there. **Torch is the only lazily
  loaded library defining a `print-object` method; a second one needs the same treatment.**

## The element width: `torch::*default-element-type*`

**Invariant: every torch value is ORIGINATED at single-float (`#f`); `linalg`'s own default stays
`#d`.** `(defparameter torch::*default-element-type* 'single-float)`, read (never assigned) at each
origination site; widths are otherwise INHERITED (`%la-etype` -> `%la-make`). Rebinding it around
model CONSTRUCTION builds in `#d`; there is no `with-dtype` macro.

**Trap: one missed origination site runs the model at MIXED widths, a decline condition for every
`--simd` kernel** (`.kb/linalg-simd.md`) — silent fallback to the scalar defun everywhere.

- Origination sites: `torch:tensor` (applied by `torch::%t-as-data`, so it CONVERTS a `#d` source;
  `:element-type nil` preserves it), `torch:parameter`, `torch:linear`, `torch:embedding`,
  `torch:layer-norm`, `torch:dropout`, `torch:pad-sequence` (bypasses `%t-as-data`).
- Deliberately still `#d`: `torch::%t-indices`, `%m-ce-indices`, `torch:topk`, `torch:multinomial`,
  `torch:padding-mask`, `torch:subsequent-mask`, `torch::%o-buffers`' scalar-parameter buffer,
  `examples/llm-from-scratch/transformer/utils.lisp`'s positional encoding, and `TorchGradcheck`
  (`eps 1e-4` differences against `tol 1e-3`).

## The two invariants of `backward`, and the adjoints

1. **Reverse topological order, computed explicitly** (`torch::%t-topo`, DFS over parents, identity
   visited marking). Tape order would be wrong for any reconvergent graph.
2. **Accumulate, never assign** — `torch::%t-accum` does `grad += g`, so a tensor reached over more
   than one path collects the SUM. `backward` requires a scalar tensor and seeds 1.0; grads are
   RETAINED on intermediates.

Shared helpers: **`torch::%t-unbroadcast`** (sum over each leading axis the operand lacks and each
extent-1 axis), `%t-keepdims` / `%t-grad-bcast` (reductions), `%t-grad-reshape` (rearrangements;
`view` IS `reshape` — nothing aliases), `%t-mm-grad-a/-b` (matmul, through
`linalg::%la-matmul-nd-tb` / `-ta`), `%t-axis-spec` (`cat`/`stack`), `%t-slice-scatter` (`slice`),
scatter-ADD into `zeros-like` (`gather`/`index-select`). `var`/`std`: composed, no bespoke adjoint.
`argmax`/`topk`/`multinomial`: none, they answer a RAW value. `dropout`/`masked-fill`: the mask is a
CONSTANT operand (`-inf` fills survive softmax, `.kb/linalg.md`). One member each for
`softmax`/`log-softmax` `:axis`, `gelu`, `layer-norm`: `linalg::%la-softmax-grad`,
`%la-log-softmax-grad`, `%la-gelu-grad`, `%la-layer-norm-grad`. **Extend
`testsupport/TorchGradcheck` with one `gc-check` row per new op.**

## `torch:no-grad`: the one macro, and its three seams

A BUILT-IN `LispMacroExpander` expansion (`expandTorchNoGrad` -> `(let ((torch::*grad-enabled*
nil)) body...)`), not a `defmacro`: the compile path runs `UserMacroExpander` BEFORE the library
splice. Dynamic rebinding, so wasm needs no EH mode.

- **Interpreter ordering**: the `TORCH:NO-GRAD` case in `evalCons` calls `ensureTorchLoaded()`
  BEFORE evaluating the expansion, else the variable is not yet special and the binding is lexical.
- **`SpecialVarCollector`**: the `let` is synthesized after the scan, so `TORCH:NO-GRAD` must be
  listed in `LispMacroExpander.expandBuiltinMacro`; missing it is a LOUD compile error ("dynamically
  bound here but has no thread-local store").
- **`LibraryDefunPruner`**: `torch::*grad-enabled*` is synthesized AFTER the pruner runs, so a
  `torch:no-grad` occurrence is a hardcoded reference edge — the second beside `vec:aref` ->
  `vec:aset` (`.kb/library-defun-pruning.md`).
- Needs an explicit `IndentRules` entry (`"no-grad"`, `Style.body(0, 2)`) (`.kb/formatter.md`).

## The module layer

`torch::%module` — `torch::%m-new`, conc-name `torch::%m-`, predicate `torch:modulep`. Fields
`kind` (KEYWORD), `fields` (plist, KEYWORD value ...), `forward-fn` (applied by `torch:forward` as
`(funcall fn module args...)`), `training`. `torch::%mo-fields` / `%mo-set-fields` serve both this
record and the optimizer's.

- **The fields plist IS the parameter registration** (parameters, buffers, submodules, lists of
  submodules, hyper-parameters); a forward reads them back with `torch:field`, never from a
  closed-over variable. **Field names and kinds are KEYWORDS, not symbols**
  (`(eq 'weight 'torch::weight)` is NIL).
- `torch:parameters` (`%m-collect` / `%m-collect-fields`) walks field VALUES by IDENTITY, recursing
  into modules and LISTS, so a tensor field without `requires-grad` is a buffer and a plain LIST of
  modules is a `ModuleList` (no such type). `%m-set-mode` (`torch:train`/`eval`) walks the same
  shape; `torch:zero-grad` takes a module or an optimizer.
- **No `torch:call`, no funcallable module**: `torch:forward` is the single spelling and accepts a
  plain FUNCTION, hence no `torch:relu` MODULE. `torch:fields` walks a tree from OUTSIDE;
  `torch:set-data` writes into the very tensor a module's fields point at.
- `torch:linear` stores its weight `(in out)`, NOT PyTorch's `(out in)`, so the forward is a plain
  `torch:matmul`. `torch:gelu`'s `:approximate` defaults to `:none`.
- `torch:cross-entropy-loss`'s **soft-label target is told apart by SHAPE ALONE**
  (`torch::%m-ce-soft-p`) — **a LIST is always indices**, and `:ignore-index` (which drops the
  position from BOTH the sum and the mean's denominator) does not apply to that branch.

## The optimizers

`torch::%optimizer` — `torch::%o-new`, conc-name `torch::%o-`, predicate `torch:optimizerp`. Fields
`kind`, `params`, `fields` (every hyper-parameter AND state buffer — no `torch:state` surface; `:lr`
among them is all an LR schedule needs), `step-count` (initform 0), `step-fn`.
`torch::%o-params` is the generated ACCESSOR, `torch::%o-param-list` the module-or-list coercion.
`torch:optimizer` is public like `torch:module`; there is no parameter-GROUP object.

- **`torch:adamw` is the SAME step function, not a twin**: `torch::%o-adam-step` reads
  `:weight-decay` and `:decoupled`.
- **`torch:clip-grad-norm` lives here because nothing else can write a grad** (no `torch:set-grad`);
  it returns the norm as MEASURED and scales by `max-norm / (norm + 1e-6)`.
- **The update is ELEMENT-WISE and IN PLACE**, `setf row-major-aref`; it uses no torch op, so
  `torch:step` needs NO `torch:no-grad`. A nil grad is skipped.
- **Adam's element loop is `linalg::%la-adam-step (x g m v ps)`**, the rule packed into an
  eleven-element double vector `ps` (`lr`, `lr*wd`, `wd`, `b1`, `1-b1`, `b2`, `1-b2`, `eps`, `c1`,
  `c2`, `mode`; mode 0 = no decay, 1 = coupled, 2 = decoupled) — in `linalg:` because the
  acceleration seam intercepts only `linalg:` members (`.kb/gpu.md`, `.kb/linalg-simd.md`).
  `torch::%o-sgd-step` is still a boxed loop.
- **`torch:step` increments the counter FIRST**: Adam's bias correction divides by `1 - beta^t`,
  `t` = `torch:step-count`, so step 1 is fully corrected with magnitude `lr`.

## Batching, the masks, and `--simd`

`torch:pad-sequence` (-> one padded rank-2 tensor, BATCH FIRST), `torch:shuffled-batches` (a list of
examples, or an integer `n` for `0..n-1`, -> a list of LISTS), `torch:padding-mask`,
`torch:subsequent-mask`. `torch:inference-mode` was NOT added: a second name for `torch:no-grad`.

- The shuffle draws from the SEEDED `linalg` generator (`linalg:permutation`), so an epoch
  reproduces on every backend; `:shuffle nil` makes it the eval pass.
- The masks are RAW linalg arrays, not tensors: any NON-ZERO counts as masked, so `linalg:add`
  combines a padding mask with a causal one; shaped `(batch 1 length)` and `(1 n n)`.
- torch bottoms out in LITERAL `linalg:` calls with literal keywords (the
  `compiler.LinalgKernelCallLayout` contract), so it is accelerated under `--simd` exactly where
  linalg is, with no torch-specific interceptor. **`--no-gc` is unsupported**: rejected before any
  `defstruct` (`LINALG::%LA-MAKE: lambda-list keywords ... are not supported with --no-gc`).

## The fused compositions, and the accumulated-gradient protocol

Five transformer-step compositions are ONE internal `linalg` member each so `--gpu` can run each as
one kernel (`.kb/gpu.md`, "The fused tier"); **nothing about the bits moved on any backend**:
`%la-gelu` / `%la-gelu-grad`, `%la-layer-norm` / `%la-layer-norm-grad`, `%la-layer-norm-affine` /
`%la-layer-norm-affine-grad` (TWO arrays), `%la-softmax-grad`, `%la-log-softmax-grad`, and
`%la-dropout-mask` (no adjoint, over an explicit state vector `%la-rng-restore` restores). A SCALAR
input keeps the old composition in `torch:gelu` and `torch:layer-norm`.

- **Each defun IS the composition it replaced, member for member and in the tape's order**
  (`linalg.lisp`, "the fused compositions") — which is why `-0.0` gradients come out `+0.0`.
- **The order the tape accumulates one input's several contributions in is part of the value, and
  the adjoint members take it over**: they take `old`, the gradient `x` holds when the adjoint runs,
  and answer the gradient it holds AFTER the node; `torch::%t-fold-grad` reads the slot, CLEARS it
  and hands the answer to `%t-accum`.
- **Layer-norm's affine is inside the node**: at nn.LayerNorm's own shape (weight and bias VECTORS
  of the input's last extent — anything else keeps the three nodes) it is ONE node with parents
  `(x weight bias)` whose adjoint answers a two-element LIST (`(cadr r)` through `%t-unbroadcast`, a
  non-tracking parent `nil`); `norm` is no longer stored.

## The views

A `torch::%view` in the `store` slot names the SOURCE tensor, a KIND and an argument: `:swap`
(`torch:transpose` of exactly the last two axes; `linalg::%la-swap-last`), `:scale` (`torch:div` by
an UNTRACKED scalar; `linalg:div`), `:fill` (`torch:masked-fill` with a NUMBER under a mask that
broadcasts INTO the array's shape; `linalg:where`; arg `(mask . value)`). Anything else stays the
eager node, whose adjoint a view's own also is.

- A view materializes on the first `%t-data` read (`torch::%v-materialize`, so a view of a view
  resolves) and REPLACES the view in the store. **Every operation reads its operands through
  `%t-data`, so none of them knows views exist**; the three that do are `torch:matmul`,
  `torch:softmax` and `torch:shape` (`torch::%t-dims` answers a view's dims without materializing).
- **`torch:matmul` reads a view's source where it lies and routes the tape edge to the source**:
  right operand a view of `s` -> `linalg::%la-matmul-nd-tb a s`; left -> `%la-matmul-nd-ta s b`
  (both views, or a vector opposite: materialize). **The routing respects tracking** — an untracked
  view stays the parent.
- **`torch:softmax` consumes a `:fill` and/or `:scale` chain as ONE node**: `:axis` form ->
  `torch::%t-attention-softmax` -> `linalg::%la-scaled-masked-softmax (x scale mask fill ax)`,
  adjoint `%la-scaled-masked-softmax-grad (g out ax scale mask)`; the parent recorded is the deepest
  tensor reachable through views THAT TRACK. A `:scale` over a `:fill` fuses the scale alone; any
  other chain and the whole-array form materialize.
- **Trap: a view read by a SECOND consumer is the one case whose bits differ from the eager tape** —
  `(a + b) / s` eagerly vs `a / s + b / s` fused.

## Whole-package acceptance: `examples/llm-from-scratch/`

`transformer/{attention,utils,transformer}.lisp`, `chapter02/section{2,3,4,5}.lisp`, `gpt/` and
`chapter03/`, all in `examples/examples.yaml` with `.expected` files. **A module tree several levels
deep really walks** (`torch:parameters` finds all 63 tensors of the section-5 Transformer, 195 for
`transformer/shapes.lisp`'s two-block one) and **the output is ONE text on all four backends**,
training loop included.

## Wiring

Package `torch` in `PackageRegistry` (`TORCH_FUNCTIONS` / `torchFunctionNames()`; does not use `cl`,
no nickname); interpreter lazy load in `LispEvaluator.resolveFunction` + `ensureTorchLoaded`. On
every compile path (`RontoLispCli`, `RontoPlayground`, the corpus and per-backend test helpers)
**`TorchLibrary.process` runs BEFORE `LinalgLibrary.process`** — get the order wrong and a
torch-only program fails to compile with undefined `linalg:` functions. torch.lisp is a resolver
fixed-point, registered in the native-image `resource-config.json` (typeReachable `TorchLibrary`),
and formatter-pinned like every checked-in `.lisp`.

## Tests

- Gradcheck table: `LispEvaluatorTest.torchGradcheckTable`,
  `JvmLispCompilerTest.compileAndRunTorchGradcheckTable`,
  `WasmLispCompilerIntegrationTest.compileTorchGradcheckTable`; ci-spec `torch-fit-cross-backend`.
- Records: `TorchGradcheck.RECORD_PRINT_PROGRAM` on
  `LispEvaluatorTest.torchRecordsPrintAndCompareByIdentity`,
  `JvmLispCompilerTest.compileAndRunTorchRecordPrinting` and
  `WasmLispCompilerIntegrationTest.compileTorchRecordPrinting`; ci-spec
  `torch-record-print-cross-backend`.
- `LispEvaluatorTest#theVeryFirstPrintOfATensorAlreadyRoutesThroughItsPrinter`;
  `LibraryDefunPrunerTest.keepsOnlyTheTransitiveClosureOfTheCalledTorchFunction`;
  `LibraryDefunPrunerTest.torchNoGradKeepsTheSynthesizedGradEnabledVariable`;
  `PackageResolverTest.torchLibraryFormsAreAResolverFixedPoint`.
- `TorchGradcheck.NN_TRAINING_PROGRAM` and `OPTIMIZER_PROGRAM` on interpreter, JVM, wasm-GC; ci-spec
  `torch-nn-cross-backend`, `torch-optim-cross-backend` (SGD exact dyadic at lr `0.125`).
- `TorchGradcheck.FUSED_PROGRAM` + ci-spec `torch-fused-compositions`; `VIEW_PROGRAM` + ci-spec
  `torch-transpose-view` (five `matmul-transposed-*` rows); `ATTENTION_PROGRAM` + ci-spec
  `torch-attention-views` (`attention-softmax`, `scaled-softmax`, `masked-softmax`, `div-scalar`).
- No new `IndentRules` entry for the module, optimizer or batching layers.

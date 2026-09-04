# The `torch` package (tensor + reverse-mode autograd over linalg)

One hand-written Lisp-source library `src/main/resources/am/ik/rontolisp/eval/torch.lisp`,
following the `linalg.lisp` pattern (driver `eval.TorchLibrary`, a `LinalgLibrary` mirror),
so one implementation runs identically on all four backends. **torch NEVER reimplements a
kernel — it wraps a `linalg:` one and adds its adjoint** (`.kb/linalg.md`).

## Records

**Decision: the three records are `defstruct`s; everything else is a plain defun;
`defclass`/`defmethod` stay out.** CLOS is excluded for the DISPATCH reason alone — nothing
here dispatches. Re-evaluation trigger: a genuine dispatch need.

- A bundled defstruct is expanded into its generated defuns AHEAD of reachability
  (`BundledStructs` in `LibraryDefunPruner`, `.kb/library-defun-pruning.md`), so each
  constructor, predicate and accessor prunes INDIVIDUALLY —
  `LibraryDefunPrunerTest.keepsOnlyTheTransitiveClosureOfTheCalledTorchFunction`.
- Each record carries `(:print-object ...)` (`.kb/defstruct.md`): `#<TENSOR #d(1.0 2.0)
  :REQUIRES-GRAD T>`, `#<MODULE :LINEAR>`, `#<OPTIMIZER :SGD :STEP-COUNT 0>`, identical on
  all four backends under `prin1` and `princ`. **The printers NEVER spell the tape slots**
  (`backward-fn` holds a closure whose printed form is backend-dependent).
- `torch::%mo-fields` / `torch::%mo-set-fields` serve both the module and optimizer records
  (no slot arithmetic).

`torch::%tensor` — constructor `torch::%t-new`, conc-name `torch::%t-`, predicate
`torch:tensorp`, no copier, no `:type (vector ...)` (heterogeneous fields):

| field | contents |
| --- | --- |
| store | a linalg array (packed float, any rank, `:element-type` honoured), or a NUMBER (the rank-0 scalar tensor; `torch:shape` nil), or a `torch::%view`. Read through the defun `torch::%t-data`, which materializes; the accessor `torch::%t-store` is written by `torch:set-data` and the optimizers only |
| grad | nil, or a value of data's shape — a RAW linalg value, not a tensor |
| requires-grad | the LEAF flag from `(torch:tensor x :requires-grad t)` |
| parents | the input tensors this one was computed from |
| backward-fn | nil, or `(lambda (grad-out) ...)` -> the per-parent gradient list (nil for untracked parents) |

A tensor "tracks" when `requires-grad` or `backward-fn` is set (`torch:requires-grad-p`).
Every op routes operands through `torch::%t-wrap` (tensor passes through; number / raw array
/ list becomes a constant leaf) and results through `torch::%t-result`, which records the
tape edge only while `torch::*grad-enabled*` is true AND some parent tracks. Reductions of a
whole tensor produce scalar (number) data; the emap-based unary ufuncs get number branches
in the `torch::%t-r*` helpers.

- **`eq`/`eql` on a record instance is REFERENCE identity, on every backend.**
  `torch::%t-topo`'s visited marking and `torch::%m-collect`'s parameter dedup are `member`
  (i.e. `eql`) over records and both mean IDENTITY (`Environment.isIdentityAggregate` puts
  instances beside conses). `equal` on instances stays structural
  (`.kb/instance-syntax.md`).
- **The interpreter must load torch BEFORE it decides a print's routing.** Printing
  operators pick the `print-object` route when the FORM is expanded, before the argument
  that lazily loads torch is evaluated, so the first `(print (torch:tensor ...))` rendered
  raw `#S(TORCH::%TENSOR ...)`. `LispEvaluator.referencesTorch` triggers
  `ensureTorchLoaded()` in the printing case. **Torch is the only lazily loaded library
  defining a `print-object` method; a second one needs the same treatment.** Pinned by
  `LispEvaluatorTest#theVeryFirstPrintOfATensorAlreadyRoutesThroughItsPrinter`.
- The REPL's (and `DocExamplesTest`'s) value ECHO uses the internal printer, which consults
  no `print-object` method, so a tensor echoes `#S(TORCH::%TENSOR ...)` while `(print it)`
  gives `#<TENSOR ...>`. Hence doc pages show a `print` call plus its output block, not a
  `; =>` value.

## The element width: `torch::*default-element-type*`

**Invariant: every torch value is ORIGINATED at single-float (`#f`); `linalg`'s own default
stays double-float (`#d`).** Mirrors `torch.float32` / numpy `float64`.
`(defparameter torch::*default-element-type* 'single-float)`, read (never assigned) at each
origination site. `%la-make`'s "literal `:element-type`" rule is untouched (that rule is
about the two `make-array` calls inside `%la-make`).

Widths are otherwise INHERITED (`%la-etype` threads the input's width into `%la-make`).
**Trap: if ONE origination site is missed the model runs at MIXED widths, a decline
condition for every `--simd` kernel** (`.kb/linalg-simd.md`) — silent fallback to the scalar
defun everywhere. The origination sites, all reading the variable:

| function | originates |
| --- | --- |
| `torch:tensor` | the `:element-type` DEFAULT, applied by `torch::%t-as-data` to a number, list, general array or packed one — so `(torch:tensor (linalg:from-list ...))` CONVERTS a `#d` source. `:element-type nil` restores preserve-the-source; `'double-float` builds `#d` |
| `torch:parameter` | the same default, spelled again (it forwards `:element-type` to `torch:tensor`) |
| `torch:linear` | weight + bias, `linalg:uniform` (2 sites) |
| `torch:embedding` | the table, `linalg:randn` |
| `torch:layer-norm` | gain (`linalg:ones`) + bias (`linalg:zeros`) (2 sites) |
| `torch:dropout` | the inverted-dropout MASK, `linalg:rand` (hot path) |
| `torch:pad-sequence` | the padded batch, `linalg:full` (builds data directly through `torch::%t-new`, bypassing `%t-as-data`) |

Deliberately still `#d`: `torch::%t-indices` / `torch::%m-ce-indices` (INDEX vectors — raw,
never an arithmetic operand), `torch:topk` / `torch:multinomial` (they thread `%la-etype` of
the input), `torch:padding-mask` / `torch:subsequent-mask` (raw masks reaching data only
through `linalg:where`), `torch::%o-buffers`' one-element buffer for a SCALAR parameter.

Hazards:

- **Mixed widths in user code**: `(torch:add tn (linalg:ones ...))` pairs `#f` with `#d` —
  correct (the defun widens, keeping the first operand's width) but slow; so does handing
  `torch:set-data` a raw `#d` array. User rule: `doc/{en,ja}/guides/neural-networks.md`.
- **Sometimes KEEP the buffer double**: the sinusoidal positional encoding in
  `examples/llm-from-scratch/transformer/utils.lisp` stays `#d` because
  `chapter02/section3.lisp` asserts two encoding rows' dot product depends only on their
  offset to within 1e-6, inside f32's own resolution at d_model 128. An accuracy claim the
  example PRINTS beats a kernel it does not measure.
- **`TorchGradcheck` is pinned to `'double-float`**
  (`src/test/java/.../testsupport/TorchGradcheck.java`): central differences at `eps 1e-4`
  against `tol 1e-3` relative, where f32 leaves about three significant digits.
- **`#f` pays only where a kernel is intercepted**: the boxed rank->=3 `%la-matmul-nd` is
  ~1.7x SLOWER at `#f` (widens on every read, narrows on every store).
- **Byte-identical output survives** (`train-gpt-soseki.lisp`, all four backends, with and
  without `--simd`); the width is visible underneath (`3.669992446899` vs
  `3.669992437386`).
- Rebinding: `(let ((torch::*default-element-type* 'double-float)) ...)` around the model
  CONSTRUCTION builds the whole model in `#d`; no `with-dtype` macro exists or is needed. It
  works even as the very first form of a program (unlike `torch:no-grad`), and covers only
  what is ORIGINATED inside it.

## The two invariants of `backward`, and the adjoint table

1. **Reverse topological order, computed explicitly.** `torch::%t-topo` is a DFS over the
   parents with identity (`member`/`eql`) visited marking; the reverse finish order puts
   every consumer before the tensors it consumed. Tape order would be wrong for any
   reconvergent graph.
2. **Accumulate, never assign.** `torch::%t-accum` does `grad += g` (`linalg:add` into the
   slot). A tensor reached over more than one path collects the SUM (gradcheck rows
   `residual`, `index-select-repeat`). `backward` requires a scalar (one-element) tensor and
   seeds 1.0; grads are RETAINED on intermediates.

Broadcasting adjoints all route through **`torch::%t-unbroadcast`**: the incoming gradient
summed over every broadcast axis — each leading axis the operand lacks
(`linalg:sum :axis 0` repeatedly) and each axis where the operand's extent is 1
(`:axis i :keepdims t`). Reduction adjoints share `torch::%t-keepdims` and
`torch::%t-grad-bcast` (`zeros-like` + broadcast add); pure rearrangements share
`torch::%t-grad-reshape`.

| op | forward (linalg) | adjoint |
| --- | --- | --- |
| `add`/`sub` | `linalg:add`/`sub` | `g` / `-g`, unbroadcast per operand |
| `mul`/`div` | `mul`/`div` | `g*b`, `g*a` / `g/b`, `-g*a/b^2`, unbroadcast |
| `neg` | `negative` | `-g` |
| `power` | `power` | `g*b*a^(b-1)`; exponent `g*out*ln a` computed ONLY when the exponent tracks (ln of a non-positive base would signal) |
| `exp`/`log`/`sqrt`/`tanh` | ufuncs (number branch for scalars) | `g*out` / `g/a` / `g/(2 out)` / `g*(1-out^2)` |
| `relu` | `relu` | `g * (a > 0)` mask (0 at 0, like PyTorch) |
| `matmul` | `dot` (vec.vec) / `matmul` | rank-cased in `%t-mm-grad-a/-b`: general `g.b^T` / `a^T.g` through `linalg::%la-matmul-nd-tb` / `-ta`, reading the transposed operand where it lies; vector sides via expand-dims products; batch axes unbroadcast |
| `sum`/`mean` | `sum`/`mean` | broadcast back (`%t-grad-bcast`); mean divides by the reduced count |
| `var`/`std` | COMPOSED from mean/sub/mul/sum/div (+ sqrt) | from the tape — no bespoke adjoint; keeps the `(n - ddof)` divisor differentiable |
| `amax` | `amax` | mask `(= a out)`, gradient split EVENLY among ties (PyTorch's rule) |
| `argmax` | `argmax` | none — returns the RAW index value/array, not a tensor |
| `softmax`/`log-softmax` | `softmax`/`log-softmax` | `s*(g - sum(g*s))` / `g - exp(out)*sum(g)`; in the `:axis` form each is the ONE member `linalg::%la-softmax-grad` / `%la-log-softmax-grad` |
| `gelu` (`:none`) | `%la-gelu` | `%la-gelu-grad` |
| `layer-norm` (the normalization) | `%la-layer-norm` | `%la-layer-norm-grad` |
| `dropout` | `%la-dropout-mask` then `mul` | mask is a constant: `g * mask` |
| `masked-fill` | `where` | `g` where the mask is zero, 0 where filled; mask and fill value are constants. `-inf` fills are safe through softmax (the `exp` underflow clamp, `.kb/linalg.md`) |
| `reshape`/`view`/`unsqueeze`/`squeeze` | `reshape`/`expand-dims`/`squeeze` | `%t-grad-reshape`. `view` IS `reshape` here — linalg results are fresh copies, nothing aliases |
| `transpose` | `transpose` (axes normalized non-negative) | transpose by the INVERSE permutation |
| `cat`/`stack` | `concatenate`/`stack` | slice the gradient back per input (`%t-axis-spec`); stack drops the new axis again |
| `slice` | the `linalg:slice` plan | `%t-slice-scatter`: same `%la-slice-bound` normalization and odometer, adding into `zeros-like` |
| `gather`/`index-select` | `gather`/`take-rows` | scatter-ADD into `zeros-like` (repeated indices accumulate) |

Acceptance: the table-driven gradient check `testsupport/TorchGradcheck`, one `gc-check`
row per differentiable op — **extend it with one row per new op**. Analytic vs central
differences at relative tolerance 1e-3, run VERBATIM on `LispEvaluatorTest.torchGradcheckTable`,
`JvmLispCompilerTest.compileAndRunTorchGradcheckTable`,
`WasmLispCompilerIntegrationTest.compileTorchGradcheckTable`; `--component` plus a "fit
y = 2x" loop is ci-spec `torch-fit-cross-backend` (exact dyadic values). On WASM the
polynomial `exp`/`log`/`tanh` differ from `Math.*`, but the check compares the backward
against differences of the SAME forward.

Records: `TorchGradcheck.RECORD_PRINT_PROGRAM` on
`LispEvaluatorTest.torchRecordsPrintAndCompareByIdentity`,
`JvmLispCompilerTest.compileAndRunTorchRecordPrinting`,
`WasmLispCompilerIntegrationTest.compileTorchRecordPrinting`, ci-spec
`torch-record-print-cross-backend`.

## `torch:no-grad`: the one macro, and its three seams

A BUILT-IN `LispMacroExpander` expansion (the usocket `with-*` pattern — a `defmacro` in
torch.lisp cannot work: the compile path runs `UserMacroExpander` BEFORE the library
splice). `expandTorchNoGrad` produces `(let ((torch::*grad-enabled* nil)) body...)` — a
DYNAMIC rebinding of the library's `defparameter`, so each backend's ordinary
special-binding save/restore applies and no EH mode is forced on wasm.

- **Interpreter ordering**: the `TORCH:NO-GRAD` case in `evalCons` calls
  `ensureTorchLoaded()` BEFORE evaluating the expansion, so the `defparameter` has declared
  the variable special by the time the `let` binds (otherwise the binding is lexical and the
  ops read the global).
- **`SpecialVarCollector`**: the JVM gives a special a thread-local store only when it sees
  a binding form, and this `let` is synthesized during codegen after the scan.
  `TORCH:NO-GRAD` is therefore listed in `LispMacroExpander.expandBuiltinMacro` (which the
  collector — and `macroexpand-1` — expand through). Missing this is a LOUD compile error
  ("dynamically bound here but has no thread-local store"), never silence.
- **`LibraryDefunPruner`**: the expansion synthesizes `torch::*grad-enabled*` AFTER the
  pruner runs, so a `torch:no-grad` occurrence is a hardcoded reference edge to it — the
  second such edge beside `vec:aref` -> `vec:aset` (`.kb/library-defun-pruning.md`);
  `LibraryDefunPrunerTest.torchNoGradKeepsTheSynthesizedGradEnabledVariable`.

Formatter needs an explicit `IndentRules` entry (`"no-grad"`, `Style.body(0, 2)`): no
`with-`/`do-`/`def` prefix, so the naming-convention guess would lay its body out as call
arguments (`.kb/formatter.md`).

## The module layer

`torch::%module` — constructor `torch::%m-new`, conc-name `torch::%m-`, predicate
`torch:modulep`:

| field | contents |
| --- | --- |
| kind | a KEYWORD naming the layer (`:linear`, `:sequential`, ...) |
| fields | a plist, KEYWORD value ..., holding every parameter, buffer, submodule, list of submodules and hyper-parameter |
| forward-fn | applied by `torch:forward` as `(funcall fn module args...)` |
| training | the train/eval flag, `t` at construction |

- **The fields plist IS the parameter registration.** A layer's forward reads its
  parameters back with `torch:field`, never from a closed-over variable, so a parameter that
  exists cannot be absent from `torch:parameters`. `TorchGradcheck`'s layer rows
  `torch:set-field` the checked inputs over a freshly built layer, so a forward reading a
  closure copy fails.
- **Field names and kinds are KEYWORDS, not symbols**: rontolisp symbols are
  package-distinct (`(eq 'weight 'torch::weight)` is NIL).
- `torch:parameters` (`%m-collect` / `%m-collect-fields`) walks the field VALUES: a leaf
  tensor with slot 3 set is collected once by IDENTITY (`member`), a module recurses into
  its own fields, a LIST recurses element-wise, anything else contributes nothing. So a
  tensor field WITHOUT `requires-grad` is a buffer; a plain LIST of modules is a
  `ModuleList`, so no such type exists; `torch:train`/`torch:eval` (`%m-set-mode`) walk the
  same shape; `torch:zero-grad` accepts a module as well as a tensor (at the price of a
  `zero-grad` reference edge to `torch:parameters`).
- **No `torch:call`, no funcallable module.** `torch:forward` is the single spelling and
  also accepts a plain FUNCTION — hence no `torch:relu` MODULE: an activation goes into a
  `torch:sequential` as `(function torch:relu)`. `torch:gelu`'s `:approximate` defaults to
  `:none`, the exact `x * (1 + erf(x / sqrt(2))) / 2` matching `nn.GELU`; `:tanh` is the
  GPT/BERT composition.
- **`torch:fields` is what makes a module tree walkable from OUTSIDE** (a fresh spine over
  live values), which is why `nn.Module.apply` / `named_parameters` have no counterpart: a
  walk is `torch:fields` plus `torch:module-kind`.
- **`torch:set-data`** exists because an optimizer must write into the very tensor a
  module's fields point at; rebuilding the tensor cannot reach a nested parameter.

Layers: `torch:linear` stores its weight `(in out)`, NOT PyTorch's transposed `(out in)`,
so the forward is a plain `torch:matmul` and the bias broadcasts over every leading axis
(PyTorch's `U(-1/sqrt(in), 1/sqrt(in))` init kept). `torch:embedding` keeps `N(0, 1)`.
`torch:layer-norm` normalizes over the LAST axis with the biased (`ddof` 0) variance.
`torch:dropout` is inverted dropout reading slot 4, its mask one member
(`linalg::%la-dropout-mask`) over an explicit generator state.

Losses are plain functions. `torch:cross-entropy-loss` flattens all but the last axis, picks
`-log-softmax` at the target class, and under `:ignore-index` drops the position from BOTH
the sum and the mean's denominator (the ignored index is clamped to 0 before the
`torch:gather` so a sentinel target cannot index out of range). It also takes PyTorch's
**probability (soft-label) target**, told apart by SHAPE ALONE (`torch::%m-ce-soft-p`): a
target whose `array-dimensions` equal the logits' is a distribution, everything else is
class indices — so **a LIST is always indices**, the probability spelling needs a tensor or
array, and `:ignore-index` does not apply to it. The soft branch (`torch::%m-ce-soft`) is
composed from `torch:mul`/`torch:sum` over `torch:log-softmax`, so the gradient reaches the
TARGET too (gradcheck rows `cross-entropy-soft`, `cross-entropy-soft-rank1`).

Acceptance: `TorchGradcheck.NN_TRAINING_PROGRAM` (a 2-8-1 ReLU MLP on XOR, 200 SGD steps
over `torch:parameters`) on interpreter, JVM and wasm-GC; ci-spec `torch-nn-cross-backend`
also pins the exact-dyadic module forward/backward, the parameter walk and the mode switch.
Both use only `+ - * /` and `max`, so every backend follows the same trajectory. No new
`IndentRules` entry.

## The optimizers

`torch::%optimizer` — constructor `torch::%o-new`, conc-name `torch::%o-`, predicate
`torch:optimizerp`:

| field | contents |
| --- | --- |
| kind | a KEYWORD naming the rule (`:sgd`, `:adam`, ...) |
| params | the list of parameter tensors it updates |
| fields | a plist, KEYWORD value ..., holding every hyper-parameter AND every state buffer |
| step-count | the optimizer's OWN counter (initform 0), incremented by `torch:step` BEFORE the rule runs |
| step-fn | `(lambda (self) ...)`, applied by `torch:step` |

Name split the conc-name forces: `torch::%o-params` is the generated ACCESSOR; the
module-or-list coercion is `torch::%o-param-list`.

- **The fields plist is the state**, read through `torch::%mo-fields` /
  `torch::%mo-set-fields`; no `torch:state` / `torch:set-state` surface. Momentum and Adam's
  `m`/`v` are fields (`:buffers`, `:m`, `:v`) holding a general vector indexed by the
  parameter's position, allocated on the first step; `:lr` is a field too, which is all an
  LR schedule needs (`(torch:set-field opt :lr new)`) with no scheduler type.
- **`torch:adamw` is the SAME step function, not a twin.** `torch::%o-adam-step` reads
  `:weight-decay` and `:decoupled`: nil-decoupled adds `wd * param` to the GRADIENT
  (`torch.optim.Adam`), t-decoupled shrinks the parameter before the moments are touched
  (`torch.optim.AdamW`), `wd` 0 is neither. No parameter-GROUP object: two optimizers over
  disjoint parameter LISTS are what a group is here.
- **`torch:clip-grad-norm` lives here because nothing else can write a grad**
  (`torch:set-data` writes DATA; there is no `torch:set-grad`). It returns the norm as
  MEASURED (before clipping) and scales in place by `max-norm / (norm + 1e-6)` only when the
  bound is exceeded. Its two element loops are `linalg::%la-sum-squares` /
  `linalg::%la-scale`; `torch:index-select`'s backward is one `linalg::%la-scatter-rows`.
- **The update is ELEMENT-WISE and IN PLACE**, `setf row-major-aref` over the parameter's
  packed array with no temporary. It uses no torch op, so it records nothing on the tape and
  `torch:step` needs NO `torch:no-grad`. A scalar parameter (data a plain NUMBER) is one
  branch inside the element loop; a parameter whose grad is nil is skipped.
- **Adam's element loop is `linalg::%la-adam-step (x g m v ps)`**, once per parameter, the
  whole rule packed into the eleven-element double vector `ps` (`lr`, `lr*wd`, `wd`, `b1`,
  `1-b1`, `b2`, `1-b2`, `eps`, `c1`, `c2`, `mode`; mode 0 = no decay, 1 = coupled,
  2 = decoupled). It is in `linalg:` because the acceleration seam intercepts `linalg:`
  members and NOTHING else (`.kb/gpu.md`, `.kb/linalg-simd.md`). `lr * wd` is multiplied in
  torch.lisp while both may still be exact rationals. `torch::%o-sgd-step` is still a boxed
  loop here.
- **`torch:step` increments the counter FIRST.** Adam's bias correction divides by
  `1 - beta^t` with `t` = `torch:step-count`, so the first step is fully corrected with
  magnitude `lr` — the classic off-by-one; the `adam-3steps` gradcheck row pins the sequence
  and the ci-spec case pins `t = 1`.
- **`torch:optimizer` is public**, like `torch:module`: an unshipped rule is a plain defun
  over the same record. Both accept a MODULE or a parameter list; `torch:zero-grad` gained
  an optimizer branch.
- The rules follow **PyTorch's**, not `examples/deep-learning-from-scratch/`'s (whose Adam
  folds the bias correction into a step size and leaves `eps` outside it); that example
  keeps its own hand-written optimizers on purpose.

## Batching and the masks

`torch:pad-sequence` (list of sequences -> one padded rank-2 tensor, BATCH FIRST),
`torch:shuffled-batches` (a list of examples, or an integer `n` standing for `0..n-1`, -> a
list of LISTS), `torch:padding-mask`, `torch:subsequent-mask`.

- A batch is an ordinary list, so the caller keeps its own pairing of parallel sequences;
  the integer form batches several parallel arrays at once by handing back index lists.
- The shuffle draws from the SEEDED `linalg` generator (`linalg:permutation`, integer
  arithmetic), so an epoch reproduces on every backend; `:shuffle nil` makes the same
  function the evaluation pass.
- The two masks are RAW linalg arrays, not tensors: a mask carries no gradient,
  `torch:masked-fill` takes it as a constant, and any NON-ZERO counts as masked so
  `linalg:add` combines a padding mask with a causal one. Shaped to broadcast over a
  `(batch query-length key-length)` score — `(batch 1 length)` and `(1 n n)`.
- `torch:inference-mode` was NOT added: a second name for `torch:no-grad` here.

Acceptance: `TorchGradcheck.OPTIMIZER_PROGRAM` (one SGD step, the L2 term, a three-step
momentum sequence, a scalar parameter, a skipped gradient-less parameter, Adam's three steps
and its `:lr` field, every batching/mask helper, then the chapter-2.3.4 skip-connection
experiment) on interpreter, JVM, wasm-GC; ci-spec `torch-optim-cross-backend`, whose SGD
values are exact dyadic rationals (lr `0.125`) while Adam is a tolerance predicate. No new
`IndentRules` entry.

## Whole-package acceptance: `examples/llm-from-scratch/`

Chapter 2 of 『作ってわかる大規模言語モデルの仕組み』 —
`transformer/{attention,utils,transformer}.lisp` plus `chapter02/section{2,3,4,5}.lisp`, all
in `examples/examples.yaml` with `.expected` files. What it pins that nothing else does:

- **A module tree several levels deep really walks**: `torch:parameters` over the section-5
  Transformer finds all 63 tensors through module -> list-of-modules -> module -> parameter
  (195 for the two-block one in `transformer/shapes.lisp`), all moving under one
  `torch:adam`.
- **The output is ONE text on all four backends**, training loop included (the seeded
  generator is integer arithmetic and the example rounds its printed floats, so the WASM
  `exp`/`log`/`sin` approximations cannot show through).
- **Speed is the binding constraint**: a `d_model` 8 / 1-block / 2-head model over an 8-pair
  corpus for 40 epochs is ~2 min on the plain interpreter and ~4 s on the JVM. Anything
  larger does not fit the examples harness's 240 s per-leg cap.

The `gpt/` + `chapter03/` increment closed seven library gaps in `linalg.lisp` /
`torch.lisp`: `linalg:erf`, `torch:erf`, `torch:gelu`, `torch:fields`, `torch:topk`,
`torch:multinomial`, `torch:adamw`, `torch:clip-grad-norm` (plus `torch:adam`'s missing
`:weight-decay`). Beyond them:

- **The two sampling primitives are the first NON-differentiable tensor functions after
  `torch:argmax` and follow its shape**: a RAW linalg array, never a tensor. `torch:topk`
  answers the VALUES, or the indices under `:indices t` — ONE of `torch.topk`'s pair,
  because every function here is single-valued — ties to the lowest index.
  `torch:multinomial` draws from the seeded `linalg` generator; without-replacement default.
- **Two deliberate DIVERGENCES from the book**, named in `gpt/trainer.lisp`: the book's
  `get_lr` computes a warmup for the LOG LINE only and never writes it back; and
  `forward(idx, targets)` returns a tuple, split here into `gpt-forward` and `gpt-loss`.
- **The corpus is INLINE and public domain** (nothing downloaded or vendored).
- **`chapter03/section2.lisp` needs no torch** for three of its four parts: its BPE learner
  depends on Python `dict` INSERTION order, so every table there is an ordered association
  list and the tie rule is a strict `>`, which makes the port's 100 merges come out in the
  book's exact order.

## `--simd`, and what is NOT accelerated

torch bottoms out in LITERAL `linalg:` calls with literal keywords (the
`compiler.LinalgKernelCallLayout` pattern-match contract), so a torch program is accelerated
under `--simd` exactly where linalg is, with no torch-specific interceptor — including the
rank->=3 stacked `%la-matmul-nd` and `linalg:erf` (so the EXACT `torch:gelu` is accelerated
too). **`--no-gc` is unsupported**: a torch program is rejected long before any `defstruct`
is reached (`LINALG::%LA-MAKE: lambda-list keywords ... are not supported with --no-gc`).

## The fused compositions, and the accumulated-gradient protocol

Five transformer-step compositions are now ONE internal `linalg` member each, so `--gpu` can
run each as one kernel (`.kb/gpu.md`, "The fused tier"); **nothing about the bits moved on
any backend**:

| torch op | forward member | adjoint member |
|---|---|---|
| `torch:gelu` (`:none`, over an array) | `%la-gelu (x)` | `%la-gelu-grad (g x old)` |
| `torch:layer-norm`'s normalization | `%la-layer-norm (x eps)` | `%la-layer-norm-grad (g x eps old)` |
| `torch:layer-norm`, the WHOLE module forward | `%la-layer-norm-affine (x w b eps)` | `%la-layer-norm-affine-grad (g x w eps old)`, TWO arrays |
| `torch:softmax` `:axis` form | `linalg:softmax` (unchanged) | `%la-softmax-grad (g out ax)` |
| `torch:log-softmax` `:axis` form | `linalg:log-softmax` (unchanged) | `%la-log-softmax-grad (g out ax)` |
| `torch:dropout`'s mask | `%la-dropout-mask (shape p st single)` | — (a constant) |

**Each defun IS the composition it replaced, member for member and in the tape's order**
(`linalg.lisp`, "the fused compositions") — e.g. `torch:mean`'s `%t-grad-bcast` as a
broadcast `add` onto `zeros-like`, which is why `-0.0` gradients come out `+0.0`.

**The order the tape accumulates one input's several contributions in is part of the value,
and the adjoint members take it over.** GELU reached `x` twice, layer-norm four times, and
`%t-accum` adds each onto whatever `x` held, so a fused adjoint answering `A1 + .. + A4`
would associate differently. The members take `old`, the gradient `x` holds when the adjoint
runs, and answer the gradient it holds AFTER the node; `torch::%t-fold-grad` reads the slot,
CLEARS it, and hands the answer back for `%t-accum` to store. Nothing else can land between
the contributions because a DFS finishes a node only after its parents and the composition's
internal nodes are reachable from nowhere else.

**Layer-norm's affine is inside the node, and its adjoint answers a two-element LIST.** At
nn.LayerNorm's own shape (an array input, weight and bias VECTORS of its last extent —
anything else keeps the three nodes) the module is ONE node with THREE parents
`(x weight bias)` over `%la-layer-norm-affine`. Its adjoint calls
`%la-layer-norm-affine-grad` once through `%t-fold-grad`: `(car r)` is `x`'s gradient with
the broadcast `g * weight` folded in; `(cadr r)` is `g * norm`, passed through the SAME
`torch::%t-unbroadcast` the `torch:mul` adjoint used. The bias's is `%t-unbroadcast` of `g`.
A parent that does not track gets `nil`. `norm` is no longer stored (`.kb/linalg.md`,
`.kb/gpu.md` "Layer-norm's affine").

`torch:dropout` needs no protocol (its mask was always a constant operand of one
`torch:mul`); its three members (`rand`, `greater`, `div`) are now one over an explicit
state vector the member advances IN PLACE, which `%la-rng-restore` puts back. A scalar input
keeps the old composition in both `torch:gelu` and `torch:layer-norm`.

Pinned by `TorchGradcheck.FUSED_PROGRAM` (against the old compositions) on the three test
backends and ci-spec `torch-fused-compositions` on all four.

## The views

**The record's data slot is `store`, and `torch::%t-data` is a defun over it.** A view is a
`torch::%view` record naming the SOURCE tensor, a KIND and an argument:

| kind | made by | data | arg |
|---|---|---|---|
| `:swap` | `torch:transpose` exchanging exactly the last two axes (rank-2 transpose, or an axes list `(0 .. n-3 n-1 n-2)`) | `linalg::%la-swap-last` of the source's | nil |
| `:scale` | `torch:div` of an array by an UNTRACKED scalar (a number, or a scalar tensor that does not track) | `linalg:div` of the source's by it | the divisor |
| `:fill` | `torch:masked-fill` of an array with a NUMBER under a mask that broadcasts INTO the array's shape | `linalg:where` of the mask, the value, the source's | `(mask . value)` |

`torch::%t-data` is the old accessor plus one branch: a view is materialized on the first
read (`torch::%v-materialize`: the kind's member over the source's data, itself read through
`%t-data`, so a view of a view resolves) and the array REPLACES the view in the store.
**Every operation reads its operands through `%t-data`, so none of them knows views exist**;
the three that do are `torch:matmul`, `torch:softmax` and `torch:shape` (`torch::%t-dims`
answers a view's dims from the source's without materializing — swapped for `:swap`, the
source's own otherwise, which is why a `:fill` is made only when the mask does not widen the
array). Any other permutation, a transpose of a scalar or vector, a division by an array or
a tracked scalar, a fill with an array value or a widening mask, is the eager node it always
was. A view's own adjoint is the eager node's (`g` swapped, `g / s`,
`where(mask, 0, g)`).

**`torch:matmul` reads a view's source where it lies and routes the tape edge to the
source.** Right operand a view of `s`: `linalg::%la-matmul-nd-tb a s`; left:
`%la-matmul-nd-ta s b` (both views: the right one is materialized; a vector on the other
side: the view is materialized). The parent recorded is `s` ITSELF and the gradient is
computed straight in `s`'s orientation (`(a^T . g)^T` IS `g^T . a`), so the view's own
adjoint never runs. **The routing respects tracking**: a view made under `torch:no-grad`, or
of a constant, does not track, and then the VIEW stays the parent so no gradient reaches the
source.

**`torch:softmax` consumes a `:fill` and/or `:scale` chain as ONE node.** In its `:axis`
form over a store that is a `:fill` view, a `:scale` view, or a `:fill` over a `:scale`,
`torch::%t-attention-softmax` reads the innermost source's data and calls
`linalg::%la-scaled-masked-softmax (x scale mask fill ax)`, whose defun IS the three forwards
in the chain's order. The parent recorded is the deepest tensor reachable through views THAT
TRACK; the adjoint is `linalg::%la-scaled-masked-softmax-grad (g out ax scale mask)` —
softmax's adjoint, then `where(mask, 0, ·)`, then `/ s`. A view that does not track stays the
parent and the adjoint folds only the views above it. **Trap: a view read by a SECOND
consumer is the one case whose bits differ from the eager tape** — eagerly both gradients
summed at the view and passed the division once; fused, the softmax's contribution passes on
its own and the tape adds at the score (`(a + b) / s` vs `a / s + b / s`). A `:scale` over a
`:fill` materializes the fill and fuses the scale alone; any other chain, and the whole-array
form, materialize.

**What a reader of the store must know**: `torch:set-data` and the two optimizer updates
write `%t-store` directly (a parameter is never a view); `torch:detach` and the printer go
through `%t-data` and materialize. A view materialized LATE sees the source's data as it is
THEN (the SGD update writes a parameter's array in place) — PyTorch's aliasing too.

Pinned by `TorchGradcheck.VIEW_PROGRAM` (forward and both gradients against
`(torch:add view 0.0)`, the materialized route) and ci-spec `torch-transpose-view`; the five
`matmul-transposed-*` gradcheck rows cover the shapes.
`TorchGradcheck.ATTENTION_PROGRAM` pins the fused route against the materialized chain, bit
for bit, plus ci-spec `torch-attention-views`; the `attention-softmax`, `scaled-softmax`,
`masked-softmax` and `div-scalar` gradcheck rows check the fused adjoint against finite
differences. Measurements: `.kb/gpu.md` "The attention head's transpose" and "The attention
scale and mask".

## Wiring

Package `torch` in `PackageRegistry` (`TORCH_FUNCTIONS` / `torchFunctionNames()`; does not
use `cl`, no nickname). Interpreter: lazy load in `LispEvaluator.resolveFunction` +
`ensureTorchLoaded`. Compile paths (`RontoLispCli`, `RontoPlayground`, the corpus tests, the
per-backend test helpers): **`TorchLibrary.process` runs BEFORE `LinalgLibrary.process`** —
get the order wrong and a torch-only program fails to compile with undefined `linalg:`
functions. torch.lisp is written in canonical package shape (resolver fixed-point,
`PackageResolverTest.torchLibraryFormsAreAResolverFixedPoint`), registered in the
native-image `resource-config.json` (typeReachable `TorchLibrary`), and formatter-pinned like
every checked-in `.lisp`.

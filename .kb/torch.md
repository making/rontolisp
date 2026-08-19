# The `torch` package (tensor + reverse-mode autograd over linalg)

One hand-written Lisp-source library,
`src/main/resources/am/ik/rontolisp/eval/torch.lisp`, following the
`linalg.lisp` pattern (driver `eval.TorchLibrary`, a `LinalgLibrary` mirror) so a
single implementation runs identically on all four backends. It is the
differentiable layer of the PyTorch-style stack (todo-458): a tensor that
records how it was computed, and a `torch:backward` that walks that history to
fill in gradients. The stateless array math it runs on stays in `linalg`
(`.kb/linalg.md`) -- torch NEVER reimplements a kernel, it wraps one and adds
its adjoint.

## The tensor: a fixed-layout record, and the pruning decision

**Decision (todo-460, binding for the whole torch package including the 461
module layer and the 462 optimizers): torch is defun-only BY DESIGN -- no
`defclass`/`defmethod`/`defstruct`, ever.** `LibraryDefunPruner` prunes only
`defun`/`defparameter`/`defvar`/`defconstant` for rontolisp's OWN libraries
(`.kb/library-defun-pruning.md`; the CLOS `Candidates` machinery applies to
third-party provenance only), so a CLOS-based tensor would have made the whole
library non-prunable: every program touching `torch:softmax` would carry every
op and (later) every module and optimizer. Building on plain defuns over a
fixed-layout record keeps every torch definition inside the pruner's existing,
audited mechanism -- `LibraryDefunPrunerTest.keepsOnlyTheTransitiveClosureOfTheCalledTorchFunction`
pins that a `torch:tensor`-only program drops `torch:backward`/`torch:matmul`
AND the unused linalg members. CLOS dispatch buys the tensor nothing: values
flow through closures on the tape, not through generic functions. If 461 ever
finds a genuine need for CLOS in torch, the price is widening the pruner's own-
library scope to the CLOS kinds first -- do not add a `defclass` to torch.lisp
without that.

The record is a six-slot general vector (`(make-array 6)`), discriminated by
`torch:tensorp` on the tag symbol in slot 0 (the optimizer record of todo-462 is
six slots too, which is why the TAG, not the length, is the discriminator):

| slot | field | contents |
| --- | --- | --- |
| 0 | tag | the symbol `torch::%tensor` |
| 1 | data | a linalg array (packed float, any rank; `:element-type` honoured) or a NUMBER -- a plain number is the rank-0 scalar tensor (`torch:shape` nil, like `linalg:ndim` 0) |
| 2 | grad | nil, or a value of data's shape -- a RAW linalg value, not a tensor |
| 3 | requires-grad | the LEAF flag from `(torch:tensor x :requires-grad t)` |
| 4 | parents | the input tensors this one was computed from |
| 5 | backward-fn | nil, or `(lambda (grad-out) ...)` -> the per-parent gradient list (nil entries for untracked parents) |

A tensor "tracks" when slot 3 or 5 is set (`torch:requires-grad-p`). Every op
routes operands through `torch::%t-wrap` (tensor passes through; number / raw
array / list becomes a constant leaf) and results through `torch::%t-result`,
which records the tape edge only while `torch::*grad-enabled*` is true AND some
parent tracks -- otherwise the result is a plain leaf and the inputs stay
collectable. Reductions of a whole tensor produce scalar (number) data;
`linalg`'s binary kernels accept numbers on both sides, and the emap-based
unary ufuncs get number branches in the `torch::%t-r*` helpers, so scalar
tensors flow through every op.

Printing a tensor prints the raw record (slot 5 is a closure, whose printed
form is backend-dependent) -- examples and cross-backend pins must print
`torch:data`/`torch:grad`/`torch:item`, never the tensor itself.

## The two invariants of `backward`, and the adjoint table

1. **Reverse topological order, computed explicitly.** `torch::%t-topo` is a
   DFS over the parents with identity (`member`/`eql`) visited marking; the
   reverse finish order it builds has every consumer before the tensors it
   consumed, so when a node's `backward-fn` runs its grad slot is complete.
   Tape order would be wrong for any reconvergent graph.
2. **Accumulate, never assign.** `torch::%t-accum` does `grad += g`
   (`linalg:add` into the slot). A tensor reached over more than one path -- a
   residual `x + f(x)`, an embedding row selected twice -- collects the SUM;
   the gradcheck `residual` and `index-select-repeat` rows pin both. `backward`
   requires a scalar (one-element) tensor and seeds 1.0; grads are RETAINED on
   intermediates too (torch's `retain_grad`, for free).

Broadcasting adjoints all route through ONE helper, **`torch::%t-unbroadcast`**
(the todo's `%grad-unbroadcast`): the incoming gradient summed over every
broadcast axis -- each leading axis the operand lacks (`linalg:sum :axis 0`
repeatedly) and each axis where the operand's extent is 1 (`:axis i :keepdims
t`) -- so a `(d)` bias against a `(b s d)` activation gets a `(d)` gradient.
The reduction adjoints share `torch::%t-keepdims` (reduced value normalized to
the keepdims shape so it broadcasts) and `torch::%t-grad-bcast` (materialize at
the input's shape via `zeros-like` + broadcast add); the pure rearrangements
share `torch::%t-grad-reshape`.

| op | forward (linalg) | adjoint |
| --- | --- | --- |
| `add`/`sub` | `linalg:add`/`sub` | `g` / `-g`, unbroadcast per operand |
| `mul`/`div` | `mul`/`div` | `g*b`, `g*a` / `g/b`, `-g*a/b^2`, unbroadcast |
| `neg` | `negative` | `-g` |
| `power` | `power` | `g*b*a^(b-1)`; exponent `g*out*ln a` -- computed ONLY when the exponent tracks (ln of a non-positive base would signal) |
| `exp`/`log`/`sqrt`/`tanh` | ufuncs (number branch for scalars) | `g*out` / `g/a` / `g/(2 out)` / `g*(1-out^2)` |
| `relu` | `relu` | `g * (a > 0)` mask (0 at 0, like PyTorch) |
| `matmul` | `dot` (vec.vec) / `matmul` | rank-cased in `%t-mm-grad-a/-b`: general `g.b^T` / `a^T.g` with the last two axes swapped (`%t-swap-last`), vector sides via expand-dims products; batch axes unbroadcast |
| `sum`/`mean` | `sum`/`mean` | broadcast back (`%t-grad-bcast`); mean divides by the reduced count |
| `var`/`std` | COMPOSED from mean/sub/mul/sum/div (+ sqrt) | from the tape -- no bespoke adjoint; keeps the `(n - ddof)` divisor differentiable |
| `amax` | `amax` | mask `(= a out)`, gradient split EVENLY among ties (PyTorch's amax rule) |
| `argmax` | `argmax` | none -- returns the RAW index value/array, not a tensor |
| `softmax`/`log-softmax` | `softmax`/`log-softmax` | `s*(g - sum(g*s))` / `g - exp(out)*sum(g)`, per `:axis` distribution |
| `masked-fill` | `where` | `g` where the mask is zero, 0 where filled; mask and fill value are constants. `-inf` fills are safe through softmax (the `exp` underflow clamp, `.kb/linalg.md`) |
| `reshape`/`view`/`unsqueeze`/`squeeze` | `reshape`/`expand-dims`/`squeeze` | `%t-grad-reshape` (row-major order is shared). `view` IS `reshape` here -- linalg results are fresh copies, nothing aliases |
| `transpose` | `transpose` (axes normalized non-negative) | transpose by the INVERSE permutation |
| `cat`/`stack` | `concatenate`/`stack` | slice the gradient back per input (`%t-axis-spec`); stack drops the new axis again |
| `slice` | the `linalg:slice` plan | `%t-slice-scatter`: the same `%la-slice-bound` normalization and odometer, adding into `zeros-like` at the source positions |
| `gather`/`index-select` | `gather`/`take-rows` | scatter-ADD into `zeros-like` (repeated indices accumulate -- the shared-embedding case) |

Acceptance is the shared table-driven gradient check
(`testsupport/TorchGradcheck`, one `gc-check` row per differentiable op --
extend it with one row per new op, as 461 did for every layer and loss): analytic vs central differences at
relative tolerance 1e-3, run VERBATIM on the interpreter
(`LispEvaluatorTest.torchGradcheckTable`), the JVM
(`JvmLispCompilerTest.compileAndRunTorchGradcheckTable`) and wasm-GC
(`WasmLispCompilerIntegrationTest.compileTorchGradcheckTable`); the
`--component` leg plus the "fit y = 2x" training loop is the byte-identical
ci-spec case `torch-fit-cross-backend` (exact dyadic values, like the linalg
cases). On WASM the polynomial `exp`/`log`/`tanh` differ from `Math.*`, but the
check compares the backward against differences of the SAME forward, which is
exactly the consistency that matters.

## `torch:no-grad`: the one macro, and its three seams

`torch:no-grad` is a BUILT-IN `LispMacroExpander` expansion (the usocket
`with-*` pattern -- a `defmacro` in torch.lisp could not work: the compile path
runs `UserMacroExpander` BEFORE the library splice, so a spliced defmacro would
never expand). `expandTorchNoGrad` produces
`(let ((torch::*grad-enabled* nil)) body...)` -- a DYNAMIC rebinding of the
library's `defparameter`, so each backend's ordinary special-binding
save/restore applies and no EH mode is forced on wasm. Three seams keep that
sound:

- **Interpreter ordering**: the `TORCH:NO-GRAD` case in `evalCons` calls
  `ensureTorchLoaded()` BEFORE evaluating the expansion, so the `defparameter`
  has declared the variable special by the time the `let` binds (otherwise the
  binding would be lexical and the ops would read the global).
- **`SpecialVarCollector`**: the JVM compiler gives a special a thread-local
  store only when it sees a binding form; the `let` here is synthesized during
  codegen, after the scan. `TORCH:NO-GRAD` is therefore listed in
  `LispMacroExpander.expandBuiltinMacro` (which the collector -- and
  `macroexpand-1` -- expand through). Missing this is a LOUD compile error
  ("dynamically bound here but has no thread-local store"), never silence.
- **`LibraryDefunPruner`**: the expansion synthesizes `torch::*grad-enabled*`
  AFTER the pruner runs, so a `torch:no-grad` occurrence is a hardcoded
  reference edge to it -- the second such edge beside `vec:aref` ->
  `vec:aset` (`.kb/library-defun-pruning.md`);
  `LibraryDefunPrunerTest.torchNoGradKeepsTheSynthesizedGradEnabledVariable`.

The formatter needs an explicit `IndentRules` entry (`"no-grad"`,
`Style.body(0, 2)`): no `with-`/`do-`/`def` prefix, so the naming-convention
guess would lay its body out as call arguments (`.kb/formatter.md`).

## The module layer (todo-461): a second record, walked for its parameters

The `nn` half is the SAME defun-only decision applied again -- a module is a
five-slot general vector (so it never collides with the six-slot tensor),
`torch:modulep` discriminating on the `torch::%module` tag in slot 0:

| slot | field | contents |
| --- | --- | --- |
| 0 | tag | the symbol `torch::%module` |
| 1 | kind | a KEYWORD naming the layer (`:linear`, `:sequential`, ...) |
| 2 | fields | a plist, KEYWORD value ..., holding every parameter, buffer, submodule, list of submodules and hyper-parameter |
| 3 | forward-fn | applied by `torch:forward` as `(funcall fn module args...)` |
| 4 | training | the train/eval flag, `t` at construction |

**The fields plist IS the parameter registration** -- the defun-only stand-in
for walking a CLOS instance's slots, which is what the todo asked for. A
layer's forward reads its parameters back with `torch:field`, never from a
closed-over variable: with the plist as the single place the tensors live, a
parameter that exists cannot be absent from `torch:parameters`, which is the
failure mode ("a layer that forgets to declare one trains silently wrong") the
layout removes. `TorchGradcheck`'s layer rows exercise exactly that seam: they
`torch:set-field` the checked inputs over a freshly built layer's parameters,
so a forward reading a closure copy would fail the check.

Field names and kinds are KEYWORDS, not symbols: rontolisp symbols are
package-distinct (`(eq 'weight 'torch::weight)` is NIL), so a plain symbol
would be `cl-user::weight` in a user program and never `eq` to what the library
wrote.

`torch:parameters` (`%m-collect` / `%m-collect-fields`) walks the field VALUES:
a leaf tensor with slot 3 set is collected once by IDENTITY (`member`, so a
shared weight appears once), a module recurses into its own fields, a LIST
recurses element-wise, everything else contributes nothing. Consequences worth
knowing:

- a tensor field WITHOUT `requires-grad` is a buffer -- collected by nothing,
  cleared by nothing;
- a plain LIST of modules is a `ModuleList`, so no such type exists here;
- `torch:train`/`torch:eval` (`%m-set-mode`) walk the same shape, and
  `torch:zero-grad` accepts a module (clearing every parameter's grad) as well
  as a tensor -- one spelling, at the price of a `zero-grad` reference edge to
  `torch:parameters` (the walker only; no layer, no loss).

Two surface decisions the todo's text does not get:

- **no `torch:call`, no funcallable module.** A record cannot be funcallable in
  this Lisp-2, and two names for one operation is worse than one; `torch:forward`
  is the single spelling, and it also accepts a plain FUNCTION, which is why
  there is no `torch:relu` MODULE either -- an activation goes into a
  `torch:sequential` as `(function torch:relu)`.
- **`torch:set-data`** was added with the layer, not before it: an optimizer (and
  the 461 acceptance training loop) must write the new value into the very tensor
  a module's fields point at. Rebuilding the tensor, the pre-461 idiom in the
  `torch-fit-cross-backend` case, cannot reach a nested parameter.

`torch:linear` stores its weight `(in out)`, NOT PyTorch's transposed
`(out in)`, so the forward is a plain `torch:matmul` and the bias broadcasts
over every leading axis; PyTorch's default
`U(-1/sqrt(in), 1/sqrt(in))` init is kept for both, `torch:embedding` keeps
`N(0, 1)`, and `torch:layer-norm` normalizes over the LAST axis with the biased
(`ddof` 0) variance -- composed from torch ops, so the normalization itself is
differentiable. `torch:dropout` is inverted dropout reading slot 4, and the
losses are plain functions: `torch:mse-loss` and a `torch:cross-entropy-loss`
that flattens all but the last axis, picks `-log-softmax` at the target class,
and under `:ignore-index` drops the position from BOTH the sum and the mean's
denominator (the padding case) -- the ignored index is also clamped to 0 before
the `torch:gather` so a sentinel target cannot index out of range.

Acceptance beyond the gradcheck rows: `TorchGradcheck.NN_TRAINING_PROGRAM`, a
2-8-1 ReLU MLP trained on XOR for 200 SGD steps over `torch:parameters`, run on
the interpreter, the JVM and wasm-GC; the `--component` leg is the ci-spec
`torch-nn-cross-backend` case, which also pins the exact-dyadic module
forward/backward, the parameter walk and the mode switch. Both programs use only
`+ - * /` and `max` (ReLU, MSE, the seeded Wichmann-Hill generator), so every
backend follows the same trajectory.

461 adds no body-taking operator, so the formatter needs no new `IndentRules`
entry: every module and loss name is a plain function, laid out as a call.

## The optimizers (todo-462): a third record, and the in-place update

The same defun-only decision a third time. An optimizer is a SIX-slot general
vector -- the same length as a tensor, and deliberately so: the LENGTH is only a
shape pre-check, the TAG in slot 0 is what all three predicates actually
discriminate on, and a fourth distinct length would have been a fiction. Slots:

| slot | field | contents |
| --- | --- | --- |
| 0 | tag | the symbol `torch::%optimizer` |
| 1 | kind | a KEYWORD naming the rule (`:sgd`, `:adam`, ...) |
| 2 | params | the list of parameter tensors it updates |
| 3 | fields | a plist, KEYWORD value ..., holding every hyper-parameter AND every state buffer |
| 4 | step-count | the optimizer's OWN counter, incremented by `torch:step` BEFORE the rule runs |
| 5 | step-fn | `(lambda (self) ...)`, applied by `torch:step` |

Four decisions worth keeping:

- **The fields plist is the state, again.** `torch:field`/`torch:set-field` now
  read slot 2 of a module or slot 3 of an optimizer (`torch::%m-fields-slot`),
  so there is ONE accessor pair for both records and no `torch:state` /
  `torch:set-state` surface at all. The momentum buffer and Adam's `m`/`v` are
  fields (`:buffers`, `:m`, `:v`) holding a general vector indexed by the
  parameter's position, allocated on the first step; the learning rate is a
  field too, which is the whole of what an LR schedule needs
  (`(torch:set-field opt :lr new)`) without a scheduler type existing.
- **The update is ELEMENT-WISE and IN PLACE**, `setf row-major-aref` over the
  parameter's packed array with no temporary: a fresh array per parameter per
  step is the allocation that dominates a small training loop. Because the rule
  uses no torch op it records nothing on the tape, so -- unlike the hand-written
  `torch:set-data` update the 461 acceptance loop uses -- `torch:step` needs NO
  `torch:no-grad` around it. A scalar parameter (data is a plain NUMBER) is the
  one branch inside the element loop; a parameter whose grad is still nil is
  skipped, like PyTorch's `if p.grad is None: continue`.
- **`torch:step` increments the counter FIRST.** Adam's bias correction divides
  by `1 - beta^t` with `t` = the optimizer's own `torch:step-count`, so the
  first step is fully corrected and has magnitude `lr`. That is the classic
  off-by-one; the `adam-3steps` row of the optimizer table pins the exact
  three-step sequence and the ci-spec case pins `t = 1` as a predicate.
- **`torch:optimizer` is public**, like `torch:module`: a rule this package does
  not ship (AdamW, a gradient clip) is a plain defun over the same record, and
  the built-in `torch:sgd`/`torch:adam` are ordinary callers. Both accept a
  MODULE or a parameter list (`torch::%o-params`), and `torch:zero-grad` gained
  an optimizer branch -- the three PyTorch spellings of "clear the gradients"
  are one function here.

The rules follow **PyTorch's**, not the `examples/deep-learning-from-scratch/`
book's (whose Adam folds the bias correction into a step size and leaves `eps`
outside it, a different sequence): the coverage target is a book written against
`torch.optim`. That example keeps its own hand-written optimizers on purpose --
its point is that the reader writes them -- and must not be made to depend on
this package.

## Batching and the masks (todo-462): plain functions, no `DataLoader`

`torch:pad-sequence` (list of sequences -> one padded rank-2 tensor, BATCH
FIRST), `torch:shuffled-batches` (a list of examples, or an integer `n` standing
for `0..n-1`, -> a list of LISTS), `torch:padding-mask` and
`torch:subsequent-mask`. Three things this shape buys:

- a batch is an ordinary list, so the caller keeps its own pairing of parallel
  sequences (source and target) instead of a collate protocol -- and the integer
  form batches several parallel arrays at once by handing back index lists;
- the shuffle draws from the SEEDED `linalg` generator (`linalg:permutation`,
  integer arithmetic), so an epoch reproduces on every backend, and
  `:shuffle nil` makes the same function the evaluation pass;
- the two masks are RAW linalg arrays, not tensors: a mask carries no gradient,
  `torch:masked-fill` takes it as a constant, and any NON-ZERO counts as masked
  so `linalg:add` combines a padding mask with a causal one. They are shaped to
  broadcast over a `(batch query-length key-length)` score -- `(batch 1 length)`
  and `(1 n n)`.

`torch:inference-mode` was NOT added: it would be a second name for
`torch:no-grad` here (there is no version counter to invalidate), so the todo's
"only if it turns out to differ" resolved to no.

Acceptance beyond the earlier two programs: `TorchGradcheck.OPTIMIZER_PROGRAM`,
a table of `ok` rows -- one SGD step, the L2 term, a three-step momentum
sequence, a scalar parameter, a skipped gradient-less parameter, Adam's three
steps and its `:lr` field, and every batching/mask helper -- followed by the
book's chapter-2.3.4 experiment: the same feed-forward block learning the
identity with and without a skip connection around it, trained by `torch:adam`,
asserting that both losses at least halved and that the residual one both
started and finished lower. It runs on the interpreter, the JVM and wasm-GC; the
`--component` leg is the ci-spec `torch-optim-cross-backend` case, whose SGD
values are exact dyadic rationals (lr `0.125`) while Adam, which takes a square
root, is pinned as a tolerance predicate. 462 adds no body-taking operator
either, so again no new `IndentRules` entry.

## `--simd`, and what is deliberately NOT accelerated

torch bottoms out in LITERAL `linalg:` calls with literal keywords (the
`compiler.LinalgKernelCallLayout` pattern-match contract), so a torch program is
accelerated under `--simd` exactly where linalg is -- for free, with no torch-
specific interceptor. The standing candidate remains the one `.kb/linalg.md`
records: the rank->=3 stacked `%la-matmul-nd` (what a transformer forward pass
spends its time in) is not intercepted on any path; measure before adding an
interceptor, and if one lands, torch inherits it without change. `--no-gc` is
unsupported (torch needs arrays, like linalg).

## Wiring (the LinalgLibrary pattern, plus the ordering rule)

Package `torch` in `PackageRegistry` (`TORCH_FUNCTIONS` /
`torchFunctionNames()`; does not use `cl`, no nickname). Interpreter: lazy load
in `LispEvaluator.resolveFunction` + `ensureTorchLoaded`. Compile paths
(`RontoLispCli`, `RontoPlayground`, the corpus tests, the per-backend test
helpers): **`TorchLibrary.process` runs BEFORE `LinalgLibrary.process`**, so
the `linalg:` references inside the spliced torch defuns are seen by the linalg
detection -- get the order wrong and a torch-only program fails to compile with
undefined `linalg:` functions. torch.lisp is written in canonical package shape
(resolver fixed-point,
`PackageResolverTest.torchLibraryFormsAreAResolverFixedPoint`), registered in
the native-image `resource-config.json` (typeReachable `TorchLibrary`), and
formatter-pinned like every checked-in `.lisp`.

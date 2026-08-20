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

## The tensor: a `defstruct` record, and the pruning decision

**Decision (todo-466, superseding todo-460's "defun-only, no `defstruct`
either"): the three records are `defstruct`s; everything else is a plain defun;
`defclass`/`defmethod` stay out, ever.** The original rule existed for ONE
reason -- `LibraryDefunPruner` could not prune a bundled library's `defstruct`,
so one would have been an unprunable root that also anchored every name its body
spells, and every program touching `torch:softmax` would carry the whole
package. **todo-465 removed that reason**: a bundled defstruct is now expanded
into its generated defuns AHEAD of reachability (`BundledStructs` in the pruner,
`.kb/library-defun-pruning.md`), so each constructor, predicate and accessor
prunes INDIVIDUALLY --
`LibraryDefunPrunerTest.keepsOnlyTheTransitiveClosureOfTheCalledTorchFunction`
pins that a `torch:tensor`-only program drops `torch:backward`/`torch:matmul`
and the unused linalg members AND the record's own unread accessors
(`%t-parents`, `%t-backward-fn`) and the whole module and optimizer records with
their printers.

**CLOS is still excluded, and now for the DISPATCH reason alone, not the pruning
one**: nothing in this package dispatches -- the binary ops normalize
tensor/number/raw-array through `torch::%t-wrap` instead of a method matrix, and
`torch:forward` goes through the module's own closure precisely so a
`torch:sequential` can hold a bare `lambda` with no wrapper type existing. A
`defclass` would buy the records nothing and cost the CLOS surface. That is the
re-evaluation trigger: only a genuine dispatch need justifies revisiting it.

What the swap bought, beyond the accessors pruning:

- **A printed record.** Each record carries `(:print-object ...)`
  (`.kb/defstruct.md`), so a tensor is `#<TENSOR #d(1.0 2.0) :REQUIRES-GRAD T>`,
  a module `#<MODULE :LINEAR>` and an optimizer
  `#<OPTIMIZER :SGD :STEP-COUNT 0>` -- identical on all four backends, `prin1`
  and `princ` alike. The printers spell only what every backend agrees on and
  NEVER the tape slots: `backward-fn` holds a closure whose printed form is
  backend-dependent, which is why the pre-466 representation could not be
  printed at all and this file used to forbid examples and cross-backend pins
  from printing a tensor. That rule is retired.
- **`torch:tensorp` stopped allocating.** It was
  `(and (arrayp x) ... (equal (array-dimensions x) '(6)) (eq (row-major-aref x 0) 'torch::%tensor))`
  -- a fresh list per call, on the hottest predicate in the package (every op
  entry runs it through `%t-wrap`). It is the generated tag test now.
- **`torch::%m-fields-slot` is gone.** It returned a slot NUMBER recovered by
  type test (2 for a module, 3 for an optimizer) so that `torch:field` /
  `torch:set-field` could serve both records. The pair is
  `torch::%mo-fields` / `torch::%mo-set-fields` over named accessors now -- same
  one-accessor-pair-for-both-records surface, no slot arithmetic.
- **The tag-vs-length discrimination note is gone**: a `defstruct` tag test
  cannot confuse two records, so the optimizer's "six slots like a tensor, which
  is why the TAG is the discriminator" bookkeeping has nothing to say.

The public API did NOT change: `torch:tensorp`/`torch:data`/`torch:grad`/
`torch:shape`/`torch:item`/`torch:field`/`torch:parameters`/... are the same
names with the same behavior; only their bodies moved. Nothing outside
torch.lisp learns the accessor names.

`torch::%tensor` (constructor `torch::%t-new`, conc-name `torch::%t-`,
predicate `torch:tensorp`, no copier):

| field | contents |
| --- | --- |
| data | a linalg array (packed float, any rank; `:element-type` honoured) or a NUMBER -- a plain number is the rank-0 scalar tensor (`torch:shape` nil, like `linalg:ndim` 0) |
| grad | nil, or a value of data's shape -- a RAW linalg value, not a tensor |
| requires-grad | the LEAF flag from `(torch:tensor x :requires-grad t)` |
| parents | the input tensors this one was computed from |
| backward-fn | nil, or `(lambda (grad-out) ...)` -> the per-parent gradient list (nil entries for untracked parents) |

No `:type (vector ...)`: the fields are heterogeneous (a linalg array, a
closure, a list, a flag), so the packed-integer arm of `.kb/defstruct.md` does
not apply and the instance is an ordinary object.

A tensor "tracks" when `requires-grad` or `backward-fn` is set
(`torch:requires-grad-p`). Every op
routes operands through `torch::%t-wrap` (tensor passes through; number / raw
array / list becomes a constant leaf) and results through `torch::%t-result`,
which records the tape edge only while `torch::*grad-enabled*` is true AND some
parent tracks -- otherwise the result is a plain leaf and the inputs stay
collectable. Reductions of a whole tensor produce scalar (number) data;
`linalg`'s binary kernels accept numbers on both sides, and the emap-based
unary ufuncs get number branches in the `torch::%t-r*` helpers, so scalar
tensors flow through every op.

### Two seams the record swap made load-bearing

- **`eq`/`eql` on a record instance is REFERENCE identity, on every backend.**
  `torch::%t-topo`'s visited marking and `torch::%m-collect`'s parameter dedup
  are `member` (i.e. `eql`) over records, and both mean IDENTITY. The JVM and
  both WASM backends always compared instances by reference; the INTERPRETER
  compared them with `LispInstance.equals`, which is structural -- so two
  distinct scalar tensors holding the same number (an untracked `1.0` constant
  leaf, two `(torch:tensor 0.5 :requires-grad t)` parameters) were `eql` there
  and the tape conflated them. `Environment.isIdentityAggregate` puts instances
  beside conses now, which is what CL specifies and what closes todo-444's Gap 2.
  `equal` on instances stays structural, deliberately (`.kb/instance-syntax.md`).
- **The interpreter must load torch BEFORE it decides a print's routing.** The
  printing operators pick the `print-object` route from the registry as it
  stands when the FORM is expanded, which is before its argument is evaluated --
  and evaluating the argument is what lazily loads torch. Without a pre-load the
  very first `(print (torch:tensor '(1.0 2.0)))` of a session rendered the raw
  `#S(TORCH::%TENSOR ...)` while every later print routed.
  `LispEvaluator.referencesTorch` triggers `ensureTorchLoaded()` in the printing
  case for exactly this, the same ordering seam the `torch:no-grad` case below
  has. Torch is the only lazily loaded library that defines a `print-object`
  method; a second one needs the same treatment.
  Pinned by `LispEvaluatorTest#theVeryFirstPrintOfATensorAlreadyRoutesThroughItsPrinter`.

**Still raw, and NOT a defect of this layer**: the REPL's (and `DocExamplesTest`'s)
value ECHO renders with the internal printer, which consults no `print-object`
method -- so a tensor typed at the REPL echoes `#S(TORCH::%TENSOR ...)` while
`(print it)` gives `#<TENSOR ...>`. That is true of every `print-object` struct,
predates this change, and is why the doc pages show a `print` call plus its
output block rather than a `; =>` value.

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

The records' own acceptance is `TorchGradcheck.RECORD_PRINT_PROGRAM` -- the
three printed renderings plus the `eq`/`eql`/`member` identity lines -- run
verbatim on the interpreter
(`LispEvaluatorTest.torchRecordsPrintAndCompareByIdentity`), the JVM
(`JvmLispCompilerTest.compileAndRunTorchRecordPrinting`) and wasm-GC
(`WasmLispCompilerIntegrationTest.compileTorchRecordPrinting`), with the
`--component` leg in the ci-spec case `torch-record-print-cross-backend`.

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

The `nn` half is the SAME record decision applied again -- `torch::%module`
(constructor `torch::%m-new`, conc-name `torch::%m-`, predicate
`torch:modulep`):

| field | contents |
| --- | --- |
| kind | a KEYWORD naming the layer (`:linear`, `:sequential`, ...) |
| fields | a plist, KEYWORD value ..., holding every parameter, buffer, submodule, list of submodules and hyper-parameter |
| forward-fn | applied by `torch:forward` as `(funcall fn module args...)` |
| training | the train/eval flag, `t` at construction |

**The fields plist IS the parameter registration** -- the stand-in for walking a
CLOS instance's slots, which is what the todo asked for. A
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
  `torch:sequential` as `(function torch:relu)`. `torch:gelu` joined it the
  same way with the chapter-3 port, and is a pure COMPOSITION of torch ops -- no adjoint of
  its own -- over `torch:erf`, whose kernel is `linalg:erf`. Its
  `:approximate` defaults to `:none`, the exact
  `x * (1 + erf(x / sqrt(2))) / 2`, matching `nn.GELU`'s own default; `:tanh`
  is the GPT/BERT form. Shipping only the `tanh` form would have been a
  divergence whose "why" was "we did not add erf" -- see `.kb/linalg.md`.
- **`torch:fields` is what makes a module tree walkable from
  OUTSIDE.** `torch:field` reads one field by name, which is all a forward
  needs; a WALK -- `nn.Module.apply`, `nn.Module.named_parameters` -- needs the
  whole plist, and gets it as a fresh spine over live values. That is why
  neither of those two PyTorch methods has a counterpart here: a walk is
  `torch:fields` plus `torch:module-kind`, and dispatching on what a layer IS
  beats PyTorch's substring test over dotted names (`'ln' in name` also
  selects a layer someone called `blend`). `examples/llm-from-scratch/gpt/`
  writes both walks -- the weight init and the weight-decay split -- in eight
  lines each.
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

`torch:cross-entropy-loss` also takes PyTorch's **probability (soft-label)
target** -- added by todo-463, because the book's chapter-2 notebooks call
`nn.CrossEntropyLoss` that way and an example that hand-rolls
`-sum(p * log-softmax(x))` around the library is the signal the library is
missing it. The two forms are told apart by SHAPE ALONE
(`torch::%m-ce-soft-p`): a target whose `array-dimensions` equal the logits'
is a distribution, everything else is class indices. A LIST is therefore
always indices -- a list as long as the class count would otherwise be
ambiguous with an unbatched distribution -- so the probability spelling needs
a tensor or an array, and `:ignore-index` does not apply to it (there is no
single class to drop), exactly like PyTorch. The soft branch
(`torch::%m-ce-soft`) is composed from `torch:mul`/`torch:sum` over
`torch:log-softmax`, so the gradient reaches the TARGET too when it requires
one; the `cross-entropy-soft` and `cross-entropy-soft-rank1` gradcheck rows
check both operands.

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

The same record decision a third time -- `torch::%optimizer` (constructor
`torch::%o-new`, conc-name `torch::%o-`, predicate `torch:optimizerp`):

| field | contents |
| --- | --- |
| kind | a KEYWORD naming the rule (`:sgd`, `:adam`, ...) |
| params | the list of parameter tensors it updates |
| fields | a plist, KEYWORD value ..., holding every hyper-parameter AND every state buffer |
| step-count | the optimizer's OWN counter (initform 0), incremented by `torch:step` BEFORE the rule runs |
| step-fn | `(lambda (self) ...)`, applied by `torch:step` |

Note the name split the conc-name forces: `torch::%o-params` is the generated
ACCESSOR, and the module-or-list coercion an optimizer is built from is
`torch::%o-param-list`.

Four decisions worth keeping:

- **The fields plist is the state, again.** `torch:field`/`torch:set-field` read
  the fields of a module or of an optimizer through `torch::%mo-fields` /
  `torch::%mo-set-fields`, so there is ONE accessor pair for both records and no
  `torch:state` / `torch:set-state` surface at all. The momentum buffer and
  Adam's `m`/`v` are
  fields (`:buffers`, `:m`, `:v`) holding a general vector indexed by the
  parameter's position, allocated on the first step; the learning rate is a
  field too, which is the whole of what an LR schedule needs
  (`(torch:set-field opt :lr new)`) without a scheduler type existing.
- **`torch:adamw` is the SAME step function, not a twin.**
  `torch::%o-adam-step` reads two more fields, `:weight-decay` and
  `:decoupled`: nil-decoupled adds `wd * param` to the GRADIENT
  (`torch.optim.Adam`), t-decoupled shrinks the parameter directly before the
  moments are touched (`torch.optim.AdamW`), and `wd` 0 is neither. A second
  copy of the inner loop would have been a second place for the bias
  correction to drift. There is no parameter-GROUP object either: two
  optimizers over disjoint parameter LISTS are what a group is here, which is
  how a transformer decays its weight matrices and leaves its biases,
  LayerNorm gains and embedding tables alone.
- **`torch:clip-grad-norm` lives here because nothing else can write a grad.**
  `torch:set-data` writes a parameter's DATA; there is no `torch:set-grad`, so
  `torch.nn.utils.clip_grad_norm_` cannot be a user-level defun. It returns
  the norm as MEASURED (before clipping, so a loop can log it) and scales in
  place by `max-norm / (norm + 1e-6)`, PyTorch's denominator, only when the
  bound is exceeded.
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

## The whole-package acceptance (todo-463): `examples/llm-from-scratch/`

The parent item's coverage target was a BOOK, and its port is the acceptance
test the unit tables cannot be: `examples/llm-from-scratch/` is chapter 2 of
『作ってわかる大規模言語モデルの仕組み』 (the `book-llm-from-scratch`
repository) rewritten on this package -- `transformer/{attention,utils,
transformer}.lisp` for the library half, `chapter02/section{2,3,4,5}.lisp` for
the notebooks, all declared in `examples/examples.yaml` with `.expected` files.
Three things it pins that nothing else does:

- **a module tree several levels deep really walks.** `torch:parameters` over
  the section-5 Transformer finds all 63 of its tensors through
  module -> list-of-modules -> module -> parameter (195 for the two-block one
  in `transformer/shapes.lisp`), and every one of them moves under one
  `torch:adam`.
- **the output is ONE text on all four backends**, training loop included: the
  seeded generator is integer arithmetic, and the example rounds its printed
  floats, so the WASM `exp`/`log`/`sin` approximations cannot show through --
  the interpreter, the JVM, wasm-GC and `--component` legs were diffed
  byte-for-byte, including the 40-epoch training losses and the greedy decode.
- **speed is the binding constraint, not correctness.** A `d_model` 8 /
  1-block / 2-head model over an 8-pair corpus for 40 epochs is ~2 min on the
  plain interpreter and ~4 s on the JVM; the interpreter cost is dominated by
  the rank-3 `%la-matmul-nd` the `--simd` section below still lists as the
  standing interceptor candidate. Anything larger does not fit the examples
  harness's 240 s per-leg cap, which is why the example documents the book's
  shapes and tests shrunken ones.

The one library gap the port surfaced was PyTorch's PROBABILITY target for
`torch:cross-entropy-loss` (see the module-layer section above); it was closed
in torch.lisp rather than worked around in the example, which is the rule the
todo set for this port.

## The GPT increment: `examples/llm-from-scratch/{gpt,chapter03}/`

Chapter 3 of the same book -- the reusable `llm_from_scratch/gpt/` package and
its two notebooks -- ported on the same rule, and it surfaced SEVEN library
gaps rather than one. All seven were closed in `linalg.lisp` / `torch.lisp`:
`linalg:erf`, `torch:erf`, `torch:gelu`, `torch:fields`, `torch:topk`,
`torch:multinomial`, `torch:adamw` and `torch:clip-grad-norm` (plus
`torch:adam`'s missing `:weight-decay`, which `torch:sgd` always had). Each is
described in its own section above; what the port adds beyond them:

- **the two sampling primitives are the first NON-differentiable tensor
  functions after `torch:argmax`, and they follow its shape**: a RAW linalg
  array, never a tensor. `torch:topk` answers the VALUES, or the indices under
  `:indices t` -- ONE of `torch.topk`'s pair, because every function in this
  package is single-valued -- with ties going to the lowest index, so a run
  reproduces where `torch.topk`'s tie order is not specified at all.
  `torch:multinomial` draws from the seeded `linalg` generator, which is what
  keeps a SAMPLED text identical on four backends; without-replacement is the
  default, like PyTorch.
- **the example carries two deliberate DIVERGENCES from the book, both
  because porting the code as written would carry a defect across**, and both
  named in the file that makes them (`gpt/trainer.lisp`): the book's `get_lr`
  computes a warmup for the LOG LINE only and never writes it back, so its
  optimizer runs the whole warmup at the base rate; and `forward(idx, targets)`
  returns a `(logits, loss)` tuple, which splits into `gpt-forward` and
  `gpt-loss` here.
- **the corpus is INLINE, and public domain.** The notebook downloads
  『吾輩は猫である』 from 青空文庫 with requests + BeautifulSoup; nothing is
  downloaded or vendored here, so the opening of the novel is in the source --
  the same choice `chapter02/section5.lisp` made for its parallel corpus.
- **`chapter03/section2.lisp` needs no torch at all** for three of its four
  parts: it is the book's `section03_tokenizer.py`, whose BPE learner depends
  on Python `dict` INSERTION order (`max(pair_freqs, key=...)` breaks a tie by
  taking the pair seen first). Every table there is an ordered association
  list and the tie rule is a strict `>`, which is what makes the port's 100
  merges come out in the book's exact order -- diffed against the Python.

## `--simd`, and what is deliberately NOT accelerated

torch bottoms out in LITERAL `linalg:` calls with literal keywords (the
`compiler.LinalgKernelCallLayout` pattern-match contract), so a torch program is
accelerated under `--simd` exactly where linalg is -- for free, with no torch-
specific interceptor. The standing candidate remains the one `.kb/linalg.md`
records: the rank->=3 stacked `%la-matmul-nd` (what a transformer forward pass
spends its time in) is not intercepted on any path; measure before adding an
interceptor, and if one lands, torch inherits it without change. `--no-gc` is
unsupported (torch needs arrays, like linalg): a torch program is rejected
there long before any `defstruct` is reached -- measured, the error is
`LINALG::%LA-MAKE: lambda-list keywords ... are not supported with --no-gc` --
so the backend's own defstruct rejection never comes into play.

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

# JVM backend: a tail call through a value is a real call while shallow and a bounce through the class's trampoline past 64

**Invariant: on the JVM, a call in tail position whose target the compiler cannot name -- a
`funcall`/general call of a computed designator, a local function's call, an `apply` of a
computed designator -- in a `defun`'s or a lambda's body (a closure, an `flet`/`labels`
function, a continuation, a split-off `_k$N`) is a VALUE TAIL: a call of the class's
`_vtc<n>` (`_vtcv` for an `apply`), which makes it a real call, its answer passed on
unchecked, while fewer than 64 value tails have run since the nearest ordinary call -- the
DEPTH, an argument every method that may bounce takes last -- and answers a BOUNCE past it, on
every thread. Every caller of a compiled function's result checks it for one, and a tail call
the compiler can name hands its callee's bounce on.** A chain of tail calls through values --
a closure calling itself through its variable, two closures in variables, a continuation
handed to a defun that calls it, a state machine of closures in a table, a function applying
itself -- runs in constant stack, as on the interpreter
([interpreter-tail-calls.md](interpreter-tail-calls.md)) and wasm
([wasm-tail-calls.md](wasm-tail-calls.md)), and a shallow one -- an adapter, a composition, a
`reduce` step -- is an ordinary call the JIT can inline. Defuns bounce since
2026-10-03 (`.todo/b69`), lambdas, `apply` and the pass-through since the same day
(`.todo/c08`); the count is an argument since 2026-10-06 (`.todo/d52`), a static field
before. Self and mutual tail calls the compiler can name are jumps instead
([jvm-self-tail-calls.md](jvm-self-tail-calls.md)).

## Mechanics (`JvmTailBounce`)

- **The value tail** (`emitValueTail`, `emitSpreadValueTail`): the designator and the
  arguments evaluate onto the stack, left to right, then the method's depth, and the site
  calls `_vtc<n>(fn, a1..an, depth)` -- `_vtcv(fn, argList, depth)` for an `apply`, which
  calls the raw apply `_applyRaw`. The site builds no array, so it is smaller than the inline
  bounce it replaced.
- **`_vtc<n>`** (`valueTailBody`): with the arity's copy (below) a forwarder, `return
  _vtcd_n(fn, .., depth + 1)`, six instructions Graal inlines while it parses the caller, the
  copy checking the depth at its entry; without one (a routed arity, `--optimize=size`) `depth
  < 64 ? _invoke_n(fn, .., depth + 1) : _vtcb_n(fn, ..)`. `_vtcv`: `depth < 64 ?
  _applyRaw(fn, list, depth + 1) : Object[]{FALSE, fn, list}`. `_vtcb<n>` makes the bounce. The
  answer is never checked here, so a bounce from deeper in the chain reaches the nearest frame
  that checks. A `throw`, a `return-from` or an error that leaves a chain leaves nothing
  behind: the count is an argument.
- **The value tails' own dispatcher** `_vtcd<n>` (`valueTailDispatcherName`): a copy of
  `_invoke_<n>`, same cases, that only `_vtc<n>` calls. A dispatcher's branch profile is one
  per method, so through the shared one a JIT inlining a chain hop by hop inlines every
  target the program's indirect calls reach at every hop; through the copy, only what value
  tails reach. Built beside `_invoke_<n>` when that one is a single segment
  (`JvmLispCompiler`'s dispatcher build, again in `rebuildDispatchers`; a rebuild over fewer
  cases that brings an arity under one segment retargets `_vtc<n>`, `retargetValueTails`);
  a routed arity's value tails call `_invoke_<n>`, since its copy would double the largest
  dispatchers, and so does every arity at `--optimize=size`, which declines speed-for-size
  trades. The trampoline re-enters the shared one. `_vtcv` calls the raw apply as before.
  The copy answers a depth past 64 with `_vtcb<n>`'s bounce at its entry
  (`emitCopyEntryCheck`) and keeps the fast path alone: a value that is no function value --
  a symbol, an interpreted closure, a count no case takes -- goes to the shared dispatcher,
  which handles it as an ordinary call, so the copy is smaller than the dispatcher it copies.
  Measurements: "the value tails' own dispatcher" and "the count as an argument" below.
- **The depth** is an `int`, the last parameter of every method that may answer a bounce: a
  `bounceVisible` defun (its descriptor minted with it before Pass 2), a lambda whose body
  reads it, a continuation split from either -- and of every dispatcher (`_invoke_<n>` and
  its segments, `_vtcd<n>`, `_invoke_v`) and of `_applyRaw`, whose cases pass it to a target
  that takes it (`JvmRuntimeBuilder.dispatcherDesc`). An ordinary call passes 0
  (`emitDispatchCall`, a direct call of a bouncing defun that checks its result, `_apply`,
  the thread, async and HTTP entries, a `jvm-export` wrapper, the eval runtime's calls); a
  named tail call that hands the bounce on passes its own (`emitDirectCall`), as do a tail
  group's jump (into the sibling's slot, `Member.depthSlot`) and epilogue and a
  continuation's call; a value tail passes its own plus one; the trampoline the limit. Every
  lambda compiles with the depth's slot after its parameters; one that never reads it
  (`Ctx.depthRead`: no value tail, no named callee's bounce handed on, no continuation,
  not a tail group's member) is written without the parameter, `bounceVisible` false, and the
  body that made its value is re-pointed at that descriptor (`MethodCode.retargetValueOf`) --
  a lambda is named only by the dispatchers and that body, both settled after Pass 2c. No
  field, no owner: every thread makes real calls. Inlined, the depth is a constant per hop
  and the checks fold; the count in a static field cost two stores and three loads a hop no
  JIT folded. The dispatcher keeps its search-tree id and the funcval cast in one-byte
  slots (the cast over the funcval parameter): an arity-1 dispatcher near C2's hot-inlining
  size (325 bytes) stays where it was.
- **The bound** is per ordinary call: at most 64 frame groups (`_vtc<n>`, the dispatcher --
  the copy, or the router and segment when routed --, the callee, and any named tail call
  the callee hands on between) since the nearest call that passed 0, on any thread. A
  non-tail recursion whose every level runs a chain keeps up to 64 groups a level -- the
  static count bounded the owner thread's whole stack. `-Drontolisp.jvm.value-tail-limit=N`
  at compile time (clamped to 0..32767) replaces 64; 0 makes every value tail bounce, for
  measuring.
- **The bounce** is the value tail's answer past the limit: `Object[]{Boolean.TRUE,
  designator, arg...}`, or for an `apply` `Object[]{Boolean.FALSE, designator, argList}`. No
  Lisp value is an `Object[]` with a `java.lang.Boolean` in slot 0.
- **The check** is `invokestatic _unw` after every call whose callee may answer one: every
  dispatcher call site, a direct call of a `bounceVisible` defun, `_apply`'s exit
  (`buildApplyEntry`), the async/thread/http entries, a pull stream's close thunk (its answer
  dropped after the check; unchecked until 2026-10-06, a close whose tail ran a chain past the
  limit stopped at the bounce -- and while the count was one thread's, a close of one hop
  stopped on every other: a stream an async body drained skipped a close shaped like
  http.lisp's `(lambda () (funcall release nil))`,
  `JvmAsyncCompilerTest#aPullStreamsCloseThunkMakesTheCallsItsTailMakesThroughAValue`), a
  `jvm-export` wrapper of a bouncing defun, the linalg/geom/simd scalar fallbacks and
  `%check-sequence-runtime`'s call. On a
  bounce `_unw` hands it to `_tramp`, which re-enters `_invoke_<n>` -- or the raw apply
  `_applyRaw` for a spread bounce, so a chain of applies keeps no frame -- in a loop until a
  real value comes back: the deferred call runs in the checking frame.
- **The trampoline re-enters at the limit.** `_tramp` passes 64 as the depth to the
  dispatcher (or `_applyRaw`) it re-enters: the chain it drives filled the limit once, so its
  value tails keep bouncing instead of refilling 64 real calls a round, which measured up to
  2.8x slower on Graal (Measurements). An ordinary call the re-entered callee makes starts
  at 0; under the static count, held at the limit for the whole thread, a chain nested in a
  trampolined one bounced at every hop too.
- **The tail mark** is the self jump's (`Ctx.tailMark`, [jvm-self-tail-calls.md](jvm-self-tail-calls.md)):
  only a call whose value is the method's result with no dynamic extent in between is a value
  tail.
- **A named tail call stays direct.** `(funcall #'name ..)` / `(apply #'name ..)` of a function
  the registry has, and a call by name, compile to the `invokestatic` (`JvmDesignatorCall`);
  in a method whose callers all check (`Ctx.passesBounces`: every lambda, a `bounceVisible`
  defun, a continuation) the callee's bounce is returned unchecked (`passesThrough`). So a
  continuation calling a defun that calls the continuation keeps no frame per round, and a
  `(funcall #'f ..)` tail allocates nothing.
- **Which defuns may answer one** (`bouncingDefuns`, before Pass 2): a value call on the
  tail walk (`JvmTailGroup.tailCalls`, the mark's own relays), a direct tail call of such a
  defun, or a member of a tail group with one. Its `FunctionInfo.bounceVisible` decides the
  defun's `tailBounce`, so a walk that misses a relay only keeps that call a call. A defun's
  descriptor is needed at its callers before its body is compiled, hence the walk; a lambda
  is named only by the dispatchers and the body that makes its value, so every lambda
  compiles as one that may bounce (only dispatchers, `_apply` and the runtime entries call
  it, and each checks) and its own body settles it: one that never read its depth is written
  without it and is not `bounceVisible` (The depth, above).
- **The gate is decided after the shake.** `_tramp` and `_unw` are always declared; `_unw`'s
  body is written once the class is assembled (`trampolineLive`): with its body still empty
  `_tramp` is reachable from nothing, so `JvmClassSplitter.reachable` from the shake roots
  says whether a method that bounces (`Ctx.bouncingBodies`, by identity; a tail group's
  layout joins it when a member's body does) is kept. If none is, `_unw` answers its argument
  and `_tramp` -- with every dispatcher arity it re-enters and the closures only those
  reach -- is shaken away. A closure no kept body makes counts for nothing, its dispatcher
  case included (`.kb/optimize-dead-code-elimination.md`, "A dispatcher case lives while a
  kept body makes its value"): `reduce :from-end`'s argument-swapping lambdas in a wrapper
  body nothing calls held `_tramp` in `clos`/`sort`/`string` (below) until they stopped
  counting. The `_vtc<n>` helpers and the `_vtcb<n>` that make their bounces are reached only
  from the bodies that make value tails, so they go with `_tramp`. The dispatchers are
  rebuilt over the values kept bodies make AFTER `_unw` is written, so every caller of a
  dispatcher counts, the trampoline's re-entries among them.

## What keeps a frame

- A call inside a dynamic extent: a special binding (a parameter named like a special
  included, [dynamic-special-variables.md](dynamic-special-variables.md)), `unwind-protect`,
  `handler-case`, `catch`, ... -- the callee runs inside it.
- A call the Lisp-2 rewrite makes through a fresh cons (a nested `defun`'s global variable),
  and a self call in a split-off `_k$N`. The body of an inline `((lambda ...) args)` hands the
  mark on since 2026-10-04 ([jvm-self-tail-calls.md](jvm-self-tail-calls.md)); a value call
  there is a value tail.
- A value tail while shallow, by design: up to 64 frame groups since the nearest ordinary
  call, on any thread, which a stack trace shows.

## Measurements (2026-10-03, x86-64 Linux, Xeon E5-2697A v4, Oracle GraalVM 25.0.4, `java Prog`)

**Depth**, 1,000,000 rounds, 16 MiB worker: a closure through its variable, a closure pair, a
continuation through a defun, a two-state closure table, a closure applying itself, a defun
applying the closure that calls it, Clojure's `(@f (dec m))` (through `%clojure-call`'s
`apply`), Scheme's lambdas and `apply`: `StackOverflowError` -> answers.

**The gate.** Before it, the pre-pass's conservative predicate (any head the
registry lacks "may bounce") made every compile trampolined -- the wrapper catalog's
`+`/`min`/`append` bodies end in `DO`, a non-registry head -- so every class with a
dispatcher site carried `_tramp`, which kept every arity's dispatcher and the dead closures
they name: bench-report `bignum`, `list`, `mandelbrot`, `matmul`, `sieve` and the size-report
`zlib` carried it with no bounce at all. The precise predicate frees those programs of the
trampoline and shrinks the classes whose dead dispatchers it removed; the live `_invoke_2` of
`clos`/`sort`/`string` names dead swapping lambdas, whose tails now bounce. An `_unw` site is
one shared-helper call, nothing measurable in time (bench-report with the trampoline forced
off: equal within noise).

**Time.** bench-report, the spec corpora and a cl-ppcre scan loop: unchanged within noise.
A bounce costs what the dispatcher call it replaces could inline away: a chain of forwarding
closures or through a `compose`d closure pays it in full, unless Graal's escape analysis
removes the bounce of forwarding closures made in the calling function. A defun's bounce was
already that price -- the cost the limit below takes off the shallow path. Clojure (develop
after `.todo/c00`, whose call sites funcall a real function): `reduce` with a two-argument
`fn`, 2M calls of a local two-argument `fn`, `map` over 2M elements -- unchanged within noise;
before `.todo/c00` `map`'s per-element lambda handed `%clojure-call`'s apply bounce on and
paid for it.

## Real calls while shallow

Same box (64 threads, shared with other builds), Oracle GraalVM 25.0.4, Graal (`java Prog`) and
C2 (`java -XX:-UseJVMCICompiler Prog`), every value tail bouncing -> the limit: a real call
through the shared dispatcher beat the bounce in most shapes, on both JITs -- the forwarding
closures, adapters and defun forwarders aside, which the bounce already served or which Graal's
escape analysis removes.

Where it loses, on Graal only: Graal scalar-replaces a bounce whose array its inlined `_tramp`
loop consumes when one closure type dominates the trampoline's profile, while the real call
went through the shared dispatcher at every hop -- so a chain through globals of one closure
type, a few hops through megamorphic dispatch, and a deep state machine whose `_tramp` profile
also holds the 64 real calls each chain starts with run slower (alone in its program the state
machine measures alike either way). No limit recovers them; at 0, every value tail bouncing,
they measure as every bounce did. The cause was first read as the
dispatcher not inlined past one recursion level; the 2026-10-06 traces overturned that for
Graal (below): it inlines the dispatcher recursively, each level with every target the
dispatcher's one profile holds. The one-type chain has run faster than its bounce since.

Rejected, on a prototype measured the same way:
- A `ThreadLocal<int[]>` count, so every thread makes real calls: worse than this count in
  every shape and worse than the bounce in the one-type ones.
- Without the trampoline holding the count: every chain shape slower.
- A smaller limit: nesting past it runs slower than every value tail bouncing; the 8-type
  gains are the same at every limit from 2.
- The real call in a method of its own (`_vtc<n>` the check and the bounce, `_vtcCall<n>` the
  counted call), to keep it out of the trampoline's compiled loop: worse, for a method more per
  arity.

Bytes: bench-report's ten programs byte-identical (`.class` and both wasm: none keeps a value
tail); size-report's legs unchanged but the one with value tails, whose call sites shrink and
whose helpers cost; the corpora as one program level; of the JVM-compiled examples the
many-value-tail ones shrink and the rest pay, once per class, for `_vtc<n>`, `_vtcClaim`, the
two fields and `_tramp`'s hold. WASM output byte-identical.

Time on whole programs, unchanged within noise: the corpora, a cl-ppcre scan loop; Clojure
`comp`, `partial`, `map`/`filter`/`reduce` loops.

## The value tails' own dispatcher (2026-10-06)

Same box; the comparison's columns: every value tail bouncing (`value-tail-limit=0`) / real
calls through the shared `_invoke_<n>` (before) / through `_vtcd<n>` (now). The own dispatcher
puts a one-hop value tail back at bounce-free cost on both JITs -- the one-type forwarder
inlines whole, its bounce the slower by far on Graal -- and serves the chain shapes at the
shared dispatcher's rate or better.

What a chain of several hops still pays is the count and the copy's one profile, which every
hop of the chain shares. The count: a prototype with none (unbounded, a measurement only) runs
the chains faster by a fraction of a hop (`.todo/d52`, an argument since: "the count as an
argument" below); with the owner check or the exception handler left out instead, nothing
moves. The shared profile: the megamorphic composition and the 8-state chains
of 10 lose on it, while on Graal the adapter and the forwarder of the composition's
program win.

The traces behind it (one forwarder through a global): `-Djdk.graal.TraceInlining=true`
shows `_invoke_1` inlined into itself seven levels deep, each level with both lambdas' cases
-- the branch profile is the method's, so the forwarder's call and the leaf's are not told
apart -- and stops at the eighth; through `_vtcd1` the run loop inlines `_invoke_1` with the
forwarder alone and `_vtcd1` with the leaf alone. C2's `-XX:+PrintInlining` refuses the second
recursion level of a small `_invoke_2` ("recursive inlining is too deep",
`MaxRecursiveInlineLevel=1`). So neither JIT stops for the dispatcher's shape or size: the
plan's other item -- a `tableswitch` over the funcIds, smaller segments, "a shape the JIT
inlines one more level" -- was not built.

The 8-instance state machine beside the CPS sums is no property
of the bounce: alone in its program every variant is bimodal, and under
`-XX:+UseParallelGC` the bounce, the shared and the own dispatcher all measure alike.

Rejected, on a prototype measured the same way:
- An allocation-free bounce on the owner thread (the designator and the arguments in
  owner-confined statics, a shared marker as the answer): under G1, the default, slower
  wherever a chain bounces. A static field lives in
  the class mirror, an old-generation object, so every store of a young argument into it
  takes G1's cross-region post-write barrier; under `-XX:+UseParallelGC` the same statics beat
  the array. A young
  array is what G1 makes cheap, and Graal often removes it.
- The trampoline re-entering the copy too: the 8-instance state machine beside many arity-2
  lambdas slower on Graal, the rest equal.
- A copy for a routed arity too: the chain shapes gain, but the copy doubles the largest
  dispatchers -- a real slice of the JVM-compiled examples' and ci-spec's classes -- and the
  cl-ppcre loop, the closure-heavy real program measured, moves under neither.

Bytes: bench-report's ten programs identical (`.class` and both wasm); the corpora as one
program identical (every arity their value tails use is routed); of the JVM-compiled examples
those with a copy pay a hair, each copy at
most one segment. WASM output identical.

## The count as an argument (2026-10-06, `.todo/d52`)

Same box and method. Columns: the count in a static field (before) -> the depth argument.

**The premise** (prototypes, Graal / C2, a chain of 3 through globals): the count in a static
field costs a noticeable slice of a hop, none at all (unbounded, a measurement only) is the
fastest, the depth checked in `_vtc<n>` sits between, and the depth checked in the copy's
entry matches the unbounded prototype. Checked in `_vtc<n>`, the method is no longer six
instructions: `-Djdk.graal.TraceInlining=true` shows the cost-benefit phase weighing it at
every hop like the copy, and the chain's last hop stays a call ("the reason for not inlining
is unspecified"); as a forwarder Graal inlines it while it parses the caller.

Across the shapes the depth argument never loses to the count but for one, on C2 only: two
forwarders made in the function that calls them. There C2's escape
analysis sees every closure, and its `AggressiveUnboxing` misfires once no store is left in
the chain -- under `-XX:-AggressiveUnboxing` the depth beats the count, under
`-XX:-EliminateAutoBox` the two measure alike, the unbounded prototype without a count lands
where the depth does, and the depth with one `putstatic` of it per hop recovers the loss. A
store a hop is
the cost this change removes, so none is added. The state machine of 8 lambdas
and the pair that run on the trampoline past the limit pay for it; the closure through its own
variable is bimodal on both, the depth landing on the slow mode
more often.

The recursion depth the bound per ordinary call costs, 16 MiB worker, the largest n a
non-tail recursion through a defun survives (cold start, binary search to 1%): the
static count bounded the whole stack, so its chains bounced from the 65th value tail on and a
level kept the trampoline's frames; the depth keeps a level's chain as real calls, and the
surviving depth shrinks the more value tails a level carries.

Rejected, measured the same way:
- Every lambda taking the depth, read or not: the no-value-tail recursion survives less of it,
  Graal's tree evaluator loses the `:add` handler's first `ev` call from the
  inlining, and every closure's case in the dispatchers a byte larger.
- The dispatcher's id and funcval after the depth's slot: an arity-1 `_invoke_1` loads the id
  with a two-byte `iload` at every node of its search tree, past C2's `FreqInlineSize`
  (325), and its leaf calls pay for it. With the id in
  the first free slot and the funcval cast over its parameter, the dispatcher stays inlinable;
  so does the copy, delegating
  everything but a function value to the shared dispatcher.
- The bounce array built in the copy instead of `_vtcb<n>`: no different.
- A catch-all handler around `_vtc<n>`'s call, for C2's two forwarders: no
  different.

## Tests

`JvmLispCompilerTest#aShallowValueTailIsACallAndAChainPastTheLimitBouncesIntoTheTrampoline`
(every chain length around the limit, `_vtc<n>` a forwarder, the copy's bounce through
`_vtcb<n>`, the trampoline passing the limit, a lambda that reads no depth taking none, no
field),
`#aValueTailCallsThroughADispatcherOfItsOwnWhileItsArityFitsOneSegment` (the copy, the
trampoline on the shared one, none at `--optimize=size` or for a routed arity, a copy the
shake's rebuild brings),
`#aNonLocalExitLeavesAChainOfValueTailsFromEitherSideOfTheLimit` (`throw`, `return-from`, an
error, from past and under the limit), `#aLambdasTailCallThroughAValueRunsInConstantStack`,
`#everyFormTheTailMarkPassesHandsABounceOnAndAPlainTailNeedsNoCheck` (every relay, the
pass-through through RB-HOP, a plain tail's callers carry no check),
`#aTailApplyThroughAValueBouncesWithItsArgumentListUnspread` (with the Clojure call),
`#aTailCallOfALiteralDesignatorIsADirectCallNotABounce`,
`#aTailThroughAValueInASplitOffContinuationHandsItsBounceOn`,
`#theTrampolineIsWrittenOnlyWhenAMethodThatBouncesSurvivesTheShake`,
`#aClosuresValueTailInsideASpecialParametersBindingStaysACall`,
`#theValueTailTrampolineIsEmittedOnlyWhereATailLeavesThroughAValue`,
`#theUnwrapCheckAtACallSiteIsOneCallToASharedHelper`;
`JvmExportTest#anExportedDefunWhoseTailCallsThroughAValueAnswersThatCallsValue`,
`#aChainThroughValuesRunsOnASmallStackOnEveryThread` (five threads with 256 KB stacks, a
1,000,000-deep chain each, at once),
`#aShallowValueTailIsARealCallOnEveryThreadAndTheTrampolineDrivesTheRest` (the closure frames
on the stack when a chain's end signals: one per hop under the limit, on two threads, one in
all past it); ci-spec
`closures-calling-through-a-value-in-tail-position-run-in-constant-stack`,
`tail-calls-through-a-value-answer-alike-at-every-depth` (adapters, compositions, `apply`
forwarders, `reduce :from-end`, chain lengths around the limit, exits, multiple values,
nesting past the limit) and scheme-spec
`tail-call-through-a-value-jvm`,
`lambdas-and-applies-calling-through-a-value-in-tail-position-run-in-constant-stack` (all four
backends).

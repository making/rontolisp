# JVM backend: a tail call through a value is a real call while shallow and a bounce through the class's trampoline past 64

**Invariant: on the JVM, a call in tail position whose target the compiler cannot name -- a
`funcall`/general call of a computed designator, a local function's call, an `apply` of a
computed designator -- in a `defun`'s or a lambda's body (a closure, an `flet`/`labels`
function, a continuation, a split-off `_k$N`) is a VALUE TAIL: a call of the class's
`_vtc<n>` (`_vtcv` for an `apply`), which makes it a real call, its answer passed on
unchecked, while fewer than 64 value-tail frames are on the stack of the thread that owns the
count, and answers a BOUNCE otherwise -- past the limit, and on every other thread. Every
caller of a compiled function's result checks it for one, and a tail call the compiler can
name hands its callee's bounce on.** A chain of tail calls through values -- a closure calling
itself through its variable, two closures in variables, a continuation handed to a defun that
calls it, a state machine of closures in a table, a function applying itself -- runs in
constant stack, as on the interpreter ([interpreter-tail-calls.md](interpreter-tail-calls.md))
and wasm ([wasm-tail-calls.md](wasm-tail-calls.md)), and a shallow one -- an adapter, a
composition, a `reduce` step -- is an ordinary call the JIT can inline. Defuns bounce since
2026-10-03 (`.todo/b69`), lambdas, `apply` and the pass-through since the same day
(`.todo/c08`). Self and mutual tail calls the compiler can name are jumps instead
([jvm-self-tail-calls.md](jvm-self-tail-calls.md)).

## Mechanics (`JvmTailBounce`)

- **The value tail** (`emitValueTail`, `emitSpreadValueTail`): the designator and the
  arguments evaluate onto the stack, left to right, and the site calls `_vtc<n>(fn, a1..an)`
  -- `_vtcv(fn, argList)` for an `apply`, which calls the raw apply `_applyRaw`. The site
  builds no array, so it is smaller than the inline bounce it replaced.
- **`_vtc<n>`** (`valueTailBody`): `d = _vtcDepth; if (d < 64) { if (currentThread() ==
  _vtcOwner) { _vtcDepth = d + 1; r = _invoke_n(fn, ..); _vtcDepth = d; return r; } if
  (_vtcOwner == null) _vtcClaim(); } return bounce`. A catch-all handler around the call
  restores `d` and rethrows, so a `throw`, a `return-from` out of a closure or an error that
  leaves a chain leaves no count behind; the answer is never checked here, so a bounce from
  deeper in the chain reaches the nearest frame that checks.
- **The owner and the count** are two private statics, `_vtcOwner` and `_vtcDepth`. Only the
  owner writes the count, so no thread can make it drift: another thread reads it at most and
  bounces every value tail, the behavior before the limit. `_vtcClaim` (synchronized) makes
  the first thread to make a value tail the owner, once, for the class's life; that call
  bounces. The compiled `main`'s worker claims it in a program, the first caller in a
  `--no-main` library. Not a `ThreadLocal`: its lookup per hop cost more than the owner
  check everywhere and more than the bounce it saves where one closure type runs
  (Measurements); not a shared static: a racing thread drifts it, and a negative drift lets a
  chain grow unbounded; not a depth parameter: it changes every lambda's and bouncing defun's
  descriptor and bounds the frames per non-tail nesting level instead of per thread.
- **The bound** is the owner's whole stack's, nested non-tail calls included: at most 64
  frame groups (`_vtc<n>`, the dispatcher -- its router and segment when routed --, the
  callee, and any named tail call the callee hands on between), a constant a program pays at
  most once. `-Drontolisp.jvm.value-tail-limit=N` at compile time (clamped to 0..32767)
  replaces 64; 0 makes every value tail bounce, for measuring.
- **The bounce** is the value tail's answer past the limit: `Object[]{Boolean.TRUE,
  designator, arg...}`, or for an `apply` `Object[]{Boolean.FALSE, designator, argList}`. No
  Lisp value is an `Object[]` with a `java.lang.Boolean` in slot 0.
- **The check** is `invokestatic _unw` after every call whose callee may answer one: every
  dispatcher call site, a direct call of a `bounceVisible` defun, `_apply`'s exit
  (`buildApplyEntry`), the async/thread/http entries, a `jvm-export` wrapper of a bouncing
  defun, the linalg/geom/simd scalar fallbacks and `%check-sequence-runtime`'s call. On a
  bounce `_unw` hands it to `_tramp`, which re-enters `_invoke_<n>` -- or the raw apply
  `_applyRaw` for a spread bounce, so a chain of applies keeps no frame -- in a loop until a
  real value comes back: the deferred call runs in the checking frame.
- **The trampoline holds the count at the limit.** On the owner, `_tramp` saves `_vtcDepth`,
  sets it to 64 while it loops and restores it on every exit (a catch-all handler): the chain
  it drives filled the limit once, so it keeps bouncing instead of refilling 64 real calls a
  round, which measured up to 2.8x slower on Graal (Measurements). Emitted only in a class
  with value tails; a class without them mints none of the count's pool entries.
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
  defun's `tailBounce`, so a walk that misses a relay only keeps that call a call. Every
  lambda may (`bounceVisible` true: only dispatchers, `_apply` and the runtime entries call
  it, and each checks).
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
  counting. The `_vtc<n>` helpers, `_vtcClaim` and the count's two fields are reached only
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
- A value tail while shallow, by design: up to 64 frame groups on the owner thread, which a
  stack trace shows.

## Measurements (2026-10-03, x86-64 Linux, Xeon E5-2697A v4, Oracle GraalVM 25.0.4, `java Prog`)

**Depth**, 1,000,000 rounds, 16 MiB worker: a closure through its variable, a closure pair, a
continuation through a defun, a two-state closure table, a closure applying itself, a defun
applying the closure that calls it, Clojure's `(@f (dec m))` (through `%clojure-call`'s
`apply`), Scheme's lambdas and `apply`: `StackOverflowError` -> answers.

**The gate, measured first.** Before it, the pre-pass's conservative predicate (any head the
registry lacks "may bounce") made every compile trampolined -- the wrapper catalog's
`+`/`min`/`append` bodies end in `DO`, a non-registry head -- so every class with a
dispatcher site carried `_tramp`, which kept every arity's dispatcher and the dead closures
they name: bench-report `bignum`, `list`, `mandelbrot`, `matmul`, `sieve` and the size-report
`zlib` carried it with no bounce at all. `_unw` sites cost 3 B each and nothing measurable in
time (bench-report with the trampoline forced off, best of 5: equal within noise). Sizes before
-> after (both the gate and the precise predicate): `bignum` 22,270 -> 16,857 B (-24%),
`mandelbrot` 21,820 -> 16,484 (-24%), `sieve` 34,010 -> 28,701 (-16%), `matmul` 35,955 ->
30,621 (-15%), `list` 28,320 -> 24,781 (-12%), `zlib` 183,370 -> 182,136 (`_unw` sites 605 ->
94); `clos`/`sort`/`string` +78..+90 B (their live `_invoke_2` names the dead swapping lambdas,
whose tails now bounce); `fib`, `hash`, `hello_world`, `pi_approx` identical. The corpora:
`_unw` sites ci-spec 18,913 -> 1,400, scheme-spec 3,906 -> 369, clojure-spec 6,862 -> 1,190;
ci-spec 7,675,613 -> 7,626,213 B, scheme-spec 1,040,872 -> 1,029,926, clojure-spec 3,911,324 ->
3,946,792 (+0.9%: code 14 KB smaller, but the top level then packs into fewer `_top$N` chunks
whose frames carry every earlier form's locals, `.todo/137`). The class write (shake, gate
scan, placement) of ci-spec: 1,471-1,516 -> 1,463-1,520 ms.

**Time.** bench-report, the spec corpora and a cl-ppcre scan loop: unchanged within noise.
A bounce costs what the dispatcher call it replaces could inline away: 10M calls through two
forwarding closures 3 -> 171-209 ms, through a `compose`d closure 137-160 -> 211-224 ms (the
first is C2's figure for that shape: Graal's escape analysis removes the bounce of two
forwarding closures made in the calling function, 3-4 ms either way). A defun's bounce was
already that price -- the cost the limit below takes off the shallow path. Clojure (develop
after `.todo/c00`, whose call sites funcall a real function): `reduce` with a two-argument
`fn`, 2M calls of a local two-argument `fn`, `map` over 2M elements -- unchanged within noise;
before `.todo/c00` `map`'s per-element lambda handed `%clojure-call`'s apply bounce on,
170-183 -> 192-203 ms.

## Measurements: real calls while shallow

Same box (64 threads, shared with other builds), Oracle GraalVM 25.0.4: `java Prog` (the Graal
JIT) and `java -XX:-UseJVMCICompiler Prog` (C2); best of 7 alternating runs, each the best of
5 in-process rounds; ms for 10M calls unless noted, every value tail bouncing -> the limit.

| shape | Graal | C2 |
| --- | --- | --- |
| two forwarding closures made in the calling function | 3 -> 0 | 173 -> 151 |
| the same through globals (opaque, one closure type) | 53 -> 99 | 179 -> 138 |
| a `compose`d closure | 124 -> 3 | 209 -> 151 |
| an argument-swapping adapter | 4 -> 3 | 123 -> 41 |
| a defun `(funcall f x)` / an `(apply f args)` forwarder | 3 -> 3 / 14 -> 16 | 32 -> 34 / 32 -> 34 |
| `reduce :from-end` with a lambda, 1,000 elements x 10,000 | 60 -> 58 | 167 -> 131 |
| 1 / 3 / 5 forwarders through globals | 32 / 127 / 243 -> 58 / 140 / 261 | 134 / 252 / 387 -> 94 / 229 / 338 |
| 8 closure types per site: forwarder, two forwarders, composition, adapter, defun | 160 / 230 / 231 / 285 / 142 -> 138 / 169 / 175 / 185 / 101 | 231 / 322 / 312 / 313 / 160 -> 186 / 246 / 256 / 247 / 135 |
| a tree evaluator dispatching every node through a handler closure, depth 10 / 40 | 227 / 240 -> 210 / 219 | 243 / 229 -> 185 / 197 |
| 3M-hop chains: a closure through its variable, a pair, a continuation; 10 CPS sums of 100,000 | 36 / 26 / 25; 24 -> 37 / 26 / 28; 25 | 31 / 34 / 27; 27 -> 30 / 31 / 29; 26 |
| 3M hops of 8 instances of one state lambda | 32 or 75 (bimodal) -> 85 | 121 -> 67 |
| 8 different state lambdas: 3M hops / 300,000 chains of 10 | 44 / 29 -> 53 / 48 | 62 / 76 -> 65 / 69 |

Where it loses, on Graal only: Graal scalar-replaces a bounce whose array its inlined `_tramp`
loop consumes when one closure type dominates the trampoline's profile, while a real call
through the dispatcher cannot be inlined past one recursion level -- so a chain through
globals of one closure type, a few hops through megamorphic dispatch, and a deep state machine
whose `_tramp` profile also holds the 64 real calls each chain starts with run slower (the
state machine alone in its program measures 46 both ways; beside the CPS sums 31 -> 87). No
limit recovers them (16 / 4 / 2: one forwarder 55-62, chains of 10 50-62, against 29-36);
at 0, every value tail bouncing, they measure as every bounce did (the state machine 35, 31).

Rejected, measured on a prototype the same way:
- A `ThreadLocal<int[]>` count, so every thread makes real calls: worse than this count in
  every shape and worse than the bounce in the one-type ones -- C2 defun 33 -> 69, `apply`
  forwarder 35 -> 67, two forwarders a defun made 167 -> 182; Graal globals 53 -> 144; 8
  types per site, two forwarders, Graal 233 -> 214 and C2 340 -> 284 against 166 and 242
  for this count.
- Without the trampoline holding the count: Graal CPS sums 25 -> 52-66, a pair 25 -> 70, a
  continuation 24 -> 34-52.
- A smaller limit: nesting past it runs slower than every value tail bouncing (Graal tree depth
  40 at limit 16: 301 against 248; at 64: 231), chains of 3 and 5 too (limit 2: 218 / 321
  against 146 / 252 at 64); the 8-type gains are the same at every limit from 2.
- The real call in a method of its own (`_vtc<n>` the check and the bounce, `_vtcCall<n>` the
  counted call), to keep it out of the trampoline's compiled loop: the state machine 87 -> 83
  where it loses, chains of 10 48 -> 57, of 3 141 -> 173 -- worse, for a method more per arity.

Bytes: bench-report's ten programs byte-identical (`.class` and both wasm: none keeps a value
tail); size-report `hello_world`, `pi_approx`, `dom_reactor` identical, `zlib` 177,226 ->
177,625 B (three value tails: their sites shrink 156 B, the helpers cost 555 B); the corpora as
one program ci-spec 7,481,492 -> 7,479,662, scheme-spec 1,015,212 -> 1,014,291, clojure-spec
7,333,406 -> 7,327,324; the 116 JVM-compiled examples 46,998,990 -> 46,994,319: 42
identical, 8 smaller by up to 4,264 B (the cl-ppcre and ningle programs, many value tails),
66 larger by 79-562 B -- `_vtc<n>`, `_vtcClaim`, the two fields and `_tramp`'s hold, ~250-450 B
once per class, in a class with one to three value tails. WASM output byte-identical.

Time on whole programs, unchanged within noise: the corpora (best of 3) ci-spec 24.43 ->
24.29 s, scheme-spec 4.29 -> 4.09, clojure-spec 2.83 -> 2.79; a cl-ppcre scan loop (three
scanners, 50,000 rounds) Graal 1,078 -> 1,071 ms, C2 1,198 -> 1,255; Clojure `comp`,
`partial`, `map`/`filter`/`reduce` loops.

## Tests

`JvmLispCompilerTest#aShallowValueTailIsACallAndAChainPastTheLimitBouncesIntoTheTrampoline`
(every chain length around the limit, the count zero after, the worker the owner),
`#theValueTailCountComesBackWhenANonLocalExitLeavesAChain` (`throw`, `return-from`, an error,
from past and under the limit), `#aLambdasTailCallThroughAValueRunsInConstantStack`,
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
`#aChainThroughValuesRunsOnASmallStackOnEveryThreadAndOnlyTheFirstOwnsTheCount` (the owner's
1,000,000-deep chain on a 256 KB stack, four other threads at once); ci-spec
`closures-calling-through-a-value-in-tail-position-run-in-constant-stack`,
`tail-calls-through-a-value-answer-alike-at-every-depth` (adapters, compositions, `apply`
forwarders, `reduce :from-end`, chain lengths around the limit, exits, multiple values,
nesting past the limit) and scheme-spec
`tail-call-through-a-value-jvm`,
`lambdas-and-applies-calling-through-a-value-in-tail-position-run-in-constant-stack` (all four
backends).

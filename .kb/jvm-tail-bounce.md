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
loop consumes when one closure type dominates the trampoline's profile, while the real call
went through the shared dispatcher at every hop -- so a chain through globals of one closure
type, a few hops through megamorphic dispatch, and a deep state machine whose `_tramp` profile
also holds the 64 real calls each chain starts with run slower (the state machine alone in its
program measures 46 both ways; beside the CPS sums 31 -> 87). No limit recovers them (16 / 4 /
2: one forwarder 55-62, chains of 10 50-62, against 29-36); at 0, every value tail bouncing,
they measure as every bounce did (the state machine 35, 31). The cause was first read as the
dispatcher not inlined past one recursion level; the 2026-10-06 traces overturned that for
Graal (below): it inlines the dispatcher recursively, each level with every target the
dispatcher's one profile holds. The one-type chain has run faster than its bounce since.

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

## Measurements: the value tails' own dispatcher (2026-10-06)

Same box, each round started at a 1-minute load under 16 (3-5 measured); best of 5 alternating
runs (the 8-types program 11), each the best of 5 in-process rounds; ms for 10M calls unless
noted. Columns: every value tail bouncing (`value-tail-limit=0`) / real calls through the
shared `_invoke_<n>` (before) / through `_vtcd<n>` (now).

| shape | Graal | C2 |
| --- | --- | --- |
| one forwarder through a global, alone in its program | 30 / 58 / 4 | 128 / 82 / 71 |
| two forwarders through globals | 38 / 78 / 72 | 206 / 132 / 117 |
| chains of 3 / 5 through globals, each alone | 125 / 165 / 141; 241 / 268 / 244 | 266 / 208 / 196; 388 / 352 / 294 |
| chains of 1 / 3 / 5 in one program | 32 / 62 / 4; 120 / 137 / 148; 239 / 231 / 258 | 125 / 83 / 75; 247 / 203 / 185; 380 / 386 / 281 |
| two forwarders made in the caller / made by a defun | 141 / 0 / 0; 3 / 0 / 0 | 191 / 145 / 43; 183 / 86 / 46 |
| a `compose`d closure | 142 / 3 / 0 | 217 / 155 / 130 |
| an argument-swapping adapter; a defun `(funcall f x)`; an `apply` forwarder | 3 / 3 / 3; 3 / 3 / 3; 14 / 15 / 15 | 128 / 41 / 40; 33 / 33 / 34; 33 / 33 / 33 |
| `reduce :from-end` with a lambda, 1,000 elements x 10,000 | 96 / 57 / 57 | 183 / 135 / 131 |
| 8 closure types per site: `apply-to` defun, composition, adapter, forwarder, two forwarders | 166 / 96 / 93; 218 / 174 / 197; 288 / 176 / 146; 157 / 137 / 113; 231 / 160 / 153 | 169 / 131 / 132; 270 / 219 / 244; 313 / 242 / 228; 226 / 163 / 170; 304 / 226 / 192 |
| a tree evaluator, depth 10 / 40 | 239 / 212 / 206; 258 / 216 / 215 | 249 / 203 / 206; 231 / 203 / 202 |
| 8 state lambdas: 3M hops; 300,000 chains of 10 | 43 / 45 / 41; 33 / 47 / 53 | 61 / 62 / 62; 65 / 65 / 67 |
| 3M hops of 8 instances of one state lambda, beside 10 CPS sums of 100,000 | 35 / 83 / 86 | 118 / 119 / 117 |
| the same alone in its program (bimodal: medians 84 / 82 / 86 and 65 / 61 / 62) | 58 / 55 / 83 | 60 / 56 / 58 |
| 3M-hop chains: a closure through its variable, a pair, a continuation; 10 CPS sums | 49 / 48 / 52; 25 / 26 / 25; 25 / 24 / 25; 24 / 24 / 24 | 74 / 37 / 69 (bimodal); 30 / 28 / 28; 26 / 26 / 26; 23 / 25 / 26 |
| the cl-ppcre scan loop above | - / 1,016 / 1,002 | - / 1,202 / 1,196 |

The one-type forwarder now inlines whole on both JITs, its bounce 7x slower on Graal. What a
chain of several hops still pays is the count and the copy's one profile, which every hop of
the chain shares. The count: a prototype with none (unbounded, a measurement only) runs chains
of 3 / 5 in Graal 147 -> 85 / 241 -> 168, C2 199 -> 136 / 315 -> 246, two forwarders Graal
73 -> 64, C2 124 -> 85 -- 20-42% of a hop (`.todo/d52`, an argument since: "the count as an
argument" below); with the owner check or the exception handler left out instead, nothing
moves (Graal chain of 3: 143 -> 141 / 145).
The shared profile: the megamorphic composition and the 8-state chains of 10 lose 13% on
Graal, 11% / 3% on C2, while on Graal the adapter and the forwarder of the composition's
program win 17-18%.

The traces behind it (one forwarder through a global): `-Djdk.graal.TraceInlining=true`
shows `_invoke_1` inlined into itself seven levels deep, each level with both lambdas' cases
-- the branch profile is the method's, so the forwarder's call and the leaf's are not told
apart -- and stops at the eighth; through `_vtcd1` the run loop inlines `_invoke_1` with the
forwarder alone and `_vtcd1` with the leaf alone. C2's `-XX:+PrintInlining` refuses the second
recursion level of a 106-byte `_invoke_2` ("recursive inlining is too deep",
`MaxRecursiveInlineLevel=1`). So neither JIT stops for the dispatcher's shape or size: the
plan's other item -- a `tableswitch` over the funcIds, smaller segments, "a shape the JIT
inlines one more level" -- was not built.

The 8-instance state machine beside the CPS sums (31 -> 87 when the limit came) is no property
of the bounce: alone in its program every variant is bimodal (the row above), and under
`-XX:+UseParallelGC` the bounce, the shared and the own dispatcher all measure 46-47.

Rejected, measured on a prototype the same way:
- An allocation-free bounce on the owner thread (the designator and the arguments in
  owner-confined statics, a shared marker as the answer): under G1, the default, slower
  wherever a chain bounces -- 8 state lambdas, 3M hops, Graal 47 -> 125, C2 67 -> 149; the
  state machine beside the CPS sums Graal 80 -> 119, C2 126 -> 137. A static field lives in
  the class mirror, an old-generation object, so every store of a young argument into it
  takes G1's cross-region post-write barrier; under `-XX:+UseParallelGC` the same statics are
  5-10% faster than the array (Graal 45 -> 41, 47 -> 42; C2 67 -> 65, 73 -> 68). A young
  array is what G1 makes cheap, and Graal often removes it.
- The trampoline re-entering the copy too: the 8-instance state machine beside 300 arity-2
  lambdas 53 -> 84 on Graal, the rest equal.
- A copy for a routed arity too: with 400 arity-1 lambdas, one forwarder and a chain of 3 gain
  (Graal 74 -> 57 / 183 -> 140, C2 110 -> 81 / 226 -> 227), but the copy doubles the largest
  dispatchers -- the 116 JVM-compiled examples 46,705,620 -> 47,741,956 B (+2.2%; the ningle
  and clack stacks +3.9-4.9%), ci-spec's class 7,632,202 -> 8,074,526 (+5.8%) -- and the
  cl-ppcre loop, the closure-heavy real program measured, moves under neither.

Bytes: bench-report's ten programs identical (`.class` and both wasm); size-report `zlib`
178,156 -> 179,416 (three value tails), the rest identical; the corpora as one program
identical (every arity their value tails use is routed: ci-spec 7,644,257 B, scheme-spec
998,355, clojure-spec 7,309,978); the 116 JVM-compiled examples 46,705,620 -> 46,766,125
(+0.13%): 52 identical, 64 larger by 147-5,274 B (`net/httpbin-jzon` +0.92%), each copy at
most one segment; the cl-ppcre loop's class 692,679 -> 696,354. WASM output identical.

## Measurements: the count as an argument (2026-10-06, `.todo/d52`)

Same box and method (rounds started under a 1-minute load of 10, most at 3-8); best / median
of 5 alternating runs (7-9 where noted), each the best of 5 in-process rounds; ms for 10M
calls unless noted. Columns: the count in a static field (before) -> the depth argument.

**The premise, measured first** (prototypes, Graal / C2, a chain of 3 through globals): count
151 / 204, none at all (unbounded) 87 / 142, the depth checked in `_vtc<n>` 126-140 (one run
89) / 156, the depth checked in the copy's entry 87-97 / 151; a chain of 5 Graal 253 -> 176 /
190 / 191, C2 328 -> 245 / 223 / 246. Checked in `_vtc<n>`, the method is no longer six
instructions: `-Djdk.graal.TraceInlining=true` shows the cost-benefit phase weighing it at
every hop like the copy, and the chain's last hop stays a call ("the reason for not inlining
is unspecified"); as a forwarder Graal inlines it while it parses the caller.

| shape | Graal | C2 |
| --- | --- | --- |
| one forwarder through a global, alone | 4 / 5 -> 3 / 4 (7 runs) | 77 / 80 -> 46 / 53 |
| two forwarders through globals | 77 / 80 -> 62 / 68 | 129 / 132 -> 87 / 98 |
| chains of 3 / 5 through globals, each alone | 150 / 155 -> 84 / 92; 211 / 265 -> 190 / 221 (7 runs) | 200 / 208 -> 160 / 164; 325 / 385 -> 263 / 286 |
| chains of 1 / 3 / 5 in one program | 5 / 5 -> 4 / 4; 154 / 169 -> 121 / 128; 283 / 328 -> 212 / 221 | 81 / 92 -> 45 / 56; 219 / 237 -> 148 / 183; 324 / 346 -> 245 / 371 |
| a chain of 1 / 3 with 400 arity-1 lambdas (routed) | 68 / 77 -> 52 / 63; 155 / 193 -> 137 / 143 | 88 / 100 -> 72 / 75; 232 / 243 -> 169 / 187 |
| two forwarders made in the caller / made by a defun | 0 -> 0; 0 -> 0 | 42 / 44 -> 77 / 78; 49 / 50 -> 76 / 78 |
| a `compose`d closure | 0 -> 0 | 157 / 173 -> 133 / 139 |
| an adapter; a defun `(funcall f x)`; an `apply` forwarder | 4 -> 3; 4 -> 0; 16 / 17 -> 15 / 15 | 43 / 45 -> 37 / 39; 34 / 38 -> 30 / 32; 32 / 33 -> 29 / 33 |
| `reduce :from-end` with a lambda, 1,000 elements x 10,000 | 61 / 69 -> 62 / 69 | 139 / 161 -> 118 / 201 (bimodal) |
| 8 closure types per site: `apply-to`, composition, adapter, forwarder, two forwarders, leaf | 97 / 102 -> 85 / 91; 206 / 223 -> 192 / 201; 155 / 158 -> 147 / 154; 131 / 135 -> 81 / 86; 182 / 188 -> 157 / 162; 35 / 40 -> 37 / 58 (bimodal) | 135 / 149 -> 114 / 122; 257 / 267 -> 234 / 250; 231 / 249 -> 195 / 198; 183 / 211 -> 140 / 167; 211 / 236 -> 175 / 192; 102 / 109 -> 100 / 111 |
| a tree evaluator, depth 20 / 40 / 80 / 160 in one program | 232 / 236 -> 223 / 226; 235 / 240 -> 221 / 224; 333 / 337 -> 221 / 225; 318 / 331 -> 222 / 230 | 266 / 279 -> 181 / 235; 191 / 205 -> 163 / 189; 248 / 288 -> 196 / 206; 277 / 281 -> 190 / 208 |
| 8 state lambdas: 3M hops; 300,000 chains of 10 | 49 / 56 -> 46 / 77 (bimodal); 54 / 57 -> 59 / 60 | 63 / 70 -> 76 / 85; 74 / 77 -> 68 / 73 (9 runs) |
| 3M hops of 8 instances of one state lambda, beside 10 CPS sums; the sums | 80 / 86 -> 35 / 76 (bimodal); 25 / 27 -> 24 / 26 | 129 / 135 -> 120 / 126; 27 / 28 -> 27 / 28 |
| the same state machine alone (bimodal) | 45 / 53 -> 55 / 57 | 60 / 66 -> 61 / 61 |
| 3M-hop chains: a closure through its variable, a pair, a continuation | 49 / 50 -> 45 / 51; 25 / 26 -> 24 / 25; 25 / 26 -> 24 / 25 | 41 / 78 -> 38 / 118 (bimodal, 9 runs); 31 / 33 -> 35 / 37; 28 / 29 -> 27 / 28 |
| the cl-ppcre scan loop: best of 5 rounds; of 20; 200,000 scans a round | 804 / 906 -> 853 / 901; 776 / 801 -> 735 / 778; 3,112 / 3,584 -> 3,163 / 3,642 | 985 / 1,152 -> 979 / 1,042 |
| bench-report's ten programs (no value tail) | equal within noise | equal within noise |

What it loses, all on C2. Two forwarders made in the function that calls them: C2's escape
analysis sees every closure, and its `AggressiveUnboxing` misfires once no store is left in
the chain -- under `-XX:-AggressiveUnboxing` the count runs 38 / 42 and the depth 31 / 33,
under `-XX:-EliminateAutoBox` both 42-43, the unbounded prototype without a count 78 / 87
like the depth, and the depth with one `putstatic` of it per hop 33 / 34. A store a hop is
the cost this change removes, so none is added. The 3M-hop state machine of 8 lambdas
(+20%) and the pair (+12%) run on the trampoline past the limit; the closure through its own
variable is bimodal on both (28-45 or 105-122 ms a run), the depth landing on the slow mode
more often.

The recursion depth the bound per ordinary call costs, 16 MiB worker, the largest n a
non-tail recursion through a defun survives (cold start, binary search to 1%): with no value
tail -- each level calls a closure through the dispatcher -- 113,275-114,252 -> 102,536; with
one value tail a level 68,365 -> 46,398; with a chain of three a level 68,365 -> 26,627. The
static count bounded the whole stack, so its chains bounced from the 65th value tail on and a
level kept the trampoline's frames; the depth keeps a level's chain as real calls.

Rejected, measured the same way:
- Every lambda taking the depth, read or not: the no-value-tail recursion 93,261 (C1 frames
  only, `-XX:TieredStopAtLevel=1`: 114,252 -> 93,749; interpreted 49,327 -> 47,130); Graal's
  tree evaluator at depth 40 266 / 274, against 214 / 235 when only a lambda that reads it
  takes it (the count: 223 / 241) -- the `:add` handler's first `ev` call left out of the
  inlining; and every closure's case in the dispatchers a byte larger.
- The dispatcher's id and funcval after the depth's slot: an arity-1 `_invoke_1` loads the id
  with a two-byte `iload` at every node of its search tree, the 8-type program's 318 B -> 347
  B, past C2's `FreqInlineSize` (325): its leaf calls 97 / 114 -> 133 / 137. With the id in
  the first free slot and the funcval cast over its parameter, 322 B; the copy, delegating
  everything but a function value to the shared dispatcher, 310 B (the count's: 319 / 319).
- The bounce array built in the copy instead of `_vtcb<n>`: no different (Graal tree depth 40
  274 / 279 against 269 / 278, chain of 3 95 / 98 against 93 / 98).
- A catch-all handler around `_vtc<n>`'s call, for C2's two forwarders: 69 / 80, no
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

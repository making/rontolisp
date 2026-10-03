# JVM backend: a tail call through a value bounces through the class's trampoline

**Invariant: on the JVM, a call in tail position whose target the compiler cannot name -- a
`funcall`/general call of a computed designator, a local function's call, an `apply` of a
computed designator -- in a `defun`'s or a lambda's body (a closure, an `flet`/`labels`
function, a continuation, a split-off `_k$N`) is a BOUNCE, every caller of a compiled
function's result checks it for one, and a tail call the compiler can name hands its callee's
bounce on.** A chain of tail calls through values -- a closure calling itself through its
variable, two closures in variables, a continuation handed to a defun that calls it, a state
machine of closures in a table, a function applying itself -- runs in constant stack, as on
the interpreter ([interpreter-tail-calls.md](interpreter-tail-calls.md)) and wasm
([wasm-tail-calls.md](wasm-tail-calls.md)). Defuns since 2026-10-03 (`.todo/b69`), lambdas,
`apply` and the pass-through since the same day (`.todo/c08`). Self and mutual tail calls
the compiler can name are jumps instead ([jvm-self-tail-calls.md](jvm-self-tail-calls.md)).

## Mechanics (`JvmTailBounce`)

- **The bounce** is the method's result: `Object[]{Boolean.TRUE, designator, arg...}`
  (`emitBounce`), or for an `apply` `Object[]{Boolean.FALSE, designator, argList}`
  (`emitSpreadBounce`). No Lisp value is an `Object[]` with a `java.lang.Boolean` in slot 0.
- **The check** is `invokestatic _unw` after every call whose callee may answer one: every
  dispatcher call site, a direct call of a `bounceVisible` defun, `_apply`'s exit
  (`buildApplyEntry`), the async/thread/http entries, a `jvm-export` wrapper of a bouncing
  defun, the linalg/geom/simd scalar fallbacks and `%check-sequence-runtime`'s call. On a
  bounce `_unw` hands it to `_tramp`, which re-enters `_invoke_<n>` -- or the raw apply
  `_applyRaw` for a spread bounce, so a chain of applies keeps no frame -- in a loop until a
  real value comes back: the deferred call runs in the checking frame.
- **The tail mark** is the self jump's (`Ctx.tailMark`, [jvm-self-tail-calls.md](jvm-self-tail-calls.md)):
  only a call whose value is the method's result with no dynamic extent in between bounces.
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
  reach -- is shaken away. A dead closure a live dispatcher still names keeps it
  (`clos`/`sort`/`string` below: `reduce :from-end`'s argument-swapping lambdas).

## What keeps a frame

- A call inside a dynamic extent: a special binding (a parameter named like a special
  included, [dynamic-special-variables.md](dynamic-special-variables.md)), `unwind-protect`,
  `handler-case`, `catch`, ... -- the callee runs inside it.
- A call the Lisp-2 rewrite makes through a fresh cons (a nested `defun`'s global variable),
  the body of an inline `((lambda ...) args)`, and a self call in a split-off `_k$N`.

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
forwarding closures 3 -> 171-209 ms, through a `compose`d closure 137-160 -> 211-224 ms;
Clojure's lazy `map` over 2M elements 170-183 -> 192-203 ms (its per-element lambda passes
`%clojure-call`'s apply bounce on). A defun's bounce was already that price.

## Tests

`JvmLispCompilerTest#aLambdasTailCallThroughAValueRunsInConstantStack`,
`#everyFormTheTailMarkPassesHandsABounceOnAndAPlainTailNeedsNoCheck` (every relay, the
pass-through through RB-HOP, a plain tail's callers carry no check),
`#aTailApplyThroughAValueBouncesWithItsArgumentListUnspread` (with the Clojure call),
`#aTailCallOfALiteralDesignatorIsADirectCallNotABounce`,
`#aTailThroughAValueInASplitOffContinuationHandsItsBounceOn`,
`#theTrampolineIsWrittenOnlyWhenAMethodThatBouncesSurvivesTheShake`,
`#aClosuresValueTailInsideASpecialParametersBindingStaysACall`,
`#theValueTailTrampolineIsEmittedOnlyWhereATailLeavesThroughAValue`,
`#theUnwrapCheckAtACallSiteIsOneCallToASharedHelper`;
`JvmExportTest#anExportedDefunWhoseTailCallsThroughAValueAnswersThatCallsValue`; ci-spec
`closures-calling-through-a-value-in-tail-position-run-in-constant-stack` and scheme-spec
`tail-call-through-a-value-jvm`,
`lambdas-and-applies-calling-through-a-value-in-tail-position-run-in-constant-stack` (all four
backends).

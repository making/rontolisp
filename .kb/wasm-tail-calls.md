# WASM backend: every call in tail position is a `return_call`

Scope: the GC WASM backend (`codegen.wasm`), Preview 1 and `--component`; both run the same
core module. The JVM has no counterpart; the interpreter has its own mechanism, the loop in
`eval` (`.kb/interpreter-tail-calls.md`); their depths are recorded below and are not this
file's invariant. `--no-gc` is untouched (its own
compiler never arms the flag).

**Invariant: a call in tail position of a compiled function is emitted as `return_call`
(0x12, the tail-call proposal), so it runs in constant stack -- through a function value,
a direct call, a `labels` lambda, `apply`, the value of a `return-from` -- and the
dispatcher and `_apply` tail-call their target too, so a call through a value costs the
caller's frame alone.** Nothing a program prints changes; what changes is the depth
ceiling (unbounded where it was ~3,000) and the bytes (smaller: the dispatcher's `br` per
case is gone). A program without a tail call still moves by the dispatcher's bytes. Wasmtime
47 and V8 (node 24) run the modules; both had `tail-call` on by default. Landed 2026-09-19
(`.todo/899`).

## The tail position (`Ctx.tailPosition`, one flag, consumed at `compileExpr` entry)

- `compileExpr` reads `ctx.tailPosition` into a local and CLEARS it before dispatching, and
  `compileCons(cons, ctx, tail)` passes the consumed value EXPLICITLY to the arms that need
  it. So a form the flag never reached compiles its sub-forms as non-tail without knowing
  the flag exists -- a `handler-case` body, an `unwind-protect` protected form, a
  `multiple-value-prog1` first form, a `progv` body, a `catch` body keep their frame by
  construction. The one rule for a new form compiler: nothing to do, unless the form is
  tail-transparent, in which case it re-arms the flag right before compiling the one
  sub-form whose value is its own.
- **Armed** by the defun and lambda body loops in `WasmLispCompiler` (the last body form;
  `compileForEffect` statements never are). **Re-armed** by `WasmIfCompiler` (both arms,
  never the test), `WasmPrognCompiler` (last form), `WasmLetCompiler` (last form, only when
  no binding is dynamic: a special's restore runs after the body, in a protected region),
  the `let*`/`the`/`locally` re-dispatches, `WasmBlockCompiler` (last form; the
  `%block`/named/`%fn-block` shapes are plain wasm blocks), and -- through
  `BlockMarker.tail` -- `WasmReturnCompiler`/`WasmReturnFromCompiler` for the exit value
  when the target block is in tail position and the exit crosses no `UnwindScope` (an
  inlined cleanup would run after the value). `WasmDotimesCompiler`'s own marker says
  false.
- **Emitted** by `WasmFunctionCallCompiler` (`compileDefault` -> `compileDirectCall`, the
  `funcall` dispatch, `WasmDesignatorCall.emitCall`), `WasmApplyCompiler` (the two physical
  direct-call arms and `_apply`). A host import call (`compileLiteralImportCall`) never is:
  its result type is the host's, and `return_call` requires the callee's results to EQUAL
  the caller's -- every compiled Lisp function answers one `(ref null eq)`, which is what
  makes the rest legal. The top level (`_start`, void) never arms it.
- The general indirect call `((lambda ..) ..)` and `multiple-value-call` are ordinary
  calls (conservative, not a bug). An `error` in tail position is a `return_call` like any
  other direct call.

## The runtime side

- `WasmRuntimeBuilder.emitDispatchCases`: a case is `return_call target`, not
  `call target; br $result` (2 bytes less per case; the `$result` block stays, unbranched,
  and validates because every path into its `end` is polymorphic). `emitPageTable`: a node
  page `return_call`s the child page. `WasmEvalRuntimeBuilder.emitSpreadDispatch`
  (`_apply`'s closure arm and its fallback) is a `return_call` of `_dispatch_spread`: with a
  plain call there, `(apply f f (list ..))` in tail position left one `_apply` frame per
  round and exhausted the stack at 300,000 (the integration test caught it).

## The post-passes (`am.ik.wasm`) -- all of them decode bodies, so all of them learned 0x12

- `WasmSections.scanBody`: 0x12 records a FUNC ref like 0x10 (the shaker's liveness, the
  index remap, `WasmCallForwarding.redirectCalls`); 0x13 like 0x11.
- `WasmCodeModel.decodeInstr`: one LEB immediate, like `call`.
- `WasmRefTypeFolder`: the `0x12` arm pops the arguments and joins the callee's parameter
  sets like a call, then delivers the callee's RETURN sets to the function frame like a
  `return` and marks the rest unreachable. Without the arm the folder threw on the opcode.
- `WasmCallForwarding.forwardTarget`: a forwarder's body is now `local.get..; return_call g;
  end`, accepted beside the `call` shape -- or every forwarder redirect would have been lost.
- `WasmLocalSink`: `return_call` counts as a global write like `call`.
- `WasmInliner`: three rules. (1) A `return_call callee` site is a call site: the moved
  body is followed by a `return`, because the emitter's tail sites are followed only by
  value-passing `end`s and `br`s but a dispatcher case falls through into the NEXT case's
  body. (2) A `return_call X` inside a moved body becomes `call X; br <wrapper>`: the
  callee's frame is gone either way, so the stack is exactly as deep as the tail call left
  it. (3) A TRAILING one (the body's last instruction at depth 0 -- every forwarder) needs
  no block and no branch: it becomes `call X` falling off the end. Without (3) the wrapper
  block cost what the move saved and `bench_fib` kept a one-line wrapper (+8 B); without
  (1) `bench_clos`'s once-called lambdas stayed out of the dispatcher.
  `WasmInlinerTest#aTailCallSiteTakesTheMovedBodyFollowedByAReturn`,
  `#aTailCallInsideAMovedBodyBecomesACallAndABranchOutOfTheWrappingBlock`,
  `#aTrailingTailCallInAMovedBodyNeedsNoBlock`.

## Measurements (2026-09-19, x86-64 Linux, Xeon E5-2697A v4, wasmtime 47.0.3, node 24.19, Java 25)

**Depth, default stacks, largest passing.** A tail call through a procedure value,
`(define (g self n) (if (= n 0) 'done (self self (- n 1))))`, before -> after: wasm 2,975
-> 5,000,000 passes (the probe's ceiling), component the same; Common Lisp `(funcall self
self ..)`, mutual `defun`s, a `labels` self loop and `apply` of a literal: 100,000
overflowed, 3,000,000 answer. Unchanged: JVM `java Prog` 1,844 (`-Xss16m` 17,677; at
`-Xss256m` 8,000,000 passes, because after ~10k invocations the JIT's frames are a
fraction of the interpreter's -- `.kb/interpreter-stack.md`), interpreter 15,497 (the
16 MiB worker; 5,000,000 -- the probe's ceiling -- since the interpreter's own loop landed
later the same day, `.kb/interpreter-tail-calls.md`). One Scheme call through a value is
two JVM frames, `g` and `_invoke_2`;
`%scheme-ensure-procedure` returns before the call. V8: a 1,000-deep `labels` loop (2,000
frames) threw `Maximum call stack size exceeded` under node's WASI before and runs now.

**Bytes** (baseline jar vs this change, `wasm-tools validate` ok on every module): the
size-report rows `hello`/`pi` `--optimize`/`size`/`component` byte-identical (no
dispatcher), the `--optimize=off` rows -146 B; `zlib` -288/-138/-137/-137 (plain, optimize,
size, component); `dom_reactor` +1/+1 (optimize, size: a moved forwarder's `return`);
bench-report `list` -99, `sort` -40, `string` -2, `clos` +24 (two once-called
error-signalling lambdas whose `return_call` is not trailing, so the inliner's block costs
more than the move saves -- declined by its own arithmetic), the rest 0;
`examples/scheme` -67 to -423 B (evaluator -423, streams -359, differentiation -323),
component builds the same; gzip moves the same direction. Outputs identical on wasm and
component for all six Scheme examples.

**Time** (best of 7 alternating runs, output checked): bench-report programs within noise
(fib 160 -> 160 ms, matmul 900 -> 885, sieve, hash, bignum, mandelbrot, string, evalfib the
same); a closure chain is FASTER, because the dispatcher's frame is gone while the target
runs: 10M iterations of a 1,000-deep `labels` loop 200 -> 167 ms (one-parameter: 205 ->
141), a defun through `(funcall f f ..)` 173 -> 135, a closure through a global 230 -> 170,
mutual `defun`s 102 -> 91, `(apply f f ..)` (2M) 142 -> 108; the Scheme `(self self ..)`
loop 200 -> 143 on wasmtime and 158 -> 74 on V8. The same module with every `return_call X`
rewritten to `call X; return` runs at the baseline's speed, so the gain is the tail call
itself, not the code around it.

**The measurement trap this item fell into**: a first set of A/B timings read the change
as +43% on the `labels` loop. The BASELINE runs were crashing -- their driver was a
10,000-deep Common Lisp self-recursion `(rounds (- k 1) ..)`, a plain `call` before this
change, and a stack overflow is a fast run. The timing loop now prints each arm's last
output line beside its time; `.kb/measurement-probes.md` rule 5, trap G.

## Engines

wasmtime enables the tail-call proposal by default (since 21; 47 verified), V8 since
Chrome 112 (node 24 verified through `node:wasi`), Firefox 121, Safari 18.2 -- every engine
that runs the wasm-GC modules the backend already emits. Cranelift's `call_ref` is 30x a
direct call on this box (7.6 s vs 0.25 s per 100M), so the dispatcher must keep its
`br_table` of direct calls and never become a `call_ref`.

## Tests

`WasmLispCompilerIntegrationTest#aTailCallRunsInConstantStackAndATailPositionThatKeepsItsFrameStillDoes`
(300,000 deep through a value, direct mutual, `labels`, `apply` literal and value,
`return-from`, `let`/`progn`/`the`; and the frame-keeping tail positions: a special
`let`, `unwind-protect`, `handler-case`, `multiple-value-prog1`; the component twin), the
three `WasmInlinerTest` cases above. The Scheme side lowers to the same `funcall`
(`.kb/scheme-frontend.md`, "Tail-call groups").

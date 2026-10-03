# JVM backend: a self tail call is a jump back to the method's first instruction

**Invariant: a call in tail position to the function whose method is being emitted -- a
`defun` calling itself by name or through a literal `#'name`, a `labels` function calling
itself through its own variable -- evaluates its arguments into the physical sequence a call
would pass, stores them into the parameter slots and `goto`s the method's first
instruction.** A loop written as tail recursion (Clojure's `loop`/`recur` and `defn`
`recur`, the Clojure lowering's per-element `labels` verbs, a Common Lisp accumulator) runs
in constant stack on the JVM as it does on the interpreter
([interpreter-tail-calls.md](interpreter-tail-calls.md)) and on both wasm backends
([wasm-tail-calls.md](wasm-tail-calls.md)). Landed 2026-10-03 (`.todo/b95`). The JVM's other
tail mechanism, the trampoline for a tail call through a value, is `JvmTailBounce`
([scheme-frontend.md](scheme-frontend.md), "Not members").

## Mechanics (`JvmSelfTailCall`)

- **The loop head is bound at position 0**, before the prologue that boxes a captured
  parameter into its `Object[1]` cell (`Ctx.selfLoop`, set in Pass 2a for every defun and in
  Pass 2c for a `labels` lambda). Re-running the boxing gives each round a fresh cell, so a
  closure made in one round keeps that round's binding. An unused label emits nothing
  (`CodeReplay` labels only branch targets): a function with no self tail call is
  byte-identical.
- **The arguments are `JvmPhysicalArgs.emit`'s sequence** -- required, physical optionals or
  the UNSUPPLIED marker, the rest list -- all evaluated before the first store, then stored
  last to first into slots `0..n-1` (a defun) or `1..n` (a lambda, slot 0 its environment,
  which stays: the same closure). The body's own prologue re-derives defaults, `&key` and the
  `&optional` surplus check from them, so every lambda-list shape loops. A count the lambda
  list rules out keeps the call, which signals.
- **The tail position is the trampoline's mark** (`Ctx.tailMark`): laid by
  `JvmBodyOutliner` on the final spine item -- now whenever the method has a `selfLoop`, not
  only in a trampolined class -- and re-laid by the forms that hand a sub-form's value on
  unchanged and open no dynamic extent: `if`, `progn`, a `let` without a special binding, the
  three blocks (`JvmBlockCompiler`, last body form), the value of a `return`/`return-from`
  that is itself marked (every form between it and the method's result, its target block
  among them, is on the marked chain), and the pass-through lowerings through
  `JvmExprCompiler.compileExpansion` (`let*`, `locally`, `flet`, `labels`, `the`, `cond`,
  `case`, `ecase`, `and`, `or`, `when`, `unless`, `typecase`, `etypecase`). The extended
  relays reach the bounce too, so a trampolined class bounces in those positions as well.
- **A `labels` function is recognized by its variable**: the expansion's one assignment
  `(setq __LABELS<n>_<name> (lambda ...))` (`LispMacroExpander.isLabelsFunctionVariable`;
  the rewrite turns every other mention into a read) records the lambda form in
  `Ctx.lambdaSelfVars`, `JvmLambdaCompiler` copies it into `LambdaInfo.selfVar`, and inside
  that lambda `(funcall var ..)` is a self call while `var` is the closure's own capture.
- The jump is emitted only from an empty operand stack with no unwind or spill scope open
  (`atTail`): the frame the verifier and HotSpot's OSR entry need
  ([jvm-osr-backedges.md](jvm-osr-backedges.md)); on the marked chain it always is.

## What keeps a real call

- A dynamic extent between the call and the result: a special `let` (its restore), an
  `unwind-protect`, `handler-case`, `handler-bind`, `restart-case`, `catch`, `progv`,
  `multiple-value-prog1`, `with-output-to-string`. Pinned by the `SPEC`/`UP`/`HC`/`CT`
  methods of the test below: each keeps its one self call.
- A tail in a `_k$N` continuation (`JvmBodyOutliner` split a body past the method-size
  budget): another method, so a call, one frame a round.
- Mutual tail calls (two `labels` functions, `letfn` siblings, two defuns): not a self call.
  A Scheme program groups them (`.kb/scheme-frontend.md`, "Tail-call groups"); Common Lisp and
  Clojure keep a frame per hop on the JVM -- two through a lambda's dispatcher, so a `labels`
  even/odd pair overflows at 1,000,000 and Clojure's `letfn` twin at 150,000, while two
  defuns pass 1,000,000 only because C2 inlines one into the other (`.todo/c02`).
- A tail `apply` of itself, and a nested `defun` (a global variable that may be reassigned).

## A Clojure deviation it creates

Clojure keeps a frame per named self call (only `recur` jumps), so the book corpus's
`(is (thrown? StackOverflowError (tail-fibo 1000000N)))` (shcloj4 `examples.test.functional`,
a `letfn` entry calling itself by name) passes on the oracle. Every backend here runs that
call in constant stack, so it computes the millionth Fibonacci number instead and the
assertion fails: measured 2026-10-03, compiled JVM `Ran 7 tests containing 19 assertions. 1
failures, 0 errors.` in 19 s (0 failures in 1.5 s before, when the JVM overflowed like the
oracle). `ClojureProjectNamespacesTest` keeps the rest of the corpus copy and leaves that
line out; `doc/*/clojure/deviations.md` states it.

The multi-arity `defn` lowering changed with this item: a fixed clause's `recur` calls its
own helper `c%f%<n>` instead of the dispatch defun, which made every round a mutual
recursion of two methods (`ClojureBindingLowering.multiDefun`, all four backends; pinned by
`ClojureLoweringTest.recurTargetsAnyEnclosingFnAndChecksItsArity`).

## Measurements (2026-10-03, x86-64 Linux, Xeon E5-2697A v4, Oracle GraalVM 25.0.4, wasmtime 47)

**Depth**, 1,000,000 rounds, compiled `java Prog` on the default 16 MiB worker, before ->
after: `defun`, `labels`, `&optional`/`&key`/`&rest` defuns, a closure captured per round, a
`values` tail, the self call behind `block`/`return-from`/`let*`/`the`/`locally` and inside
a `labels`/`flet` body: overflow -> answers (a CL defun passed 200,000 before);
`(funcall #'f ..)` answered before too, as a trampoline bounce, and is a jump now. Clojure:
`loop`/`recur` passed 100,000 and overflowed at 150,000, a `defn` `recur` passed 150,000
and overflowed at 200,000, a multi-arity clause and `(count (distinct (range 200000)))`
overflowed -- all answer now. The interpreter and wasm answer every one of these but three
gaps of their own: the interpreter overflows on a `return-from` whose value is the self call,
wasm and the component on a self call inside a `labels`/`flet` body and on a multi-arity
Clojure `fn` clause's `recur` (`.todo/c01`).

**Time**, best of 5 alternating runs, the same programs at depths the old emission
survived: 10,000 calls of a 10,000-round CL defun loop 447-547 -> 93-100 ms, the `labels`
twin 1,132-1,617 -> 85-98 ms; 1,000 Clojure `loop`s of 10,000 rounds 263-301 -> 181-202 ms;
300 `(count (distinct (range 10000)))` 1,013-1,111 -> 1,065-1,137 ms (hashing dominates;
noise). bench-report: nine programs compile byte-identically, `string` grows 5 B and runs
845 -> 846 ms (best of 7).

**Bytes**: size-report `hello_world`/`pi_approx` identical, `zlib` 183,365 -> 183,370 B;
the whole `clojure-spec.yaml` program 3,655,421 -> 3,653,061 B, 172 methods jumping back to
their start.

## Tests

`JvmLispCompilerTest#aSelfTailCallJumpsBackToTheMethodsFirstInstruction` (no self call left,
one entry jump, a `labels` method looping, 1,000,000 deep),
`#everyTailTransparentFormHandsTheJumpOnAndADynamicExtentKeepsItsCall` (the shapes above,
semantics and bytecode), `#aSelfTailCallInASplitOffContinuationStaysACall`; clojure-spec
`deep-recur-answers-on-every-backend` (all four backends: `loop` 1,000,000, a `defn` and a
multi-arity clause 300,000, `distinct`/`range` over 200,000).

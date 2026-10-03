# JVM backend: a self tail call is a jump back to the method's first instruction, a mutual one a jump inside its tail group

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
([jvm-tail-bounce.md](jvm-tail-bounce.md)).

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

## Mutual tail calls: tail groups (`JvmTailGroup`)

**Invariant: functions whose tail calls to each other form a cycle -- top-level defuns, or the
functions of one `labels` form (a Clojure `letfn`) -- are a tail group, and a call on the
tail mark from one member to another is a jump.** A mutual recursion runs in constant stack
as a self tail call does. Landed 2026-10-03 (`.todo/c02`).

- **Finding**: `JvmTailGroup.ofDefuns` before Pass 2a, `.ofLabels` on each expansion in the
  `labels` arm (fresh per compile of the form, so its lambda conses name this instance's
  members, `Ctx.tailGroupMembers` -> `LambdaInfo.tailMember`). A walk of each body's tail
  positions that mirrors the mark (`if`, `progn`, a `let`/`let*` binding no special, the
  blocks and a `return`/`return-from` reached through them, the pass-through lowerings; an
  `flet`/`labels` body's names shadow defuns), then Tarjan: each strongly connected component
  of two members or more. A disagreement with the emitter only loses a group or keeps a member
  whose call is no jump -- only the mark makes a jump.
- **Emitting**: each member compiles alone, exactly as without the group. A sibling call on
  the mark (`JvmSelfTailCall.tryDirect`/`tryFuncall` -> `JvmTailGroup.emitJump`) evaluates the
  sibling's physical arguments, stores a `labels` sibling's closure (its variable's value; only
  the expansion assigns it) into slot 0, the arguments into the parameter slots, and branches
  to a label the member never binds (`Member.jumps`).
- **Laying out** (`finish`, after Pass 2d, every program body compiled): a member a call names
  -- an `invokestatic` of its method in a program body (`MethodCode.forEachEntry`), or a
  `jvm-export` -- gets the group ROOTED at it as its method (`Ctx.laidOut`): its own code at
  the entry, then the members its jumps reach, appended from the compiled bodies
  (`MethodCode.append(block, landing)`), each jump landing on its target's first instruction.
  Every other member keeps its own code, each jump landing on an epilogue that reloads the
  arguments and calls the target's method: one frame on the way into a rooted method, whose
  loop then runs in constant stack. While the non-rooted members' jumps still form a cycle,
  the first member of each is rooted too -- a `labels` function is only ever called through
  the dispatcher, so its group roots its first function. A rooted method past
  `HugeMethodLimit` (8000) lays the whole group out apart: every jump an epilogue, a frame a
  round as before. The shake drops a rooted method nothing calls.
- **Why rooted methods** (2026-10-03, a generic function whose method tail-calls the generic,
  30,000 x 1,000 calls, steady state, Graal): calls 67-89 ms; ONE shared method with a switch
  at its entry and direct jumps 71-74 ms, but a cycle with two entry heads is irreducible and
  Graal refuses its OSR (`Multiple OnStackReplacementNodes generated`), so one long call stays
  interpreted ([jvm-osr-backedges.md](jvm-osr-backedges.md)); the same method with every jump
  re-entering the switch (reducible) 171-190 ms (C2 162-250); rooted 67-73 ms. Rooting EVERY
  member put the whole group into each method a dispatcher names (+0.47-1.40% on 15 example
  classes, against +0.21-0.39%). When the jumps form a cycle the root does not close
  (`JvmTailGroup.reducible`), that root's method dispatches instead: `iconst_0; istore m`, a
  switch on `m`, each jump storing its target's index and re-entering the switch.
- **Bounces**: a member's method may run any member's code, so every member's
  `FunctionInfo.bounceVisible` is the group's OR, set before Pass 2a
  (`JvmTailBounce.bouncingDefuns`); a `labels` member is a lambda, which may always bounce.
  An epilogue's call of a sibling hands the sibling's bounce on: the member's own callers
  check ([jvm-tail-bounce.md](jvm-tail-bounce.md)).
- **Frames**: a rooted method's frame holds its largest member's locals, which a deep non-tail
  recursion through it pays per level.
- `-Drontolisp.jvm.debug-tail-groups=true` prints `[tail-calls] labels A->B ...` for every
  `labels` form with a sibling tail call and `[tail-group] ...` per group, with each rooted
  member's size.

**The premise, measured with that hook** (2026-10-03): a `labels` tail call to a sibling in 8
of 239 example files (cl-base64's `output-group`, llm's `layer-tensor`, jzon's
`read-element`), none of them a cycle; one even/odd pair each in ci-spec and clojure-spec;
none in scheme-spec (the Scheme lowering groups its own, `.kb/scheme-frontend.md`). Defun
cycles in 30 example programs, all in spliced code: the stream `read` delimiter
(`%rd-datum` -> `%rd-dispatch` -> `%rd-sharp`), generic functions whose methods tail-call the
generic (cl-ppcre, cl-unicode, cffi, dissect, rove, trivial-gray-streams), asdf's test-op
pair, Clojure's regex matcher, a multi-arity `defn`'s clause helper.

## What keeps a real call

- A dynamic extent between the call and the result: a special `let` (its restore), an
  `unwind-protect`, `handler-case`, `handler-bind`, `restart-case`, `catch`, `progv`,
  `multiple-value-prog1`, `with-output-to-string`. Pinned by the `SPEC`/`UP`/`HC`/`CT`
  methods of the test below: each keeps its one self call.
- A parameter named like a special: `LambdaLists.toNative` binds it by a special `let` around
  the whole body, so no call in that body is on the mark -- a self call, a `labels` self call
  and a sibling call all stay calls, and the group walk agrees (`ofDefuns` stops at the
  lowered body's `let`, `ofLabels` drops a member whose physical parameter is special), so such
  a function joins no group and a call into it is an ordinary call. Pinned by
  `#aTailCallInsideASpecialParametersBindingStaysACall`;
  [dynamic-special-variables.md](dynamic-special-variables.md), "Parameters named like a
  special", has the depths.
- A self tail call in a `_k$N` continuation (`JvmBodyOutliner` split a body past the
  method-size budget): another method, so a call, one frame a round (a tail through a value
  there bounces and passes on, [jvm-tail-bounce.md](jvm-tail-bounce.md)).
- A tail group laid out apart (a rooted method past 8000 bytecodes): each member's tail call
  to another is a direct call, a frame a round. A jump from a member that is not rooted is a
  call into a rooted method: one frame per entry, not per round.
- A tail `apply` of itself (it bounces, [jvm-tail-bounce.md](jvm-tail-bounce.md)), and a nested
  `defun` (a global variable that may be reassigned).

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
overflowed -- all answer now. The interpreter and wasm answer every one of these since
2026-10-03 too: the interpreter had overflowed on a `return-from` whose value is the self call
(`.kb/interpreter-tail-calls.md`), wasm and the component on a self call inside a
`labels`/`flet` body and on a multi-arity Clojure `fn` clause's `recur`
(`.kb/wasm-tail-calls.md`, "Built-in macro expansions").

**Time**, best of 5 alternating runs, the same programs at depths the old emission
survived: 10,000 calls of a 10,000-round CL defun loop 447-547 -> 93-100 ms, the `labels`
twin 1,132-1,617 -> 85-98 ms; 1,000 Clojure `loop`s of 10,000 rounds 263-301 -> 181-202 ms;
300 `(count (distinct (range 10000)))` 1,013-1,111 -> 1,065-1,137 ms (hashing dominates;
noise). bench-report: nine programs compile byte-identically, `string` grows 5 B and runs
845 -> 846 ms (best of 7).

**Bytes**: size-report `hello_world`/`pi_approx` identical, `zlib` 183,365 -> 183,370 B;
the whole `clojure-spec.yaml` program 3,655,421 -> 3,653,061 B, 172 methods jumping back to
their start.

**Mutual tail calls** (tail groups, same box, a loaded 64-core host). Depth, 1,000,000
rounds, `java Prog`: a CL `labels` even/odd pair and Clojure's `letfn` twin overflowed (the
twin passed 100,000) -> answer; two defuns answered in 1 of 3 runs (C2 inlining one into the
other) and overflowed under `-Xint` -> answer every time, `-Xint` included. Time, best of
iterations 3-5 in one process, range over 7 alternating runs: 1,000 x a 10,000-round defun
pair 6-9 -> 3-4 ms, the `labels` pair 65-78 -> 37-44; 10M shallow `(ev? (mod k 4))` defuns
19-22 -> 21-24, `labels` 123-143 -> 28-32 (no dispatcher); 10,000 x a generic function over
1,000 elements 27-28 -> 24-26; 100 x `read` of 2,000 data (the `%rd-*` group) 783-862 ->
779-898. Bytes: of the examples suite's 116 JVM legs 15 change, every one by a group in
spliced code, +2,988 to +11,135 B (+0.21% to +0.39%); bench-report and size-report
byte-identical; the ci-spec program 7,618,685 -> 7,622,142 B, clojure-spec 3,692,871 ->
3,700,216 B (Clojure's regex matcher: four functions, three rooted).

## Tests

`JvmLispCompilerTest#aSelfTailCallJumpsBackToTheMethodsFirstInstruction` (no self call left,
one entry jump, a `labels` method looping, 1,000,000 deep),
`#everyTailTransparentFormHandsTheJumpOnAndADynamicExtentKeepsItsCall` (the shapes above,
semantics and bytecode), `#aSelfTailCallInASplitOffContinuationStaysACall`; clojure-spec
`deep-recur-answers-on-every-backend` (all four backends: `loop` 1,000,000, a `defn` and a
multi-arity clause 300,000, `distinct`/`range` over 200,000).

Tail groups: `JvmLispCompilerTest#aMutualTailCallAmongDefunsOrLabelsFunctionsIsAJump` (the
defun and `labels` pairs 1,000,000 deep, no call left, `OD?` shaken),
`#aMutualTailCallRunsInConstantStackWithNoJitToInlineItAway` (the same under `-Xint`, a child
JVM), `#everyShapeOfAMutualTailCallJumpsAndADynamicExtentKeepsItsCall` (lambda lists, a
closure per round, values, a tail `return-from`, three members, a member as a value, a
bouncing member, a special binding and an `unwind-protect` keeping their calls, a wrong
count), `#aCycleTheRootDoesNotCloseDispatchesOnAMemberIndex`,
`#aGroupPastTheMethodSizeLimitKeepsAMethodPerMember`;
`MethodCodeTest#aBlockBranchesToALandingLabelOfTheBodyItJoins`, `#aLandingLabelIsOneTheBlockNeverBinds`;
clojure-spec `letfn-mutual-tail-calls-run-in-constant-stack` (all four backends, 1,000,000
deep).

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
  among them, is on the marked chain), an inline lambda's last body form
  (`JvmLambdaCompiler.compileCall`: the body runs in place), and the pass-through lowerings
  through `JvmExprCompiler.compileExpansion` (`let*`, `locally`, `flet`, `labels`, `the`,
  `cond`, `case`, `ecase`, `and`, `or`, `when`, `unless`, `typecase`, `etypecase`,
  `multiple-value-bind`, `destructuring-bind`, `symbol-macrolet`, `with-slots`,
  `with-accessors`, `multiple-value-call`, `dolist`, `dotimes`, `do`, `do*`, `loop`, `prog`,
  `prog*`; since 2026-10-04 for the ones after `etypecase`). The extended relays reach the
  value tail too, so a call through a value in those positions is one as well. Only an arm
  that emits nothing after its expansion may relay.
- **The exit chain** (`Ctx.exitMark`, 2026-10-04): the forms that relay the mark lay a second
  one on EVERY sub-form they compile in place -- a `progn`'s, a plain `let`'s, a block's
  statements, both `if` arms, a `while` body, the statements of a `tagbody`, an inline
  lambda's body, an expansion -- when they carry either mark themselves. A block records
  whether it is on the tail mark (`BlockTarget.tail`); an exit on the exit chain to such a
  block, with no unwind or spill scope open, marks its value form
  (`JvmReturnCompiler.isTailExit`). That is the value of a `return` out of a loop body whose
  block ends the method: `(dolist (x l) (return (f ..)))`. Matched by identity like the mark,
  so a `catch` (which opens no scope in statement position), a special `let`, an
  `unwind-protect`, a `handler-case` -- none relays it -- break the chain by construction.
- **A `labels` function is recognized by its variable**: the expansion's one assignment
  `(setq __LABELS<n>_<name> (lambda ...))` (`LispMacroExpander.isLabelsFunctionVariable`;
  the rewrite turns every other mention into a read) records the lambda form in
  `Ctx.lambdaSelfVars`, `JvmLambdaCompiler` copies it into `LambdaInfo.selfVar`, and inside
  that lambda `(funcall var ..)` is a self call while `var` is the closure's own capture.
- The jump is emitted only from an empty operand stack with no unwind or spill scope open
  (`atTail`): the frame the verifier and HotSpot's OSR entry need
  ([jvm-osr-backedges.md](jvm-osr-backedges.md)); on the marked chain it always is.
- In a linked class (a JVM host or fragment) a jump to a `notinline` defun -- self or a tail
  group sibling -- first reads its cell with the arguments on the stack: once a run-time
  redefinition set it, the call takes the class's named entry and returns its answer; a
  null cell keeps the jump (`JvmFunctionCell`, [jvm-run-time-lowering.md](jvm-run-time-lowering.md),
  "Redefinition").

## Mutual tail calls: tail groups (`JvmTailGroup`)

**Invariant: functions whose tail calls to each other form a cycle -- top-level defuns, or the
functions of one `labels` form (a Clojure `letfn`) -- are a tail group, and a call on the
tail mark from one member to another is a jump.** A mutual recursion runs in constant stack
as a self tail call does. Landed 2026-10-03 (`.todo/c02`).

- **Finding**: `JvmTailGroup.ofDefuns` before Pass 2a, `.ofLabels` on each expansion in the
  `labels` arm (fresh per compile of the form, so its lambda conses name this instance's
  members, `Ctx.tailGroupMembers` -> `LambdaInfo.tailMember`). A walk of each body's tail
  positions that mirrors the mark (`if`, `progn`, a `let`/`let*` binding no special, the
  blocks and a `return`/`return-from` reached through them, the pass-through lowerings --
  `multiple-value-bind`/`destructuring-bind` binding no special, `symbol-macrolet`,
  `with-slots`, `with-accessors`, a `multiple-value-call` as the `funcall` it expands to --,
  an inline lambda's body when no parameter is special; an `flet`/`labels` body's names
  shadow defuns; NOT an exit out of a loop body, whose call stays a call in a group and
  never bounces), then Tarjan: each strongly connected component
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
- **Why rooted methods** (2026-10-03, a generic function whose method tail-calls the generic):
  ONE shared method with a switch
  at its entry and direct jumps is no faster than plain calls, and a cycle with two entry heads
  is irreducible --
  Graal refuses its OSR (`Multiple OnStackReplacementNodes generated`), so one long call stays
  interpreted ([jvm-osr-backedges.md](jvm-osr-backedges.md)) -- and every jump
  re-entering the switch (reducible) is slower still. Rooting the cycle heads is what runs at
  the plain-call rate; rooting EVERY
  member instead puts the whole group into each method a dispatcher names. When the jumps form a cycle the root does not close
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
  there is a value tail and passes its bounce on, [jvm-tail-bounce.md](jvm-tail-bounce.md)).
- A tail group laid out apart (a rooted method past 8000 bytecodes): each member's tail call
  to another is a direct call, a frame a round. A jump from a member that is not rooted is a
  call into a rooted method: one frame per entry, not per round.
- A tail `apply` of itself (a value tail, [jvm-tail-bounce.md](jvm-tail-bounce.md)), and a
  nested `defun` (a global variable that may be reassigned).
- A `multiple-value-call` of a computed function (a value in a temporary: a value tail).
  `(multiple-value-call #'f ..)` keeps the literal in its `funcall`
  (`LispMacroExpander.expandMultipleValueCall`, 2026-10-04), so it is a jump; the name is
  then resolved after the producers, which is what SBCL prints for a producer that redefines
  it (`(:NEW 1)`; the interpreter printed `(:OLD 1)` before).

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
survived: the tail-called loops run several times faster, the rest within noise.
bench-report: nine programs compile byte-identically.

**Bytes**: size-report's programs unchanged to within a few bytes; 172 methods of the whole
`clojure-spec.yaml` program jump back to their start.

**Mutual tail calls** (tail groups, same box, a loaded 64-core host). Depth, 1,000,000
rounds, `java Prog`: a CL `labels` even/odd pair and Clojure's `letfn` twin
answer, and so do two mutually tail-calling defuns, `-Xint` included, where the call-based
emission answered only in some runs. Time: the `labels` pairs run much faster than the
call-based emission, defun pairs and the `read` group level. Bytes: of the examples suite's
JVM legs only the ones with a group in
spliced code change, and then only marginally; bench-report and size-report
byte-identical (Clojure's regex matcher: four functions, three rooted).

**Loop exits, binding forms, lambda heads** (2026-10-04, wasmtime 49, the same box). Depth,
1,000,000 rounds of `(defun f (n) <form>)`, `java Prog` and `java -Xint Prog`: the self call in
the tail of `multiple-value-bind`, `destructuring-bind`, `symbol-macrolet`,
`(multiple-value-call #'f ..)`, a lambda head (required or `&optional`), and the value of a
`return` out of `dolist`/`dotimes`/`loop`/a `tagbody` statement: `StackOverflowError` (or, for
`multiple-value-call` and `dolist`, a pass only when C2 inlined the call; a self call was left in
the bytecode) -> answers, no self call left. Bytes, base jar vs this change: bench-report's ten
programs and size-report `hello_world`/`pi_approx`/`zlib` identical as `.class`, and as `.wasm`
and component (`--optimize`) but `zlib` +1 B; `clojure-spec` as one program 5,965,571 B `.class`
/ 4,986,328 B `.wasm`, identical; 30 `examples/{console,ml,llm-from-scratch,deep-learning-from-scratch}`
programs identical on both. The same change fixed the multiple-value settle of a result-less
`dotimes` ([multiple-values.md](multiple-values.md)), which the tests here first hit.

## Tests

`JvmLispCompilerTest#aSelfTailCallJumpsBackToTheMethodsFirstInstruction` (no self call left,
one entry jump, a `labels` method looping, 1,000,000 deep),
`#everyTailTransparentFormHandsTheJumpOnAndADynamicExtentKeepsItsCall` (the shapes above,
semantics and bytecode), `#anExpansionALambdaHeadAndALoopExitInTailPositionHandTheJumpOn` (the
binding forms, `multiple-value-call`, lambda heads, loop exits, a tail group and a bounce through
them; a special binding and a `catch` keeping their call),
`#aSelfTailCallInASplitOffContinuationStaysACall`; ci-spec
`loop-exits-binding-forms-and-lambda-heads-in-tail-position-run-in-constant-stack` (all four
backends, 100,000 deep); clojure-spec
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

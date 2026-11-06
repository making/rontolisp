# The interpreter runs a hot function's body compiled, not walked

**Invariant: once a lambda has been called `COMPILE_AFTER_CALLS` (2) times, its body runs as a
tree of `CompiledForm`s (`eval/BodyCompiler`, `CompiledForms`, `CompiledBody`) that does what
`LispEvaluator.evalCons` would do with each form -- with the same `Environment`s, the same names
in them, the same `%mv-spill` writes, the same frames for blocks and exits -- worked out once.
Whatever the compiler cannot tell the arm of is left to the walk (`CompiledForms.Pending`), and
every compiled decision that a later definition can overturn is re-checked against the epoch it
was made under.** A debugger session (`debugging`) runs nothing compiled. Landed with `.todo/i80`.

## Why a closure tree (measured 2026-11-06, linux-x64, Oracle GraalVM 25.0.5, load 8-25)

The two candidates were a tree of objects over the interpreter's values, and running the JVM
backend's bytecode for a hot defun.

- **The JVM backend's bytecode cannot run on the interpreter's values.** A cons there is an
  `Object[2]`, an instance its own layout, a symbol, a closure, a global, a special's binding and
  a generic's dispatcher are the compiled program's own: a defun run from the interpreter would
  convert every argument and result, and lose `eq` and every mutation through shared structure.
  Nor is it faster where coalton spends its time: fset's `compare` of two symbols, the whole
  program compiled with `-o`, took 1.17-1.47 s per 40,000 compares (13.2 s the first round)
  against the walk's 0.72-1.24 s; `fib 30` took 7 ms against 1.6-2.1 s.
- **A closure tree runs everywhere the interpreter does**, the native binary included, which
  defines no class at run time. A tree whose nodes are bytecode (a class per hot body over the
  interpreter's values) would remove the megamorphic call per node too, but only on HotSpot.
- Measured on the tree as it landed, builds side by side: `fib 30` 0.92-1.54 s -> 0.52-0.60 s
  (warm rounds); fset's `compare` 0.56-0.71 s -> 0.30-0.46 s per 40,000 (warm rounds, a box under
  load 30); coalton's whole load 2168 s -> 1460 s, up to `library/math/bounded.lisp` 86.1-87.7 s
  -> 59.5-63.7 s ([asdf.md](asdf.md), the coalton entry).

## A class per hot body: built, measured, not kept (2026-11-06)

A prototype wrote each hot body as one hidden class: each node's code where the tree calls the
node, the nodes, layouts and the built-in a site found bound as class-data constants, an exit to
the frame's own block a return, any other node called as the tree calls it. Tight code ran 2-7
times as fast (best of 8 warm rounds: an empty `dotimes` round 133 -> 33 ns, `(+ x 1)` 142 -> 33,
a `let` 166 -> 23, `char<` 166 -> 25, a two-argument call 178 -> 86, `fib 25` 189 -> 82 ns a call).
coalton's load did not, up to `library/math/bounded.lisp` side by side with the tree: a class
after 200 runs 60.6 s against 54.0, after 2,000 56.6 against 53.3, after 10,000 52.4 against 49.4,
after 50,000-200,000 54.6-55.9 against 56.3; `math/num.lisp` alone 5-13% shorter at best. Why:

- A class is compiled per body, the tree's node methods once for all of them. 147 classes took
  the JIT from 77 s to 90 s of compile-thread time and 61 MB to 81 MB of code; one class took
  100-565 ms to compile. The load runs 5,200 compiled bodies, few long enough to repay it.
- What a class leaves as it is -- the call protocol and the scopes ("Where the time goes now") --
  is where the load spends its time.

## Where the time goes now

`math/num.lisp` under JFR (2026-11-06, the `setf` memo in): 95% of the samples run compiled, 5% in
walks. By the first frame of each sample that is not the JDK's: the compiled nodes 64% (the
argument arrays 10%, a lexical read 8%, a call's guard and binding 10%, a `let`'s scope 6%,
`CompiledFrames.call` 4%, a body's run and statements 8%), built-ins applied through `apply`'s
megamorphic body 10%, package and symbol lookups over names 7% (`symbol-package`,
`find-package`), the value-count register 3%, the built-ins' own bodies 2%. Of the bytes
allocated, a scope's `Environment` was a third: 128 bytes, 21 of its 29 fields the global
environment's, beside a `NameMap`, the argument array and the `ArgList`. The global
environment's own state is an object of its own since (`Environment.Globals`; a scope holds
`Globals.NONE`, every field null as its own were), a scope 56 bytes: the load to
`math/bounded.lisp` 51.6 -> 46.2 s side by side, `math/num.lisp` 21.9 -> 19.8. Up to
`math/bounded.lisp`, `typep`'s expansion is 0.3% of the samples, the `typecase` family 0.1%,
`make-instance` 0.2%: a stamp over what they read would buy no more than that.

## What a compile reads, and what it guards

The compiler runs and expands nothing. It reads what the walk left on the forms it ran:

| form | evidence | holds while |
| ---- | -------- | ----------- |
| ordinary call | `FunctionCell#wasCalledOrdinarily` (set by `noteCall`) and the count its `BuiltinCallArity` shape accepts | `callSiteEpoch`, else `stillOrdinaryCall` (no methoded built-in, user macro or compiler macro claims the name) |
| built-in macro arm | the head's `ExpansionNote`, or `builtinMacroExpansions` with `expandsOnly` (a note is per head object, which the forms one macro makes share) | `methodedEpoch` -- the only state a pure arm's expansion depends on; a multiple-value consumer's memo only while no producer is methoded |
| user macro | its `ExpansionNote`, or `userMacroExpansions` under the macro (no `&environment`, no expander) | `callSiteEpoch`, else the name is still that macro and not methoded |
| `flet`/`labels`/slot macros | the walk's `expandedPerSite` memo | `macroTableEpoch` and no walk under way |
| `setf` | its `PlaceNote`, or `placeExpansions` | `placeEpoch` ([interpreter-expansion-memo.md](interpreter-expansion-memo.md), "The setf memo") |
| `lambda` | the walk's `lambdaShapes` site | -- |
| lexical variable, `let`, `setq`, parameters | the compile-time `BodyCompiler.Layout` | the specials' epoch, else the name is still not special |

The special forms `evalCons` runs itself are compiled from its arms: `quote`, `if` (a
`DispatchChain` included: each shape combination's tests and arms compiled when met), `let`
(`lexicalLet`'s, and `evalLetIn`'s for a special or `*package*` binding: its layout made from the
names proclaimed special at the compile, re-checked when a proclamation moves their epoch),
`progn`, `setq` (a free name the walk's way, through a global symbol macro too), `function`,
`lambda`, `block`/`%block`, `return-from`, `return`, `while`, `tagbody`, `go`, `unwind-protect`,
`%obj-ref`/`%obj-set`. So are the arms that decline a shape to the built-in's call and note none
of it: the floor family but `(floor (/ a b))`, `reduce`/`sort` (their `:from-end`/`:key` lowering a
function of the form). A guard that fails hands the form to its `Pending`, which walks it and
compiles it again from what the walk then left -- in place, never below itself, so a guard
flipping back and forth grows nothing.

A body the evaluator rewrites in place (a methoded producer's tail, `relowerProducerTail`)
compiles again: `bodyRewriteEpoch` moves after the rewrite.

Left to the walk until a memo with a stamp exists for them: `make-instance` (expanded at each
evaluation, [interpreter-expansion-memo.md](interpreter-expansion-memo.md), "The setf memo"),
`typep`/`typecase` and the other arms reading the class registry (a
`handler-case`'s protected form among them: its handler clusters' tests are made from the
registry at each evaluation), the condition signalling forms, `catch`, a compiler macro's site, a
uiop operator, the print family.

## Frames, tails and the stack

- A compiled body runs in the frame that called it: `evalCons`'s loop (its lambda arm runs
  `CompiledBody.run` and takes the `TailCall`/`TailForm` it answers in its `call:` loop), or a
  compiled frame (`CompiledFrames.call`, the same protocol: `owner`, `BlockTailExit`, the
  `funcall` seam, the `ConditionTrace` place, the async body's name). A tail call is a
  `TailCall`, so a chain of tail calls through compiled bodies runs in constant stack.
- A frame steps its tail (`CompiledForm.step`, looped in `CompiledBody.run`): an `if`, a `let`, a
  `progn`, a block in tail position answer the form to step onto next instead of calling it, so a
  tail chain costs one Java frame however long it is -- as `evalCons`'s loop does. Recursing
  through `tail()` calls cost 3x the walk's depth (12,270 warm against 37,534).
- Non-tail `depth` on the CLI's 16 MiB, `(defun depth (n) (if (= n 0) 0 (+ 1 (depth (- n
  1)))))`: compiled under `-Xbatch` 47,251 against the walk's 42,198; `-Xint` 22,579 against
  25,019; a cold first recursion (default flags, the tree's classes not yet JIT-compiled while
  the walk's are) 17,323 against 30,149-32,093. Under C2 (`-XX:-UseJVMCICompiler`) 72,126 against the
  walk's 13,437. Merging the argument loop into `Call` bought `-Xint` depth and cost Graal's.
- An exit from a statement to a block its frame owns (a `return` out of a `dolist` body) hands
  its value form to the frame, for the stack (`via-dolist` 100,000 deep): a compiled statement
  leaves the exit in the thread's register (`Environment.deferExit`) and answers
  `LispEvaluator.EXITED` up to the frame, which steps the compiled value form in the exit's scope
  (`CompiledForms.exited`); the walk's statement throws it (`BlockTailExit`), and a frame walks
  its form. Until it carried the compiled form, those value forms were 8% of `math/num.lisp`'s
  samples, walked; until 2026-11-06 the compiled statement threw too -- 2.8 million throws up to
  `math/bounded.lisp`. Without them, side by side: a `dotimes` left at its first round 710 ->
  340 ns, fset's `compare` of two symbols 0.25 -> 0.18 s per 40,000, its map operations (500
  symbol keys, 5,000 `with` and `lookup` and 10 `image` a round) 2.0 -> 1.5 s a round, coalton's
  load to `math/bounded.lisp` 49.8 -> 48.2 s.

## Traps

- An argument's spill clear is not dead: a later argument may read `%mv-spill` (an expansion's
  `(cons __q %mv-spill)`), so every atom clears as the walk's `singleValue` does.
- `ArgList`'s array becomes the callee's scope when the layout is exactly the parameters: nothing
  else may hold it.
- A scope a compiled body makes shares its layout's names (`NameMap` copies them before writing a
  key) and holds up to `PRESIZED_SCOPE_MAX` of them in its arrays, so `slot(i)` holds while no
  name is added: a map is promoted to a hash only when a put adds a name past its room, which no
  walked form does to a scope it did not make.
- A walk's empty special `let` body answers nil and leaves the channel as its inits left it; the
  compiled one does the same.
- A statement that answers `EXITED` has left its exit in the register: every caller of
  `statement` answers it on up or, owning the exit's block, takes the exit. One that drops the
  answer leaves the exit for some later frame to take as its own.

## Tests

- `LispEvaluatorTest.aCallSiteFollowsWhatItsOperatorBecomes` -- the compiled half: a function
  redefined, a name made a macro, a macro redefined, a parameter and a `let`'s name proclaimed
  special, a `labels` after the macro table moved, an exit's compiled value form through an
  `unwind-protect`, a `setq` through a symbol macro, a `setf` after each input of its expansion
  moved.
- With `-Drontolisp.interpreter.compile-after=1` (every body compiled at its first call) the
  `eval`, `cli`, `ansi`, `scheme`, root, `macro`, `reader`, `clojure` and `compiler` packages run
  green, and at the default `eval`, `cli` and `e2e`; the ci-spec `cases:` on the interpreter print
  what they printed walked (2026-11-06).
- `LispEvaluatorHotMethodSizeTest` -- `evalCons`'s `call:` loop.

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
  (warm rounds); fset's `compare` 0.48-0.54 s -> 0.40-0.45 s per 40,000; coalton's load up to
  `library/math/bounded.lisp` 86.1-87.7 s -> 59.5-63.7 s ([asdf.md](asdf.md), the coalton entry).

## Where the time goes now

`math/num.lisp` under JFR: 82% of the samples run compiled, 18% in walks the compiled code hands
back -- `setf` statements 5-6% (`.todo/i79`), then forms of 1-2% each: an `if` a macro the compiler
could not classify expands to, a call it left pending, a free variable's read, a `handler-case`.
The compiled share is the tree's own cost, spread thin: a built-in applied through `apply`'s
megamorphic body 9%, the argument arrays 8%, a lexical read 6% (its epoch check and the parent
chain), a `let`'s scope 6%, a call's scope 4%, a call's guard 4%, a body's statements' dispatch 4%.
No hotspot is left that a node could shed; the dispatch between nodes and the allocations are
what a class per hot body would take away (`.todo/i80`).

## What a compile reads, and what it guards

The compiler runs and expands nothing. It reads what the walk left on the forms it ran:

| form | evidence | holds while |
| ---- | -------- | ----------- |
| ordinary call | `FunctionCell#wasCalledOrdinarily` (set by `noteCall`) and the count its `BuiltinCallArity` shape accepts | `callSiteEpoch`, else `stillOrdinaryCall` (no methoded built-in, user macro or compiler macro claims the name) |
| built-in macro arm | the head's `ExpansionNote`, or `builtinMacroExpansions` with `expandsOnly` (a note is per head object, which the forms one macro makes share) | `methodedEpoch` -- the only state a pure arm's expansion depends on; a multiple-value consumer's memo only while no producer is methoded |
| user macro | its `ExpansionNote`, or `userMacroExpansions` under the macro (no `&environment`, no expander) | `callSiteEpoch`, else the name is still that macro and not methoded |
| `flet`/`labels`/slot macros | the walk's `expandedPerSite` memo | `macroTableEpoch` and no walk under way |
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

Left to the walk until a memo with a stamp exists for them: `setf` and `make-instance`
(`.todo/i79` -- once it memoizes them, `memoizedBuiltinExpansion` or a kind of its own lets the
compiler take them), `typep`/`typecase` and the other arms reading the class registry (a
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
  the walk's are) 17,323 against 30,149. Under C2 (`-XX:-UseJVMCICompiler`) 72,126 against the
  walk's 13,437. Merging the argument loop into `Call` bought `-Xint` depth and cost Graal's.
- A `BlockTailExit` a compiled exit throws carries its value form compiled, which a compiled
  frame continues with in the exit's scope (a walk frame walks the form): the deferral is for the
  stack (`via-dolist` 100,000 deep). Until it carried the compiled form, those value forms were
  8% of `math/num.lisp`'s samples, walked.

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

## Tests

- `LispEvaluatorTest.aCallSiteFollowsWhatItsOperatorBecomes` -- the compiled half: a function
  redefined, a name made a macro, a macro redefined, a parameter and a `let`'s name proclaimed
  special, a `labels` after the macro table moved, an exit's compiled value form through an
  `unwind-protect`, a `setq` through a symbol macro.
- With `-Drontolisp.interpreter.compile-after=1` (every body compiled at its first call) the
  `eval`, `cli`, `ansi`, `scheme`, root, `macro`, `reader`, `clojure` and `compiler` packages run
  green, and at the default `eval`, `cli` and `e2e`; the ci-spec `cases:` on the interpreter print
  what they printed walked (2026-11-06).
- `LispEvaluatorHotMethodSizeTest` -- `evalCons`'s `call:` loop.

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
  `library/math/bounded.lisp` 81.0 s -> 64.1 s, `math/num.lisp` 40.3 -> 29.6 s ([asdf.md](asdf.md),
  the coalton entry). What the tree leaves is the walk of what it cannot compile (below) and the
  cost of a node: a megamorphic `eval` or `step` per form, an argument array, a scope.

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
(all bindings lexical), `progn`, `setq`, `function`, `lambda`, `block`/`%block`, `return-from`,
`return`, `while`, `tagbody`, `go`, `%obj-ref`/`%obj-set`. A guard that fails hands the form to
its `Pending`, which walks it and compiles it again from what the walk then left -- in place,
never below itself, so a guard flipping back and forth grows nothing.

A body the evaluator rewrites in place (a methoded producer's tail, `relowerProducerTail`)
compiles again: `bodyRewriteEpoch` moves after the rewrite.

Left to the walk until a memo with a stamp exists for them: `setf` and `make-instance`
(`.todo/i79` -- once it memoizes them, `memoizedBuiltinExpansion` or a kind of its own lets the
compiler take them), `typep`/`typecase` and the other arms reading the class registry, the
condition signalling forms, `handler-case`, `unwind-protect`, `catch`, a special `let`, the print
family.

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
- A `BlockTailExit` a compiled exit throws carries the value FORM, which the frame walks: the
  deferral is for the stack (`via-dolist` 100,000 deep), not for speed.

## Traps

- An argument's spill clear is not dead: a later argument may read `%mv-spill` (an expansion's
  `(cons __q %mv-spill)`), so every atom clears as the walk's `singleValue` does.
- `ArgList`'s array becomes the callee's scope when the layout is exactly the parameters: nothing
  else may hold it.
- A scope a compiled body makes shares its layout's names (`NameMap` copies them before writing a
  key) and keeps up to `PRESIZED_SCOPE_MAX` names linear, never promoted, so `slot(i)` holds.

## Tests

- `LispEvaluatorTest.aCallSiteFollowsWhatItsOperatorBecomes` -- the compiled half: a function
  redefined, a name made a macro, a macro redefined, a parameter proclaimed special, a `labels`
  after the macro table moved.
- The whole `eval` package (3,078 tests), `cli` and `scheme` run with
  `-Drontolisp.interpreter.compile-after=1` (every body compiled at its first call): green but
  `WideListStackTest`'s wasm leg, the compile path's own (2026-11-06).
- `LispEvaluatorHotMethodSizeTest` -- `evalCons`'s `call:` loop.

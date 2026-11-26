# i80. The interpreter's cost per form: run a hot function's body compiled, not walked

Difficulty: High

Landed 2026-11-06 (`.kb/interpreter-compiled-bodies.md`): a lambda called twice runs its body as a closure tree
compiled from what the walk left on its forms; the JVM backend's bytecode was measured and rejected there. Side by
side it takes about 30% off coalton's load (`.kb/asdf.md`, the coalton entry), not the further large factor this
item asks for.

Measured since (2026-11-06, the same file):

- A class per hot body (the tree's nodes as one method's code, the bound built-in a constant) was built and is not
  kept: tight code 2-7 times as fast, coalton's load to `math/bounded.lisp` not faster at any threshold -- the JIT
  compiles a class per body, and the load runs 5,200 bodies. Its premise, that the dispatch between nodes is what
  the load pays for, did not hold ("A class per hot body"; the prototype in
  `.todo/artefacts/i80-a-class-per-hot-body/`).
- Done, each on every platform: a loop's exit handed to its frame without a throw, a scope without the global
  environment's 20 fields, the value register cleared only when it holds something, a compiled `#'name`'s generic
  key made once. Side by side, the whole load 1018 s -> 821 s (box under load 5-15), every dear file 1.2-1.35
  times as fast.
- Measured and not kept: a scope's `NameMap` folded into its `Environment`, a call keeping its own copy of what its
  cell holds -- within the noise.

The plan, rewritten around that measurement:

- What the whole load still pays for, under JFR: the argument arrays 10% of the samples, a call's guard and binding
  8% (6.6% at the read of the cell's binding), a lexical read 8%, built-ins through `apply`'s megamorphic body 10%,
  lookups by name 6% (a free variable's read 3%: a special not bound dynamically is looked up by name in every
  scope up to the global environment, `LispEvaluator.freeValue`), package and symbol lookups 5% (a name split into
  package and member each time; a compiled `#'name`'s part of it is gone). Each is a few percent, and no change of
  the tree's nodes takes the large factor.
- The large factor takes a call that makes no `Environment`, `ArgList` or argument array -- the scope in the callee's
  locals, made an `Environment` only where a walk, a closure or the debugger reads it -- a compiler over the
  interpreter's values with a way back to the tree. On HotSpot it must repay its JIT cost on a load of 5,200
  bodies, which a class per body did not; the native image would need it ahead of time. The compile paths' read-time
  image runs coalton's typechecker on this interpreter (`.kb/asdf.md`, "The compile paths"), so its speed is
  theirs too.
- The forms still walked (`make-instance`, the `typep`/`typecase` family, a compiler macro's site, the print family)
  are under 1% of the load's samples: a stamp over what they read would buy no more.

For whoever picks it up (stopped 2026-11-07, every change pushed):

- Touched: `CompiledForms` (`Body.statements` and every caller of `statement` pass `LispEvaluator.EXITED` up;
  `CompiledForms.exited`; `FunctionValue`'s key), `CompiledBody.run`, `ValueCountRegister` (the exit slot,
  `clear`), `Environment` (`Globals`, `deferExit`/`takeExit`), `ClosRegistry.findNormalizedGeneric`.
- How it was measured: coalton's load traced per component with `load-until.lisp` (in the artefact directory;
  `I75_STOP=library/math/bounded.lisp` stops it after about 50 s), the builds run at once from copies of
  `target/classes`, three or more for one decision -- on a shared box one pair differs by up to 5% with no change
  between them. JFR (`settings=profile`) by each sample's first frame outside the JDK; a hidden class's frames do not
  show in its stacks.
- Next, by size: the call protocol's allocations and loads (a compiled callee's scope already takes the argument
  array as its values, so the `ArgList` around it is needed only where a built-in or the walk takes a list), a
  special's read without the walk up the scope chain, then the compiler of the large factor above.

What it must keep, as the tree does: redefinition (the call-site notes' epochs), special variables and `progv`,
multiple values, non-local exits, the debugger's frames when a SWANK server runs, and every pinned answer of the
four backends (`.kb/running-backends.md`). Done when coalton's load drops by a further large factor, measured
side by side.

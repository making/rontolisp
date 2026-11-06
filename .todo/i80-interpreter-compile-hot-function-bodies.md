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
  the load pays for, did not hold ("A class per hot body").
- What the load pays for is the call protocol and the scopes, on every platform: the argument arrays 10% of
  `math/num.lisp`'s samples, a call's guard and binding 10%, built-ins applied through `apply` 10%, a lexical read
  8%, package and symbol lookups over names 7% ("Where the time goes now").
- Done: an exit from a loop statement handed to its frame without a throw, and a scope that no longer carries the
  global environment's 21 fields: the load to `math/bounded.lisp` 52.6 -> 46.2 s, fset's map operations 2.0 ->
  1.5 s a round.

The plan, rewritten around that measurement:

- What is left of the protocol, each a few percent: a scope's `NameMap` folded into its `Environment` (one object
  and one load a slot read less); `symbol-package` and `find-package` answering from the symbol rather than
  resolving its name each call; a call site's guard and binding read as one load.
- The large factor: no change of the tree's nodes gives it. It takes a call that makes no `Environment`, `ArgList`
  or argument array -- the scope in the callee's locals, made an `Environment` only where a walk, a closure or the
  debugger reads it -- which is a compiler over the interpreter's values with a way back to the tree. On HotSpot it
  must repay its JIT cost on a load of 5,200 bodies, which a class per body did not; the native image would need it
  ahead of time. The compile paths (`.todo/i73`) are the other road to coalton's speed.
- The forms still walked (`make-instance`, the `typep`/`typecase` family, a compiler macro's site, the print family)
  are under 1% of the load's samples: a stamp over what they read would buy no more.

What it must keep, as the tree does: redefinition (the call-site notes' epochs), special variables and `progv`,
multiple values, non-local exits, the debugger's frames when a SWANK server runs, and every pinned answer of the
four backends (`.kb/running-backends.md`). Done when coalton's load drops by a further large factor, measured
side by side.

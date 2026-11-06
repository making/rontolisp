# i80. The interpreter's cost per form: run a hot function's body compiled, not walked

Difficulty: High

Landed 2026-11-06 (`.kb/interpreter-compiled-bodies.md`): a lambda called twice runs its body as a closure tree
compiled from what the walk left on its forms; the JVM backend's bytecode was measured and rejected there. Side by
side it takes about 30% off coalton's load (`.kb/asdf.md`, the coalton entry), not the further large factor this
item asks for. The premise held only for what a compile removes -- classifying a form, looking a variable up by
name: the tree's own cost per node is now most of the time (`.kb/interpreter-compiled-bodies.md`, "Where the time
goes now").

The plan, rewritten around that measurement:

- The tree's cost: a megamorphic `eval`/`step` per node, an argument array and an `ArgList` per call, a built-in
  applied through `apply`'s megamorphic body, a scope per binding form, an epoch read per guard. A HotSpot tier
  that emits one class per hot body from its `CompiledForm` tree -- the nodes' code in one method, the layouts and
  guards as constants, the built-in a site calls bound to the site -- removes the dispatch and lets the JIT inline
  the built-ins; the native image, which defines no class at run time, keeps the tree. Measure it on fset's
  `compare` and on `math/num.lisp` against the tree before building it whole.
- The forms still walked: `setf` statements (`.todo/i79`; its per-site memo is what the compiler then takes);
  `typep`/`typecase`/`etypecase`/`check-type` and a `handler-case`'s protected form, whose expansions read the
  class registry at each evaluation and need a stamp over what they read; a compiler macro's site; the print
  family.

What it must keep, as the tree does: redefinition (the call-site notes' epochs), special variables and `progv`,
multiple values, non-local exits, the debugger's frames when a SWANK server runs, and every pinned answer of the
four backends (`.kb/running-backends.md`). Done when coalton's load drops by a further large factor, measured
side by side.

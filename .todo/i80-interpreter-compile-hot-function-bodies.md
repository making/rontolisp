# i80. The interpreter's cost per form: run a hot function's body compiled, not walked

Difficulty: High

Measured 2026-11-06 (`.kb/asdf.md`, the coalton entry): coalton's load is about half its 60 minutes now, and what
is left is no hotspot but the cost of walking each form. Per file it is 30 to 240 times SBCL's compile and load
(`seq.lisp` 372 s against 3.2). JFR up to `library/math/real.lisp`: `evalCons`'s own dispatch 22% of the
samples, built-in calls through `apply` 6%, a variable's lookup by name about 10% (`Environment$NameMap.get`
6%), scope creation 7% (`lexicalLambdaScope`, `lexicalLet`, `Environment.<init>`), a call's length walk and
argument list 6% (`LispCons.properLength`, `ArrayList.add`). fset's tight loops pay it in full: `fset:compare`
of two symbols takes 13.7 µs against SBCL's 37 ns. Per-form notes and memos (i75) took the overhead of finding
out what a form is; what remains is doing it by walking.

Not measured: how much of the per-form cost a closure tree removes on this interpreter (resolved lexical
addresses instead of names, operators classified once, argument arrays sized at compile time), against running
the JVM backend's bytecode for a hot defun from the interpreter, which the backends' pinned semantics may or may
not allow. Measure both on fset's `compare`/`wb-map-tree-with` and on coalton's `math/num.lisp` before choosing.

The work: compile a function's body once, the first time it runs or once it runs often, into something that
runs without re-reading the conses, falling back to `evalCons` for any form it does not cover. What it must keep:
redefinition (the call-site notes' epochs), special variables and `progv`, multiple values, non-local exits,
the debugger's frames when a SWANK server runs, and every pinned answer of the four backends
(`.kb/running-backends.md`). Done when coalton's load drops by a further large factor, measured side by side.

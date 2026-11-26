# i80: a class per hot compiled body, the prototype that was measured and not kept

Taken 2026-11-06 on linux-x64 (64 cores, Oracle GraalVM 25.0.5), base commit `d2dc7b7aa`. The numbers and the
verdict are in `.kb/interpreter-compiled-bodies.md`, "A class per hot body".

- `BodyClassWriter.java`, `BodyClass.java`: the writer (`java.lang.classfile`, a hidden class per body, its
  constants class data loaded by `ldc` of `classDataAt`) and the class it extends. Into `src/main/java/am/ik/rontolisp/eval/`.
- `tree.diff`: the tree's side, against `d2dc7b7aa` -- the nodes' fields the writer reads, `LispEvaluator.applyBuiltin`
  (the built-in arm of `apply` with the body passed in), `CompiledBody.run` writing and running the class, a heat count
  of runs and loop rounds. It also carries the measurement's own counters, printed at exit under
  `-Drontolisp.interpreter.class-debug=true`: the delegated nodes run, the bodies' heat, the exits thrown.
- Properties: `-Drontolisp.interpreter.class-after=N` (runs and loop rounds before a body's class is written; 0
  none), `-Drontolisp.interpreter.class-max-code=N` (the longest method kept, 7900 by default).
- Probes: `micro.lisp` (ns per round of each operation), `fset-compare-long.lisp`, `fset-map.lisp`, and
  `load-until.lisp` (coalton's load traced per component, `I75_STOP=library/math/bounded.lisp` stops it there).

What it would need to pay: its classes compiled by the JIT for few enough bodies, and the call protocol and scopes
it leaves as the tree has them replaced -- the plan in `.todo/i80`.

# j89: what was measured (2026-11-18)

Results are in `.kb/java-frontend.md`: "A thread suspended and resumed: the two leads
measured" (the analysis and the prototype), "Threads" (a thread started for a time) and
"java.util.concurrent" (the classes, the order of a future's dependents).

- `held/held.py` -- `rontolisp Program.java --dump-ir > program.ir; python3 held.py [-v] program.ir ..`:
  which functions of a program have to be resumable once a `synchronized` region no
  suspension can be reached from is known never to be held by a suspended thread. Four
  readings of a call through a function value, side by side; `-v` says why each function
  and each region is in the set. What it cannot tell is in its header: the interface of
  a lambda bound to a variable and of a FUNCALL through a field or a local, which the
  lowering knows and the IR does not.
- `async/` -- the resumable-body lead tried by hand on what the pipeline has:
  `plain.lisp` against `resumable.lisp` (20,000,000 calls of a function that never
  suspends, as a call and as an `async-defun` awaited; `-o x.wasm [--component]`, then
  `wasmtime run`), `buffer.lisp` (a bounded buffer of two async bodies, an
  `unwind-protect` and a `handler-case` across the awaits; the component prints what
  the interpreter and the JVM print), `spin2.lisp` (the same with `(wait-for 0)` as the
  yield: never ends on the component, `.todo/k02`).
- `probes/` -- run under a JDK 25 (`java Probe.java`) and under rontolisp, the outputs
  compared: `Order` (the order a future's dependents run in), `Shows` (what a lock, a
  latch, a pool and a future print), `Msgs` (a pool's refusals), `Cf` (a future's state,
  the stages of two futures, limits), `Fj` (fork/join: the lines that differ are the
  exception a task rethrows on another thread, which a JDK copies), `Hidden` (a private
  member of a superclass hides nothing around the subclass).

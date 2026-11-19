# k07: what was measured (2026-11-19)

Results are in `.kb/java-frontend.md`: "What k07 measured and made of the resumable
lowering", "The host's events give the turns under a component", "A thread suspended and
resumed: the two leads measured" and "Threads"; `.kb/async-await.md`, "`%task-swap`".
wasmtime 49.0.0, x86-64.

- `serve/` -- what a waiting request does to its instance under `wasmtime serve`.
  `unawaited.lisp`: a Lisp handler that awaits a timer (`/slow`: a second request is
  answered meanwhile by the same instance) and one that answers with an unawaited timer
  body left (`/bg`: the body never runs, the task has exited). `App.java` with
  `origin.py` and `try.sh PORT` (`rontolisp App.java -o App.wasm --component` first): a
  handler asleep 1.5 s, a task scheduled 700 ms past its response, a handler asking an
  origin that takes 1.5 s; a request 0.4 s into each. `Spin.java`: a daemon that turns
  for ever beside a server.
- `loop/` -- `Loops.java`: `break`, `continue`, `return` and labelled exits out of loops
  that take turns; prints `1 1` under a JDK and on both wasm targets. The benchmark is
  k00's `bench/Counter.java`. Its lowered forms are a Lisp program
  (`java -cp out:target/classes am.ik.rontolisp.java.Probe Counter.java -v -v`, the lines
  from the first `(DEFSTRUCT` on, compile with `-o x.wasm`, then `wasmtime compile` and
  `wasmtime run --allow-precompiled`); the thread's lambda was replaced by hand:
  - as lowered then (a resume body): 2.50 s
  - `(while (funcall (lambda () (block run (while test body.. (if (< (setq slice ..) 0)
    (return-from run t))) nil))) (await turn))`: 1.67 s
  - the same with the local copied into the lambda and back: 1.57 s
  - a named function taking and answering the local: 1.55 s
  - a plain lambda that counts no turn (the bound): 1.65 s
  - that with `increment()` holding no monitor: 0.26 s; with the monitor and no
    `hash-table-count` at its exit: 1.33 s
- `spill/sites.py` -- writes the seven Lisp programs behind "what a suspend site costs":
  329 B at one await; 2,839 / 4,615 / 6,405 B at 11 awaits and 0 / 10 / 20 locals;
  5,357 / 8,353 / 11,363 B at 21.
- `cm-threading/threads.wat` -- the component model's threads: `ROUNDS` round trips
  between two threads, a GC struct held 20 frames deep on the suspended one, a `JUNK`-element
  array made between two switches. `sed -e s/ROUNDS/1000000/ -e s/JUNK/64/
  -e s/thread.switch-to/thread.suspend-then-resume/ threads.wat > t.wat`,
  `wasm-tools parse t.wat -o t.wasm` (1.254.0),
  `wasmtime run -W component-model-threading=y -W gc=y [-C collector=drc|copying|null]
  --invoke 'run()' t.wasm`: 21000000 in 1.1 to 1.3 s under each collector; without the
  flag the component is refused.

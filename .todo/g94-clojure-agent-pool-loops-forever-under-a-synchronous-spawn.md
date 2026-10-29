# Clojure: the agent pool loops forever where a spawn runs its body at once

Difficulty: Medium

Read from the code, not measured in a browser (2026-10-29). The browser playground's
interpreter splices the host library (`LispEvaluator.clojureProgram` calls
`ClojureLibrary.splice(program, true)`), and its `rontolisp:make-thread` runs the body
synchronously (`src/web/java/.../Target_AsyncRuntime.spawnThread`). The first `send`
reaches `%clojure-agent-pool-start`, whose worker body `%clojure-agent-pool-work` is an
endless `(loop ... (sleep 0.001))`, so the spawn never returns: `send.md`'s Run cell
(`(send a * 6)`) should hang the page. `DocExamplesTest` runs the cells on real threads and
cannot see it.

Plan: confirm in the web image, then give the pool the shape the taps took (`clojure.lisp`
"Taps", g88): a worker that ends when the queue is empty and a submit that starts one when
none runs. That also removes the four workers' 1 ms poll on the interpreter and the JVM.
Keep `ClojureAgentPoolTest`'s ordering and error-mode pins.

# Clojure: an nREPL server over the interpreter's run-time lowering

Difficulty: High

Recorded as a follow-up of g83 until `eval` worked; it does on the interpreter since g83
(`.kb/clojure-frontend.md`, "Lowering at run time"). ClojureWasm (cljw) ships an nREPL
server, and editors (CIDER, Calva, Conjure) speak nREPL, not the `clojure>` prompt.

Shape: bencode over TCP (`rontolisp:tcp-*`, `.kb/tcp-sockets.md`), the `clone`, `close`,
`describe`, `eval`, `load-file`, `interrupt` and `completions` ops, one `ClojureSession`
per nREPL session bound to the evaluator through `SourceSession.attach`, `*out*`/`*err*`
captured per message, `ns` reported after each eval.

Gap the workflow hits at once: in an open lowering (a session, a form lowered at run time)
a redefined `defn` reaches only forms lowered after it, since calls lower direct
(`.kb/clojure-frontend.md`, "Lowering at run time", deviations). The oracle's callers go
through the var, and an nREPL user redefines a function and re-runs its callers constantly,
so calls in an open lowering through the var's cell belong to this item -- measure the
interpreter cost before choosing.

Acceptance: an nREPL client (e.g. `clj -M -m nrepl.cmdline --connect`) evaluates forms,
switches namespaces and loads a file against `rontolisp --nrepl`; the redefinition case above
answers the oracle's.

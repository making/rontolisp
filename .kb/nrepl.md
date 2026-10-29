# nREPL: `rontolisp --nrepl` serves the Clojure session to editors

**Invariant: one lowering and one evaluator serve every nREPL session -- the oracle's one
runtime, where what one session defines another calls -- and each session owns the
variables a REPL thread binds, which every message of it runs under on its own thread.**
Shapes measured against nREPL 1.3.1 on clj 1.12.6 (2026-10-30): every op below answered
message for message as `nrepl.cmdline`'s server answers, the differences listed at the end.

## Layout

- `cli/NreplServer` -- transport and sessions: a `ServerSocket` on the loopback interface,
  one virtual thread reading each connection (`cli/Bencode`: byte strings as UTF-8 text,
  dict keys sorted on write), replies written whole under the connection's lock. Ops:
  `describe` (`ops`, `versions` -- clojure from `*clojure-version*`, java, nrepl `1.3.1`,
  rontolisp -- `aux.current-ns`), `clone`, `close`, `ls-sessions`, `eval`, `load-file`,
  `interrupt`, `completions`; anything else `[done unknown-op error]`, an unknown session
  `[done unknown-session error]`. A message naming no session runs in a fresh one made for it
  (the reply names it, like the oracle's ephemeral session).
- `eval/ClojureNrepl` -- the Clojure half, so `cli` names no language package: the
  `SourceSession` it attaches (its `ClojureSession` the run-time lowering, `eval` included),
  the session variables, the evaluation of a message, failure reports, completions.
- `RontoLispCli`: `--nrepl[=PORT]` (value-less key, `=` for the port), refused beside a
  program, `-o`, `test` or a non-Clojure `--source-language`; prints nREPL's
  `nREPL server started on port P on host localhost - nrepl://localhost:P` (CIDER parses it),
  writes `.nrepl-port` in the working directory (deleted at exit). Stdin is empty: no
  `stdin` op, so `(read-line)` reads end of input instead of blocking on the server's
  terminal. Each session's thread takes `--stack` (`RontoLispCli.stackBytes`).

## A session's variables

`ClojureSession.threadBoundSpecials()`: `*ns*`, `*file*`, `*source-path*`, `*1` `*2` `*3`
`*e`, the main-bound flags (`ClojureCoreSpecials.replThreadBound`: `*print-length*`,
`*warn-on-reflection*` ...). `ClojureNrepl` defines them at the root once (an empty first
buffer), keeps a value map per session (`Bindings`) and runs each message under
`LispEvaluator.withThreadBindings` -- dynamic bindings pushed on the running thread,
proclaimed special (a standard stream too, so a Lisp read of `*error-output*` sees the
binding), written back at the end. A message runs on a COPY the session adopts when it ends,
an interrupted one's never (the oracle keeps the state of the last completed eval). Per
message only: `*standard-output*`/`*error-output*` bound to stream values over the reply
writers (`LispEvaluator.openJavaStream`, kind `:FILE` -- a Writer entry's direction is read
off the entry), an explicit `ns` (must exist: `ClojureSession.knowsNamespace`, else
`[error namespace-not-found done]`), `file`.

Output: a writer sends `out`/`err` on a newline, a `flush` or past 1 KiB, and both flush
before each value and at the end (the oracle's PrintWriter flushes on `println`). Rejected:
routing the evaluator's process streams per thread -- it would convey output to threads the
evaluation starts, but `force-output` and the `PrintStream`'s autoflush are one call there,
so every `print` became a message, and stdin's shared `BufferedReader` crossed sessions.
Consequence kept: a thread the evaluation starts (a `future`) writes to the server's stdout.

## A message

- `eval`: `ClojureSession.read(code, file, ns)` -- the session's buffer lowering in the
  session's namespace (the lowering's `currentNs` set per read; one lowering at a time),
  every datum's entry echoing (an `ns` datum its nil, which the REPL leaves out), each
  entry's position (`ClojureTopLevel.location`). Each datum: value `{value, ns}`, or a
  failure -- the report on `err`, `{status [eval-error], ex, root-ex}` -- and the next datum
  runs. A text that stops reading (`ClojureSession.readFailure`: the readable prefix) runs the
  prefix, then reports `Syntax error reading source at (REPL:L:C)`; a lowering refusal stops
  with `Syntax error compiling at (...)` and `Compiler$CompilerException`.
- The report of a run failure is the shipped `clojure.main`'s: `ex-str` of `ex-triage` of
  `*e`, its line the datum's (`merge {:clojure.error/line L}` under the triage's own), plus
  `(name (class *e))` / root cause, through one run-time lowering of a `load-string`
  (`%clojure-lower-at-run-time`, no history). A stack overflow restores the control state
  and reports `StackOverflowError`.
- `load-file`: `*ns*` and `*file*` bound for the load (the file's `ns` is the load's own),
  the first failure ends it, the last value answers without `ns`, `ex`/`root-ex`
  `Compiler$CompilerException` (the oracle's `Compiler/load` wraps), the report naming the
  `file-name`. The history takes ONE value, the load's (the datums' own records are undone).
- `completions`: `clojure/ClojureCompletion` over the lowering's tables, compliment-lite's
  sources: the namespace's interns, refers and visible core names, an alias's or namespace's
  publics behind `x/` (a lowering namespace's `varsOf`), namespaces (known plus shipped
  requirable), special forms, literals; segment-fuzzy (`fuzzy-matches?` ported), sorted by
  text. Types `function`/`macro`/`var` from the lowering's kinds. No keywords, classes or
  members.

## Interrupt

`LispEvaluator.interrupt(thread)`: a pending set read through one volatile counter at every
closure application in `evalCons` (tail calls and a Clojure `loop`'s recur included), every
`while` iteration and every `tagbody` step; the thread stops with `LispInterruptSignal` (no
condition: no handler-case or Clojure catch takes it, every `unwind-protect` cleanup runs),
and `Thread.interrupt` wakes a sleep. The server answers the running message
`[interrupted done]` at once, suppresses its later replies and hands the session a fresh
thread, like the oracle (whose `Thread.stop` is gone on Java 25: the cooperative stop is the
only one). `destroy-thread` keeps `Thread.interrupt` alone.

## The cmdline client's forms

`nrepl.cmdline` sends each form `pr-str`'d under `*print-meta*`, so every list carries the
`{:line L :column C}` the oracle's reader puts on it. The Clojure reader drops a metadata
map of only `:line`/`:column`/`:end-line`/`:end-column` on a list (never a collection or any
other key) and keeps the place for the datum's position (`printedPositions`). Before
(2026-10-30) `^{:line 2} (defn g [] 1)` echoed `#<procedure>` and `^{...} (ns my.app)`
refused `ns`, in the REPL and in a file too.

## Redefinition in an open lowering

Measured before choosing (2026-10-30, interpreter, one JVM, rounds 2-3 of 3): 1M calls of a
one-argument identity function cost ~0.4 µs each direct and ~1.5 µs through the var's value
cell and the IFn dispatcher (`^:redef`'s shape); `fib 25` showed no difference (3.3-3.7 s
either way, `%clojure-+`/`-` dominate). Chosen: zero call cost --
`ClojureLowering.repointsRedefinitions()` (incremental and not `carriesRuntimes`: a session,
a datum lowered as an interpreter program runs) makes a redefinition store its function into
every cell a call may name (`setf symbol-function` of the bare symbol and each version a
pinning pass lowered calls of, `pinnedDefnCounts`), and calls lowered there name that cell
(`currentDefnSym`). A `def` over a defn re-points the cells to a forwarder over the value
cell (`(def f (memoize f))` memoizes f's own recursion, the oracle's), a defn over a def
stores the value cell. deftest likewise. Whole files keep the versions (four backends
agree); the JVM's fragments keep theirs (`.kb/jvm-run-time-lowering.md`, Limits; `.todo/h14`).

## Differences from the oracle's server

No `eval` class in a report's place (`at (REPL:1)`), no hash in printed objects, rontolisp's
refusal words (`unknown name: x`), a read failure's own words and place, `in-ns` answers nil,
no `stdin`/`lookup`/middleware ops, no `:file` on a namespace candidate, a forward reference
inside one message resolves (the session pre-declares a buffer). Interpreter only.

## Tests

`cli/NreplServerTest` (in process, port 0: the oracle's replies for describe, clone, eval,
ns switches, the redefinition case, load-file, completions, ls-sessions, close, unknown op
and session; interrupt), `ClojureSessionTest#aLaterBufferRedefinitionReachesTheCallsAnEarlierOneLowered`,
`#aLaterBufferTestRedefinitionNotifiesTheWatchesAnEarlierOneAdded`,
`ClojureReaderTest#derefMetaVarAndDiscard` (position metadata), clojure-spec
`requiring-resolve-and-load-string` (a load's `*ns*`), `RontoLispCliTest#aProgramTheModeCannotTakeIsAClearError`.
By hand: `clj -M -m nrepl.cmdline --connect` against `rontolisp --nrepl` (evaluate, switch,
`load-file`, the redefinition) answered as against `clj -M -m nrepl.cmdline` (2026-10-30).

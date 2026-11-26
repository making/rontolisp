# k18. Java front end, `--native`: the scheduler waits in the runner with no thread going on

Difficulty: High

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.**

State after k07 (2026-11-19, `.kb/java-frontend.md`, "The host's events give the turns
under a component" and "java.net.http"): under a component a thread waiting for a reply,
a time or a request leaves the others their turns, because the host's events give them.
A `--native` module still runs k00's loop (`java.lisp`, `#-rontolisp-component`:
`%java-thread-finish` / `%java-thread-run`), and what it cannot do is wait for several
things at once:

- **A thread waiting for a reply holds the turn.** `%java-suspending-http-reply` gives the
  threads that can go on one turn and then forces the fetch's DEFERRED future, whose thunk
  blocks in `rlhttp.head`: a thread asleep does not wake until the reply is in, a reply
  that waits for what a sleeping thread would send never comes, and a `main` asking its
  own server through the client waits for ever (the request is never taken).
  `java-http-spec.yaml`'s `waiting` program has no `--native` leg for it, and
  `doc/*/java/index.md`'s example and `examples/java/http/Client.java` run on the host's
  threads only.
- **A request is taken only where no thread can go on**, nested in the call of the one
  that waits (`%http-serve-step`), so a handler asleep is not entered beside another.

What it takes, as far as k07 read it (nothing measured):

- The runner serves every fetch on a thread of its own and reads the whole body into
  memory (`rontolisp-native/runner/src/http/mod.rs`, `Reply`), and queues every request
  that arrived whole (`serve.rs`). So one import can wait for all of it: "until a reply
  is complete, a request has arrived or so many milliseconds have passed" -- a sequence
  number the module hands back, so that an event between its look and its wait is not
  lost -- and one can ask a reply whether it is complete without waiting.
- The fetch's future has to be askable: the handle is inside the deferred thunk
  (`HostFetchLibrary.runnerSource`), and `%future-settled-p` answers nil for a deferred
  future.
- `java.lisp`'s loop then waits in that import where no thread can go on, until the
  soonest timed thread at most, and looks at its replies in flight and its server after
  it; `%java-http-launched` (already what a scheduled program's send is lowered to) is
  where an exchange joins the replies in flight. `java.lisp` has no `rontolisp-native`
  variant: Preview 1 has no such import (a seam in `http-serve.lisp` / the fetch library,
  as `%http-serve-step` is).
- Requests at once in one module would need each request a frame of its own answering
  later, which the reactor transport (`%http-reactor-handle`) does not have: decide
  whether the nested step stays.

## Done when

The `waiting` program runs on the `--native` leg, a `main` asking its own server through
the client is answered there, and the two deviations are out of `doc/*/java/deviations.md`
-- or the numbers say why not, in `.kb/java-frontend.md`.

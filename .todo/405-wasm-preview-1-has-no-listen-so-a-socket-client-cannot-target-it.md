# 405. WASM Preview 1 has no `listen`, so a socket client cannot target it

Difficulty: High

Found by the dexador spike (`.todo/396`). Compiling a dexador program to
Preview 1 used to stop at the frontend:

```
error: listen requires the interpreter, the JVM backend or a --component
       socket stream (no non-blocking input probe exists on this WASM target)
```

**Update (2026-08-16, todo-114):** the compile error became a CALL-time error
(same message, raised when the call runs) -- the usocket shim's new
`wait-for-input` carries a `listen` call site spliced unpruned into every
usocket program, so it had to build as dead code (the todo-195 policy, the
same transition tcp-connect made). That moves the refusal, it does NOT decide
this item: options 1-3 below are all still open, and option 1 should be
costed together with `.todo/415`'s future-race primitive (polling a read
future's settled state is the same machinery).

Interpreter, JVM and WASM `--component` all run the program; Preview 1 is the
only backend that cannot, which breaks the "all four backends" rule
(`CLAUDE.md`) for the whole class of socket clients, not just this one.
dexador's use is the ordinary one -- drain whatever is left on a kept-alive
connection before returning it to the pool:

```lisp
(loop while (ignore-errors (listen underlying-stream))
      do (read-byte underlying-stream nil nil))
```

## The decision to take

There are three honest answers and this item is to pick one, not to assume the
first:

1. **Give Preview 1 a probe.** WASI Preview 1 has `poll_oneoff` and
   `fd_read` on a non-blocking fd; whether the rontolisp P1 socket stream can
   expose either through the existing stream table (`.kb/tcp-sockets.md`) is
   the question to answer first, because everything else is a workaround.
2. **Answer `NIL` and document it.** `listen` answering "nothing buffered" is
   a legal CL answer, and the loop above would simply not drain. It is also a
   LIE on a socket with data pending, and `.todo/114`'s Tier 1 already weighs
   the same trade-off for `usocket:wait-for-input` -- take both decisions
   together, or neither.
3. **Keep the compile error and say so.** Declare Preview 1 out of scope for
   socket CLIENTS and write it into `.kb/tcp-sockets.md` as a divergence WITH
   ITS REASON, so the next visitor can tell when it can be retired.

Whichever wins, the message must name what the user can do (today it names the
three backends that work, which is already better than most).

## Note

The `--component` backend does have the probe, and it is what makes dexador
work there -- so this is a P1-only hole in an otherwise complete socket story,
and option 1 is worth costing before falling back to 2 or 3.

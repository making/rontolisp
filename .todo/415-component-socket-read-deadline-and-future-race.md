# 415. A component socket has no read deadline (no future-race primitive)

Difficulty: High

Split out of `.todo/114` Tier 1b (2026-08-16). `rontolisp:tcp-set-timeout` is
real on the interpreter/JVM (`SO_TIMEOUT`) but SIGNALS on the WASM component:
wasi:sockets@0.3.0 exposes no receive-timeout knob and the socket reads are
scheduler futures with no deadline argument (`.kb/tcp-sockets.md`, the
read-deadlines section). The refusal is deliberate (a timeout that never fires
is the failure mode a client sets it to avoid), but it costs two real things:

- **dexador's DEFAULT `(dex:get url)` fails on the component** -- its
  `*default-read-timeout*` is 10, and
  `(setf (usocket:socket-option conn :receive-timeout) 10)` reaches the
  signalling primitive at connect time. The workaround is
  `:read-timeout nil` (dexador guards the setf with `(when read-timeout ...)`),
  which `.todo/396`'s definition of done should not have to carry forever.
- **`usocket:wait-for-input` cannot be honest there** -- it returns
  immediately claiming readiness because component `listen` sees only the
  chunk buffer and a poll would spin forever on data waiting host-side.

## The essential fix

A scheduler-level FUTURE RACE (or deadline) primitive: the timer future
already exists (`rontolisp:wait-for` over wasi:clocks/monotonic-clock), and a
read is already a future -- what is missing is "settle on whichever of these
settles first". With it:

- `tcp-set-timeout` stores the deadline in the `*sock-table*` entry and
  `%sock-fill` races the stream read against a timer, signalling the same
  catchable error shape the interpreter/JVM raise;
- `wait-for-input` can race a chunk-refill probe against the timeout instead
  of claiming readiness;
- `listen` on the component could even become kernel-honest (start the read
  future, poll its settled state) -- which is `.todo/405`'s option 1 shape for
  Preview 1, so cost them together.

Design questions to answer first: what happens to the LOSING future (a
pending stream read cannot be cancelled without dropping the stream -- for a
read timeout that is acceptable, the client abandons the connection; for
wait-for-input it is not, so that one needs a probe that does not consume),
and whether the race lives in the scheduler (`_sched_*`) or as a Lisp-visible
combinator.

When this lands, retire the signalling arm in `sockets.lisp`'s
`tcp-set-timeout`, the wasm claim branch in `usocket:wait-for-input`
(usocket.lisp), and the corresponding notes in `.kb/tcp-sockets.md` and the
doc pages -- the divergences carry this todo as their re-evaluation trigger.

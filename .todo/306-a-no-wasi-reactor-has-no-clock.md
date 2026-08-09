# `--no-wasi`: the clock is the last load-time blocker

Difficulty: Medium

Since todo-284 a `--no-wasi` module answers `random`, `uiop:getenv` and the
filesystem lookups, and only two WASI slots refuse: standard input (`fd_read`,
a bare trap) and the clock. The clock is now the one that actually blocks a
real library.

## The measurement (2026-08-09)

```bash
cat > mw.lisp <<'EOF'
(ql:quickload '("clack" "clack-handler-reactor" "lack" "lack-request"
                "lack-middleware-session"))
(defpackage :mw (:use :cl))
(in-package :mw)
(defun app (env) (declare (ignore env)) (list 200 '(:content-type "text/plain") (list "ok")))
(defvar *app* (lack:builder (:session) #'app))
(clack:clackup *app* :server :reactor :use-thread nil)
EOF
rontolisp mw.lisp -o mw.wasm --no-wasi --optimize=size
node -e '...instantiate + _initialize...'   # -> RuntimeError: unreachable
```

`lack-middleware-session` reads the clock while it loads, so the error is
signalled from a top-level form: `GET-UNIVERSAL-TIME requires a host clock,
which --no-wasi excludes ...`. It is a real Lisp condition now rather than a
bare `unreachable` (`strings mw.wasm | grep 'requires a host'` finds it), but
nothing is there to catch it and the message goes to the output sink, so what
the user sees is still `RuntimeError: unreachable` from `_initialize`.

Everything else in that stack works: `lack:builder` composes, and
`lack.request:request-parameters` reads both a query string and a urlencoded
POST body on a `--no-wasi` build (measured the same day, node 24).

## Why the clock was left refusing

`.kb/wasm-export-no-wasi.md` states the rule the stubs follow: a stub answers
when the answer is **true of this module**, and refuses when answering would
invent a value the program cannot tell from a real one. "No environment
variables" and "no files" are true of a reactor. "The time is 0" is not true of
anything -- it names 1970 -- so `clock_time_get` kept its trap and the three
Lisp clock built-ins lower to a named call-time error instead.

That reasoning is still sound. What has changed is that the *host* now has a way
to hand things in: `__ronto_seed_random (i64) -> ()` (todo-284) is an exported
hook a JavaScript host calls before `_initialize`. The clock has the same shape
of answer available, and it is a better one than a stub could invent.

## Options

1. **A host-set clock, symmetric with the seed hook.** Export
   `__ronto_set_time (i64) -> ()` taking nanoseconds since the Unix epoch;
   `clock_time_get` then reports that cell. The host really does know the time,
   so this is not invention -- it is the same "the host hands it over" move the
   seed hook already makes, and it is the only option that makes
   `lack-middleware-session` load.
   Worth noting while deciding: a Cloudflare Worker's own clock is **frozen**
   for the duration of a request (a deliberate timing-attack mitigation), so a
   single value that does not advance between host calls is not a degraded
   imitation of that platform's clock -- it is exactly what that platform has.
   A host that wants it to advance calls the setter per request, the way
   `src/index.js` could call it next to `__ronto_seed_random`.
   Open sub-questions: does the monotonic clock (`get-internal-run-time`) get
   the same cell or stay refusing? Does an unset clock keep signalling (yes,
   almost certainly -- the constant-zero start state that is fine for `random`
   is exactly the 1970 lie here).
2. **Leave it and document.** The `.kb` already records the reason; the
   examples' Limitations sections already say "no clock". Cheapest, and it
   keeps a library that timestamps at load time unloadable.
3. **A monotonic counter.** Rejected on sight, recorded so it is not
   re-proposed: a counter that advances per call is not a clock, and
   `get-internal-real-time` differences computed from it would be meaningless
   numbers that look like milliseconds.

## The general safety net that is still missing

Independently of the clock: there is no BUILD-time diagnostic for "this
`--no-wasi` module reaches a refusing primitive from a top-level form". Finding
the four in the `lack-request` chain (`random`, `getenv`, `path_open`,
`clock_time_get`) took four separate node runs plus reading wasm function
indices out of a backtrace, each time. The reachability information is a call
graph over the emitted module rooted at `FUNC_START` -- `am.ik.wasm.WasmTreeShaker`
already computes reachability, but exposes no "which functions are reachable
from root R" query, so this needs a small API there and then one line of build
output naming the primitive. Entry-shape independent (it works for the reactor
component, which has no `_initialize` to fail in), and it stays useful however
option 1-3 above is decided.

## Done when

- A `--no-wasi` program that reads the clock at load time either loads, or
  fails with a diagnostic that names the primitive at BUILD time -- verified on
  V8 (node is enough), not inferred.
- `.kb/wasm-export-no-wasi.md`'s stub table and its rule are updated together:
  if the clock starts answering, the rule sentence has to say why that is not a
  fabrication, or the rule has to change.
- `examples/cloudflare-workers/httpbin-clack/README.md` loses (or restates) the
  "One thing in that stack is still out of reach" paragraph, and the
  Limitations lists that say "no clock" across the Worker examples follow.

## Related

`.kb/wasm-export-no-wasi.md` (the stub table, the seed hook, the rule),
`.kb/clack.md`, `examples/cloudflare-workers/httpbin-clack/README.md`
(the measurement chain), `.todo/280`, `.todo/281`.

# ONE Clack source across every host (the `:server` designator is still per-host)

Difficulty: High

`clackup` on a host-driven reactor WORKS today: `.todo/285` shipped the
`clack-handler-cloudflare-workers` backend whose `run` stores the application and
leaves a `rontolisp::%http-reactor` marker that `eval/HttpReactorInliner`
turns into the synthesized `handle-request` export (`.kb/clack.md`, "The
host-driven reactor"). The original symptom of this item -- `_initialize
TRAPPED: RuntimeError: unreachable` -- is gone, and it was never `clackup`
failing: it was the `:rontolisp` backend's wasm `run` delegating to the
`rontolisp:http-handler` directive, which means "bind a socket" everywhere.

What is left is this item's SECOND acceptance criterion, and only that: one
source file that runs unchanged on the interpreter, the JVM, `wasmtime serve`
and a reactor. The `#+`/`#-` is already gone; the `:server` designator is not:

```lisp
;; examples/net/httpbin-clack.lisp:111
(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)

;; examples/cloudflare-workers/httpbin-clack/worker.lisp:130-136
(ql:quickload "clack-handler-cloudflare-workers")
(clack:clackup #'app
               :server :cloudflare-workers
               :use-thread nil
               :use-default-middlewares nil)
```

Everything above those lines is byte-identical between the two files (pinned by
the `diff` in `examples/cloudflare-workers/httpbin-clack/README.md`). The tail
is the whole remaining gap.

## The two routes

- **Make `:rontolisp` itself reactor-aware.** Its wasm `run` would choose
  between the `http-handler` directive and the `%http-reactor` marker at COMPILE
  time -- the information is there, `--no-wasi` is a compiler flag, and both
  front-ends (`HttpLibrary`, `HttpReactorInliner`) already read a directive
  nested in a shim's `run`. Keeps every existing source working untouched.
- **Teach `clackup` a `:server :auto`.** Explicit at the call site, and it leaves
  `:rontolisp` meaning "owns a socket". Costs a source edit to port a program,
  which is the thing this item is trying to remove.

Either way the second `ql:quickload` line has to go too, or resolve to whichever
backend the target host needs -- a source that names
`clack-handler-cloudflare-workers` is still a per-host source even if the
designator becomes uniform.

## The keywords

`:use-thread nil` is a genuine per-host fact and stays (the interpreter and the
JVM have `:thread-support`, so `clackup` would otherwise apply `run` -- the
`*app*` store -- on another thread and race the next form; on WASM it is already
the default).

`:use-default-middlewares nil` is NOT: it is only waiting on `.todo/283`.
lack's `backtrace` middleware prints to `*error-output*`, and
`(symbol-value '*error-output*)` is unbound on the compile paths, which turns a
handled error into a WASM trap. Drop the keyword when 283 lands.

## Non-goals as scoped by the author

Not the blocking behaviour of `run` on the JVM and the interpreter: they own a
socket and blocking there is correct (hunchentoot parity). Per the repository's
own rule, treat this scoping as the author's, not a ratified constraint -- if
the essential fix needs to touch it, widen it and say so.

## Done when

- ONE source compiles and serves on the interpreter, the JVM, `--component`
  (`wasmtime serve`) and a reactor host, with no `#+`/`#-`, no per-host `:server`
  and no per-host `ql:quickload`. Verified on each of the four, not inferred --
  workerd via `wrangler dev` for the reactor leg.
- `examples/net/httpbin-clack.lisp` and
  `examples/cloudflare-workers/httpbin-clack/worker.lisp` become the same file
  end to end, or the README explains why the tail must still differ.
- `.kb/clack.md` records how the backend is chosen and WHY, so the next visitor
  can tell whether the reason still holds.

## Settled earlier (do not re-litigate)

From `.todo/285`, deployed and curl'd rather than inferred:

- the exported entry point is the JSON envelope (`handle-request`), and the
  envelope shape is an API now -- `.kb/clack.md` documents which parts are
  load-bearing;
- the user asks for the reactor with the `:server` designator, not by inference
  from `--no-wasi` alone;
- `run` returning at once and `stop` returning nil is the reactor's behaviour;
  `:port` / `:address` are ignored, as under `--component`.

## Related

`.kb/clack.md` (the two handler backends, the discovery protocol, the marker and
its synthesis), `.kb/http-server.md`, `.todo/283`, `.todo/290`, `.todo/284`.

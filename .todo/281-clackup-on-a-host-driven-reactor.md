# `clack:clackup` on a host-driven reactor (a handler backend that owns no socket)

Difficulty: High

On a host that calls an exported function instead of handing us a socket -- a
Cloudflare Worker, and any other `wasm-export` reactor embedding -- a Clack
application runs fine (`.todo/280`), but `clack:clackup` itself does not. The
program COMPILES and then traps at instantiation:

```
_initialize TRAPPED: RuntimeError: unreachable
```

because the shim's `#+rontolisp-wasm` `run` delegates to the `rontolisp:http-handler`
directive, and on Preview 1 -- which `--no-wasi` output belongs to -- that directive
is a call-time error by design (no incoming TCP, `.kb/tcp-sockets.md`).

So today the Worker example calls the application through a handler backend
(`clack-handler-cloudflare-workers`, `.kb/clack.md`) instead of through `clackup`.
That is four forms rather than fifteen lines now, but it still means the source
cannot be the same source that runs under `wasmtime serve` or on the JVM: the entry
point differs. Closing that is what this item is for.

## Why the shape is already close

`clack.handler.rontolisp:run` on the WASM leg does exactly the right thing already --
it stores the application in `*app*` and returns without blocking:

```lisp
#+rontolisp-wasm
(defun clack.handler.rontolisp:run (app &key ...)
  (setf clack.handler.rontolisp::*app* app)
  (rontolisp:http-handler 'clack.handler.rontolisp::%app port :raw-body :buffered))
```

Only the delegation TARGET is wrong for a reactor. The `--component` serve path
already demonstrates the missing half: `HttpLibrary` detects the directive nested in
a defun, lowers the call site to nil, and APPENDS a synthesized `%serve-dispatch`
bridge plus its `wasm-export` after the program so the package-qualified handler name
resolves against the shim's own spliced defpackage. A reactor needs the same
synthesis with a different bridge -- one that takes the request as a value rather
than off a socket.

## The design questions to settle first

These are the reason this is not a mechanical change; none has an obvious answer:

- **What is the exported entry point?** The JSON envelope
  (`{method,target,headers,body}` in, `{status,headers,body}` out) is a convention
  this repository invented for `cloudflare-workers/httpbin`, not anything Clack or
  WASI defines. Committing `clackup` to it makes it an API. The alternatives are a
  `wit-export` world (typed, `--component`, but then jco cannot drive a stackful
  async export -- see `examples/cloudflare-workers/README.md`) or letting the user
  declare the export and only wiring the dispatch.
- **How does the user ask for it?** A new `:server` designator (`:rontolisp-reactor`?)
  is explicit and keeps `:rontolisp` meaning "owns a socket"; inferring it from
  `--no-wasi` is invisible but needs no source change to port a program. The second
  is more attractive precisely because it keeps ONE source running on every host,
  which is the point of the item.
- **What does `run` return, and what does `clack:stop` do?** Both are meaningless on
  a reactor. The WASM leg already returns nil from `stop`; `run` returning at once is
  already its behaviour.
- **`:port` / `:address`** are ignored, as they already are under `--component`.

## Non-goals as scoped by the author

Not the JVM or interpreter backends: they own a socket and `run` blocking there is
correct (hunchentoot parity). Not `--component`, which has a real WASI HTTP host.
Per the repository's own rule, treat this scoping as the author's, not a ratified
constraint -- if the essential fix needs to touch them, widen it and say so.

## Done when

- `(clack:clackup #'app :server ... )` in a `--no-wasi` program instantiates without
  trapping, and the exported entry point answers requests -- verified on V8 (node is
  enough; workerd via `wrangler dev` for the real check), not inferred.
- ONE source compiles and serves on the interpreter, the JVM, `--component`
  (`wasmtime serve`) and a reactor host, with no `#+`/`#-` in the user program. That
  is the acceptance criterion; anything less leaves the Worker on its own dialect.
- `examples/cloudflare-workers/httpbin-clack/app.lisp` (`.todo/280`) drops its
  hand-written adapter and calls `clackup`, or the README explains why it should not.
- `.kb/clack.md`'s "WASM component / Preview 1" section gains the reactor leg, with
  the reason for the divergence written down so the next visitor can tell whether it
  still holds.

## Measurement to reproduce the current state

```bash
cat > p.lisp <<'EOF'
(ql:quickload "clack")
(defun app (env) (declare (ignore env)) (list 200 '(:content-type "text/plain") '("hi")))
(rontolisp:wasm-export 'ping :params '() :returns :s32)
(defun ping () 42)
(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
EOF
rontolisp p.lisp -o p.wasm --no-wasi --optimize     # compiles: 1,476,101 B
node -e 'const m=new WebAssembly.Instance(new WebAssembly.Module(require("fs").readFileSync("p.wasm")),{});
         try{m.exports._initialize();console.log("OK",m.exports.ping())}catch(e){console.log("TRAP",e.message)}'
# -> TRAP unreachable
```

## Update (2026-08-07): the measurement above is too broad

The `TRAP unreachable` reproduced above is NOT clackup failing on a reactor. It is
(a) the `:rontolisp` backend's wasm `run` delegating to the `rontolisp:http-handler`
directive, and (b) clackup's two `format t` calls hitting the stubbed `fd_write`.
With a backend whose `run` only stores the app, and with `:silent t :debug nil`,
`clackup` runs on a `--no-wasi` reactor TODAY and the exported function answers
requests. `.todo/285` is that narrow slice, with the full measurement and the one
decision left (what to do about the two prints); `clack-handler-cloudflare-workers`
already exists as the backend. Narrow or close this item if 285 lands.

## Update (2026-08-07): 285 landed -- what is left of this item

`.todo/285` shipped, so `(clack:clackup #'app :server :cloudflare-workers
:use-thread nil :use-default-middlewares nil)` IS the whole Worker half today
(deployed and curl'd, not inferred). Of the design questions above, two are now
settled by that: the exported entry point is the JSON envelope (`handle-request`,
synthesized by `eval/HttpReactorInliner` from a `rontolisp::%http-reactor` marker
the handler backend leaves in `run`), and the user asks for it with the `:server`
designator rather than by inference from `--no-wasi`.

**What this item still wants is only its second acceptance criterion**: ONE
source with no `#+`/`#-` AND no per-host `:server`, so the same file runs under
`wasmtime serve`, on the JVM, on the interpreter and on a reactor. That means
either making `:rontolisp` itself reactor-aware (its wasm `run` would have to
choose between the `http-handler` directive and the reactor marker at COMPILE
time -- the information is there: `--no-wasi` is a compiler flag) or teaching
`clackup` a `:server :auto`. Also still open, and now the visible remainder of
the gap: the two keywords. `:use-thread nil` is a genuine per-host fact, but
`:use-default-middlewares nil` is only waiting on `.todo/283`.

## Related

`.kb/clack.md` (the handler backend and its discovery protocol -- the package must
NOT be pre-seeded, the system answers to two names, the compile paths splice the shim
eagerly), `.kb/http-server.md`, `.todo/285`, `.todo/280`, `.todo/279`.

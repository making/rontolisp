# e31. Ring application on Cloudflare Workers

Difficulty: Low

Depends on e30.

The Ring adapter already has the `--no-wasi` reactor leg (`ring.adapter.rontolisp/run-server`
lowers to `rontolisp::%http-serve`, `.kb/clojure-frontend.md` "Ring adapter"), so a Worker
needs no new runtime: only a deployable example, the counterpart of
`examples/cloudflare-workers/hello-clack-one-source`.

Measured 2026-10-08 (`wrangler dev` 4.148.0, exec jar of 06:23): `examples/clojure/ring-hello.clj
-o src/worker.wasm --no-wasi --optimize=size --emit-js-glue` (419,012 B, 0 imports), with the
e30 calls guarded by hand, served `/`, `/greet?name=`, urlencoded POST `/greet`, a
`text/plain` POST `/echo` (UTF-8 round trip), `/home` 302 and 404, all as on the other
backends.

## Plan

1. `examples/cloudflare-workers/ring-hello-one-source/`: no Clojure of its own; `build.sh`
   compiles `../../clojure/ring-hello.clj` (as `hello-clack-one-source` does
   `net/hello-clack.lisp`), `wrangler.jsonc`, `package.json`, the byte-identical
   `src/index.js`, the generated `src/worker.js` checked in, a README.
2. Pin it: the `examples.yaml` entry of `clojure/ring-hello.clj` already compiles `--no-wasi`;
   add whatever the `cloudflare` examples slice runs for the one-source Clack sibling
   (`-Drontolisp.examples.only=cloudflare`, `.kb/running-backends.md`), and the
   `HostGlueEmitterTest` pin of the checked-in glue if the siblings have one.
3. Docs: the Workers section of `doc/{en,ja}/clojure/reference/ring.md` (build command,
   `wrangler dev` / `deploy`), and `examples/cloudflare-workers/README.md`.

Not this item: Worker `env`/`ctx` bindings (KV, D1, secrets) in the request map -- the
transport has no vendor-specific surface (`.kb/clack.md`, "Why the designator names the
TRANSPORT").

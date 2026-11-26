# hello-session-one-source — a servlet's sessions in Workers KV

There is **no Java in this directory**. The program is
[`java/hello-session`](../../java/hello-session)'s
[`HelloSession.java`](../../java/hello-session/src/main/java/HelloSession.java)
*itself* — a lambda servlet counting the visits of a session, the file a JDK runs as
it stands. Its sessions are kept by
[`servlet-httpserver-keyvalue`](https://github.com/making/rontolisp/tree/develop/servlet-httpserver-keyvalue)
in `wasi:keyvalue/store@0.2.0-draft2`, and here that interface is answered by the
Worker's KV namespace `KEYVALUE`: a visit counts on in the next isolate and after the
next deployment.

```bash
./build.sh          # ../../java/hello-session/src/main/java/HelloSession.java -> src/worker.wasm + src/worker.js + src/index.js
npx wrangler dev    # http://localhost:8787, the namespace kept in .wrangler/
```

```console
$ curl -c jar -b jar http://localhost:8787/
Hello World! Visit 1 of this session.
$ curl -c jar -b jar http://localhost:8787/
Hello World! Visit 2 of this session.
```

## Deploying to Cloudflare

Needs a Cloudflare account; the free plan is enough. From this directory:

```bash
npm install                # wrangler, from package.json
npx wrangler login         # once: opens the browser to authorize wrangler
./build.sh                 # src/worker.wasm, src/worker.js, src/index.js
npx wrangler deploy
```

The first deploy creates the KV namespace itself: `wrangler.jsonc` names the binding
`KEYVALUE` and no `id`, so wrangler provisions a namespace named
`<worker name>-keyvalue` and binds it. A later deploy keeps that namespace
(`env.KEYVALUE (inherited)`), so the sessions outlive a deployment.

```console
$ npx wrangler deploy
Total Upload: 965.29 KiB / gzip: 240.27 KiB
The following bindings need to be provisioned:
Binding              Resource
env.KEYVALUE         KV Namespace
Provisioning KEYVALUE (KV Namespace)...
🌀 Creating new KV Namespace "rontolisp-hello-session-one-source-keyvalue"...
✨ KEYVALUE provisioned 🎉
...
Deployed rontolisp-hello-session-one-source triggers (0.40 sec)
  https://rontolisp-hello-session-one-source.<your-subdomain>.workers.dev
```

```console
$ curl -c jar -b jar https://rontolisp-hello-session-one-source.<your-subdomain>.workers.dev/
Hello World! Visit 1 of this session.
$ curl -c jar -b jar https://rontolisp-hello-session-one-source.<your-subdomain>.workers.dev/
Hello World! Visit 2 of this session.
```

To use a namespace of your own instead (one shared with another Worker, say), create
it and put its `id` under the binding in `wrangler.jsonc` before the deploy:

```bash
npx wrangler kv namespace create KEYVALUE   # prints the id
```

Looking inside, and the log:

```bash
npx wrangler kv key list --binding KEYVALUE --remote   # servlet-httpserver:session:<id> per session
npx wrangler tail                                      # what the program prints, live
```

Removing it all — the Worker, then the namespace (the id from
`npx wrangler kv namespace list`):

```bash
npx wrangler delete
npx wrangler kv namespace delete --namespace-id <id>
```

## How the store reaches KV

A `--no-wasi` module imports `wasi:keyvalue/store` the way a component's core module
imports it, through the canonical ABI: its results, options and bucket handles are
more than a flat core import carries. The generated `src/worker.js` lifts and lowers
those values itself, and its `defaultHost()` answers each member with a call of the
KV namespace the store's identifier names — the default store `""` is the binding
`KEYVALUE` in [`wrangler.jsonc`](wrangler.jsonc).
[`wasi:keyvalue` over Workers KV](../../../doc/en/guides/wasm-host-boundary.md#wasikeyvalue-over-workers-kv)
has the details.

What Workers KV is, a session is:

- **A request waits for KV's reads, not its writes.** A visit reads the session
  (`get`, 3-7 ms where it was read before) and, the first time an isolate serves a
  session, the sweep key; the session's `put` (0.15-0.25 s) goes out after the
  response (`ctx.waitUntil`). Measured on 2026-11-25 from one client, colo DFW,
  9 visits each:

  | | the handler | the response's first byte |
  | --- | --- | --- |
  | the response waiting for the `put` | 160-207 ms | 235-402 ms |
  | the `put` after the response | 3-12 ms | 61-224 ms |

- **A key is written once a second at most.** The session store writes the
  session at every change — each `setAttribute`, and the access time once it moved
  by a tenth of the session's interval — and Workers KV takes one write a second to
  a key, so the glue holds a request's writes, puts each key once after the
  response, and a key's next put waits a second after its last, the newest value
  replacing the one waiting. A put that fails is logged (`npx wrangler tail`).
- **Requests sent back to back may read an older visit.** A request reaching
  another isolate before the previous visit's `put` is done (about 0.2 s after its
  response) reads the session before it: ten requests sent one after another
  counted 1 2 3 4 5 4 4 4 6 5, and with 0.3 s between them 1 to 10.
- **A session is eventually consistent.** A write is usually seen at once where it
  was made, and up to a minute later elsewhere: a browser whose requests reach one
  location counts on; requests reaching two may read an older visit count.
- **Two requests of one session at once**: the last write wins, as on every store.

## One source, a JDK and the hosts

| Build | Where the sessions are |
| --- | --- |
| a JDK (`mvn exec:exec` in [`java/hello-session`](../../java/hello-session)) | in memory, as long as the process |
| interpret, `-o Hello.class` | in memory, as long as the process |
| `-o hello.wasm --component` on sluice-wicket-runtime-wasi (`?wasi=keyvalue`) | the host's SQLite file |
| `-o worker.wasm --no-wasi`, this directory | Workers KV |

`wasmtime serve -S keyvalue=y` (wasmtime 51) links the first draft only and refuses
the component; a `--native` executable imports no WIT interface, so the program is
refused there.

## What's in here

| File | Purpose |
| --- | --- |
| [`../../java/hello-session/src/main/java/HelloSession.java`](../../java/hello-session/src/main/java/HelloSession.java) | **The whole program** — not in this directory, deliberately |
| [`build.sh`](build.sh) | `--no-wasi --host-random --host-output --optimize=size --emit-worker-entry` over that file, the container, the store and `@WitImport` from this checkout's sources, the Servlet API from its sources jar |
| [`wrangler.jsonc`](wrangler.jsonc) | The Worker, with the KV namespace binding `KEYVALUE` |
| `src/index.js` | Written by `./build.sh` (`--emit-worker-entry`) on the first build: three lines over the generated `worker()`. Yours to edit — `worker(module, { keyvalue: { "": "SESSIONS" } })` keeps the sessions in another binding |
| [`src/worker.js`](src/worker.js) | The boundary, GENERATED by `--emit-js-glue` and pinned by `HostGlueEmitterTest`. Do not edit; `./build.sh` rewrites it |
| `src/worker.wasm` | A build product — run `./build.sh` first |

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello-session-one-source/build.sh
```

# The simple Worker pays for a streaming boundary it does not use

Difficulty: Medium

`--no-wasi` takes the request and response bodies OUT of the reactor envelope
unconditionally (`HttpReactorInliner.bodyOutOfBand` = `reactor && WASM_GC`), and
`--host-fetch` does the same to the reply body. What that buys is real -- a
binary body crosses exactly, a large one never doubles linear memory, a Worker
can forward a streamed upstream response (`.kb/clack.md`, `.kb/fetch-http.md`).
What it costs is paid by every program, including the ones that fetch one JSON
document and answer one JSON document: four host functions and three pieces of
host-side cursor state, none of which such a Worker has any use for.

The two shapes should both be reachable, and the SIMPLE one should be the one
with nothing in it.

## What the spike established (2026-08-13)

One program -- a ticker Worker: one outgoing fetch, one JSON answer -- built
both ways, each driven through the CURRENT generated glue, all run on node 24
`--experimental-wasm-jspi`:

| | imports | `index.js` | host-side state |
| --- | --- | --- | --- |
| streaming (today) | `env.fetch`, `env.readResponseBody`, `env.readRequestBody`, `env.writeResponseBody` | 66 code lines | the upstream reader, `requestBody`, `responseChunks`, the `lisp.drop` call, the `serially` critical section |
| envelope | `env.fetch` | 46 (45 once the mode drops the declaration) | none |

Module size: 148,533 B vs 147,959 B (48,912 vs 49,029 gzip -9 -n). **The
boundary is not a size decision** -- do not sell it as one.

The 20 lines are not the point; the STATE is. Both defects the todo-340 review
turned up on this surface were state-lifetime bugs (a reply cursor outliving its
source, and instance selection outside the critical section). An envelope host
has no cursor to outlive anything.

### The envelope shape can be taken further than "fewer lines"

Both halves that remain are FIXED by the transport rather than chosen by the
program, so both can be EMITTED:

- **`env.fetch`** -- `--host-fetch` fixes both directions of its envelope
  (`FetchResponseShape`, pinned by `HostFetchLibraryTest`). Its host half is the
  same twenty lines in every program: call the platform `fetch`, answer
  `{status, headers, body}`, answer `{error}` on a throw. `dog-fetcher`'s and
  the ticker's differ in nothing.
- **the reactor envelope** -- `{method, target, headers, body, scheme,
  remote-addr}` in, `{status, headers, body}` out, owned by `%http-make-env` and
  documented as an API (`.kb/clack.md`). Mapping a `Request` onto it and a
  `Response` off it is transport work, not program work.

With both emitted, the whole Worker is:

```js
import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
```

**Verified**: hand-editing the generated glue into exactly that shape (the
derived `env.fetch`, an `api.fetch(request)` over the envelope, and a `worker()`
wrapper that instantiates on the first request and retires a trapped instance)
runs the ticker end to end under JSPI, including two overlapped calls going
through the glue's own promise queue. The suspension, the `promising` entry and
the serialisation all stay inside the generated file.

## What already works, and what is in the way

- **The Lisp half needs nothing.** `%http-reactor-dispatch`'s body source and
  sink are `&optional` and its docstring already states the fallback ("a host
  that leaves them out keeps the envelope's own `\"body\"` key in both
  directions"); `%http-reactor-envelope` emits the `:body` key when there is no
  sink; `%host-fetch-body`'s `in-band` argument is already the head's own
  `"body"` key. Every one of those paths is live and pinned -- they are what
  `--component`, `--no-gc`, a WASI command module, the interpreter and the JVM
  run today (ci-spec `http-reactor-body-source`, `WasmHostFetchBodyE2eTest`'s
  in-band case, `examples/cloudflare-workers/httpbin-component`).
- **`--emit-js-glue=envelope` already parses and is SILENTLY IGNORED.** The flag
  sits in `CliOptions.noValueKeys`, which (like `--optimize`) accepts a value
  through the `=` form. So the CLI surface costs nothing -- but an unvalidated
  mode name currently compiles the wrong boundary without a word. Refusing an
  unknown value is part of this item, not a nicety.
- **Selecting the envelope TODAY takes two lines of internals**, which is the
  wrong end of the tool:

  ```lisp
  (rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)
  (defun handle-request (json) (rontolisp::%http-reactor-dispatch json))
  ```

  It works -- taking the entry point over makes `HttpReactorInliner` synthesize
  nothing (`declaresExport`), and the module then imports zero functions
  (verified with `WebAssembly.Module.imports`, GET and a POST body round-tripped
  both ways). It is not an answer: it asks a user who wanted the SIMPLER
  boundary to know a `rontolisp::` name to get it.

## The work

1. **`RontoLispCli`** -- read the mode off `--emit-js-glue`, refuse an unknown
   one by name, and keep bare `--emit-js-glue` meaning the streaming boundary.
   `CliOptions` needs no change.
2. **`HttpReactorInliner.bodyOutOfBand`** -- the mode joins `backend` and
   `reactor`. One `if`; the `bodyImport` block and the two extra `dispatch`
   arguments already hang off it.
3. **`HostFetchLibrary`** -- guard the reply-body import block the same way and
   collapse `%host-fetch-body` to its `in-band` arm (the `%host-fetch-open`
   counter and `%host-fetch-pull` exist only for the split).
4. **`reader/Features`** -- a feature the mode adds (`rontolisp-body-imports`,
   present only in the streaming mode) so a HAND-WRITTEN reactor can follow it.
   `examples/cloudflare-workers/httpbin/worker.lisp` guards its own imports with
   `#+(and rontolisp-reactor (not rontolisp-component))` today; that double
   negative becomes `#+rontolisp-body-imports` and starts meaning what it says.
5. **`HostGlueEmitter`** -- `Surface` gains the two facts the build already has
   (this is a `--host-fetch` build; this export is the reactor envelope), and
   emits the derived `env.fetch`, the `fetch(request) -> Response` adapter and
   the `worker(module)` wrapper. A host may still override any of them: the
   generated `env.fetch` is a DEFAULT, not a replacement for `host.env.fetch`.
6. **A new example** -- `examples/cloudflare-workers/btc-ticker`: fetch
   `https://api.bitflyer.com/v1/ticker?product_code=BTC_JPY` and answer the
   current BTC/JPY price. It is the hello-world of the envelope mode and its
   `index.js` is the three lines above. `dog-fetcher` STAYS on the split
   boundary -- the pair is then a controlled comparison of the two, like
   `httpbin` / `httpbin-clack` is for clack.
7. **Tests** -- a `HostGlueEmitterTest` byte-pin of the new example's glue (the
   `theCheckedInWorkerGlueIsWhatAFetchingReactorBuildWrites` rule, second
   instance), the emitted-`env.fetch` and `worker()` paths in
   `WasmHostGlueE2eTest` against a LOCAL stub upstream (the spike used
   `node:http` on a fixed port -- an example that reaches the real API must not
   be what a test run depends on), and a `RontoLispCliTest` case per refused
   mode name.
8. **Docs / `.kb`** -- the mode belongs in `doc/{en,ja}/guides/wasm-host-boundary.md`
   beside the `--emit-js-glue` section, and the "which boundary, and what each
   costs" table belongs in `.kb/wasm-import.md` with the measurements above.
   `.kb/clack.md`'s `bodyOutOfBand` paragraph and `.kb/fetch-http.md`'s ":body
   split" paragraph both currently state the split as unconditional; both are
   re-evaluation triggers this item fires.

## Open questions

- **Does the mode belong on `--emit-js-glue` at all?** It changes the MODULE's
  imports, so a user who hand-writes the host cannot reach it without also
  asking for a glue file they will not use. The alternative is a boundary flag
  of its own with `--emit-js-glue` staying a boolean. Starting point (the
  requester's): the value form, because in practice the two always move
  together and the generated host is most of the point.
- **The mode names.** `envelope` / `streaming` matches the vocabulary already in
  the `.kb` files (in band / out of band, "the envelope is an API now"). Do not
  name the modes `simple` / `complex` -- one of them is a value judgement and
  the other is wrong.
- **How far the generated Worker goes.** A Cloudflare `fetch` handler takes
  `(request, env, ctx)`, and `remote-addr` from `cf-connecting-ip` is a
  Cloudflare-ism in an otherwise runtime-neutral file. Decide whether `worker()`
  is Cloudflare-shaped or whether the platform bits are host-supplied.
- **Whether the `httpbin-*` family moves.** They are on hand-written glue
  predating `--emit-js-glue` (197 lines each, four copies). Migrating them onto
  the generated glue drops them to ~40 lines WITHOUT changing their boundary --
  a separate, smaller win that this item's step 4 makes possible but does not
  require.

## Non-goals

- **The split boundary does not change.** It stays the default and stays what
  `dog-fetcher` uses; this adds a second shape, it does not retire the first.
- **Not a size or a speed item.** The modules measured within 600 B of each
  other and the envelope one is marginally larger gzipped.
- **`--component` is untouched.** It is already in-band everywhere and refuses
  `--emit-js-glue` (jco writes its glue); the mode is a `--no-wasi` core-module
  decision, behind the same gate as the flag it rides.

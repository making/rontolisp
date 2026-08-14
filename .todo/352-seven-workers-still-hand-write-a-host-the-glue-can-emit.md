# Seven Workers still hand-write a host the glue can emit

Difficulty: Low

`--emit-js-glue` now writes the whole host half of a reactor boundary on BOTH
`--host-boundary` shapes: `defaultHost()` for `--host-fetch`, and `worker(module)`
for the Request/envelope/Response mapping and the reactor's own body imports. The
two Workers that use it are three lines each
(`btc-ticker/src/index.js`, `dog-fetcher/src/index.js`).

Seven others still carry a hand-written host, in two shared files:

| file | used byte-identically by |
| --- | --- |
| `hello-clack/src/index.js` (45 lines) | `hello-clack`, `hello-tiny-routes`, `hello-ningle` |
| `httpbin/src/index.js` (54 lines) | `httpbin`, `httpbin-clack`, `httpbin-clack-one-source`, `httpbin-tiny-routes`, `httpbin-ningle` |

Six of the eight go through `clack:clackup` / `rontolisp:http-handler`, so the
compile path synthesizes the bridge and `Surface.envelopeExport` is set: adding
`--emit-js-glue` to their `build.sh` and replacing `src/index.js` with

```js
import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
```

is the whole change. **`httpbin` itself is the exception and must stay
hand-written**: it writes `(rontolisp:wasm-export 'handle-request ...)` by hand,
so `envelopeExport` is deliberately null (`WasmLispCompiler` recognises the
SYNTHESIZED bridge, not the export name -- `.kb/wasm-import.md`) and no `worker()`
is emitted for it. Either leave it as the one hand-written host in the set --
which is a fair thing for the "no library, boundary included" example to be -- or
give the compile path a way to recognise a hand-written envelope export, which is
a bigger decision than this item.

## Why it is worth doing

- Each hand-written host is a copy of state whose LIFETIME is the thing that goes
  wrong. Every defect found on this surface (todo-340's review, todo-351's) was a
  state-lifetime bug, and two of them -- the instance bound outside the critical
  section, and the un-awaited `remoteAddr` -- existed in a generated file precisely
  because the hand-written one had been transcribed imperfectly. One generated
  copy is one place to fix.
- The `hello-*` trio is currently on the DEFAULT (envelope) boundary while its
  host still declares `readRequestBody`/`writeResponseBody`. Harmless (the module
  does not link them, verified) but it is dead code in a file whose whole purpose
  is to be read.
- The READMEs' "N lines, boundary included" / "byte-identical" columns become "3
  lines" everywhere, which is the honest summary now.

## Non-goals

- **The boundaries do not change.** The five `httpbin*` directories echo request
  bodies and must keep `--host-boundary=streaming`; the `hello*` trio stays on the
  default. This is a host-side change only.
- Not a size item: the generated glue is bigger than the hand-written host it
  replaces, and neither is in the `.wasm`.

## Gates

- `HostGlueEmitterTest` grows a byte-pin per newly generated `src/worker.js`, or
  the existing two are argued to cover them (they are derived from DECLARATIONS,
  so a Worker with the same import/export shape emits the same file -- check
  before assuming).
- Each directory's `check.lisp` still passes through `ExamplesE2eTest`.
- Drive at least one of each family under node before and after and diff the
  answers; `wrangler dev` for one of them.

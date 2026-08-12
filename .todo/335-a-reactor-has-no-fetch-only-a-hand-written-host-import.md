# A reactor has no `fetch` -- only a hand-written host import

Difficulty: High

`rontolisp:fetch` is wasi:http, so it is a compile error on `--no-wasi`
(`WasmFetchCompiler` raises the component-only error; `.kb/fetch-http.md`). A
reactor that has to call out therefore writes the transport itself:
`examples/cloudflare-workers/dog-fetcher/worker.lisp` declares

```lisp
(rontolisp:wasm-import 'host-fetch :from "env" :as "fetch"
                       :params '(:string) :returns :string)
```

and invents a JSON envelope (`{"status":…,"body":…}` / `{"status":0,"error":…}`)
because the import vocabulary carries flat values and strings, not records. Every
Worker that calls out will invent that envelope again, each one differently, and
none of them will be the `(:status <int> :headers <alist> :body <stream>)` plist
that `compiler/FetchResponseShape` derives for every other backend. The one
source that runs on the interpreter, the JVM and a component does NOT run here --
which is exactly the divergence the shipped example had to route around by not
using `fetch` at all.

## Proposal

`rontolisp:fetch` compiles on a `--no-wasi` reactor, lowered to a host import,
returning the SAME shape it returns everywhere else. The example's whole "the way
out" section then disappears and `examples/net/dog-fetcher.lisp` becomes
compilable as a Worker with no edit but the routing.

Opt-in, and the reason is the zero-import contract: `--no-wasi` means the module
instantiates with `{}` (`.kb/wasm-export-no-wasi.md`). A program that fetches
imports one function, so the flag is what says the host accepted that obligation
-- `--host-random` is the precedent for the whole shape (one injected `env.*`
entry appended LAST in `hostImports`, the other stubs untouched, a build line
naming what the host now owes). Name it in the same family (`--host-fetch`), and
reject it without `--no-wasi` and under `--component`, where `wasi:http` already
answers.

## Open questions, in the order they bite

1. **What crosses.** The import is `(:string) -> :string` today because that is
   the vocabulary. Deriving the request/response JSON from `FetchResponseShape`
   (as `.kb/http-plist-shape` does for the server side) keeps the keys and their
   order from being hand-written per host; the alternative -- widening the import
   boundary to records -- is `.kb/wit.md`'s "carries it exactly or traps" problem
   and is a much larger change.
2. **`:body` is a stream everywhere else**, drained with
   `(await (read-all ...))`, and `TYPE_WASI_STREAM` is component-only. On a
   reactor the whole body has already arrived as a string when the call returns,
   so this needs either a degenerate already-settled stream on P1 or a `read-all`
   that accepts a string. Whichever it is, the SOURCE must not have to know.
3. **`fetch` returns a future.** P1 already has the degenerate
   `TYPE_P1_FUTURE` (settled at creation, resolved by `FUNC_P1_FUTURE_AWAIT`),
   which is exactly the right representation here -- the host call blocks the
   wasm stack, so the value is ready when the call returns. That makes
   `(await (fetch ...))` read identically on all five targets. See `.todo/336`:
   this is one instance of the general "a host import may suspend" question, and
   the two should agree on the future representation even if they land apart.
4. **How the host suspends.** JavaScript's `fetch` is a promise and a wasm import
   is a plain call; JSPI is what joins them (`WebAssembly.Suspending` on the
   import, `.promising` on the export), and workerd runs it with no flag --
   measured, deployed, `.kb/wasm-import.md`. The compiler emits nothing for it,
   but the BUILD should say so, the way the clock hook's line does
   (`compiler/NoWasiLoadPathRefusals`): a host that provides a plain synchronous
   `env.fetch` is also valid, and a host that provides a suspending one must
   enter the exports through `promising` or the call traps.
5. **Re-entrancy.** A suspending host fetch makes the module re-enterable
   mid-call, which nothing in the runtime is prepared for -- `.todo/337`. This
   todo must not ship a `fetch` that silently corrupts a second in-flight
   request; at minimum the limitation is stated where the flag is documented.

## Verification

- `examples/net/dog-fetcher.lisp` compiles under `--no-wasi` and answers the same
  JSON as the component build, driven by a node host that provides `env.fetch`.
- `examples/cloudflare-workers/dog-fetcher` loses its `host-fetch` import and its
  envelope, and still answers all five endpoints under `wrangler dev` AND
  deployed (that example is verified on the real edge -- keep it that way).
- The response plist is byte-identical in shape to the other backends' (pin it
  the way `FetchResponseShape` is already pinned, not by eye).
- A `--no-wasi` build that does NOT fetch still imports nothing: the zero-import
  contract is the flag's whole point.

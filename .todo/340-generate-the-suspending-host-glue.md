# Generate the suspending host glue

Difficulty: Medium

`:async t` (todo-336) made a suspending host import a declared fact: the call
returns a future, and the build prints what the host owes -- wrap the import in
`WebAssembly.Suspending`, enter the listed exports through
`WebAssembly.promising`, serialise calls. What the build does NOT yet do is
WRITE that glue, so every Worker that calls out still hand-writes the same
boilerplate: the Suspending wrapper, the `__ronto_alloc` string-return dance,
the `promising` entry, the one-promise serialisation queue
(`examples/cloudflare-workers/dog-fetcher/src/index.js` is the canonical copy).

The precedent is `gl-imports.js`: the WebGL demos' page-side import object is
GENERATED from the same WIT the module was compiled against and pinned by
`GlImportObjectTest`, so the two halves cannot drift. The suspending glue is
the same kind of derivable boilerplate:

- the import-object skeleton (one key per `:from`, one property per `:as`,
  each `:async t` entry wrapped in `WebAssembly.Suspending`),
- a string-result helper writing through `__ronto_alloc` and returning the
  `(ptr, len)` pair,
- the `promising`-wrapped entries for exactly the exports the build already
  lists (`compiler/SuspendingImports.reaches`),
- the serialisation queue todo-337's re-entrancy analysis demands (one promise
  chain; per-call allocator scopes are todo-337's problem, not this one's).

**What the boundary looks like now (2026-08-13).** `.todo/341` and `.todo/347`
landed ahead of this item, so the hand-written surface is bigger than the text
above describes and MORE derivable, not less: a fetching reactor's
`src/index.js` now writes four entries by hand -- `env.fetch`,
`env.readResponseBody`, `env.readRequestBody`, `env.writeResponseBody` -- and
three of them are the SAME `read(2)` / write shape over a cursor the host owns
(`.kb/fetch-http.md`, `.kb/clack.md`). Their bodies are boilerplate a generator
can write from the declarations; what is genuinely per-host is which source the
cursor reads (a `ReadableStream` reader, a `Uint8Array`) and nothing else.
`.todo/347` was ordered AFTER this item to avoid adding a hand-written import
before generation existed; it went first anyway because the divergence it fixed
was a correctness gap, and the cost is exactly one more entry in each of the
three glue files.

Open questions to settle first:

- Where it hangs: an `--emit-js-glue` flag beside `--emit-wit`? The generator
  should take the same derived facts the obligation lines print, not re-walk.
- One generator for both shapes (hand-written `wasm-import :async t` and
  `wit-import`ed `async func` members) -- the P1 lowering makes them the same
  declaration by compile time, so this should fall out.
- Pin like `GlImportObjectTest`: the generated glue against the declaration,
  and the dog-fetcher example rewritten onto the generated file to prove it
  carries a real Worker.

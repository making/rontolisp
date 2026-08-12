## What is measured

The Worker family compiles the real
[`examples/cloudflare-workers/`](../../examples/cloudflare-workers) sources in
place, plus two variants that exist only for the comparison: the two
tiny-routes Workers rebuilt against the full `tiny-routes` system instead of
`tiny-routes/lite`, which is what makes the price of the regex engine visible.

## Reading the numbers

**gzip is the number Cloudflare counts.** A Worker bundle is limited to 3 MB
compressed on the free plan, so the table reports raw and gzipped sizes and the
share of that limit. What a framework costs there is module size and isolate
startup, not per-request time.

**Raw and gzip do not always move together.** Removing DUPLICATE bytes -- a name
interned twice, one sentence repeated per generic -- is worth its full weight
raw and close to nothing compressed, because a compressor had already collapsed
it; the offsets that shift as a result can even cost gzip a few hundred bytes
back. So a row whose raw size drops while its gzip size ticks up is the expected
shape of a data-section dedup, not a regression: what shrank is the module the
engine loads and keeps in memory.

**`httpbin-clack` and `httpbin-clack-one-source` are the same application.**
They differ in the `:server` designator only -- `:reactor`, which is
host-driven on every backend, against `:rontolisp`, which reads the compile
target and takes its reactor shape under `--no-wasi`. The second row builds
`examples/net/httpbin-clack.lisp` itself, the file that binds a socket when
interpreted, so what the pair measures is that choosing the portable designator
costs nothing in bytes.

**`dog-fetcher` is `hello-tiny-routes` plus an outgoing request.** Its source
calls the same `rontolisp:fetch` every backend answers, and `--host-fetch`
lowers it onto ONE host import, so what separates the two rows is that lowering
plus the JSON parsing of the upstream answer: a reactor's way out costs an
import entry and a wrapper, not a transport of its own.

**The routing library is not what the ningle rows measure.** Both of them are an
order of magnitude above their tiny-routes neighbours, and almost none of that
is ningle or its router myway: ningle reads every request through the
`lack-request` chain -- `http-body`, `fast-http`, `smart-buffer`,
`circular-streams`, `yason`, `trivial-mimes`, `quri` -- which tiny-routes never
touches because its request IS the Clack environment plist. There is also no
lite row to pair them with, the way the tiny-routes rows have one: myway
compiles every rule to a cl-ppcre scanner, so the regex engine is genuinely
reachable and the tree-shaker is right to keep it.

What each compiler flag does: [`wasm-flags.md`](wasm-flags.md#flags).

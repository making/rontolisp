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

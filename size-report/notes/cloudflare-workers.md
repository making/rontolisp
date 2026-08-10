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

What each compiler flag does: [`wasm-flags.md`](wasm-flags.md#flags).

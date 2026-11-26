# m12. Worker glue: `wasi:blobstore` answered by R2

Difficulty: High

Asked by the user (2026-11-24), the third of three after m10 and m11 (read what they decided
first): a program stores files (an upload, a generated report) through `wasi:blobstore`,
answered by wasmCloud on a component and by an R2 bucket binding through the glue.
`wasmtime serve` has no blobstore host (wasmtime 51, `-S help`).

- Measure the WIT's shapes (containers, objects, incoming and outgoing values over
  `wasi:io/streams`) against what m10 made cross a core import, and R2's API (`get`, `put`,
  `list`, `delete`, `head`; bodies as streams).
- The Java side: an `@WitImport` interface, and what a servlet's `Part.write` could map to.
- An example under `examples/cloudflare-workers/` with an R2 binding; docs in `doc/en` and
  `doc/ja` together.

What m10 decided (`.kb/wasm-import.md`, "A WIT interface beyond the flat set"): the canonical
ABI on a `--no-wasi` core module, lifted by the glue from `CanonicalLayout` descriptors;
resources are values in a table the glue keeps, released by `[resource-drop]`. A `stream` or a
`future` is refused there (`WitImportDirective.refuseAsyncOnCore`), which `wasi:blobstore`'s
values over `wasi:io/streams` will meet first.

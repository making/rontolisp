# The adjacent-instruction peepholes the emitted code section still carries

Difficulty: Medium (a byte-level pass beside the shaker, every rewrite local to two or three
adjacent instructions; no dataflow)

Second of the three items `791` item 3's measurement split into
(`.kb/optimize-dead-code-elimination.md`, "What an external optimizer still finds, and what
it is made of"; scripts in `.todo/artefacts/791-module-level-slack-globals-types-data-hooks/`).
A census over the flat `wasm-tools print` of the hello-clack Worker (`--optimize=size`,
815,414 B after `.kb/cons-access-runtime.md`) and `zlib` (90,874 B):

| shape | Worker | `zlib` | per site |
| --- | ---: | ---: | --- |
| `local.set N; local.get N` -> `local.tee N` | 6,275 | 677 | 2 B |
| `local.set b; local.set a; local.get a; local.get b` -> `set b; tee a; get b` | 685 | 87 | 2 B |
| `local.tee N; drop` -> `local.set N` | 1,197 | 127 | 1 B |
| a pure value then `drop` (`ref.null`, a constant, a `local.get`) -> nothing | 1,416 | 267 | 2-3 B |
| `br 0; end; unreachable; end` when the enclosing block has no result | 1,488 | 185 | 1 B |

Roughly 20 KB on the Worker (2.5%), 2 KB on `zlib` (2%), 20-30 B on each small module. All
of it is what binaryen's `simplify-locals`/`vacuum` get on the FIRST pass over a body
without any analysis, and none of it needs one: each rewrite is decided by the bytes of two
or three adjacent instructions in one block.

## Shape

A pass in `am.ik.wasm` over `WasmCodeModel.decode`d bodies, run in
`WasmLispCompiler.shakeCore` after `WasmRefTypeFolder.fold` (it leaves `i32.const; drop`
and `ref.null; drop` debris of its own) and before the shake, at every level but `off`;
`WasmCallForwarding` is the template for a body-rewriting pass that renumbers nothing.
Adjacent means adjacent in the decoded instruction list of ONE block level: a `local.get`
that starts a new block's body, or follows an `end`, is not the set's consumer.

Two extensions of the same kind, from the fold's leftovers on the small modules:
- a function whose body is `local.get k; end` (the identity the fold leaves of a normalizer
  whose every other arm died) -- delete the `call` at each site;
- a function whose body is a constant -- replace each `call` by `drop`s of its arguments
  and the constant. `WasmCallForwarding.forwardTarget` is where both belong.

## Guards

`WasmTreeShakerCorpusTest` (validate + the shortest-encoding round trip at `default` AND
`size`), `WasmRefTypeFoldHostSuppliedValuesE2eTest` (the fold's answers must not move),
and a per-shape unit test on a hand-assembled body, as `WasmCallForwardingTest` does.
Measure on `size-report/programs/` and the hello-clack Worker, never on one micro
program.

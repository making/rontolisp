# WASM component: a SCRAM login in a program holding eval is 3x a command's

Difficulty: High

`.todo/j34` took cl-postgres's SCRAM-SHA-256 login on a component from 443-497 ms to 174-223 ms
(`postgres-hello.lisp`) and a wasi:http handler without `eval` with it (`postgres-web.lisp` under
`wasmtime serve`, 180-269 ms), but Shiori only from 776-928 ms to 578-841 ms. Shiori holds `eval`
(clack's `eval-file`), so every defun has a cell, and what j34 built stops at a cell:

- an exact local needs every assignment to answer a bounded integer whatever runs it, and a celled
  `mod32+` answers whatever a replacement answers -- so ironclad's round variables stay shadowed;
- every fused site over a celled call checks the cell and keeps its fallback (and the snapshot
  barrier), so no SHA-256 site is free of bails;
- an array parameter is not substituted through a celled call (its fallback passes every
  argument's node), so `ub32ref/be`/`ub16ref/be` stay calls in `fill-block-ub8-be`.

Ten derivations in a program holding `eval`: 28.5 G instructions before j34, 19.5 G after, against
14.0 G without `eval` (`.kb/wasm-int-fusion.md`, "Ranges, and sites with nothing to bail on").

## Goal

Shiori's login at `postgres-hello.lisp`'s speed. Done when a SHA-256 block compiled in a program
holding `eval` runs the bail-free code a program without one gets, and only while the substituted
names' cells are empty. Pin it as j34 pinned its half: a fuel budget over the same compression in a
program holding `eval`, and the replacement semantics of ci-spec
`a-defun-written-through-a-computed-name-answers-the-new-definition` unchanged.

## Direction

Version a LEAF function -- one whose body calls nothing that can run user code (the fused helpers,
the array and error runtimes; no user function, closure call, generic dispatch or printer): its
body compiled twice into one function, `if` every cell its substitutions read is empty, the body with
those names substituted as if they had none (exact locals, no cell checks, array parameters),
`else` the body as now. A leaf cannot fill a cell between the test and the substituted sites; an
error it signals never returns to it. `update-sha256-block` and `sha256-expand-block` are leaves.
A lambda inside the body registers twice (`WasmLambdaCompiler` adds one per compile), so either
reuse the first registration or keep such bodies out. Measure on Shiori under `wasmtime serve`
(`examples/db/shiori/README.md`) and through `postgres-hello.lisp` with an `eval` added.

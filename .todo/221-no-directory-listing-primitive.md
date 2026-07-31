# 221. No directory-listing primitive on any backend

## Problem

rontolisp's entire filesystem surface is "read or write a NAMED file": `open` /
`with-open-file` / `probe-file` / `uiop:file-exists-p`, plus
`uiop:directory-exists-p` since the local-time work (2026-07-31). There is no way
to ask *what is in* a directory. `uiop:collect-sub*directories` and
`uiop:directory-files` resolve as names but are stub-lowered to a call-time error
on every backend, and CL's own `directory` is not implemented at all.

## Why it matters (the one real caller so far)

local-time's `reread-timezone-repository` walks the bundled `zoneinfo/` tree and
loads every zone file it finds, which is what populates
`find-timezone-by-location-name` ("Asia/Tokyo" -> a timezone). Without it a
program has to name each zone it wants with `define-timezone`, which works
everywhere and is the documented answer (`doc/{en,ja}/guides/asdf-systems.md`),
but it is strictly less than the library offers. Everything else about local-time
works on all four backends -- see `.kb/asdf.md`.

## Why it is not just "add a primitive"

The interpreter and JVM sit on a real filesystem and could list a directory in a
few lines. **The WASM backends cannot**, in either mode, so adding it would create
exactly the kind of two-of-four divergence `CLAUDE.md` warns against:

- Preview 1 has `fd_readdir`, but rontolisp's core module deliberately imports
  only eight `wasi_snapshot_preview1` functions and their indices are pinned
  (`IMPORT_FUNC_COUNT`); a ninth import shifts nothing by itself, but
- `--component` satisfies those eight from `adapter.wat` over WASI 0.3, and the
  component's import declarations live in the PREBUILT `import-block.bin` blobs.
  A new preview1 import means a new lowered function in the block, i.e.
  regenerating the blobs (`src/wasm-component/regen.sh`) and re-running the
  component E2E on every variant.

So the work is real but bounded, and it is a WASM-component question more than a
listing question.

## What to do if picked up

1. Decide the SURFACE first, and prefer the CL one: `(directory pattern)` rather
   than the two `uiop:` names, with the `uiop:` pair lowering onto it (the way
   `uiop:file-exists-p` lowers onto `probe-file`). One primitive, three spellings.
2. Interpreter: a new `SourceLoader.listDirectory` next to `directoryExists`,
   defaulting to the empty list so the browser playground answers rather than
   fails.
3. JVM: a `_listDirectory` runtime helper + a `Jvm<Name>Compiler`, following
   `JvmProbeFileCompiler`.
4. WASM: `fd_readdir` as a ninth import in both modes, the `noWasi` trap stub to
   match, and the regenerated `--component` blobs. Verify on a component WITHOUT
   `--dir` too -- an empty preopen list must answer "empty", not trap (the same
   class of bug as the `$ensure_preopen` guard, `.kb/wasi-component.md`).
5. Then local-time's `reread-timezone-repository` and
   `find-timezone-by-location-name` become the natural E2E case.

## Non-goal

Globbing / wildcard pathname matching. `(directory "*.lisp")` is a pattern
language rontolisp has no pathname machinery for; list a directory and filter in
Lisp.

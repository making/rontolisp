# Make `load` practical and fast across the compilers

**Status:** all but one direction DONE and removed from this file
(inventory 2026-07-17); what is left is direction C below, which is **low
priority and has no consumer in the repo**. Delete this file if C stays
unwanted.

What landed (see `.kb/load-inliner.md`, `doc/{en,ja}/compiling/dynamic.md`,
`doc/{en,ja}/reference/functions/load.md`):

- **Compile-time `load` / include** -- `eval.LoadInliner` splices a literal-path,
  top-level `(load "x.lisp")` into the program before the compilers run, so Pass 1
  sees the definitions and compiles them natively; no `--dynamic`, no perf loss.
  It also implements `require`/`provide` and the ASDF splice, and threads reader
  features through `LoadInliner.Ctx`.
- **File-relative path resolution** -- a relative `load` resolves against the
  loading file's directory (`SourceLoader.resolve`/`parentDir`), on BOTH the
  compile-time include and the runtime `load`, so the `examples/` sets run from
  any working directory.
- **Browser virtual filesystem** -- `examples/browser/hiragana/wasi-shim.js`
  implements `path_open`/`fd_read`/`fd_close` over an in-memory file map, so a
  fixed `.wasm` can read a data file in the browser. The hiragana demo now loads
  its weights at runtime (`(defparameter *net* (load-hiragana-net "weights.bin"))`)
  instead of baking them in.

## C. Faster `--dynamic` / runtime-`load`ed code (the only remaining direction)

When code must stay dynamic (a genuinely runtime-determined `load`, or any
unresolvable call under `--dynamic`), the loaded defuns execute in the
tree-walking `_eval` -- `Jvm/WasmDynamicCallCompiler` routes the call through the
embedded interpreter rather than compiling it.

Options: compile loaded forms on the fly into fresh functions in the runtime
function namespace (`_fenv` / `GLOBAL_FENV`), or cache/specialize hot `_eval`
paths.

Nothing in the repo needs this today: every multi-file program in `examples/`
goes through the compile-time include and is compiled natively. Pick this up only
if a real workload has to stay dynamic AND is hot.

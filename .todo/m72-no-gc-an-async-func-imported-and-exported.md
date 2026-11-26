# m72. `--no-gc`: an `async func` imported and exported

Difficulty: High

The wasm-GC `--component` backend carries the async component model: an `async func` import
binds as an `async-defun` answering a future, `stream<u8>`/`future<T>`, `waitable-set`,
`task.return`, a callback async lift (`.kb/async-await.md`, `.kb/wasi-component.md`). `--no-gc`
refuses it: a `wit-import` of an `async func` is a compile error ("no futures in that value
model", `.kb/wit.md`), `:async t` on `rontolisp:wasm-import` is refused
(`.kb/no-gc-scalar-wasm.md`, "Refusals"), and `wit-export` refuses an `async func` under
`--no-gc --component` (`.kb/wit.md`, "The contract checks"). Every front end inherits these
refusals.

The user's decision (2026-11-26): `--no-gc` takes it too.

- **What is already there**: `--no-gc --component`'s print micro-adapter parks a task on
  `waitable-set.wait` (`bridge-nogc-print.wat`), and a printing program's exports lift as async
  (`asyncFuncTypeScalars`), so the async ABI and a blocking task exist on this backend.
- **What to decide first, by measurement**: what an `async func`'s call answers in the scalar
  value model -- the value itself, the task blocking on `waitable-set.wait` until the subtask
  returns (no future value; `.kb/async-await.md`'s blocking driver `_sched_loop` is the GC
  precedent), or a future handle the program awaits (`async-defun`/`await` as the GC backend
  has them, `.kb/no-gc-scalar-wasm.md` on what a value can be). Weigh the size each costs a
  program that does not use it (zero is the bar: a scalar-only module stays byte-identical).
- **Where it can work**: `--no-gc --component` (async ABI); a Preview 1 core module `--no-gc` has
  no async ABI, so state its refusal rather than faking one.
- **Exports**: an `async func` of a world, lifted async; the hosts (wasmtime's invoke, jco's
  limits as `.kb/no-gc-scalar-wasm.md` records them for printing programs).
- **Element types**: a `stream<T>`/`future<T>` past what the scalar boundary carries waits for
  m70 (compound types on the export path) -- coordinate, do not duplicate.

Pins: `NoGcWasmCompilerTest`'s refusals turn into cases, `NoGcWasmImportE2eTest`,
`WitOracleE2eTest`; `doc/{en,ja}/guides/wasm-nogc.md`.

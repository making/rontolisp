# A wasm module's uncaught report does not say where it happened

Difficulty: High

The interpreter prints location lines under `Unhandled condition: <report>` (the innermost
located form and its named function, then one line per async boundary with its await site;
`compiler/UncaughtReport.atLine` / `asyncLine`, `.kb/error-handling.md` "An uncaught
condition reports ONE line"). Both wasm-GC backends print the report line alone, from the
entry landing pad of `WasmUncaughtReportCompiler`, and only in EH mode.

Goal: the same lines on Preview 1 and `--component` (and so `--native`), with no byte added
to a module outside EH mode -- the report only exists there -- and the added bytes in EH mode
measured against the size campaign's benchmarks.

What is known (2026-09-26):

- A module cannot inspect its own stack, and the landing pad runs in the entry function
  after the unwind, so wasmtime's trap backtrace names only the entry.
- The `$lisp-cond` payload is `(cond . msg)`; a location has to travel in it or beside it
  (a global the landing pad reads), noted on the way out of each user function -- a
  `try_table` catch per function that fills an empty slot and rethrows is the interpreter's
  throw-path model and costs nothing at run time until something throws; its byte cost per
  function is the number to measure.
- Lines per call site would need either a table keyed by throw site (only where the signal
  is compiled -- a runtime helper's throw has no user position) or the call site noted by
  the per-function catch; function granularity plus the function's definition line is the
  fallback if per-line costs too much.
- The async hop: an async body's condition is rethrown by the await lowering, which knows
  its own position at compile time.

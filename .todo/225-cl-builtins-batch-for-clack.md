# Missing CL builtins the clack/lack sources call

Difficulty: 低〜中 (each item is small; the batch is medium because every
function follows the full 4-backend implementation order + wrappers + docs.
WASM legs that cannot be honest become call-time stubs, the todo-195 policy)

Part of the Clack milestone `.todo/223`. All verified missing by an interpreter
probe on 2026-08-01. Call sites in parentheses.

1. **`substitute-if`** (+ `substitute-if-not` for parity) —
   `lack/util:find-middleware` builds the middleware variable name with it at
   RUNTIME, so `builder` with a keyword middleware (`:backtrace`, the clackup
   default) dies without it. `substitute` exists; follow its lowering.
2. **`file-write-date`** — `clack:eval-file`'s app-file cache. Interpreter/JVM:
   `Files.getLastModifiedTime` -> universal time; WASM: call-time stub (P1) /
   honest answer if the WASI clock+fs API allows (component).
3. **`sleep`** — `clack.handler:stop` does `(sleep 0.5)` after destroying the
   acceptor thread. Interpreter/JVM: `Thread.sleep`; WASM: call-time stub or
   busy-wait — decide and record why.
4. **`ensure-directories-exist`** — the backtrace middleware's pathname
   `:output` branch. Interpreter/JVM real; WASM call-time stub.
5. **`file-length`** — `lack/util:content-length` for a pathname body
   (`(with-open-file (in body) (file-length in))`). Today it resolves but
   returns NIL on a fresh input stream (probed) — make it answer the actual
   length for file streams on interpreter/JVM.
6. **runtime `export`** (+ `unexport`) — not called by clack itself but needed
   the moment any loaded library manages exports at runtime (the spike wanted
   it for a workaround; alexandria-adjacent libraries call it). Interpreter
   package op; compile paths per `.kb/packages.md` policy.
7. **Check, may already work**: let-binding `*readtable*` / `*load-pathname*` /
   `*load-truename*` (clack `%load-file` binds all three; only the FILE-app
   path of clackup reaches it). If the specials do not exist, define them as
   let-bindable specials with honest values in `load`.

Not in this batch (own todos): `subtypep` on class metaobjects (`.todo/230`),
thread creation (`.todo/227`).

## Test

ci-spec case `clack-enablement-builtins` for the cross-backend members +
`LispEvaluatorTest` units. `file-length`/`file-write-date` need a file fixture,
so they get `LispEvaluatorTest`/JVM-compiler tests instead of ci-spec.

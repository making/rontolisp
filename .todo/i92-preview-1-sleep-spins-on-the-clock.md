# i92. Preview 1 `sleep` spins on the clock

Difficulty: Medium

On Preview 1 (and so under `--native`), `sleep` loops on `get-internal-real-time` until the
deadline (`LispMacroExpander.expandSleep`, the `spin` arm; `.kb/time-environment-builtins.md`,
"`sleep`"). Every wait burns one core. Since 2026-11-06 `usocket:wait-for-input` polls every
10 ms through that `sleep` on every backend (`.kb/tcp-sockets.md`), so a `--native` dexador
program waiting on a quiet socket holds a core for the whole wait.

Options to weigh:

1. Import WASI Preview 1 `poll_oneoff` with a clock subscription. Standard Preview 1, so a plain
   `.wasm` under `wasmtime run` gets it too. Cost: one more import beyond the current nine; check
   what the import set's size is pinned by (tree shaker, `--no-wasi`, tests that count imports).
2. A runner-side sleep in `rontolisp-native/runner`. Only `--native` gains.

Prefer 1 unless measurement says otherwise. Done when `sleep` on Preview 1 and `--native` blocks
instead of spinning, on top of the existing sleep tests (`#sleepSpinsOnTheClockOnPreview1` is
the one to rewrite), and the `.kb` pages above say so.

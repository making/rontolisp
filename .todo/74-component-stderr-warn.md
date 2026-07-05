# 74: Wire stderr (fd 2) into the WASI 0.3 component adapter

`warn` (todo 65) writes `WARNING: <message>` to fd 2. This works on the
interpreter, JVM and WASM Preview 1, but the `--component` adapter's
`fd_write` (src/wasm-component/adapter.wat) only wires fd 1 to `wasi:cli`
stdout and treats every other fd as a file-table slot, so fd 2 trapped
("unknown handle index 0"). Current lite behavior: `WasmWarnCompiler` DROPS
the message in component mode (evaluated for effect, returns nil) --
documented in doc/*/reference/macros/warn.md.

To fix properly: add a `wasi:cli` stderr import to `uni.wit`/`adapter.wat`
(fd==2 branch mirroring the fd==1 `$stdout_write` path), run
`src/wasm-component/regen.sh`, re-derive the wiring constants from
`wasm-tools dump` (see src/wasm-component/README.md), then remove the
`ctx.component` branch in `WasmWarnCompiler` and update the warn doc pages
(en+ja) and the `%warn` javadoc. The serve adapters (`adapter-serve-p1*.wat`)
may want the same wiring for warn-inside-a-handler.

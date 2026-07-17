# Combine rontolisp:fetch and rontolisp:tcp-* in one component

A `--component` program using both `rontolisp:fetch` and a `rontolisp:tcp-*`
built-in is currently a compile error (`WasmLispCompiler`:
"cannot be combined in one --component program yet"): fetch needs the http blob
variant (WASI 0.2 `wasi:http`/`wasi:io` hybrid, `import-block-http-client.bin` +
`adapter-http-client.wat`) while tcp needs the sockets variant (pure WASI 0.3,
`import-block-sockets.bin` + `adapter-sockets.wat`), and no combined variant exists.

The path is mechanical: a fourth WIT world (`uni-http-client` + `wasi:sockets/types@0.3.0`
appended last), a merged adapter (adapter-http-client.wat's fetch machinery + the real
socket table/exports from adapter-sockets.wat, replacing the four errno stubs), a
`regen.sh` stanza, and a `WasmComponentBuilder.buildHttpSock` whose wiring
constants are re-derived from `wasm-tools dump`. The core seam already supports it
(fixed slots: sock at 8-11, http at 12-13, both imported). Deferred because the
combination is niche and the interpreter/JVM support both together already; if
async `wasi:http@0.3` ships first (`.todo/002-upgrade-fetch-to-wasi-http-0.3.md`),
doing that upgrade first shrinks this to one 0.3-only world.

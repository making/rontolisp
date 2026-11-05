# i59. The GC heap pre-grow on a Proxy-Wasm host

Difficulty: Low

Every wasm-GC module allocates and drops a `$str_bytes` array of at least 16 MiB first thing in `_start` or
`_initialize` (`.kb/wasm-gc-heap-pregrow.md`), so a filter from `examples/proxy-wasm` does too. The array is for
wasmtime's copying collector, and no CLI option leaves it out: only `serve` changes its size.

`sluice-client` interprets plugins with Endive (`https://github.com/making/sluice`, `proxy-wasm-plugins.md`). There an
`i8` element takes 8 bytes, so that array is a 128 MiB `long[]` at every VM start. sluice's `max-memory` bounds a
plugin's objects as well as its linear memory, by default at 16MB, so these filters are refused at the client's start
unless `max-memory` is at least 129MB. That also takes `vms x 129MiB` off the client's derived connection limit.

Measured 2026-11-05 on sluice's `ProxyWasmFilterTest` and a real client, with the constant set to 0 in the binaries of
`http_body`, `http_headers` and `test_plugin`: all behave the same, and start in 310-497ms against 491-588ms (one JVM per
plugin, cold). What stays reachable after the start is 2-6KiB, and a filter allocates 10-390KiB per exchange.

## What to decide

- Whether a Proxy-Wasm filter needs a pre-grow at all. Measure it on Envoy's runtimes (its V8, and the wasmtime it builds
  with `gc-drc`, not the copying collector), since `check.sh` is what pins a filter's behaviour.
- How a program asks: a compile option, or following from the module exporting `proxy_abi_version_0_2_1`. `serve`'s 1 MiB
  would be 8 MiB on Endive, half of sluice's default.

## Done when

A filter from `examples/proxy-wasm/sdk-examples`, compiled as its README says, starts on `sluice-client` at the default
`max-memory`, and `examples/proxy-wasm/check.sh` and `sdk-examples/check.sh` pass. sluice's `proxy-wasm-plugins.md` can then
drop its `max-memory=129MB`.

# What a JSON exchange through sluice's json_plugin costs

A measurement of sluice-client (f408d6e), not part of it. In a copy of sluice, beside
`.todo/artefacts/i69-proxy-wasm-instructions/` (`OpCounter.java` and its `ProxyWasmFilter` hooks):

- `OpCounter.diff` on that `OpCounter.java`: counts per address for `-Dcount.addresses=<function indices>`.
- `PluginHeap.diff`: what was charged, its peak, and bytes and allocations per type index.
- `JsonCount.java` and `JsonBench.java` (the sluice session's) beside `FilterTests` in the test package.
- Build with `-Dmaven.gitcommitid.skip=true`; the plugin as sluice builds it:
  `java -Drontolisp.wasm.debug-func-sizes=true -jar rontolisp-exec.jar json_plugin.lisp -o json.wasm --no-wasi --optimize=size > json.names`.

```
# the largest body at sluice's defaults (16 MiB, a 1 s call); -Dmem=<MiB> -Dtimeout=<s> lift them
java -cp ... am.ik.sluice.client.filter.JsonBench json.wasm 8 64 192 256 288
# one exchange, limits lifted: instructions (-Dcount.ops=false for bytes only), bytes per type,
# the 40 costliest functions by the names file
java -Dcount.ops=true -cp ... am.ik.sluice.client.filter.JsonCount json.wasm 64 json.names 40
```

A type index is the module's own: in the plugin `#3` is `TYPE_CONS`, `#4` `TYPE_STRING`, `#5` `TYPE_CELL`,
`#23` `TYPE_HASH_BUCKETS` and `#24` the string bytes (`wasm-tools print` names them).
`-Daddrdump=<file>` with `count.addresses` writes the per-address counts.

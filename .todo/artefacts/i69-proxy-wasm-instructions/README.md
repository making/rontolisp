# Counting what a Proxy-Wasm filter runs per exchange

A measurement of sluice-client, not part of it. In a copy of sluice:

- `OpCounter.java` goes beside `ProxyWasmFilter.java`. Build with `-Dmaven.gitcommitid.skip=true`.
- In `ProxyWasmFilter.Vm.start()`, in the place of `if (charged != null) { builder.withUnsafeExecutionListener(charged); }`:
  `if (Boolean.getBoolean("count.ops")) { builder.withUnsafeExecutionListener(new OpCounter(charged, filter.module)); } else if (charged != null) { ... }`
- In `Vm.call`, around `export(export).apply(args)`: `long before = OpCounter.total; OpCounter.begin(export);`, and in a `finally`, `OpCounter.entered(export, before);`.
- `CountBench.java` goes beside `FilterTests` in the test package, for its package-private helpers.

Then, with the test classpath:

```
java -Drontolisp.wasm.debug-func-sizes=1 -jar rontolisp-exec.jar f.lisp -o f.wasm --no-wasi --optimize=size 2> f.names
java --enable-native-access=ALL-UNNAMED -Dcount.ops=true -cp ... am.ik.sluice.client.filter.CountBench f.wasm 5 f.names 40 20
```

The arguments: the module, the body's size, the names, how many functions to list, and the call tree's floor per exchange. An
`Instruction`'s address finds its function through `module.codeSection()`; `-Drontolisp.wasm.debug-func-sizes` names the functions.

# A module with no WASI: `no-wasi/`

[`Calc.java`](Calc.java) has no `main` and no annotation: its public static methods, `add` and
`fib`, are the exports of a module that imports nothing
([A reactor of public static methods](../../../doc/en/java/index.md#a-reactor-of-public-static-methods)).
Under a JDK it is an ordinary class. It is not a Maven project: one source file and
[`build.sh`](build.sh), which needs the JAR of this repository and wasmtime 47+, the first
with wasm-GC on by default.

```bash
./build.sh                                          # calc.wasm, then:
wasmtime run --invoke add calc.wasm 1 2             # 3
wasmtime run --invoke fib calc.wasm 10              # 55
wasmtime run --invoke add calc.wasm 2147483647 1    # -2147483648, an int wraps
```

`--invoke` takes the export's name and its arguments follow the module; wasmtime warns that
`--invoke` with arguments and results is experimental. The exports here are numbers because
`--invoke` passes nothing else: a `String` crosses as UTF-8 in linear memory, which
`--emit-js-glue`'s JavaScript stages
([`cloudflare-workers/hello-java/`](../../cloudflare-workers/hello-java/README.md)).

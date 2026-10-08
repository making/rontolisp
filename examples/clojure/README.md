# Clojure sample programs

Programs for the **experimental** Clojure front end: a `.clj` file is read as a small
subset of Clojure, as described in the
[Clojure section](../../doc/en/clojure/index.md). Each runs identically on the
interpreter, the JVM, WASM and `--component`, and each is checked in
[`examples.yaml`](../examples.yaml).

| Program | Shows |
| --- | --- |
| [`demo.clj`](demo.clj) | Recursion, `loop`/`recur`, `#(...)`, higher-order `map`/`filter`/`reduce`, vectors, keywords, `cond`, `let`, a directly called `fn` |
| [`ring-hello.clj`](ring-hello.clj) | A Ring handler served by `ring.adapter.rontolisp/run-server`, built with the built-in `ring.util.response` and the parameter middleware: one source for a socket (interpreter, JVM), `-o app.war`, `--component` under `wasmtime serve` and a `--no-wasi` reactor |
| [`host-boundary/`](host-boundary) | A host function the module imports and a function the host calls (`rontolisp.wasm`): [`main.clj`](host-boundary/main.clj) runs under wasmtime with [`host.clj`](host-boundary/host.clj) preloaded, or under node through [`run.mjs`](host-boundary/run.mjs) and the `--emit-js-glue` file |
| [`greeter/`](greeter) | A WIT world the program implements (`rontolisp.wit/export`), lifted as a component |
| [`../wit/keyvalue/page-hits.clj`](../wit/keyvalue/page-hits.clj) | A WIT interface the program calls (`rontolisp.wit/import`): a store the program provides on the interpreter and the JVM, wasmtime's `wasi:keyvalue` under `--component` |

The host boundary programs, from their own directories:

```bash
cd host-boundary
java -jar $JAR main.clj -o main.wasm --no-wasi
java -jar $JAR host.clj -o host.wasm --no-wasi
wasmtime run --preload host=host.wasm --invoke add10 main.wasm 32             # 42
java -jar $JAR main.clj -o main.wasm --no-wasi --emit-js-glue && node run.mjs  # 42

cd ../greeter
java -jar $JAR greeter.clj -o greeter.wasm --component --emit-wit
wasmtime run --invoke 'shout("Ada", true)' greeter.wasm                        # "HELLO, ADA!!!"
```

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/clojure/demo.clj                                     # interpreter
java -jar $JAR examples/clojure/demo.clj -o Demo.class && java Demo         # JVM
java -jar $JAR examples/clojure/demo.clj -o demo.wasm && wasmtime run demo.wasm
java -jar $JAR examples/clojure/demo.clj -o demo-c.wasm --component && wasmtime run demo-c.wasm
```

`rontolisp format` indents Common Lisp, not Clojure, so these files are indented by
hand.

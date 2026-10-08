# Clojure sample programs

Programs for the **experimental** Clojure front end: a `.clj` file is read as a small
subset of Clojure, as described in the
[Clojure section](../../doc/en/clojure/index.md). Each runs identically on the
interpreter, the JVM, WASM and `--component`, and each is checked in
[`examples.yaml`](../examples.yaml).

| Program | Shows |
| --- | --- |
| [`demo.clj`](demo.clj) | Recursion, `loop`/`recur`, `#(...)`, higher-order `map`/`filter`/`reduce`, vectors, keywords, `cond`, `let`, a directly called `fn` |
| [`ring-hello.clj`](ring-hello.clj) | A Ring handler served by `ring.adapter.rontolisp/run-server`: one source for a socket (interpreter, JVM), `-o app.war`, `--component` under `wasmtime serve` and a `--no-wasi` reactor |

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/clojure/demo.clj                                     # interpreter
java -jar $JAR examples/clojure/demo.clj -o Demo.class && java Demo         # JVM
java -jar $JAR examples/clojure/demo.clj -o demo.wasm && wasmtime run demo.wasm
java -jar $JAR examples/clojure/demo.clj -o demo-c.wasm --component && wasmtime run demo-c.wasm
```

`rontolisp format` indents Common Lisp, not Clojure, so these files are indented by
hand.

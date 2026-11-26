# hello-java — a Java class as a Worker

[`../hello`](../hello) in Java: [`Worker.java`](Worker.java) has no `main`, and
its public static methods are the module's exports, which
[`src/index.js`](src/index.js) calls as JavaScript functions. No annotation and
no dependency: under a JDK `Worker` is an ordinary class.

The Java language read here is the **experimental** subset of
[`doc/en/java/`](../../../doc/en/java/index.md).

```bash
./build.sh          # Worker.java -> src/worker.wasm + src/worker.js
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl http://localhost:8787/
Hello, Workers, from Java compiled to WebAssembly! (greeting 1)
$ curl 'http://localhost:8787/?name=you'
Hello, you, from Java compiled to WebAssembly! (greeting 2)
$ curl 'http://localhost:8787/add?a=2&b=3'
2 + 3 = 5
$ curl 'http://localhost:8787/fib?n=20'
fib(20) = 6765
```

The greeting count is `Worker`'s static state: it lives as long as the
instance, one per isolate.

## The module

```java
public class Worker {
    public static int add(int a, int b) { ... }
    public static int fib(int n) { ... }
    public static String greet(String name) { ... }
}
```

A program with no `main`, compiled `--no-wasi`, exports each public static
method of the class under its own name. The module imports nothing:

```console
$ node -e 'const m = new WebAssembly.Module(require("fs").readFileSync("src/worker.wasm"));
           console.log(WebAssembly.Module.imports(m), WebAssembly.Module.exports(m).map(e => e.name))'
[] [ 'memory', '_initialize', '__ronto_alloc', '__ronto_alloc_mark', '__ronto_alloc_reset', '__ronto_set_time', 'add', 'fib', 'greet' ]
```

`--emit-js-glue` writes `src/worker.js` beside it: `instantiate(module)`
creates the instance and calls `_initialize`, which runs the static
initializers, and each export becomes a function over JavaScript values -- a
`String` is copied into linear memory and back out by the glue. It is the glue a
Lisp reactor's `rontolisp:wasm-export`s get ([Running WASM in a
Browser](../../../doc/en/guides/wasm-browser.md#reactor-modules-by-hand)).

Unlike `../hello`, this module is wasm-GC (`--no-wasi`), which the Workers
runtime runs, so `--emit-js-glue` writes its glue. `--no-gc` compiles `Worker`
too, to a module any engine runs; its host then writes and reads the strings
itself, as `../hello`'s `src/index.js` does
([A reactor on `--no-gc`](../../../doc/en/java/index.md#a-reactor-on---no-gc)).
Module sizes (2026-11-25): 3,956 B here and 1,103 B with `--no-gc`; `../hello`'s
Lisp is 465 B with `--no-gc` and 2,609 B with `--no-wasi`.

## Tests

[`WorkerTest.java`](WorkerTest.java) calls the methods.
[`rontolisp test`](../../../doc/en/java/testing.md) runs it on the interpreter,
or compiled with `-o` to a module or a class:

```bash
rontolisp test WorkerTest.java
# Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello-java/build.sh
```

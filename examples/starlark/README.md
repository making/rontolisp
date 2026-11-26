# Starlark sample programs

Programs for the **experimental** Starlark front end: a `.star` file is read as
Starlark, as described in the [Starlark section](../../doc/en/starlark/index.md). Each runs
identically on the interpreter, the JVM, WASM and `--component`, and each is checked in
[`examples.yaml`](../examples.yaml).

| Program | Shows |
| --- | --- |
| [`targets.star`](targets.star) | A build graph: targets as dicts, the build order and the targets a change rebuilds, with comprehensions, default and named arguments, `sorted`, `max(key = ...)`, `%` and `format` |

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/starlark/targets.star                                    # interpreter
java -jar $JAR examples/starlark/targets.star -o Targets.class && java Targets   # JVM
java -jar $JAR examples/starlark/targets.star -o targets.wasm && wasmtime run targets.wasm
java -jar $JAR examples/starlark/targets.star -o targets-c.wasm --component && wasmtime run targets-c.wasm
```

`targets.star` keeps to the strict dialect -- no `for` or `if` at the top level, no
recursion, each global bound once -- so it also runs under Bazel's Java Starlark, which
prints the same output.

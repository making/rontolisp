# Clojure sample programs

Programs for the **experimental** Clojure front end: a `.clj` or `.cljc` file is read as a
subset of Clojure, as described in the [Clojure section](../../doc/en/clojure/index.md).
Each is checked in [`examples.yaml`](../examples.yaml) on the backends its row names, and
where a program runs under `clj` too, the expected output is `clj` 1.12.6's.

| Program | Shows | Backends |
| --- | --- | --- |
| [`demo.clj`](demo.clj) | Recursion, `loop`/`recur`, `#(...)`, higher-order `map`/`filter`/`reduce`, vectors, keywords, `cond`, `let`, a directly called `fn` | all four |
| [`collections.clj`](collections.clj) | A map's insertion-order `seq`, a large map built by `assoc`, records, a `deftype` keying a map by its own `hasheq`/`equals`, `hash`, transients, `PersistentQueue`, a `deftype`/`reify` of the collection interfaces, keyword-argument and namespaced destructuring, `case`/`condp`, `iteration`, byte arrays, `#inst`/`#uuid` | all four |
| [`text.clj`](text.clj) | `java.util.regex`'s syntax (lookaround, inline flags, named groups, `\G`), `\p{...}`, `\X`, `\N{name}`, `split`/`replace`, `format`'s flags, full case mapping, Unicode whitespace, charsets | all four |
| [`macros.clj`](macros.clj) | Macro bodies calling the program's own functions and reading its vars, `eval` at expansion time, a template naming a private helper, `&form`/`&env`, `macroexpand` at run time | all four |
| [`state.clj`](state.clj) | Atoms, refs with a rolled-back transaction and a validator, agents that fail, refuse sends and restart (`agent-error`, `restart-agent`, `:continue`), delays, `binding`, `alter-var-root`, `with-redefs` | all four |
| [`bundled-namespaces.clj`](bundled-namespaces.clj) | The clojure.jar namespaces: `clojure.walk`, `edn`, `template`, `pprint` (`code-dispatch`, `print-table`, `cl-format`), `data`, `zip`, `core.protocols`, `datafy`, `stacktrace`, `repl`, `math`, `core.reducers`, `set`, `instant`, `xml`, `java.io` | all four |
| [`portable.cljc`](portable.cljc) | Reader conditionals in a `.cljc` file (`#?`, `#?@`, the `:rontolisp` and `:clj` features), `read-string` under `{:read-cond :preserve}` and `:allow`, a tagged literal kept as data | all four |
| [`deps-project/`](deps-project) | A `deps.edn` project: a `:local/root` library written as `.cljc`, `ns` clauses (`:refer-clojure :exclude`, `:rename`, `:as-alias`), a data reader from `data_readers.clj`, `-M` through an alias's `:main-opts`, `-X` an `:exec-fn`, `rontolisp test` | all four |
| [`java-interop.clj`](java-interop.clj) | Fns as `Runnable`/`Callable`/`Comparator` (inside collections too) on a thread pool, `Comparator`'s default methods on a fn, a reified `Runnable`, a `Comparable` `deftype`, a record as a `java.util.Map`, host exceptions caught by class | interpreter, JVM |
| [`java-library/`](java-library) | A Java library of the program's own on `--java-classpath` | interpreter, JVM |
| [`http-client.clj`](http-client.clj) | `rontolisp.http-client` against a Ring handler the same program serves on a free local port: query and form parameters, a body, a streamed reply, the 404 `ex-info`, `:async` | interpreter, JVM |
| [`ring-hello.clj`](ring-hello.clj) | A Ring handler served by `ring.adapter.rontolisp/run-server`, built with the built-in `ring.util.response` and the parameter middleware: one source for a socket (interpreter, JVM), `-o app.war`, `--component` under `wasmtime serve` and a `--no-wasi` reactor | compiled only |
| [`host-boundary/`](host-boundary) | A host function the module imports and a function the host calls (`rontolisp.wasm`): [`main.clj`](host-boundary/main.clj) runs under wasmtime with [`host.clj`](host-boundary/host.clj) preloaded, or under node through [`run.mjs`](host-boundary/run.mjs) and the `--emit-js-glue` file | compiled only |
| [`greeter/`](greeter) | A WIT world the program implements (`rontolisp.wit/export`), lifted as a component | compiled only |
| [`wit-types/`](wit-types) | A WIT import whose functions take and answer an enum, flags, variants, a record, an option, a list of tuples, a `result` argument and a `result` whose error arm is thrown, against a provider the program binds | interpreter, JVM |
| [`../wit/keyvalue/page-hits.clj`](../wit/keyvalue/page-hits.clj) | A WIT interface the program calls (`rontolisp.wit/import`): a store the program provides on the interpreter and the JVM, wasmtime's `wasi:keyvalue` under `--component` | interpreter, JVM, component |

The single-file programs, on the four backends:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR examples/clojure/demo.clj                                     # interpreter
java -jar $JAR examples/clojure/demo.clj -o Demo.class && java Demo         # JVM
java -jar $JAR examples/clojure/demo.clj -o demo.wasm && wasmtime run demo.wasm
java -jar $JAR examples/clojure/demo.clj -o demo-c.wasm --component && wasmtime run demo-c.wasm
```

## The deps.edn project

[`deps-project/deps.edn`](deps-project/deps.edn) names a library in a directory of its own
(`units/`, a `:local/root`) and three aliases. From the project's directory, as `clj` runs
it:

```bash
cd deps-project
java -jar $JAR -M:invoice tea coffee           # app.main/-main with "EUR" "tea" "coffee"
java -jar $JAR -X:summary :currency '"USD"'    # app.main/summary with the merged map
java -jar $JAR test                            # the -test namespaces under test/
```

Each compiles too: the options come first (`-M` and `-X` end rontolisp's options), the
command line's arguments are kept, and the artifact's own follow them.

```bash
java -jar $JAR -o Invoice.class -M:invoice && java Invoice tea coffee
java -jar $JAR -o invoice.wasm -M:invoice && wasmtime run invoice.wasm tea coffee
java -jar $JAR test -o tests.wasm --component && wasmtime run tests.wasm
```

A Maven coordinate (`{:mvn/version "..."}`) or a git one (`:git/url`, `:git/tag`,
`:git/sha`) is fetched by the command line, as `clj` fetches it, into `~/.m2/repository`
and `~/.rontolisp/gitlibs`. Both need the network, so the examples suite runs none. A
library such as instaparse then loads like a project namespace:

```clojure
;; deps.edn
{:deps {instaparse/instaparse {:mvn/version "1.5.0"}}}
```

```bash
java -jar $JAR -M -m my.app            # resolves, fetches and runs, on every backend
```

## A Java library

[`java-library/`](java-library) holds a Java source the program calls; compile it, then
hand its classes to rontolisp (a jar works the same way, and `--java-dep
group:artifact:version` fetches one from Maven Central):

```bash
cd java-library
javac -d classes java/example/Slugger.java
java -jar $JAR main.clj --java-classpath classes
java -jar $JAR main.clj --java-classpath classes -o Main.class && java -cp .:classes Main
java -jar $JAR main.clj --java-classpath classes -o main.jar && java -jar main.jar
```

## The host boundary

From their own directories:

```bash
cd host-boundary
java -jar $JAR main.clj -o main.wasm --no-wasi
java -jar $JAR host.clj -o host.wasm --no-wasi
wasmtime run --preload host=host.wasm --invoke add10 main.wasm 32             # 42
java -jar $JAR main.clj -o main.wasm --no-wasi --emit-js-glue && node run.mjs  # 42

cd ../greeter
java -jar $JAR greeter.clj -o greeter.wasm --component --emit-wit
wasmtime run --invoke 'shout("Ada", true)' greeter.wasm                        # "HELLO, ADA!!!"

cd ../wit-types
java -jar $JAR shop.clj                                                        # the provider in shop.clj
```

`rontolisp format` indents Common Lisp, not Clojure, so these files are indented by
hand.

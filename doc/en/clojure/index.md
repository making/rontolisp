# Clojure (experimental)

**Experimental.** rontolisp reads a small subset of Clojure -- just large enough to run a
Clojure-shaped program on every backend. Conformance is partial by design and nothing here
is a subset or compatibility promise. Use it to try a Clojure program on the JVM or
WebAssembly; write Common Lisp for anything you need to keep working. Interop runs on the
interpreter and the JVM only: the wasm backends reject the `java:` surface it lowers to.

A `.clj` or `.cljc` file is read as Clojure (a `.cljc` file with its reader conditionals);
`--source-language clojure` says so for any other file. The language is picked per file, so one program may mix the two.

```bash
rontolisp hello.clj                                # interpreter
rontolisp hello.clj -o Hello.class && java Hello   # JVM
rontolisp hello.clj -o hello.wasm && wasmtime run hello.wasm
rontolisp hello.clj -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp prog.txt --source-language clojure       # any extension
```

A `deps.edn` project runs as `clj` runs it: `rontolisp -M:dev -m my.app args`,
`rontolisp -X:build`, and `rontolisp test` for its tests ([Semantics](semantics.md)).

`--no-gc` is refused: that backend has no pairs, symbols or closures.

```clojure
(defn fact [n]
  (if (< n 2) 1 (* n (fact (- n 1)))))

(println (fact 10))
(println (reduce + 0 (map #(* % %) (filter odd? '(1 2 3 4 5)))))
```

The supported surface, one page per name, is the [Reference](reference.md): the core
binding and control forms with sequential and map destructuring, the threading macros,
the seq verbs over strict list views of every collection, the persistent map and set
operations over `equal` hash tables, the numeric and predicate core, atoms and volatiles,
multimethods with hierarchies, `try`/`catch`/`finally` with `ex-info`, namespaces across
files (`ns`/`require` of project files) and `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn`, `clojure.test` with its summary runner, a Ring adapter serving a handler on every
backend (`ring.adapter.rontolisp`), and Java interop. What each form lowers to, and what stays
refused, is [Semantics](semantics.md); the reader rules are [Syntax](syntax.md); the
departures from the oracle are [Deviations](deviations.md).

## This section

| Page | Contents |
|---|---|
| [REPL](repl.md) | The `clojure>` REPL |
| [Syntax](syntax.md) | The reader: literals, characters, radix integers, keywords |
| [Semantics](semantics.md) | What lowers to what; the refused forms |
| [Deviations](deviations.md) | Where behavior departs from Clojure |
| [Reference](reference.md) | One page per supported name |

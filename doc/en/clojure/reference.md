# Reference

One page per name the Clojure front end provides: every form, verb and interop entry of
the experimental subset, grouped by area. **Each name in a table links to its own page**,
which gives the signature, the behavior, the deviation from the oracle where there is one
and a worked example. The refused forms are not listed here -- [Semantics](semantics.md)
has them.

| Page | Contents |
|---|---|
| [Syntax and definition](reference/syntax.md) | `def`/`defn`/`fn`, binding, conditionals, `quote`, `comment`, `declare` |
| [Threading](reference/threading.md) | `->`, `->>`, `as->`, `doto` and the conditional threaders |
| [Seqs](reference/seqs.md) | The seq family, lazy where taken and strict otherwise |
| [Iteration](reference/iteration.md) | `doseq`/`dotimes`/`for`, `dorun`/`doall` and `run!` |
| [Maps, sets and vectors](reference/collections.md) | The persistent collection verbs over `equal` hash tables |
| [Higher-order functions](reference/higher-order.md) | `comp`/`partial`/`complement`/`constantly`/`identity`/`memoize`/`trampoline`, `juxt`/`fnil`/`every-pred`/`some-fn`/`min-key`/`max-key` |
| [Numbers and predicates](reference/numbers.md) | Arithmetic, comparison and the type predicates |
| [Type and collection predicates](reference/predicates.md) | `seq?`/`map?`/`keyword?`/`int?` and the other kind tests, `identical?`, `distinct?`, `extends?` |
| [Instants and UUIDs](reference/instants.md) | The `#inst` and `#uuid` values: `inst-ms`, `random-uuid`, `parse-uuid` |
| [State](reference/state.md) | `atom`/`deref`/`swap!` and the volatile trio |
| [Multimethods and hierarchies](reference/multimethods.md) | `defmulti`/`defmethod`, `derive` and the hierarchy reads |
| [Protocols, records and types](reference/protocols.md) | `defprotocol`/`defrecord`/`deftype`, `reify`, the `extend` family and `satisfies?` |
| [Errors](reference/errors.md) | `try`/`catch`/`finally`, `throw`, `ex-info` and its readers |
| [Namespaces](reference/namespaces.md) | `ns` and the top-level `require`/`use`/`import` |
| [Names and keywords](reference/names.md) | `name`/`namespace`/`keyword`/`symbol` |
| [(clojure.string)](reference/string.md) | The string library, plus the core `subs` |
| [(clojure.set)](reference/clojure-set.md) | The relational set library: `union`/`intersection`/`difference`, `select`/`project`/`rename`, `index`/`join`, `subset?`/`superset?` |
| [clojure.data](reference/clojure-data.md) | Recursive comparison: `diff` answering what only each side holds and what both hold |
| [clojure.datafy](reference/clojure-datafy.md) | Values as data and navigation from data: `datafy`/`nav` over the `clojure.core.protocols` protocols, and `CollReduce`/`IKVReduce`, which `reduce`/`reduce-kv` consult |
| [clojure.core.reducers](reference/clojure-core-reducers.md) | Reducers and folders: `map`/`filter`/`mapcat`/`take`... as reducible views, `fold` in parts, `foldcat`/`cat`/`append!`, `monoid` |
| [clojure.edn](reference/clojure-edn.md) | Reading EDN data: `read-string`/`read` with `:eof`, `:readers` and `:default` |
| [clojure.instant](reference/clojure-instant.md) | Reading RFC 3339 timestamps: `parse-timestamp`, `validated`, `read-instant-date`/`-timestamp`/`-calendar` |
| [clojure.math](reference/clojure-math.md) | Functions over doubles that answer the same bits on every backend, rounding and the neighbors of a double, and long arithmetic that refuses to overflow |
| [clojure.pprint](reference/clojure-pprint.md) | Pretty printing: `pprint`/`write` within a right margin, `print-table`, `code-dispatch` for code, and the dispatch a program extends or replaces |
| [clojure.stacktrace](reference/clojure-stacktrace.md) | Printing throwables and their causes: `root-cause`, `print-throwable`, `print-stack-trace`, `print-cause-trace` |
| [clojure.template](reference/clojure-template.md) | Expression templates: `apply-template`, `do-template` |
| [clojure.walk](reference/clojure-walk.md) | Generic traversal of nested data: `walk`/`postwalk`/`prewalk`, the `-replace` pair, `keywordize-keys`/`stringify-keys` |
| [clojure.zip](reference/clojure-zip.md) | Functional tree editing with zippers: `vector-zip`/`seq-zip`/`xml-zip`, moves, edits and the depth-first walk |
| [Regular expressions](reference/regex.md) | `re-find`/`re-seq`/`re-matches`, `re-matcher`/`re-groups`, `re-pattern` and pattern `split`/`replace` |
| [Ring adapter](reference/ring.md) | `ring.adapter.rontolisp/run-server`: a Ring handler served on every transport |
| [Ring utilities](reference/ring-util.md) | The built-in `ring.util.*` and `ring.middleware.*` namespaces: response builders, URL and form coding, parameter middleware |
| [HTTP client (rontolisp.http-client)](reference/http-client.md) | `request` and `get`/`post`/`put`/`delete`/`head`/`patch` with babashka.http-client's API, over `rontolisp:fetch` on every transport |
| [WASM host functions (rontolisp.wasm)](reference/wasm.md) | `defimport`/`export`: a host function a module calls, a function the host calls |
| [WIT contracts (rontolisp.wit)](reference/wit.md) | `import`/`export`/`provide`: a WIT interface called, a WIT world implemented |
| [Java interop](reference/interop.md) | `.`, `..`, construction, `memfn`, `proxy` |
| [Transducers](reference/transducers.md) | `transduce`/`eduction`/`sequence`/`completing`, `reduced` and its companions, `cat`, and the one-argument arities of the seq verbs |
| [IO](reference/io.md) | `spit`/`slurp`/`line-seq`, `clojure.java.io/reader` and `format` |
| [Tests (clojure.test)](reference/test.md) | `deftest`/`is`/`are`/`testing` and the `run-tests` summary runner |
| [Macros](reference/macros.md) | `defmacro`, syntax-quote, `gensym`, `macroexpand-1`, `macroexpand` |

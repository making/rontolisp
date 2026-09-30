# The EXPERIMENTAL Clojure front end (`am.ik.rontolisp.clojure`)

**Invariant: a Clojure program is read case-sensitively and LOWERED to the Common Lisp
core forms the pipeline already consumes; no backend learns a Clojure name.** There is
no IR beneath those forms to target instead: both backends dispatch on the canonical
operator NAME and `FreeVarAnalyzer` is a hand walker over the same names, so an unknown
head would be a silent three-way divergence. Status is experimental -- a small subset
by design, no subset or compatibility promise -- and every user surface says so
(`--source-language` help, the title of `doc/*/clojure/index.md`). `--no-gc` is refused
by name (`CompileFrontend.run`): it has no cons cell, no symbol and no closure.

## Where it sits

- `clojure` depends on the AST types and `reader` only: `ClojureReader` (text ->
  datums, its own case-sensitive reader), `ClojureLowering` (datums -> core forms),
  `Clojure` (the facade), `ClojureSession` + `ClojureTopLevel` (the REPL session).
- Reached ONLY through the seam: `eval/SourceLanguage.CLOJURE`, picked for `.clj` or
  by `--source-language clojure` (`clj`) (`.kb/source-language.md`). Per FILE, so a
  Common Lisp file may `(load "lib.clj")` and call its functions as `(c%name ...)`.
- A whole FILE is lowered at once: defn-or-variable is decided by a pre-scan. A REPL
  has no whole program to scan and lowers through a session instead ("A session"
  below).

## The lowering table

| Clojure | lowers to | why |
|---|---|---|
| identifier `foo` | symbol `c%foo`, always prefixed | the prefix holds a lowercase letter and `%`, so no name can reach a `LispNames` case label, a lambda-list keyword or `T`/`NIL`; the spelling is otherwise verbatim, so `Foo` and `foo` stay apart; `:` -> `%c`, `%` -> `%%` keeps the map injective |
| `defn` | `defun` of the mangled name, called directly | keeps the direct call and the tree shaker. Pass one collects every top-level `def`/`defn` name, so a definition may use one below it |
| `def` | top-level `setq` of the mangled name | |
| `fn` / `#(...)` | `lambda` | `#(...)` arguments travel as one `&rest` list, `%`..`%9` as `(nth n args)`; at most 9 args; the body forms are wrapped as ONE call (`#(f a b)` -> `(f a b)`, matching the dominant spelling; multi-form bodies need an explicit `do`) |
| `let` | `let*` | Clojure's `let` is sequential |
| `loop`/`recur` | `labels` self call | the interpreter's tail calls make it constant-stack |
| `if`/`when`/`cond`/`do`/`and`/`or` | the core forms | `cond` with an odd trailing arm treats it as the default; `:else` is true; every test treats `nil` and the false object as falsey (an explicit null-or-false check, the test bound once to a temporary) |
| `not` | an explicit null-or-false check answering `T`-or-false | |
| `=`/`not=`/`<`/`>`/`<=`/`>=` | the Common Lisp operation, answering `T`-or-false | so `(= false nil)` is false and printing spells it `false` |
| `nil?`/`empty?` | `null`, answering `T`-or-false | `(nil? false)` is false |
| `false?`/`true?`/`boolean?` | their predicates (`eq` against the false object / `T`), answering `T`-or-false; as values, lambdas answering a Common Lisp boolean | |
| `map`/`filter`/`reduce`/`apply`/`concat` | `mapcar`/`remove-if-not`/`reduce`/`apply`/`append` | `reduce` is 2/3-arity with the Clojure argument order (`(reduce f val coll)`) mapped onto CL `reduce` `:initial-value`; `apply` is the 2-arity only (`(apply f args)`) |
| a vector literal | a `vector` call | |
| `first`/`rest`/`count` | `car`/`cdr`/`length` | the seq family runs over LISTS only, except these three which take any sequence |
| `str` | `concatenate 'string` over mapped parts | `(str)` is `""`; `nil` maps to `""`, `true`/`false` to `"true"`/`"false"`, anything else through `princ-to-string` |
| `println`/`print` | one `concatenate` + `princ`, the newline folded into the last part | a string prints unquoted; collections print in CL notation; parts are concatenated with NO separator (Clojure separates with spaces); each part maps like `str` except `nil` prints as `nil` |
| `true` | `LispTrue` (`T`) | a raw symbol spelled `T` is unbound -- `evalSymbolRef` looks the name up |
| `nil` | `NIL` | falsey |
| `false` | the value of `rontolisp::%clojure-false`, bound before anything else runs | a DISTINCT non-`NIL` symbol spelled `false` (the distinct-object treatment `scheme.lisp`'s `#f` uses); falsey in every conditional through the lowered tests; `eq`-comparable by name on every backend |
| a keyword `:foo` | the symbol `:FOO` verbatim (upcased) | data, never called; collides case-insensitively and prints upcased (see "Deviations") |
| `ns` | nothing | a namespace declaration defines nothing |
| `quote` | `quote`, with symbols mangled and vectors re-emitted as `vector` calls | |

## Deviations (each a real work item)

- Keywords are the symbols `:foo` verbatim and the printer upcases them (`:A` prints
  for `:a`), so `:a` and `:A` collide and printed keywords are the wrong case.
- Map and set literals are refused by name (`a map literal is not supported yet`); a
  map needs the hash-table runtime (`.kb/hash-tables.md`).
- The seq family runs over LISTS only (`first`=`car`, `rest`=`cdr`,
  `count`=`length` being the exceptions). Vectors-as-seqs need real design.
- Destructuring, `->`/`->>`, `defmulti`/`defmethod`, protocols, `atom`/`swap!`/`deref`,
  lazy seqs, metadata `^`, `var`/`#'`: all absent. The reader parses `@x`, `^meta`,
  backquote, `~` into marked lists the lowering refuses.
- `defn` inside a body works only in statement position; `declare` is absent. `def`
  inside a body mutates the global at run time, unreviewed.
- Errors carry no source position: the reader's `LispReadException`s are
  position-less (the plumbing for `file:line:column` exists --
  `.kb/source-positions.md` -- the spike just never threads the reader's line/column
  into the lowering's errors).
- Printing: `pr`/`prn`/`print-method`/`pprint` are absent; `println` concatenates with
  no separator, and a boolean nested in a collection prints in Common Lisp notation
  (`T`/`NIL` for `true`/`nil`) while the false object spells `false`.

## A session

`ClojureSession` keeps the lowering across buffers: every buffer declares its own
top-level `def`/`defn` names into the session's globals first, so a later buffer may
call what an earlier one defined. `SourceSession` prompts `clojure> `, echoes through
the Common Lisp printer, and decides completeness by bracket counting over `()[]{}` 
(outside strings and `;` comments) plus a reader probe for a trailing dispatch prefix
(`'`, `` ` ``, `~`, `@`, `^`, `#'`, `#_`, `#(`).

## Tests

`ClojureReaderTest`, `ClojureLoweringTest`, `ClojureSessionTest`,
`ClojureSpecE2eTest` (the interpreter, the JVM and both WASM backends over
`src/test/resources/clojure-spec.yaml` -- one case per lowering-table row and per
builtin group, concatenated into one program and sliced back per case, the
`ci-spec.yaml` idea), `SourceLanguageTest` (the `.clj` pick, the `clojure`/`clj`
override), `RontoLispCliTest` (the `.clj` file pick, the `clojure>` REPL
transcript, the `--no-gc` refusal), `PackageCycleTest` (the `clojure` package
sees only the AST types and `reader`). `examples/clojure/demo.clj` stays the
user-facing smoke test, pinned by `examples.yaml` (`ExamplesE2eTest`); the old
inline `ClojureE2eTest` over the same program was removed when the spec arrived.
`nth` takes the collection first (`(nth coll i)` -> `(NTH i coll)`), `quot` is
`truncate`, an `(ns ...)` form defines nothing (the file-level skip used to
match only a bare `ns` symbol), and identifiers keep their case behind the
prefix (`Foo` and `foo` no longer fold into one symbol).

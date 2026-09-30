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
| identifier `foo` | symbol `c%foo`, always prefixed | the prefix holds a lowercase letter and `%`, so no name can reach a `LispNames` case label, a lambda-list keyword or `T`/`NIL`; `:` -> `%c`, `%` -> `%%` keeps the map injective |
| `defn` | `defun` of the mangled name, called directly | keeps the direct call and the tree shaker. Pass one collects every top-level `def`/`defn` name, so a definition may use one below it |
| `def` | top-level `setq` of the mangled name | |
| `fn` / `#(...)` | `lambda` | `#(...)` arguments travel as one `&rest` list, `%`..`%9` as `(nth n args)`; at most 9 args; the body forms are wrapped as ONE call (`#(f a b)` -> `(f a b)`, matching the dominant spelling; multi-form bodies need an explicit `do`) |
| `let` | `let*` | Clojure's `let` is sequential |
| `loop`/`recur` | `labels` self call | the interpreter's tail calls make it constant-stack |
| `if`/`when`/`cond`/`do`/`and`/`or` | the core forms | `cond` with an odd trailing arm treats it as the default; `:else` is true |
| `map`/`filter`/`reduce`/`apply`/`concat` | `mapcar`/`remove-if-not`/`reduce`/`apply`/`append` | `reduce` is 2/3-arity with the Clojure argument order (`(reduce f val coll)`) mapped onto CL `reduce` `:initial-value`; `apply` is the 2-arity only (`(apply f args)`) |
| a vector literal | a `vector` call | |
| `first`/`rest`/`count` | `car`/`cdr`/`length` | the seq family runs over LISTS only, except these three which take any sequence |
| `str` | `concatenate 'string` over `princ-to-string` parts | `(str)` is `""` |
| `println`/`print` | one `concatenate` + `princ`, the newline folded into the last part | a string prints unquoted; collections print in CL notation; parts are concatenated with NO separator (Clojure separates with spaces) |
| `true` | `LispTrue` (`T`) | a raw symbol spelled `T` is unbound -- `evalSymbolRef` looks the name up |
| `nil`/`false` | `NIL` | both falsey; folded together (see "Deviations") |
| a keyword `:foo` | the symbol `:FOO` verbatim (upcased) | data, never called; collides case-insensitively and prints upcased (see "Deviations") |
| `ns` | nothing | a namespace declaration defines nothing |
| `quote` | `quote`, with symbols mangled and vectors re-emitted as `vector` calls | |

## Deviations (each a real work item)

- `false` is folded into `nil`. Both are falsey so tests behave, but `(false? x)`
  cannot distinguish them and `(= false nil)` is true. Clojure-distinct booleans need
  the distinct-object treatment `scheme.lisp`'s false value uses.
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
  no separator and booleans/keywords print as `T`/`NIL`/upcased keywords.

## A session

`ClojureSession` keeps the lowering across buffers: every buffer declares its own
top-level `def`/`defn` names into the session's globals first, so a later buffer may
call what an earlier one defined. `SourceSession` prompts `clojure> `, echoes through
the Common Lisp printer, and decides completeness by bracket counting over `()[]{}` 
(outside strings and `;` comments) plus a reader probe for a trailing dispatch prefix
(`'`, `` ` ``, `~`, `@`, `^`, `#'`, `#_`, `#(`).

## Tests

`ClojureReaderTest`, `ClojureLoweringTest`, `ClojureSessionTest`, `ClojureE2eTest`
(the interpreter, the JVM and both WASM backends on one corpus -- the `demo.clj`
seed), `SourceLanguageTest` (the `.clj` pick, the `clojure`/`clj` override),
`RontoLispCliTest` (the `.clj` file pick, the `clojure>` REPL transcript, the
`--no-gc` refusal), `PackageCycleTest` (the `clojure` package sees only the AST types
and `reader`).

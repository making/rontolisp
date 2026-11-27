# The EXPERIMENTAL Clojure front end (`am.ik.rontolisp.clojure`)

**Invariant: a Clojure program is read case-sensitively and LOWERED to the Common Lisp
core forms the pipeline already consumes; no backend learns a Clojure name.** No IR lies
beneath those forms to target instead: both backends dispatch on the canonical operator NAME
and `FreeVarAnalyzer` is a hand walker over the same names, so an unknown head would be a
silent three-way divergence. Experimental, a small subset with no compatibility promise; every
user surface says so (`--source-language` help, the title of `doc/*/clojure/index.md`).
`--no-gc` is refused by name (`CompileFrontend.run`): it has no cons cell, symbol or closure.

The oracle is `clj` 1.12.6.1673: a behavior is decided by running it there, and every
`clojure-spec.yaml` line is diffed against it. Multi-file projects (test namespaces run as
`(require 'ns) (clojure.test/run-tests 'ns)` drivers from `test/` under the project's own
`deps.edn`) are pinned by `ClojureProjectNamespacesTest`, every line the oracle's.

## Differential parity harness (g17)

`src/test/java/am/ik/rontolisp/e2e/ClojureDifferentialParityTest.java` runs `(ns, var, form)`
rows, one small pure expression per builtin, through `clj -M` and every backend, comparing
printed values. A row is one `row(...)` call; a known printer deviation is whitelisted per row
with `deviating(...)`, never globally, so the row fails if either side moves. It skips without
`clj` (like `CiSpecE2eTest` without its binary); the wasm legs need a usable `wasmtime` on
`PATH`. Its 117 rows agree on all four backends.

## Where it sits

- `ClojureReader` (text -> datums), `ClojureLowering` (the hub; the named cycle root in
  `PackageCycleTest`) and one `Clojure*Lowering` slice per feature. The package sees only the
  AST types and `reader`; what it needs of `compiler` comes through `ClojureBoundary` ("Host
  boundary").
- Run-time helpers: `src/main/resources/am/ik/rontolisp/eval/clojure.lisp`, spliced by
  `eval/ClojureLibrary`; each runtime (printer, STM, regex, ...) is referenced only when used
  and pruned otherwise, so a program without it pays nothing. Macro bodies run at lower time
  (`eval/ClojureMacroTime`).
- Reached ONLY through the seam: `eval/SourceLanguage.CLOJURE`, per FILE for `.clj`/`.cljc`
  ("Reader conditionals") or by `--source-language clojure` (`.kb/source-language.md`). A
  Common Lisp file may `(load "lib.clj")` and call `(c%name ...)` (`(|c%my.ns/name| ...)`).
- The browser needs no Java-side change; doc ` ```clojure ` fences are Run cells
  (`.kb/documentation-site.md`) checked by `DocExamplesTest`.
- A whole FILE is lowered at once: pass one pre-scans the top-level definition names, so a
  form may use a definition below it; a top-level macro call's expansion is pre-scanned right
  before it lowers (`ClojureLowering.declareExpansion`), a name settled above keeping its kind
  until the expansion's own definition (`(def p (memoize p))` after `(defn p ...)`). Below the
  top level a `def` interns its name before its value lowers (`preInternDef`), like the
  oracle's, so the value may call its own var. A call lowered while a var was only declared
  reads its root, so the `defn` defining it later, even inside an expansion, stores there too
  (`declaredRootReads`), as the oracle binds the declared var (clojure-spec
  `declare-forward-declares`). A REPL lowers through a session ("A session"); a tagged literal
  waiting for the data readers makes pass two re-read the text a datum at a time ("Data
  readers").
- Errors name the innermost form's source position (`.kb/source-positions.md`).

## Values

| Clojure | representation | notes |
|---|---|---|
| identifier `foo` | `c%foo`; a var of namespace `n` `c%n/foo` (`user`'s keep `c%foo`) | the prefix keeps names off `LispNames` case labels, lambda-list keywords and `T`/`NIL`; `:` -> `%c`, `%` -> `%%` keeps the map injective; a local carries no namespace; generated names add a lone `%` suffix (`%defN`, `%local`) |
| `nil` / `true` | `NIL` / `T` | `nil` IS the empty list |
| `false` | the value of `rontolisp::%clojure-false`, a non-`NIL` symbol spelled `false` | `scheme.lisp`'s `#f` treatment: every lowered test is an explicit null-or-false check; `java:` passes it as Java's false ("Java interop") |
| symbol carrying metadata | `(:C%SYMBOL sym meta)` | read as the symbol ("Vars and metadata") |
| `:foo`, `:a/b` | `(:C%KEYWORD "foo")` | compared by `equal` |
| `{k v}` | an `equal` hash table, never mutated: a verb answers a structure-sharing copy ("The copy a map verb takes") | the shared runtime (`.kb/hash-tables.md`) gives persistence at O(log n) a verb without a new representation every backend would print, hash and compare (a persistent-map library's cost); keys: "Structural keys" |
| `#{..}` | `(:C%SET table)` | a literal repeating `=` read forms is refused (`ClojureReader.equivKey`); "Literal keys equal once evaluated" |
| `sorted-map` / `sorted-set` (and `-by`) | `(:C%SORTED setp cmp items)` | "Sorted collections" |
| `[..]` | a CL vector | so is a string, so `vector?`/`coll?` exclude strings |
| list, seq | a CL list; lazy `(:C%LAZY cell)` | "Laziness" |
| atom, volatile, ref, agent | `(:C%ATOM #(value))`, a volatile `#(value :C%VOLATILE)`; `#(value nil validator state watches)` for a validated ref, an agent, and every atom and ref of a program that may give a reference a validator or watch | one cell shape, so STM verbs accept atoms; `volatile?` reads the length 2, so slot 1 of the longer cells stays nil ("State") |
| record / deftype / reify | `(:C%RECORD tag fields table class)` / `(:C%TYPE ...)` / a fresh `:C%REIFY` tag | "Dispatch", "Host interfaces"; a record's or deftype's tag is its class value ("Record and deftype class values") |
| class value (`class`, a class literal) | the host's `java.lang.Class` (a `clojure.lang` class a stub class) where the program reaches the host, else `(:C%CLASS name)` | "Class values"; the kind keywords stay the dispatch tags |
| `#"re"` | `(:C%PATTERN stamp ...)` | the stamp is a gensym, so `=` is identity like the oracle |
| byte array (`byte-array`, `.getBytes`) | `(:C%BYTES octets)` | "Byte arrays" |
| transient | `(:C%TRANSIENT #(kind data count live))` | "Transients" |
| `clojure.lang.PersistentQueue` | a deftype of the shipped namespace `clojure.lang` | "Persistent queues" |
| charset (`StandardCharsets/UTF_8`, `Charset/forName` of a literal) | `(:C%CHARSET name class)` | "Charset values" |
| `reduced`, var, nil dispatch value | `(:C%REDUCED x)`, `(:C%VAR "ns/name" getter)`, `(:C%NIL)` | |
| delay | `(:C%DELAY #(thunk value failure mutex))` | "Laziness" |
| `ex-info` | a condition with message and data slots | |
| `#inst`, `#uuid` | `(:C%INST ms)`, `(:C%UUID msb lsb)` | "Instants and UUIDs" |
| `1.50M`, `bigdec` / `2N` | `(:C%DECIMAL 150 2)` / integer | "Decimals"; only an integer past the long range prints its `N` |
| `\a`, `"..."` | `LispChar`, `LispString` | the oracle's spellings and escapes, else its refusals; a buffer ending mid-`\u` stays `truncated \u escape` so a session waits for more input |
| radix ints (`0x`, `Nr`, leading-`0` octal) | `LispInteger` | a shaped token parsing to nothing is `Invalid number` |

An empty `[]`, `{}` or `#{}` -- literal, quoted, or answered by a core verb the oracle answers
with its `EMPTY` statics (`(vector)`, `empty`, `pop`, ...) -- is one canonical object per kind
and program; `(vec nil)`, `with-meta`, `persistent!` and `disj` of a set's last member answer
fresh ones, like the oracle (clojure-spec `empty-collection-literals-are-one-canonical-object`).
They stay empty because nothing persistent is edited in place; `.putAll` into the empty map
throws the oracle's `UnsupportedOperationException` ("HashMap construction").

Predicates answer `T`-or-false, as calls and as values, so `(map odd? [1 2])` prints
`(true false)`. Every core verb also names a function value checking the oracle's arities
unless its row says otherwise.

## Locals named like a special

A local is lexical in the oracle, but one spelled like a `^:dynamic` var of `user` would lower
to that var's special and bind it dynamically. `localSym` renames such a local `c%name%local`
at every binding site (`binding` keeps the var's symbol). The colliding names
(`shadowedSpecials`): `*out*`/`*in*`/`*agent*` plus every `^:dynamic` var of `user` the
pre-scan finds at any depth, since the proclamation is program-wide. Only a colliding local is
renamed, so a program without one lowers byte-identically; a namespaced var never collides.

## The lowering table

A *worker* row lowers a call to one call of the fixed-parameter `rontolisp::%clojure-NAME`
after a lower-time arity check in the oracle's wording, and the value to
`#'rontolisp::%clojure-NAME-v`, checking at run time (`ClojureCoreLowering` unless the row names
another slice). A bare kebab-case pin is a `clojure-spec.yaml` case. Deviations the user page
`doc/en/clojure/deviations.md` lists are not repeated here; a row keeps only their reasons.

| Clojure | lowers to | notes |
|---|---|---|
| `defn` | `defun` of the mangled name, called directly; several arities a `c%f%N` `defun` each plus a dispatch one | keeps the direct call and the tree shaker; a wrong count: "Arity refusals". In a body: statement position only, defined when the body runs (`defnInBody`). `:pre`/`:post` rewrite every fn-shaped clause's body (`ClojureBindingLowering.conditioned`), never an inline method's |
| `definline` | `defn`: nothing here inlines call sites (pin `definline-defines-a-function-like-defn`) | as a value unknown like every macro |
| redefined `defn` | a fresh internal name per definition (`%defN`) | call sites below a definition call it; a value position captures the one current there. In a session or a run-time lowering a redefinition also re-points earlier calls (`.kb/nrepl.md`, "Redefinition in an open lowering"; `.kb/jvm-run-time-lowering.md`, "Redefinition") |
| a call to a `VARIABLE`-kind head | `funcall` of a value cell holding a real function, else `rontolisp::%clojure-call` | the dispatcher looks up collections like `IFn`; a declared, never defined name calls its unbound root (the oracle's `Attempting to call unbound fn`), but stays a direct call in a session, where a later buffer may define it |
| a call whose head is a compound form (`((fn ...) x)`, `((set v) x)`, `((first ks) m)`) | `funcall` of a `function`/`lambda` head, else `rontolisp::%clojure-call` | |
| a call to a core name the program defines or binds | the program's call | `known(name)` is checked before the core rows |
| `declare` | `nil` (a `^:dynamic` name's declaim hoisted) | a pre-scan forward declaration; a real definition wins ("Vars and metadata") |
| `def` | `%clojure-var-store`, folding to its `setq` (`ClojureArms.Family.WATCH`) | the value lowers against the OLD binding (`(def p (memoize p))` after `(defn p ...)` captures `#'c%p`); a lone string is the value. A name qualified with the current namespace (or its alias) is the bare var, so a syntax-quote template's def reaches it; another namespace's is refused. Pins `ClojureLoweringTest.defReadsItsNameAndALoneStringAsTheOracleDoes`, `syntax-quote-qualifies-unresolved-symbols-with-the-defining-namespace` |
| `defonce` | `def` unless `boundp` | |
| `defn-` | a private `defn` | "Namespaces and project files" |
| `fn` / `#(...)` | `lambda` (several arities: one over `&rest`); a named `fn`, or one a `recur` reaches, a `labels` self-binding | `#()` READS as the oracle's `(fn* [p1__N# ...] (body))` (`ClojureReader.readAnonFn`); N restarts per top-level form (the oracle's counter is process-wide), so a printed spelling stays put wherever the form sits |
| destructuring (`let`/`loop`/`fn`/`defn`/`for`/`doseq`) | `let*` pairs over one temporary per pattern | a vector pattern reads by index, one with `&` through the seq view, so `[[k v] & ks]` walks a map or set (pin `a-vector-pattern-takes-as-after-its-rest`); `:ns/keys`/`::keys` refuse what the oracle's `ns-keys` spec refuses (pin `namespaced-map-destructuring`); `ClojureLoopLowering.collectKeyNames` mirrors `bindKeys` (pin `map-destructuring-reads-through-get`). A map pattern reads a `seq?` (never a vector) as `seq-to-map-for-destructuring` does, so `& {:keys ...}` takes keyword arguments (pin `a-map-pattern-reads-a-seq-as-keyword-arguments`) |
| `let` / `letfn` | `let*` (sequential) / one `labels` | |
| `loop` / `recur` | `labels` self call | "recur"; a fresh call per `recur`, so a closure captures its own iteration's value (pin `dotimes-and-loop-closures-capture-the-iteration`) |
| `->` `->>` `as->` `doto` `cond->` `cond->>` `some->` `some->>` | datum rewrites around one temporary | `some->` stops at `nil`, not `false`; a step carrying reader metadata (`^Class (box)`) threads into the form inside (pin `threading-threads-first-and-last`) |
| `list*` | a right fold of `cons` over the seq view | value: pin `core-functions-resolve-as-values` |
| `doseq` | nested `do` loops over the seq view | never `dolist`: a realized lazy cons's tail is a wrapper, not list structure |
| `for` | `(rontolisp::%clojure-for coll step depth)` | "Laziness"; an empty vector refused |
| `dotimes [i n]` | the core `dotimes` over `(truncate n)` | the oracle's `intCast`; the variable is rebound per iteration, so a closure captures its own (same pin as `loop`) |
| `dorun` / `doall` | `%clojure-dorun` / `-doall` | |
| `if` `when` `cond` `do` `and` `or` `not` | the core forms over null-or-false tests | `and`/`or`/`assert` as values: "Macros" |
| `when-let` `if-let` `when-not` `if-not` `when-first` | `let*` over one temporary plus the test | |
| `if-some` `when-some` | `if-let`/`when-let` with `(null tmp)` as the test (`ClojureSeqLowering.testedBinding`, shared by all four) | the pattern destructures in the taken branch only (pin `if-let-and-when-let-destructure-only-in-the-taken-branch`) |
| `case` | nested `if`s over one temporary (`ClojureControlLowering.caseOf`) | one test per constant, chosen at lower time by its kind to match the oracle's hash-then-`=` (`1.0` never matches `1`, `##NaN` nothing); duplicates refused at lower time (`caseKey`). Linear, never a jump table; `(case 1M 1 :int :other)` is `:other`, like the oracle's |
| `condp` | the oracle's `emit` recursion (`ClojureControlLowering.condpOf`) | `(condp instance? ...)` has no function value to bind, so each clause tests `(instance? test expr)` |
| `while` | `(do () (falsey-test nil) body)` | |
| `locking` | host: `rontolisp:with-mutex` over `(%clojure-monitor x)`; wasm: no mutex (`ClojureLowering.hostTarget`) | one reentrant mutex per value, made under a guard mutex: a Ring handler runs a thread per request on the interpreter and the JVM. The tables are pruned with the helper, so a program without `locking` and every wasm program carries none |
| `monitor-enter` `monitor-exit` | the halves of `locking` (`ClojureControlLowering.monitorOf`) | a hold unbalanced across top-level forms stays held (the oracle throws at the boundary), a documented deviation |
| `with-redefs` | the guarded stores and the body in `unwind-protect` (`ClojureVarLowering.withRedefsOf`) | "Vars and metadata" |
| `alter-var-root` `alter-meta!` `reset-meta!` | `%clojure-alter-root` for a literal `#'x` of a value cell, else `%clojure-alter-var-root`; `%clojure-alter-meta`, `%clojure-reset-meta` | "Vars and metadata" |
| `try`/`catch`/`finally`/`throw` | `handler-case` inside `unwind-protect`; `%clojure-throw` | "Catching"; a host `Throwable` is thrown as its `java:java-exception` ("Host exceptions"), a non-exception as a `ClassCastException` whose message is its rendering |
| `ex-info` `ex-data` `ex-message` `ex-cause`, `.getMessage` `.getLocalizedMessage` `.getCause` | one `clojure.lisp` call | "Exceptions" |
| `StackTraceElement->vec` | the oracle's `[class method file line]` vector of the host element | "Exceptions"; elements exist only where the host does. Pins `stack-trace-element-vec-refuses-nil-and-foreign-values`, `ClojureInteropTest#stackTraceElementVecAnswersTheOraclesVector` |
| `EMPTY-NODE` | the empty vector | pin `empty-node-is-the-empty-vector` |
| `assert` | `if` around the `AssertionError` carrier ("Refusals") | the failed form is rendered at lower time where it can be (`ClojureStringLowering.prSource`), so no printer is linked; else `%clojure-str-of` links the collection printer |
| `atom` `deref`/`@` `swap!` `reset!` `compare-and-set!` `swap-vals!` `reset-vals!` `volatile!` `vswap!` `vreset!` | the cell; writes through the guarded `%clojure-ref-*`, the plain `%clojure-atom-*` where no reference can have a validator or watch (`.kb/concurrent-served-requests.md`) | `compare-and-set!` compares with `eql`; `deref` also reads a host `Future` ("A host `Future` under `deref`"), the HTTP client's ("HTTP client") and Clojure futures and promises ("State") |
| `future` `future-call` `promise` `deliver` | a host `FutureTask` / a rendezvous cell (`ClojureFutureLowering`) | "State" |
| `ref` `dosync` `sync` `io!` `alter` `commute` `ref-set` `ensure` `with-loading-context` | the cell under `%clojure-stm-*` | "State"; values: pin `core-functions-resolve-as-values` |
| `ref-history-count` `ref-min-history` `ref-max-history` | worker | counts committed transactions that wrote the ref, capped by its min history like the oracle's retained chain; a `ref` registers its history only where the program names a history verb (`usedRefHistory`; always in a session). Pin `ref-history-count-and-the-min-max-bounds` |
| `agent` `send` `send-off` `await` `await-for` `shutdown-agents` `agent-error` `restart-agent` `set-error-handler!` `set-error-mode!` `error-handler` `error-mode` `agent-errors` `clear-agent-errors` | the cell under `%clojure-agent-*` | "State"; values: pin `core-functions-resolve-as-values` |
| `add-watch` `remove-watch` `set-validator!` `get-validator` | `%clojure-<verb>` | "State" |
| `binding` / `set!` | `let*` of specials plus a depth counter | "State" |
| `with-precision` | `let*` rebinding `*math-context*` | "Decimals"; pin `with-precision-binds-math-context-around-an-exact-body` |
| `tap>` `add-tap` `remove-tap` | `%clojure-tap-*` (`ClojureTapLowering`) | host: the bounded queue is drained by one thread that ends at the empty take, not the oracle's daemon blocked on `take`: nothing polls, and the browser interpreter's synchronous `make-thread` delivers inside `tap>` instead of looping forever. Wasm delivers synchronously (`ClojureLibrary.HOST_ARMS_WITHOUT_HOST`), so no queue or thread reaches a module. Pins `taps-receive-every-tapped-value`, `a-full-tap-queue-answers-false` (host legs) |
| `bound-fn` / `bound-fn*` | `(bound-fn* (fn ...))`; `%clojure-bound-fn*` pushes the creation-time bindings | "State"; the capture enumerates `dynamicVars`, so a pass declaring more after a capture site lowers again with the full set seeded (pin `bound-fn-captures-the-creation-bindings`) |
| `with-local-vars` | a fresh cell per name | "State"; pin `with-local-vars-binds-fresh-var-cells` |
| `with-open` | `let*` plus `unwind-protect` closing in reverse | |
| `with-out-str` | `let*` rebinding `*standard-output*` to `make-string-output-stream` | never a literal `with-output-to-string`: it flips a WASM module into EH mode |
| `with-in-str` / `read-line` | `let*` rebinding `*standard-input*` / `(read-line *standard-input* nil nil)` | pin `with-in-str-binds-in-to-the-string-and-read-line-reads-it` |
| `time` | the value timed with `get-internal-real-time` | a double count, so a `\d+\.\d+` match over the report holds; only the shape pins |
| `*out*` `*in*` `*err*` `*agent*`, the flags, `*ns*` `*file*` `*source-path*` `*repl*` `*1`..`*e` | one special each (`ClojureCoreSpecials`); a stream read as a value goes through `Special.reader` ("Streams as values") | "State"; a program var of the name wins |
| `defstruct` `struct` `struct-map` | a key vector plus fresh-table builders | values: pin `core-functions-resolve-as-values` |
| `create-struct` `accessor` | worker | a definition is the key vector and prints as one (the oracle's: its definition object). Pin `struct-definitions-create-and-read-through-an-accessor` |
| `var-set` | `%clojure-var-set` | on a var always refused: thread bindings are not reified, and the oracle refuses a var not thread-locally bound ("Vars and metadata") |
| `with-redefs-fn` | `%clojure-with-redefs-fn` | a var with a registered root setter stores through its value cell (`needRootSetters` lowers again with every value-cell site carrying one), any other swaps its getter ("Vars and metadata") |
| `intern` | `%clojure-intern` | a new var gets a cell of its own; a `def`/`defn` of the name keeps its own cell ("Vars and metadata") |
| `with-meta` `meta` `vary-meta`, reader `^` | "Vars and metadata" | |
| `var` / `#'` | `(rontolisp::%clojure-var "ns/x" (lambda () ROOT) META)` | "Vars and metadata" |
| `defmacro` `macroexpand(-1)` `gensym`, `` ` `` `~` `~@` | "Macros" | `unquote`/`unquote-splicing` as values are unbound roots, the oracle's `Var$Unbound` (pin `core-functions-resolve-as-values`) |
| `defmulti` `defmethod` hierarchies `defprotocol` `defrecord` `deftype` `reify` `extend*` `satisfies?` `instance?` `class` `type` | "Dispatch" | interfaces and `Object` overrides: "Host interfaces"; as values the protocol and multimethod verbs read the run-time registries (pin `protocol-value-arms-run-where-calls-run`) |
| `ns` `require` `use` `import` `in-ns` | alias and refer wiring; a project namespace's file loaded at the `require` | "Namespaces and project files"; `require`/`use`/`in-ns` as values refuse every application at run time (pin `eval-and-resolve-of-a-computed-symbol-refuse-at-run-time`) |
| `all-ns` `loaded-libs` | `%clojure-all-ns` / `%clojure-loaded-libs` over the names known at lower time | a mapping past a hundred pairs is built in pieces ("Namespaces and project files") |
| `create-ns` `remove-ns` | `%clojure-create-ns` / `%clojure-remove-ns` | `find-ns`/`the-ns` read the run-time table beside the lower-time names ("Namespaces and project files") |
| `ns-publics` `ns-interns` `ns-map` `ns-refers` `ns-aliases` `ns-imports` | the map the lowering knows; anything computed refuses at run time | as a value one program-wide object (it closes over nothing, so no per-snapshot key; "Namespaces and project files") |
| `alias` `refer` `refer-clojure` `ns-unmap` `ns-unalias` | lower-time wiring; anything computed refuses at run time | "Namespaces and project files" |
| `requiring-resolve` | the var `resolve` answers, loading a project namespace first | only a quoted symbol resolves ("Namespaces and project files") |
| `load-string` `load-file` | a literal text (or a literal path's file, from the working directory like `Compiler/loadFile`) lowered where the call stands; anything computed lowers at run time on the interpreter ("Lowering at run time") | an `ns`/`in-ns` inside is the load's own, the namespace restored after the call like the oracle's `Compiler/load` binding (pin `requiring-resolve-and-load-string`) |
| `clojure.string` (`join` `split` `split-lines` `upper-case` `lower-case` `capitalize` `trim` `triml` `trimr` `trim-newline` `blank?` `starts-with?` `ends-with?` `includes?` `index-of` `last-index-of` `replace` `replace-first` `escape` `re-quote-replacement` `reverse`) | core string operations | a plain string never compiles to a pattern. `index-of`'s start is clamped into `[0, length]` first: CL's `search` refuses one outside the string. `trim`/`blank?` trim `Character.isWhitespace`, the oracle's test (`trimBag`); Java `.trim` trims `<= 32` (`javaTrimBag`) (pin `blank-and-the-trims-take-java-whitespace`). "Case mapping" |
| `clojure.set` (`union` `intersection` `difference` `select` `project` `rename-keys` `rename` `index` `map-invert` `join` `subset?` `superset?`: every public var) | `ClojureSetLowering`: worker | the oracle's own algorithms, so an answer's kind follows the same input (`union` grows its largest). Project witness: a test namespace `:use`-ing `clojure.set` over a source namespace's relations (`ClojureProjectNamespacesTest`) |
| `ring.adapter.rontolisp` (`run-server`) | `rontolisp::%http-serve` (`ClojureRingLowering`) | "Ring adapter" |
| `rontolisp.wasm` (`defimport` `export`, a `defn`'s `:wasm/export`) | `rontolisp:wasm-import` hoisted / `rontolisp:wasm-export` after the program | "Host boundary" |
| `rontolisp.wit` (`import` `export` `provide`) | `rontolisp:wit-import` hoisted / `rontolisp:wit-export` after the program; `rontolisp:wit-provide` | "Host boundary" |
| `ring.util.response` `ring.util.request` `ring.util.codec` `ring.util.mime-type` `ring.middleware.params` `ring.middleware.keyword-params` `ring.middleware.content-type` | Clojure source in the jar, loaded like a project file; each `rontolisp.internal.*` kernel is one call (`ClojureKernelLowering`) | "Ring util namespaces" |
| `rontolisp.http-client` (`request` `get` `post` `put` `delete` `head` `patch`) | the same; `rontolisp.internal.http/fetch` is `rontolisp:fetch` itself | "HTTP client" |
| `clojure.edn` (`read-string` `read`) | `ClojureEdnLowering`: worker | "Reading" |
| `clojure.walk` | Clojure source in the jar written for this front end, loaded like a project file; a startup namespace, so also on its first qualified name | "clojure.jar namespaces" |
| `clojure.template` | the same, loaded at its `require` | "clojure.jar namespaces" |
| `clojure.data` `clojure.zip` `clojure.datafy` `clojure.stacktrace` | the same, loaded at its `require` | "clojure.jar namespaces" |
| `clojure.reflect` | the same; host reflection on the interpreter and the JVM, a call-time refusal on WASM | "clojure.jar namespaces" |
| `clojure.core.protocols` | the same, a startup namespace | "clojure.jar namespaces" |
| `clojure.core.reducers` | the same; cat's accumulator is a `rontolisp.internal.reducers` kernel | "clojure.jar namespaces" |
| `clojure.pprint` | the same; the layout engine and `cl-format`'s number and case text are `rontolisp.internal.pprint` kernels | "clojure.jar namespaces" |
| `clojure.math` | the same; a double function lowers in place to `%strict-math`, `round` and the long arithmetic are kernels | "clojure.jar namespaces" |
| `subs`, `.substring` | `%clojure-subs` ("Refusals") | a bound outside the string is the oracle's `StringIndexOutOfBoundsException` where a class is read |
| `format` | `ClojureFormatLowering`: a literal string parsed at lower time as `java.util.Formatter` parses it, then one CL `format nil` of `%clojure-format-*` pieces; computed, or as a value, at run time (`%clojure-format-v`) | provenance: the refusals are the Formatter documentation's flag table, their order and words (`STEPS`) a JDK's answers over every flag set x width x precision x conversion, which `ClojureFormatLoweringTest` asks again of both parses. Refusals come after the arguments evaluate. `%e`/`%f`/`%g` round the SHORTEST decimal half up, like the oracle on JDK 25 (`%.2f` of 0.125 is `0.13`), not CL `~F` (binary half-even, saturating at a long). `%h`/`%a`/`%t` refuse only when called, so a library merely spelling one loads. Pins `clojure-format-args`, `clojure-format-flags-and-the-formatter-refusals`, `ClojureLoweringTest.ioEntryPointsAndFormat` |
| `spit` `slurp` `line-seq` `file-seq` | the io family's arms ("clojure.java.io") | `slurp` closes a stream it reads (the oracle's `with-open`), `line-seq` never. A read of a CLOSED stream goes through `%clojure-open-reader` (the oracle's `IOException: Stream closed`): unguarded, both wasm backends read a closed string input stream (`.kb/read-load-streams.md`). `read` is not guarded: the oracle wraps the failure in a `LispReader$ReaderException` over a `LineNumberingPushbackReader` but not a `PushbackReader`, one stream kind here. A second close is harmless (`.kb/read-load-streams.md`, "close on an already-closed stream"). Pins `slurp-closes-the-stream-it-reads`, `ClojureInteropTest#filesRoundTripThroughReaderAndLineSeq`, `ClojureWasmFileIoTest` |
| `read-string` `read` | `%clojure-read-string`/`-read` over the call site's namespace context | "Reading" |
| regex `#"..."`, `re-pattern` `re-matcher` `re-find` `re-seq` `re-matches` `re-groups` | `RONTOLISP::%CLOJURE-RE-COMPILE` and the shared engine (`regex.lisp`) | "Regex" |
| `map` `filter` `concat` | `%clojure-map`/`-filter`/`-concat` | lazy when an input is lazy, strict otherwise ("Laziness") |
| `reduce` / `apply` | `%clojure-reduce` / CL `apply` over the whole-collection view | a type with its own `CollReduce` row reduces through it ("clojure.jar namespaces"); "The IFn dispatcher stays at the call site". `apply` as a value is `%clojure-apply-v`, not CL `#'apply`, which refuses any last argument but a list |
| `first` `rest` `next` `seq` `cons` | `car`/`cdr` over `%clojure-seq`, `%clojure-cons` | the seq view: a map gives a two-vector per entry, a set its members, in insertion order ("Seq verbs over a wrapper"); `next` realizes the tail it answers ("Laziness") |
| `nth` / `second` | `%clojure-nth` / `%clojure-seq-nth` | |
| `take` `drop` `take-while` `drop-while` | `%clojure-take`/`-drop`/`-take-while`/`-drop-while` | lazy-or-strict ("Laziness") |
| `last` `butlast` `count` `empty?` `vec` `set` `sort` `sort-by` `reverse` `frequencies` `group-by` `select-keys` | strict, over the whole-collection view | `sort` orders by `compare` ("Sorted collections"); a comparator's answer is read like the oracle's `AFunction.compare` (`ClojureFilterLowering.comparatorBefore`) |
| `keep` `keep-indexed` `map-indexed` `remove` `distinct` `interpose` `partition` `interleave` | `%clojure-NAME` | lazy-or-strict ("Laziness"); `partition`'s pad is read lazily (tools.cli pads with `(repeat nil)`) |
| `some` `every?` `zipmap` | stepping, so an infinite input answers | |
| `mapv` `filterv` `mapcat` | a vector / `%clojure-mapcat` | several collections end with the shortest, so an infinite one beside a finite one ends (pins `mapv-answers-vectors`, `mapcat-concats-mapped-views`) |
| `ffirst` `nfirst` `fnext` `nnext` | `car`/`next` over the seq view | each level seqs (pin `fnext-and-nnext-step-past-the-head`) |
| `range` | a strict list; without an end lazy (`%clojure-range`) | a zero step signals; nothing chunks |
| `lazy-seq` `lazy-cat` `repeat` `cycle` `iterate` `repeatedly` | "Laziness" | finite `repeat`/`repeatedly` answer strict lists |
| `delay` `force` | `%clojure-delay` / `%clojure-force` | "Laziness" |
| `drop-last` `split-at` `split-with` `take-last` `nthnext` `nthrest` `peek` `pop` `not-empty` `dedupe` `replace` `find` `subvec` `key` `val` `map-entry?` `rseq` `find-keyword` `partition-all` `partition-by` `min-key` `max-key` `juxt` `fnil` `every-pred` `some-fn` `update-keys` `update-vals` `reduce-kv` `test` `empty` `comparator` `hash-set` `tree-seq` `flatten` | worker | `dedupe`, `partition-*`, `drop-last` and each half of `split-at`/`split-with` (the oracle's `[(take ...) (drop ...)]`) are lazy-or-strict ("Laziness"); `empty` of a plain collection answers the canonical empty object. A strict seq peeks where the oracle's LazySeq throws; `subvec` copies. A map entry IS a plain 2-vector (`map-entry?`): an entry representation would touch `first`/`seq`/`find`/`reduce-kv`/destructuring/`=`/printing for a distinction only `clojure.walk`-style code reads. Keywords have no intern table (`find-keyword`): one would cost every `keyword` call and every compiled output a global table |
| `hash` `hash-ordered-coll` `hash-unordered-coll` `mix-collection-hash` `hash-combine` | worker (`hash` is `%clojure-hasheq`) | "Hashes" |
| `pmap` | one future per element, dereferenced in order with the oracle's lookahead | sequential `map` on wasm. Pins `pmap-and-pvalues-run-branches-concurrently-in-order`, `pmap-and-pvalues-errors-surface-at-the-deref-in-order` |
| `pvalues` | one future per expression, dereferenced in order | `pmap`'s two pins |
| `reductions` `random-sample` `bounded-count` `partitionv` `partitionv-all` `splitv-at` `replicate` `xml-seq` | worker | pins `reductions-answers-the-running-reductions`, `random-sample-keeps-members-by-probability`, `bounded-count-counts-counted-collections-and-walks-the-rest`, `partitionv-and-partitionv-all-answer-vectors`, `splitv-at-replicate-and-xml-seq` |
| `update` `update-in` `assoc-in` `get-in` `merge` `merge-with` `into` | fresh tables over the old pairs | the `-in` verbs unroll a literal key vector at lower time, walk any other path at run time (pin `the-nested-verbs-walk-a-computed-key-path`); `merge` of a non-table goes through `%clojure-merge-entry-plist`, named without `sorted` so a program building no sorted collection carries none of it |
| `assoc` `dissoc` `get` `contains?` `keys` `vals` `conj` `disj` `hash-map` `array-map` | table operations | `assoc` onto a vector copies it whole (`make-array` plus an `aref` loop, not `copy-seq`/`coerce`), paid only by programs that assoc a vector (pins `assoc-on-a-vector-replaces-or-appends-by-index`, `update-and-the-nested-verbs-reach-into-vectors`, `replace-maps-through-a-map-or-a-vector`). `conj` onto a map accepts a seq or set of plain vectors, which the oracle refuses, since a map entry is a plain 2-vector here; `conj`'s list arm takes nil, a list and a lazy seq, never another wrapper (pin `conj-adds-to-every-collection`) |
| `sorted-map` `sorted-map-by` `sorted-set` `sorted-set-by` `subseq` `rsubseq` `compare` `vector-of` | `ClojureSortedLowering`: worker | a literal core test of `subseq`/`rsubseq` lowers to its keyword; "Sorted collections" |
| a keyword, set, map or vector in call position or as a function value | the table-aware read / member / `nth` | a literal keyword head with a wrong count refuses at lower time |
| `comp` `partial` `complement` `constantly` `identity` `memoize` `trampoline` | closures | |
| `=` / `not=` | the spliced `%clojure-equal` per neighbouring pair | functions by identity: one shared function object per `defun` on every backend; two closures over one body are never `=`. A site-independent core value (`map`, `pos-int?`) is one program-wide object (`%clojure-shared-value`), `identical?` like the oracle's var roots and usable as a map key (malli keys its registry by predicate values); a value snapshotting lower-time state (`the-ns`, `read-string`) shares one object per snapshot digest. A host object on the left asks its `equals` ([eq-numbers.md](eq-numbers.md) "Host objects", `ClojureInteropTest#equalsOfAHostObjectAndAValueAsksTheLeftOperand`; [eq-numbers.md](eq-numbers.md) "Clojure `=` of a host collection"). Cost (measured 2026-10-13): byte-identical on wasm for a program taking no core function value. Pins `function-values-compare-by-identity`, `predicate-values-are-shared-across-references`, `core-function-values-are-shared-across-references`, `snapshotting-core-values-share-one-object-per-snapshot`, `snapshotting-namespace-values-keep-their-sites-snapshot`, `read-string-values-keep-their-sites-reader-context` |
| `<` `>` `<=` `>=` `==` `nil?` `false?` `true?` `boolean?` `boolean` `string?` `symbol?` `vector?` `fn?` | the CL test answering `T`-or-false | as values `&rest` lambdas answering `T`-or-false: `(map < [1 2] [2 1])` is `(true false)`, not `(true nil)` |
| the type predicates (`coll?` `seq?` `sequential?` `map?` `set?` `list?` `record?` `seqable?` `associative?` `counted?` `indexed?` `reversible?` `ifn?`, `number?` `integer?` `int?` `double?` `float?` `ratio?` `rational?` `nat-int?` `pos-int?` `neg-int?` `infinite?` `NaN?`, `keyword?` `ident?` and the `simple-`/`qualified-` six, `char?` `var?` `volatile?` `realized?` `special-symbol?`, `inst?` `uuid?` `uri?` `class?`) | `ClojurePredicateLowering`: `(if TEST T false)`; as a value one program-wide object per name | a wrapper is a cons whose car is a CL keyword, so a list test must exclude it. `set?`/`reversible?` fold to the plain helpers where no sorted collection is built ("Sorted collections"); `decimal?`, `bytes?`, `delay?` and the like fold to `progn` where no such value is made, the oracle's answer for every value the program builds. `identical?` is `eql` plus keyword spelling ([eq-numbers.md](eq-numbers.md) "Host objects"). Pins `collection-predicates-*`, `number-predicates-*`, `name-predicates-*`, `any-not-any-*`, `var-volatile-*`, `predicates-of-kinds-*`, `special-symbol-*`, `extends-*`, `coll-is-false-for-the-tagged-wrappers`; deviations `the-seq-predicates-follow-the-list-representation`, `decimal-literals-are-decimals-and-small-bigint-literals-plain-integers`, `identical-compares-numbers-and-symbols-by-value`; `ClojureInteropTest.identicalOnHostObjectsIsIdentity`, `ClojureInteropTest.hostKindPredicatesTestTheHostClass` |
| `int` `long` | `%clojure-int-cast` / `%clojure-long-cast`; a literal folded at lower time | the oracle's `RT.intCast`/`RT.longCast` of an object (pin `int-and-long-are-the-oracles-range-checked-casts`); `int`: see `byte` |
| `cast` | a named class: the `instance?` test inline; else `%clojure-cast` | pin `cast-casts-or-refuses-with-the-oracles-classes` |
| `char` | `%clojure-char`, the oracle's `charCast` | pin `char-refuses-numbers-outside-the-char-range` |
| `quot` | `truncate` | |
| `double` `float` `byte` `short` `num` | `%clojure-NAME`; `byte`/`short` compare a double BEFORE truncating (`(byte 127.9)` refuses) | pin `primitive-casts-double-float-byte-short-and-num`. `byte`/`short`/`int` answer plain integers (pin `narrowing-casts-answer-plain-integers`, `ClojureInteropTest#narrowingCastsBehaveAsPlainIntegersAtTheCallLayer`): boxing was refused, since it would move the whole value model (`+`, `=`, printing, every `unmarshal`) on all four backends, new kind tests in every program's hottest paths for what only `java:` programs observe |
| `bigint` `biginteger` `bigdec` `rationalize` `numerator` `denominator` `unchecked-int` `-long` `-short` `-byte` `-char` `-double` `-float` | `%clojure-NAME` | integers and ratios are plain Lisp rationals ("Decimals"). A string is checked at both ends before `parse-integer`, which skips surrounding whitespace `BigInteger` refuses (pin `bigint-and-bigdec-strings-refuse-surrounding-whitespace`). `rationalize`/`bigdec` of a double read the SHORTEST decimal it prints as (`princ-to-string`, the oracle's `BigDecimal.valueOf`: `(rationalize 0.1)` is `1/10`), not the Lisp `rationalize`. Pin `narrowing-casts-answer-plain-integers` (its in-range bigint printed through `str` deviates) |
| `unchecked-add` `-subtract` `-multiply` `-inc` `-dec` `-negate` and the `-int` verbs (`unchecked-add-int` `-subtract-int` `-multiply-int` `-inc-int` `-dec-int` `-negate-int` `-divide-int` `-remainder-int`) | `%clojure-NAME` | pin `unchecked-arithmetic-verbs-wrap-at-long-and-int-width` |
| `inc'` `dec'` `+'` `-'` `*'` | the plain verb after a lower-time arity check | pins `checked-arithmetic-is-plain-arithmetic-that-never-overflows`, `checked-arithmetic-refuses-non-numbers-with-the-oracles-classes` |
| `+` `-` `*` `inc` `dec` under `*unchecked-math*` | by the flag where the call lowers, the oracle's compile-time read (`ClojureLowering.uncheckedMath`): false the plain verb, true `%clojure-wrapping-NAME`, unknown (a computed `set!`, a run-time lowering) `%clojure-NAME` reading it at run time | 64-bit integer operands wrap mod 2^64 (test.check's SplittableRandom port depends on it, "Library probes"); a decimal never wraps. Not read as each call runs: a run-time read wrapped only on the thread whose binding the file's `set!` set (`stest/check`'s `pmap` futures drew bignums). Pins `unchecked-math-wraps-star-and-plus-for-splittable-random`, `ClojureLoweringTest#setBangRefusesEveryOtherTargetLikeTheOracle` |
| `bit-and` `bit-or` `bit-xor` `bit-and-not` `bit-not` `bit-shift-left` `bit-shift-right` `unsigned-bit-shift-right` `bit-set` `bit-clear` `bit-flip` `bit-test` | worker | the oracle's on longs. Pins `bit-operations-are-the-oracles-on-longs`, `bit-operations-as-values-and-refuse-non-longs` |
| `name` `namespace` `keyword` `symbol` | string workers over the demangled spelling | pin `symbol-builds-mangled-names` |
| `str` / `pr-str` | `concatenate` over `%clojure-str-of` parts | collections print readably inside `str` too (the oracle's `toString`), so `spit` writes what `read` reads back |
| `print-str` / `prn-str` / `println-str` | `%clojure-print-str` | prints to a private string stream, never a `*standard-output*` rebinding: the parts evaluate in the caller, so what one prints reaches the real output |
| `println` `print` `pr` `prn` | `%clojure-write-datum` per part to `*standard-output*` | with several parts and any computed one, every part is bound first (the oracle evaluates all arguments before printing). `newline` answers nil, not the `"\n"` `princ` returns (pin `clojure-pprint-cl-format-lays-out-through-the-pretty-printer`) |
| `printf` `print-simple` `print-dup` `print-ctor` `char-escape-string` `char-name-string` `munge` `namespace-munge` | `printf` the `format` lowering through `princ`; the rest worker | no program `print-method` applies to `print-dup`. Pins `printf-prints-the-format-and-answers-nil`, `char-escape-string-and-char-name-string`, `munge-and-namespace-munge`, `print-simple-print-dup-and-print-ctor` |
| `rand` `rand-int` `rand-nth` `shuffle` | the program-owned generator (`.kb/random.md`) | `shuffle` pins membership, never order |
| `make-array` `aget` `aset` `alength` | general arrays; of one index the byte-array family's arms | the element class is ignored but a host `Byte/TYPE` of one dimension, a byte array ("Byte arrays"); a designator names the boxed class |
| `parse-long` `parse-double` `parse-boolean` | worker | regex-free scanners, so every backend runs them ("Portable parsers") |
| `object-array` `to-array` `into-array`, `int-array` `long-array` `double-array` `float-array` `short-array` `char-array` `boolean-array` | worker; `into-array`'s class spelling resolves at lower time to a `(:C%CLASS name)` designator | vectors ("Typed arrays") |
| `to-array-2d` | worker | "Typed arrays" |
| `aset-int` `aset-long` `aset-double` `aset-float` `aset-short` `aset-boolean` `aset-char` | worker | "Typed arrays" |
| `ints` `longs` `doubles` `floats` `shorts` `chars` `booleans` | worker | "Typed arrays" |
| Java interop | "Java interop" | interpreter and JVM only |
| `quote` | `quote` with symbols mangled | collections inside are rebuilt; a quoted empty one is the canonical empty object |
| `comment` | `nil` | |
| `transient` `persistent!` `conj!` `assoc!` `dissoc!` `disj!` `pop!` | `ClojureTransientLowering`: worker | "Transients" |
| refused by name (call-time) | the arguments, then `(error "same words")`: only the call signals, so a library merely defining such a function loads (integrant over `enumeration-seq`) | `use-fixtures`; `bean` `clojure-version` (host answers on no wasm backend); the proxy helpers; the chunk verbs; `->Vec` `->VecNode` `->VecSeq` `-cache-protocol-fn` `-reset-methods`; the `stream-*!` reductions; `pcalls` `seque` `await1` (no thread pool); the agent executor verbs; `enumeration-seq` `resultset-seq`; `compile` `get-thread-bindings` `read+string`. `gen-class` `gen-interface` stay expansion refusals (the oracle fails the compile too); `eval` and the lowering verbs over computed arguments lower at run time on the interpreter and refuse in a compiled program ("Lowering at run time"); `add-classpath` (no class loader), `Inst`, `PrintWriter-on`, `primitives-classnames` stay unknown names |

## Deviations

Each is a real work item unless the reason says otherwise. The user-facing list is
`doc/en/clojure/deviations.md` ("the page"); this one keeps the reasons, the pins and what the
page lacks.

- Printing (`%clojure-write`): maps and sets print and walk in the page's insertion order on
  all four backends (clojure-spec `a-map-grown-one-assoc-at-a-time-shares-its-table`,
  `a-maps-seq-walks-it-in-insertion-order`); a map is read as a plist through
  `%clojure-table-plist`, never `rontolisp:hash-table-plist` (newest-first). `keys`/`vals` of a
  seq of map entries read the entries, the oracle's `KeySeq`/`ValSeq`, which tools.analyzer
  needs (clojure-spec `keys-and-vals`). A hierarchy prints its hash table. Cycles print with
  datum labels copied from `%scheme-print`: sharing it would splice `scheme.lisp` into every
  Clojure program.
- `class?` of `(class 1)` is false (`class` answers a kind keyword).
- `intern` of a quoted name in the current namespace lowers like `(do (def name val) (var
  name))` (`ClojureCoreLowering.internedHere`); any other `intern` makes its var at run time
  over a cell of its own, which a `def`'s direct calls never see.
- In a file, a body defined above a `defn` redefinition still calls the older one (only
  `^:dynamic` names see the newest); a session re-points it.
- Dispatch: a record's, deftype's or `reify`'s class chain is `Object` and the protocol
  interfaces its body implements, not its host class's other supers (`IRecord`, `java.util.Map`:
  a `class` method on `java.util.Map` misses a record); a core class's chain is the oracle's
  ("Class chains").
- Metadata: `assoc` keeps it like the oracle (integrant's `build` relies on that for
  `::origin`); a symbol takes none; the side table keeps every object for the program's
  lifetime.
- Kept though the oracle refuses them: an unquoted vector libspec in a bare `require`; an odd
  trailing `cond` arm.
- The page alone lists the rest, work items unless a section gives the reason: "Decimals"
  (in-range `N` literals), "Laziness", "Locals named like a special", "Catching", "Sorted
  collections", "Structural keys".

## Exceptions

An exception is a condition on every backend.

- One class per program, `C%E-EXCEPTION` (`ClojureStateLowering.exInfoRuntime`, spliced behind
  `usedExInfo`); its report IS the oracle's `toString`, so `str`, printing, the uncaught report
  and clojure.test need no reader of their own. Verbs call `C%E-NEW`/`C%E-PARTS` by name: a
  kept library function reaching them where the lowering did not set `usedExInfo` compiles as
  an undefined call. The pruner keeps every non-defun definition, so the `define-condition`
  stays.
- `%clojure-exception-of` (`throw`, `ex-message`, `ex-cause`) makes a host `Throwable` an
  exception of its run-time class chain. `ex-message` of a `division-by-zero` is the oracle's
  `Divide by zero`, though `str` and the uncaught report keep the CL report (clojure-spec
  `catch-takes-a-division-by-zero-as-an-arithmetic-exception`). nil `ex-info` data is `{}`; a
  non-exception cause is refused.
- `Throwable->map` is a reader like `ex-data` (`exReaderOf`): the oracle's map without frames,
  `:trace []` and no `:at` (clojure-spec
  `throwable-to-map-answers-the-oracles-map-without-frames`). `StackTraceElement->vec` lowers
  inline; wasm has no elements, so only its two refusals run there (clojure-spec
  `stack-trace-element-vec-refuses-nil-and-foreign-values`,
  `ClojureInteropTest#stackTraceElementVecAnswersTheOraclesVector`).
- A construction (`ClojureInteropLowering.throwableConstruction`) is an exception only for a
  `plainThrowable` class (no public field or method beyond `Throwable`'s and `Object`'s, so the
  condition answers every member a program can call) and through a constructor the class has (a
  `^String` hint picks the string one, as in the oracle's compiler); a class with members
  (`java.net.URISyntaxException`) stays a host object until thrown.
- `.getMessage`/`.getLocalizedMessage`/`.getCause` on an unknown or plain-throwable receiver
  are `%clojure-exception-method`, so the lowering names no `java:` operator and wasm compiles
  it without the `JAVA:CALL` warning. Any other method but these and `.toString` is refused;
  the other deviations are the page's.
- Pins: clojure-spec `get-message-reads-a-caught-runtime-error`,
  `throwable-constructions-are-exceptions-on-every-backend`,
  `ex-info-carries-data-through-throw`;
  `ClojureInteropTest.aThrownHostThrowableKeepsItsClassAndMessage`.

## Catching

A catch takes an exception whose class is the class it names or a subclass of it, in clause
order; anything else passes on.

- Class chains are resolved at LOWERING time (`ClojureThrowables`' comment, `PARENTS` a table
  because a native image reflects only what it holds: a `Class.forName` test image lacked
  `StackOverflowError`; `ClojureThrowablesTest` pins its rows to reflection and its
  completeness to `JAVA_LANG`); other classes: "Host classes". `(clojure.lang.ExceptionInfo.
  ...)` is `ex-info`'s value (schema's `error!`). An unknown or non-throwable catch class is
  the oracle's refusal.
- `(catch C e body)` lowers to `((and error (satisfies |C%E-CATCHES-<C>|)) (e) body)`: a
  condition no clause takes is never caught, since re-signalling it with `(error c)` would pull
  the printer into every one-`try` program. One predicate per caught class
  (`ClojureThrowables.catchRuntime`; a session emits it ahead of the first buffer catching the
  class), since `satisfies` takes one symbol. `thrown?` lowers to the same clause, so another
  class's exception reaches the `is` as an ERROR.
- Which clause takes which condition is the `clojure.lisp` comment ("Catching"); a `SEQUENCE`
  type error is `UnsupportedOperationException`. A runtime error also matches a subclass of the
  oracle's class, since an operation may throw one (`aget`); a host failure of a class it
  cannot tell matches every catch but `ExceptionInfo`'s, so the JVM still runs `(thrown?
  StackOverflowError ...)`; a condition naming no class is a `RuntimeException` only, since
  matching every catch let `(catch NumberFormatException ...)` take a wasm `java:` call's error
  and data.generators' `bigint` retry loop forever (clojure-spec
  `a-refusal-of-the-runtime-is-caught-by-the-class-the-oracle-throws`). A host exception goes
  by the host's class ("Host exceptions").
- Catching by class without building an exception carries a NIL-answering `C%E-PARTS`, not the
  exception runtime (in a session until a buffer builds one). The type-error slots are read in
  place, for the two seeded classes only: a mixin first parent moves the slots, so other
  subclasses are plain `ClassCastException`.
- A `Throwable` catch, a program with no catch, a `class` multimethod with none and
  `examples/clojure/demo.clj` compile byte-identically to a build without the catch runtimes.
- `class`, `instance?` and the stack-trace methods read `%clojure-condition-chain`. `class`'s
  arm is family `ClojureArms.Family.EXCEPTION` (produced by a definition of `C%E-PARTS`; a
  program that can hold no condition sheds it), so the reader also travels where `class` meets
  a catch or `instance?` names a throwable class (`needsExceptionReader`). `instance?` is exact
  over the chain: an error naming no class is an instance of `RuntimeException` and its
  superclasses only, where a catch takes it whatever it names. A `defmethod` over a throwable
  class dispatches by inheritance ("Dispatch", "Class chains").
- Pins: clojure-spec `catch-takes-an-exception-of-its-class-or-a-subclass`,
  `catch-takes-a-runtime-error-by-the-class-the-oracle-throws`, the "Refusals" pins,
  `thrown-matches-the-class-and-reports-any-other-exception-as-an-error`, the `assert-*` cases
  (an `AssertionError` catch); `ClojureThrowablesTest`, `ClojureLoweringTest`
  `aCatchTestsItsClassThroughAPredicateOverTheClassChain`/`aCatchOfNoThrowableClassIsTheOraclesRefusal`,
  `ClojureSessionTest.aBufferDefinesThePredicateOfEachClassItCatchesFirst`,
  `ClojureInteropTest.aThrownHostThrowableIsCaughtByItsOwnClassChain`; `class`/`instance?`:
  clojure-spec `class-instance-and-stack-traces-of-an-exception`, `ClojureLoweringTest`
  `classInstanceAndTheStackTraceMethodsReadAnExceptionsClass`, `ClojureArmsTest`
  `theExceptionFamilyFoldsClassReadingAConditionWhereNoReaderIsDefined`,
  `ClojureInteropTest.aHostThrowableAnswersInstanceAndItsStackTraceFromTheHost`.

## Refusals

**A refusal of the run-time library or of the lowering carries the class the oracle throws for
the same call**, decided where it is detected.

- Carriers (`ClojureRefusals`, the `clojure.lisp` comment "Refusals") signal through
  `%clojure-refuse`, the one typed signal; they are fixed-arity on purpose, since an
  `&optional` value would cost the JVM class more.
- **Free where nothing reads a class** (`ClojureArms.Family.REFUSAL`): where no catch by class,
  `class` or `instance?` can tell, the strip folds each carrier back to the plain error of its
  message, `(error "~A" x)` for a computed one, never `(error <computed string>)`, whose
  object-designator expansion pulls in the run-time dispatch and the printer. The uncaught
  report is the message either way. Measured: `examples/clojure/demo.clj`, every example /
  size-report / bench program and 413 of the 458 Clojure doc examples compile byte-identically
  (the other 45 read a class, or shrink).
- **The condition gate** (`.kb/error-handling.md`, "The routing gate"): `%clojure-refuse`
  signals over the text control of a variable, which routes no report, and the
  `define-condition`'s unreferenced keyword constructor builds nothing, so a class-reading
  program carries neither the renderer nor the printer hook.
- `vec` of a non-collection is the oracle's `RuntimeException` (it casts to an array before it
  seqs), which an IAE catch does not take; the check, `%clojure-vec-arg`, is the family's one
  VIEW, testing in `%clojure-strict-seq`'s order (`--dump-ir` of 465 programs: 463 identical).
  Pins: clojure-spec
  `vec-of-a-non-collection-refuses-with-the-runtime-exception-the-oracle-throws`,
  `ClojureArmsTest#theRefusalFamilyFoldsVecsArgumentCheckToTheArgument`,
  `ClojureLibraryTest#vecRefusesANonCollectionAsRuntimeExceptionOnlyWhereAClassIsRead`.
- `subs`/`.substring`/`.charAt` are the family's aliases of `subseq`/`char`: where a class is
  read they check first, so an out-of-range bound is the oracle's
  `StringIndexOutOfBoundsException` on every backend (the unchecked compiled verbs read garbage
  or trap). Only `subs` refuses a non-string, since `.substring`'s untyped receiver may be a
  `StringBuilder` (clojure-spec
  `split-and-split-lines-answer-a-vector-and-subs-takes-only-a-string`).
- **A bound of `subs` / `.substring` / `.charAt` is truncated at run time, on every program**
  (`%clojure-string-bound`, lowering, not the refusal family), as the oracle truncates a double
  or ratio; a double past the int range clamps and is refused as outside the string (the page),
  since the oracle's exact classes would bring the carriers and `princ-to-string` into the
  helper. Pins: clojure-spec `subs-and-substring-truncate-a-double-or-ratio-bound`,
  `subs-and-substring-refuse-a-truncated-bound-outside-the-string`, `ClojureStringBoundE2eTest`
  (a program reading no class, all four backends),
  `ClojureLoweringTest#aStringBoundIsTruncatedAtRunTimeUnlessItIsALiteralInteger`.
- **A bound that is no number is refused by the refusal family's alias, not by
  `%clojure-string-bound`**, so a program reading no class compiles as before (539 doc
  examples, `examples/clojure`, the probe corpus byte-identical on wasm P1). An untyped
  receiver takes the `-by-reflection` alias (`ClojureInteropLowering.stringMethod`), the
  oracle's reflective IAE; a `^String` receiver or a `str` result, which the oracle types
  (CCE), stays IAE, since tracking hints would change the bytes of every program that hints.
  Pins: clojure-spec `subs-and-substring-refuse-a-non-number-bound-with-the-oracles-class`,
  `ClojureLoweringTest#aStringMethodOfAReceiverNotKnownToBeAStringIsTheReflectiveAlias`,
  `ClojureArmsTest#theRefusalFamilyFoldsEachRefusalToThePlainErrorOfItsMessage`.
- Naming no class (the page), also: a named-but-refused verb, lowered to the plain error so a
  defining library loads and only the call signals; a value macro; an internal invariant.
- Pins: clojure-spec `a-refusal-of-the-runtime-is-caught-by-the-class-the-oracle-throws`,
  `a-string-substring-and-charat-refuse-with-the-oracles-class`,
  `refusals-of-the-runtime-carry-the-oracles-classes` (each probe first passes a catch of a
  class no refusal is), `class-instance-and-stack-traces-of-an-exception`;
  `ClojureArmsTest#theRefusalFamily*`,
  `ClojureLibraryTest#aProgramReadingNoConditionsClassSplicesEveryRefusalAsThePlainError`,
  `ClojureRefusalsTest` (each chain is `ClojureThrowables`'),
  `RontoLispCliStreamsTest#aClojureRefusalReportsTheSameLineWhetherOrNotTheProgramReadsAClass`,
  `LispMacroExpanderTest#anUnreferencedConditionConstructorBuildsNothingToRender` /
  `#aSignalOfItsOwnTextControlNeedsNoRenderer`.

## Seq verbs over a wrapper

**A cons headed by a CL keyword is a tagged wrapper -- no user list holds one -- so one
`keywordp` of the car parts a list from every wrapper, and a wrapper no verb takes is refused
with the oracle's class.**

- A record walks its declared fields in order, then its extension keys in insertion order (the
  oracle's extension map is an array map), on all four backends (`%clojure-record-entries`, the
  printer's order). Pins: clojure-spec
  `a-records-seq-walks-its-declared-fields-in-order-then-the-rest`,
  `a-maps-seq-walks-it-in-insertion-order`.
- `%clojure-strict-seq` refuses a wrapper, `false`, a number, a symbol or a function with
  `seq`'s IAE, like the oracle. `count`/`empty?` test the list arm first and every other kind
  behind the `keywordp`, with no per-kind refusal arm (each was a test every list and vector
  paid); `count` of a non-collection is the `SEQUENCE` type error, the UOE ("Catching").
- `contains?` of a string under a non-integer key answers false (deviation: the oracle
  truncates a number, refuses anything else); the exact arm costs a little wasm. `nth` refuses
  a map, set, record or sorted collection, so a vector pattern over a map is refused like the
  oracle's.
- Measured: the seq-view splices shrink most programs that read collections; the list verbs run
  faster through the view, the vector verbs within noise.
- Pins: clojure-spec `seq-verbs-refuse-a-wrapper-or-a-scalar-with-the-oracles-class`,
  `count-and-empty-reach-maps-and-sets`, `sequential-destructuring-binds-positionally`,
  `reify-values-dispatch-and-deftypes-stay-opaque`;
  `ClojureLoweringTest#countAndEmptyPartAListFromEveryWrapperByOneTestOfItsHead`.

## Host exceptions

**On a host target (the interpreter, the JVM), an exception crossing the `java:` boundary is
the host's own in both directions.** The CL half is `.kb/java-interop.md`, "What a member
throws".

- Host to program: a catch takes a `java:java-exception` by its throwable's class
  (`%clojure-host-is-a`: typed resolved calls, not `Class.forName` + `isInstance` per check)
  and BINDS THE THROWABLE, so reads, `identical?` and a rethrow are the host's; `throw` of a
  host `Throwable` carries it unconverted. The binding is a lowering post-pass
  (`ClojureLowering.bindCaught`) once the whole file is known to name a `java:` operator (a
  session at once), since an arm could not fold back to the plain clause.
- Program to host: in a `java:` program that builds an exception, `C%E-EXCEPTION` is a
  `java:java-exception` whose builder, called by the boundary, makes the host twin once: a
  generated `C%E-HOST-OF`, one literal `java:new` per class the program builds, so nothing
  reflects (a reflective builder in `clojure.lisp` would cost every program much more class,
  and a native image may not answer). The twin is a copy, not `identical?` (user doc
  deviation); a runtime error or refusal has none. A CL file of the same program catches one as
  `java:java-exception`. `%clojure-lisp-value-p` counts an instance, else the host arms' own
  `isInstance` calls would hand a condition over as its twin.
- The arms are `ClojureArms.Family.HOST_EXCEPTION`, folded unless the program names a `java:`
  operator and the target has a host (`ClojureArms.needsHost`): `examples/`, `size-report/`,
  `bench-report/` and the `clojure-spec.yaml` program are byte-identical on wasm P1,
  `--optimize=size` and the component, and on the JVM but for the `java:` programs. Run time is
  within noise but for a caught host failure on the JVM (as a CL `handler-case` pays) and an
  ex-info loop in an interpreted `java:` file.
- Pins: `ClojureInteropTest#aFailedHostCallIsCaughtAsTheExceptionTheHostThrew`,
  `#aCaughtHostExceptionRethrowsAndWrapsAsItself`,
  `#anExceptionPassedToAHostMemberIsAHostThrowableOfItsClass`;
  `ClojureLibraryTest#aHostExceptionIsMadeOnlyByAJavaOperatorWhereTheHostIs`,
  `ClojureProjectNamespacesTest#aClojureExceptionCaughtInCommonLispCarriesItsHostException`.

## Sorted collections

**A program that builds no sorted collection compiles to the bytes it did before sorted
collections existed.** A sorted map or set is a value every map and set verb must read, so the
verbs carry an ARM for it, and a program naming no producer has every arm stripped before the
library splice.

- The value and its copy on write are the `clojure.lisp` comment ("Sorted collections"); not a
  persistent tree (an association O(log n) instead of O(n)), since every hash map verb here
  already copies and the vector keeps the runtime small.
- `compare` (`%clojure-compare`) is the oracle's `Util.compare`, strings by code point (the
  page); a comparator's answer is read as the oracle's `AFunction.compare` reads it
  (`%clojure-cmp-call`), by `sort` too. A `java.util.Comparator` is a comparator through
  `%clojure-comparator-compare`, the host call a `HOST` arm. Pins:
  `ClojureInteropTest#aHostComparatorSortsAndConstructsSortedCollections`, clojure-spec
  `a-comparator-type-sorts-and-orders-sorted-collections-through-compare`.
- The default `sort` order is `compare`, with `<` inline for two numbers: a `compare` call per
  pair is slower, on the interpreter by a wide margin.
- `subseq`/`rsubseq`: the oracle picks its walk by IDENTITY with the core `<`, `<=`, `>`, `>=`;
  a literal one lowers to a keyword, and a test passed as a value is classified by its answers
  (`%clojure-sorted-test`), since the core functions as values are fresh lambdas.
- `int`/`long` fold a literal at lower time and refuse through `princ`, not the `str` runtime.
  An interpreted cast loop runs several times slower; an inline in-range-integer arm for it was
  not made (a per-site size cost; compiled, a double cast is a raw f64 compare,
  `.kb/wasm-bignum.md`). Wrapping uses the mask form (`%clojure-wrap-bits`), not `(mod n (expt
  2 bits))`, which links a bignum division, with LITERAL mask and bounds (an overflowing `h =
  31*h + i` loop runs several times faster than through a computed one).
- **The long verbs wrap through `%mask-signed-field`** (SBCL's `sb-c::mask-signed-field`, a
  `cl` internal): two integer operands take `(%mask-signed-field 64 (op a b))`, which both
  compilers fuse at the default level into one wrapping `long`/`i64` operation
  (`.kb/jvm-int-fusion.md`), several times faster on an overflowing hash than a defun over the
  plain `*`, which built a bignum product every step. A program without an unchecked verb is
  byte-identical.
- `vector-of` answers a plain vector: a typed one would need an arm in every vector verb for a
  difference only `conj`/`assoc` show.
- **The arms.** Their shapes and the strip are `clojure/ClojureArms`' class comment (an arm of
  another shape is an `IllegalStateException` at the strip); one family per value kind,
  stripped where the program names none of its producers: `SORTED` here, `DECIMAL` in
  "Decimals", the others in their sections (a SETTER in "Byte arrays", an OWNED prefix in
  "clojure.java.io" Resources).
- `eval/ClojureLibrary.process` splices a library stripped of every family the program makes no
  value of; a whole program on the interpreter takes the same strip
  (`LispEvaluator.clojureProgram`), which runs its `str`- and `=`-hot loops markedly faster. A
  session, a `.clj` `load` and the macro-time evaluator keep the library whole, since a later
  input may build anything; a stripped library refuses later Clojure source (no program reaches
  it: `load`/`require` are read while lowering, `eval` is refused). Rejected: strip-on-load
  with growth, since a redefined defun misses every `#'` snapshot and closure taken before.
  Pins:
  `ClojureLibraryTest#aWholeProgramOnTheInterpreterTakesTheLibraryWithoutTheArmsItCanNeverTake`;
  the interpreter legs of the four-backend tests (`ClojureSpecE2eTest` ...).
- Writing an arm: no `freshTemp` and no datum lowered again (a shifted temp number would rename
  the stripped program's locals); behind an existing wrapper test where it can
  (`%clojure-strict-seq`, `count`, `empty?`, `%clojure-key-kind`); and **a pre-existing defun
  keeps its docstring byte for byte -- the JVM backend emits every docstring as an `ldc`/`pop`
  statement**, so comments go in `;;` lines.
- `%clojure-plist-table` carries an arm, so `assoc`/`merge` onto a sorted map need none in the
  lowering; a sorted key is structural-key kind 4, so a hash set stored first does not replace
  its spelling. A macro answering a sorted collection decodes to an unsorted literal, like the
  oracle's compiler (`ClojureMacroLowering.decodeSorted`).
- Measured (2026-10-03): `(println (sorted-set 3 1 2))` pulls in `compare`, the sort runtime
  and the `str` path of the refusals, against 33,229 (26,601 at `--optimize=size`) for `#{3 1
  2}`; a large sorted-map build plus lookups takes seconds compiled, tens of seconds
  interpreted.
- Pins: clojure-spec `sorted-map-and-sorted-set-print-in-comparator-order`,
  `sorted-collections-look-up-through-their-comparator`,
  `sorted-collections-grow-and-shrink-in-order`, `sorted-collections-seq-in-order`,
  `sorted-collections-equal-and-key-like-their-unsorted-kind`,
  `sorted-map-by-and-sorted-set-by-order-through-a-comparator`,
  `subseq-and-rsubseq-walk-a-bounded-range`, `sorted-collections-and-the-type-predicates`,
  `compare-orders-like-the-oracle`, `sort-reads-a-comparator-answering-a-number`,
  `sort-without-a-comparator-orders-by-compare`,
  `vector-of-stores-each-member-as-its-primitive`, `sorted-collections-refuse-like-the-oracle`,
  `clojure-set-grows-and-shrinks-a-sorted-set`,
  `empty-answers-the-empty-collection-of-its-kind`,
  `sorted-collections-carry-metadata-and-travel-through-macros`; the strip by
  `ClojureArmsTest`,
  `ClojureLoweringTest#aProgramBuildingNoSortedCollectionCarriesNoneOfItsArms`,
  `ClojureLibraryTest#aProgramBuildingNoSortedCollectionSplicesTheLibraryWithoutItsSortedArms`;
  a session keeping its arms by
  `PlaygroundReplTest#aClojureSessionKeepsTheSortedArmsOfWhatAnEarlierBufferDefined`.

## Structural keys

**A map, set, memo or method-table key finds an `=` key, though the tables are `equal` tables
whose `equal` is identity on a vector or table.** The REPRESENTATIVE scheme, its kinds, the
typed keys and the lock discipline (`%clojure-key-guard`, `.kb/concurrent-served-requests.md`)
are the `clojure.lisp` comment ("Structural keys").

- `%clojure-hash` folds by 1021 under a 2^20 mask, not `* 31` under 2^24, which packed many
  grid keys per bucket. The `eq` table makes the module slotted (`.kb/hash-tables.md`), at a
  little cost on the JVM and the interpreter; keyword-only work pays the `%clojure-table-key`
  call on wasm (the JVM within noise).
- The float zeros are one key though `equal` tells them apart (`%clojure-zero-key`, so a zero
  double literal is no scalar key), like the oracle; a NaN key is untouched.
- `%clojure-equal` answers T for one non-number object on both sides before reading it, the
  oracle's `Util.equiv` `k1 == k2`: no shared member is walked, and `(= v v)` of a collection
  holding NaN or of an endless or unrealized lazy seq is true without realizing it. A number
  keeps its test (user doc `syntax.md`).
- A function hashes by identity: under a constant each fresh closure in an `[f i]` key added a
  class to one bucket for the run (instaparse's message cache slowed run over run). Only
  functions: any other identity-compared value may be `equal` to another wrapper of it (a var's
  list).
- Untouched on purpose: hierarchy and protocol tables, `prefer-method`'s keys, record field
  reads.
- Pins: clojure-spec `structural-keys-find-equal-collections`,
  `keys-holding-distinct-closures-stay-hashed` (a regression is a minute-long interpreter leg),
  `a-value-is-equal-to-itself-before-its-contents-are-read`;
  `ClojureLoweringTest.aLiteralScalarKeySkipsTheStructuralKeyRuntime`.
- A typed key's kind (family `TYPED_KEY`) is its tag and field values, not the object: `=` need
  not read every field (a priority map's keyfn), and a kind per object would add a
  representative per object stored, each walked by the class scans; a reify or a type with
  mutable fields keeps that growth.
- The oracle's maps disagree (its hash map and set find a typed key by its hash, its array map
  compares a lookup key that is no `java.util.Collection` or `Map` by `equals` alone,
  `Util.equivPred`); here every map compares as the hash map does. Deviations: a collection
  type is bucketed by its contents, not its `hasheq`, and any other type by its own hash, so a
  core collection its `equiv` calls equal is held apart (`(contains? #{[1 2]} t)`: true there,
  false here); a class serves both sides of `=`, where the oracle asks the lookup key's
  (`(contains? #{t} {:a 1})`, a map type without `java.util.Map`: false there, true here).
- Cost: a program storing no `IHashEq` and no `Object` row folds the typed arms, so its keys
  run as before; a typed collection's bucket walks its seq (O(n)) where the oracle reads its
  `hasheq` (instaparse's `AutoFlattenSeq` caches it).
- Pins: clojure-spec `a-type-with-its-own-hash-and-equality-keys-a-map-or-set-by-value`,
  `ClojureLibraryTest#aProgramGivingNoTypeAHashOfItsOwnSplicesTheStructuralKeysWithoutTheTypedKeyArm`,
  `ClojureArmsTest#theTypedKeyFamilyIsMadeByTheStoreOfAnIHashEqOrAnObjectRow`. Verbatim
  data.priority-map 1.2.0: a core map `=` to a priority map finds it as a set member and as a
  map key on all four backends, the oracle's.

## The copy a map verb takes

**`assoc`, `dissoc`, `merge`, a set's `conj`/`disj` and the other verbs answering a changed map
copy the table first, through `rontolisp::%copy-equal-table`**: a fresh `equal` table of the
same entries in the same order, sharing the source's structure on all four backends
(`.kb/hash-tables.md`, "Copies share structure").

- Not `(plist-hash-table (hash-table-plist m))`: interpreted prelude Lisp with a list cell per
  entry, and each `assoc` reversed the walk order. Without shared structure a map grown one
  `assoc` at a time was O(n^2): `instaparse.core` (its GLL parse table and message cache)
  loaded in tens of seconds instead of seconds.
- **An `assoc` that changes nothing answers its map**, as the oracle's does when the key
  already holds that very value: extend-clj's `deftype-atom` swaps by `(compare-and-set! atom
  (assoc @atom :db old) (assoc @atom :db new))`, which loops forever if every `assoc` is a
  fresh copy. `%clojure-plist-table` answers its base when every key is held with an `eq` value
  (one lookup per pair before the copy), so `assoc`, `merge` and a map's `conj` of an unchanged
  entry answer the map itself; a record's `assoc` still rewraps and a vector's copies, as the
  oracle's do. Pin: clojure-spec
  `a-type-implementing-the-reference-interfaces-answers-the-atom-verbs` (its `identical?`
  line).

## Hashes

**`hash` is the oracle's `Util.hasheq` and answers its numbers on all four backends;
`%clojure-java-hash` is its `Util.hash` (`hashCode`), which `hash-combine`, `.hashCode` and a
member under a Java hash read** (`clojure.lisp` ";;;; Hashes"). Every number and refusal class
agrees with the oracle's but an identity hash's.

- Each 32-bit hash step is a `%mask-signed-field 32`, which the compilers fuse
  (`.kb/jvm-int-fusion.md`, `.kb/wasm-int-fusion.md`). A string hashes over its UTF-16 units,
  so a code point past the BMP hashes as its surrogates like the oracle's, though `count`
  counts it once.
- Each kind takes its oracle class's hash. Not obvious: a decimal hashes without trailing zeros
  under `hash` but by its unscaled value and scale under `.hashCode` ("Decimals"); a keyword's,
  symbol's or ratio's hash is also the `hashCode` of the handle it crosses into Java as ("Java
  interop"); a record mixes in the `hasheq` of its class name's symbol, the namespace munged
  and the name not (`my_app.core.My-Rec`); a reader value's `str` spells the oracle's
  `Class@hex` of its hash.
- A type's `hasheq`/`hashCode` answer is cast as `((Number) x).intValue()`, the oracle's for a
  computed body; for a literal body the oracle's compiler differs (a long its checked
  `intCast`, a double a compile error). An identity hash differs per backend and run, as the
  oracle's differs per run.
- A host object hashes through `%clojure-other-hash`, a HOST arm. The JVM's `numberp` takes a
  host `BigDecimal`, so a number reaching the arm is a host one; a condition is tested by
  `%obj-p` itself, since a wasm program naming `java:` keeps the host bodies but folds
  `%clojure-lisp-instance-p`.
- `hash-combine` casts its first argument through `Number`, so a character is refused where
  `(int \a)` is 97. `.hashCode` of no argument on a receiver of no known class is
  `%clojure-java-hash` on every backend (`ClojureInteropLowering.valueHashCode`).
- Probes: data.priority-map 1.2.0's `compile-if` takes the oracle's `hash-unordered-coll`
  branch (`resolve` finds it, "Macros"), so its hash and `=` answer the oracle's on all four
  backends.
- A return hint on a parameter vector reads as `(%with-meta [..] long)`;
  `ClojureBindingLowering.isVectorDatum` strips the layer, else the clause would read as a
  multi-arity arity list. `^long` on a variadic arity is accepted (the oracle's compile error).
  Pins: clojure-spec `a-return-type-hint-on-a-parameter-vector-is-metadata`,
  `ClojureLoweringTest#aReturnTypeHintOnAParameterVectorIsDropped`.
- Pins: clojure-spec `hash-is-the-oracles-hasheq-of-every-kind`,
  `the-collection-hash-verbs-mix-order-and-combine-like-the-oracle`,
  `a-type-hashes-through-its-hasheq-then-its-hash-code-else-by-identity` and the `str` line of
  `reader-conditional-and-tagged-literal-values-look-up-compare-and-print`;
  `ClojureInteropTest#aHostObjectHashesAsTheHostDoes`,
  `ClojureLoweringTest#theHashVerbsCallTheirWorkersAndATypesHasheqStoresInItsOwnFamily`,
  `ClojureArmsTest#aCollectionInterfaceFamilyIsMadeByTheStoreOfARowOfItsInterfaces` (HASHEQ).

## Decimals

**A decimal is the oracle's `BigDecimal`; a program making none compiles to the bytes it did
before decimals existed** (`clojure.lisp` ";;;; Decimals").

- Producers (`ClojureDecimalLowering.PRODUCERS`): an `M` literal (a quoted wrapper, so quoted
  data and a macro's answer count), `bigdec`, `with-precision`, the `java.math` members and
  every run-time read, so a program reading at run time (`bundled-namespaces`, edn) builds the
  family. A library helper comparing a program's numbers (`%clojure-extreme-key`) names the
  decimal helpers too, so the strip renames them in the library as in the program. `+` `-` `*`
  `inc` `dec` reach the unchecked-math workers, whose decimal arm runs ahead of the flag test.
- A decimal's structural-key kind is its scale, so `{1.50M 1}` keeps `1.50M` beside a stored
  `1.5M`; `.equals` compares the scale too. Arithmetic (the `clojure.lisp` comment) is the
  oracle's line for line, `(+ 1.5M 1/3)`'s refusal included; a zero double divisor is the plain
  double division (the oracle's literal takes its primitive overload).
- **The BigInt flag was weighed and not made.** A printed `N` past the long range needs no flag
  (no long holds such a value). An in-range BigInt (`1N`, `bigint`, `(* 1/2 2)`) would need a
  wrapper every integer consumer reads: 87 `(integerp` and 61 `(numberp` sites in
  `clojure.lisp` (counted 2026-10-29), every inline consumer the lowering emits, and the spec
  corpus's `2N`-style literals used as integers -- each a crash where the plain integer answers
  today, for a printed `N` and `int?`/`class` answers. Not worth that blast radius; the
  deviation stays (user doc). An index or count consumer refusing a decimal (the page) has the
  same blast radius, for a use money code does not make.
- **`BigDecimal`'s members are the values made here** (`ClojureDecimalLowering`, `clojure.lisp`
  ";;;; Decimal methods"): an instance call tests `%clojure-bd-p` first, folded where no
  decimal is made. A wrong argument is the oracle's reflector's refusal (`Cannot cast X to
  java.math.BigDecimal` for one overload of the count, `No matching method` for several). A
  method beyond the common ones (`sqrt` ...) runs on the host's object, refused by name on
  wasm. Constructions, statics and constants are producers on every backend; a receiver known
  to be one of the three classes is treated as unknown. `java.math.BigDecimal` is a registered
  image type ("Host classes", `ClojureHostImageTypesTest`).
- **A `BigDecimal` a member answers is a decimal, but only in a program making decimals** (the
  page): a member declaring `BigDecimal` is a producer; one declaring a supertype, or unseen
  behind an untyped receiver, is the family's view, folded to the call where no decimal is
  made, so such a program's `java:` calls pay nothing. The conversion is Lisp-level, never in
  `JavaInterop.unmarshal` or the bridge, so `java:` learns no Clojure shape and a Common Lisp
  program keeps its host object; one Java hands a fn, or inside an array, stays a host object.
- Measured over every Clojure source in the local `~/.m2` jars (2026-10-30): libraries reach
  constructions above all (tools.reader, malli, data.generators, spec.alpha, nippy); next.jdbc
  names no `BigDecimal`. tools.reader's `read-string "1.5M"`, data.generators' `bigdec` (all
  four backends, "Library probes") and data.json 2.5.2 answer the oracle's.
- Pins: clojure-spec `decimals-compute-print-and-compare-at-the-oracles-scales`,
  `with-precision-binds-math-context-around-an-exact-body`,
  `radix-integers-and-exact-decimals`,
  `checked-arithmetic-is-plain-arithmetic-that-never-overflows`,
  `decimal-methods-constructions-and-statics-answer-as-the-oracles-bigdecimal`,
  `decimal-literals-are-decimals-and-small-bigint-literals-plain-integers` (the in-range
  deviation); `ClojureLoweringTest#aProgramMakingNoDecimalCarriesNoneOfItsArms`,
  `ClojureInteropTest#aDecimalCrossesTheJavaBoundaryBothWays`,
  `#charactersRadixAndDecimalsLowerAsTheirValues`,
  `ClojureReaderTest#decimalsReadAsTheirUnscaledValueAndScale`.

## Arity refusals

A call with a count no arity of a program fn takes is the oracle's `Wrong number of args (N)
passed to: NAME` (a `clojure.lang.ArityException`).

- **Single arity: the backends' own count check, handed the words.** The body opens with
  `(declare (%arity-report "Wrong number of args (" ") passed to: NAME"))`
  (`DeclaredArityReport`), which every backend reads where it already judges a count
  ([error-handling.md](error-handling.md), "A wrong argument COUNT through a function value"),
  on the refusal path only: a right-count call costs nothing (a Clojure-side check would cost
  every call), no backend learns a Clojure name, and Common Lisp and Scheme functions keep
  their words. It stays a `program-error` (caught as `ArityException`) and out of inlining
  decisions; a wasm defun past ten parameters (`WasmArityBundler`) names itself in its bundle
  check.
- **Several arities**: the dispatch's last arm, `%clojure-fn-arity-error`.
- **The name** (`ClojureBindingLowering.arityName`; the rule is in the user doc): a `fn` takes
  its `def`'s var name only when it IS the def's value form, by identity; the oracle's demunged
  class name adds generated parts (`--N`, `evalN`, the `try` thunks) that cannot be reproduced.
- Out of scope, in their own words still: a Java member as a value, protocol methods, macros.
- Pins: clojure-spec `a-wrong-argument-count-is-refused-in-the-oracles-words`,
  `ClojureLoweringTest` (the declaration in each lowered defn and lambda), the backends'
  `DeclaredArityReportPrograms` trio, `DeclaredArityReportTest`.

## recur

`recur` targets the innermost `loop`, `fn`, `defn` clause, `letfn` entry, `lazy-seq` body
(arity 0), stored method lambda (`defmethod`, extension methods) or inline method arity: a
target stack, through which a plain lambda passes. Each multi-arity clause is its own target,
so the count must match the clause; a wrong count and a `recur` outside any target are named
refusals; `#()` recurs unchecked (its arity is known only after the body lowers).

- Tail position is enforced like the oracle. The threading rewrites lower in the incoming tail
  position, so a `recur` in a step's tail is its target's, as in the oracle's macroexpansion
  (malli's `cond->`/`recur`); a macro expansion inherits its call's tail position (data.json
  2.5.2's `codepoint-case`), an argument slot of it still refuses (`lowerTailSlot`).
- A `try` between the `recur` and its target is `Cannot recur across try`, a barrier beside the
  stack, so a target opened inside the `try` still recurs; `binding`, a non-empty `with-open`
  and `is` bodies lower behind it (the oracle wraps them in `try`), their inits do not.
- A `recur` reaching a variadic target splits it into a worker taking the rest as an ordinary
  parameter plus an `&rest` head, so the `recur` assigns exactly; an unused variadic keeps its
  single shape.
- An inline method's arity counts every parameter but the first, the target the method supplies
  (the oracle's `(recur this acc)` there is `Mismatched argument count to recur`); a used
  target wraps the body, inside the field bindings, in one `labels` entry
  (`ClojureBindingLowering.inlineRecurBody`): no worker split, and a `let` rebinding the target
  cannot substitute it. An extension body is a `fn`, whose `recur` passes the target. Pins:
  clojure-spec `recur-in-an-inline-method-passes-every-parameter-but-the-target`,
  `ClojureLoweringTest.anInlineMethodRecurPassesEveryParameterButTheTarget`.
- Constant stack comes from the backends' tail calls
  ([jvm-self-tail-calls.md](jvm-self-tail-calls.md) for the JVM's self jump and tail groups); a
  call through a value in tail position bounces through the JVM trampoline past 64 frames
  ([jvm-tail-bounce.md](jvm-tail-bounce.md)), so a `fn` in an atom calling itself runs
  1,000,000 deep on every backend. On wasm `%clojure-call`'s `apply` sits in a `cond` clause,
  which wasm compiles as a tail (`.kb/wasm-tail-calls.md`). Pins: clojure-spec
  `deep-recur-answers-on-every-backend` (a `loop` 1,000,000 deep),
  `letfn-mutual-tail-calls-run-in-constant-stack`.
- A multi-arity `defn`'s clause recurs to its own helper (`ClojureBindingLowering.multiDefun`);
  a multi-arity `fn`'s `recur` re-enters its one lambda's `cond` dispatch, so it is a tail only
  where `cond`'s clause bodies are (`deep-recur-answers-on-every-backend` runs one 300,000
  deep).
- A closure a `loop`/`dotimes`/`doseq` body creates captures its own iteration's value, like
  the oracle (clojure-spec `dotimes-and-loop-closures-capture-the-iteration`); `dotimes`
  rebinds its variable per iteration (`ClojureLoopLowering.dotimesOf`).

## Namespaces and project files

**Every namespace has its own vars.** A var is keyed `ns/name` in every program-wide table
(`ClojureLowering.varKey`) and lowers to `c%ns/name`, except `user`'s (`c%name`), so a program
without `ns` lowers unqualified. A quoted `'n/x` is the symbol of var `n/x`. User-facing rules:
`doc/en/clojure/semantics.md`, `doc/en/clojure/reference/ns.md`.

- **Resolution** (`lookupVar`; `resolveVar` adds the privacy refusal): a qualified name is an
  alias, the current namespace or any namespace an `ns`/`in-ns` created; a project namespace
  lacking the var is `No such var: l/nope`, never a class.
- **Wiring** (`ClojureNsState`): a vector whose second element is no keyword is a prefix list,
  the oracle's `libspec?` (clojure-spec `bare-require-accepts-prefix-lists`). `clojure.string`,
  `clojure.set`, `clojure.edn`, `clojure.test` and `ring.adapter.rontolisp` resolve as
  lowerings; another namespace clojure.jar defines (`ClojureBuiltinNamespaces.LANGUAGE`) is a
  built-in file ("clojure.jar namespaces") or `unknown namespace: x`; any other `clojure.*` is
  an ordinary library ("deps.edn"). `clojure.core`'s alias (`requireCore`) spells core names
  through `ClojureLowering.coreSpelling` at every site. A `:refer` of it brings a name back
  past a `:refer-clojure` filter; that and `(:refer-clojure :rename {old new})` are
  `coreRenames` entries (syntax-quote still spells `clojure.core/old`), which a program
  definition or local of the name beats like any refer. The built-in Ring namespaces and
  `rontolisp.http-client` load as project files from the jar when no root holds them ("Ring
  util namespaces", "HTTP client").
- **`load` and `(:load ...)`** lower the file like a required one under the unit key
  `load:<ns>:<path>` (`ClojureNamespaceLowering.loadOne`), once per lowering, each call site
  running its init unconditionally (`LoadMode.RELOAD`). The path is a string literal because
  the file is read while lowering; a computed path and `load` as a value lower as the program
  runs on the interpreter and are refused, at run time, in a compiled program ("Lowering at
  run time"; `ClojureLoweringTest#lowerTimeOnlyVerbsRefuseAtRunTimeAsValues`).
- **Loading** (`ClojureNamespaceLowering.loadNamespace`): an `ns` form marks its namespace
  loaded AFTER its clauses (marking first hid the cycle), so a single-file program's later
  `(:require [a])` reads nothing. A project namespace's file lowers both passes from a clean
  cursor: no local, recur target, `try` depth or gensym of the requiring form leaks in. Its
  definitions hoist ahead of the datum that loaded it, its other data become the init; what a
  `def`'s value hoists (a `reify`'s rows) stays in file order, after the `defprotocol` whose
  table the rows store into, else that table is unbound at load
  (`ClojureProjectNamespacesTest#aReifyBelowItsProtocolsExtensionsInsideALetfnStoresIntoItsTable`).
- **Init at the `require` site**: behind a `(defvar |c%n%loaded| nil)` flag, a `defvar` so a
  namespace two separately lowered files require runs once per process; `defonce` keeps its
  root through a run-time `boundp` (`.kb/compile-time-boundp.md`). Chunks split past 16 KiB of
  printed statements, each a `setq` of a lambda under a `|c%n%init|` driver so
  `GlobalVarCollector` sees the stores; a `^:dynamic` `def`'s `declaim` and counter stay
  top-level for `SpecialVarCollector`.
- **A `require`/`use` below the top of its datum** (`ClojureLowering.requireSpecsWhere`) of a
  namespace no root holds lowers to the oracle's run-time `FileNotFoundException`: the oracle
  loads when the code runs, so a program that never runs it or catches it (instaparse's
  `tree-viz`) keeps working. At the top of a datum, in an `ns` clause or in a loaded file it
  stays a lowering refusal. Pins: clojure-spec
  `macro-env-and-form-ns-resolve-and-a-require-that-runs-later`,
  `ClojureLoweringTest#aRequireTheCodeAroundItReachesFailsWhereItRunsWhenNoRootHoldsTheNamespace`.
- **Source path** (`ClojureSourcePath`, computed when a lowering starts): the entry file's own
  root, the project's `:paths`, its dependencies' roots ("deps.edn"); `deps.edn` and not a new
  flag, being the oracle's own declaration. Lookup is `RT.load`'s order, `.clj` under every
  root before `.cljc` under any (instaparse 1.5.0 ships both for 14 namespaces). Every
  reflective question about a class name goes through `ClojureHostClasses.load`, over the
  program's Java class loader (`.kb/java-interop.md`, "The program's Java class path").
- **Records**: `typeKeyOf` resolves the namespace's own type, then an imported or dotted name
  matching the class, else the only type of that simple name; the tag is the class name's
  designator, so two namespaces' records of one simple name are two classes. An imported name
  no type's class matches is the host class, never another namespace's type of that simple
  name (rewrite-clj's imported `PushbackReader` is not tools.reader's deftype). Pin:
  clojure-spec `a-vector-import-spec-registers-its-classes`.

## deps.edn

**Invariant: the source path is the oracle's classpath, minus what this build does not
read, named when a lookup misses; its jars holding classes are the program's Java class
path too.** `ClojureSourcePath.computeRoots`: the entry's own root, the merged map's `:paths`,
every library `ClojureDepsGraph.resolve` selects in the oracle's order, then `Found.builtin`.
Every rule below is the oracle's answer to `clj -Srepro -Spath` over fixture trees (a `file:`
Maven repository with `:mvn/local-repo` seeded from `~/.m2` minus its `_remote.repositories`,
`file://` git repositories, `GITLIBS` set). Provenance (`.kb/java-frontend-provenance.md`):
the selection, the root map, the git checks and `-X` are written from the published reference
and the CLI's answers alone, never tools.deps' source. User-facing: `doc/en/clojure/semantics.md`,
`doc/en/clojure/deviations.md`.

- **Maps** (`ClojureDepsEdn`): root, user-level, project. The root map (`rootMap`) is stated
  from what `clj` answers in an empty directory and the reference's default repositories,
  never from the oracle's root file, which is neither read nor copied. A `:local/root`
  library's map merges over the root map alone (`:paths` default `["src"]`). A repeated key is
  checked here (the code reader checks set elements only).
- **Validation** in the oracle's words, walked in map order (the oracle sorts its problems;
  one problem reads the same). **An unknown key passes**, the spec's `s/keys` being open:
  refusing it would block a valid `deps.edn` of a dependency its user cannot edit.
- **Selection** (`ClojureDepsGraph`): the reference's rules ("Dependency Expansion") and, for
  what they leave open, the oracle's `-Spath`/`-Stree` over forty constructed Maven graphs,
  some thirty `:local/root` trees and 1,500 generated graphs (up to eight libraries,
  exclusions, cycles), every classpath identical here. What they showed, each a case of
  `ClojureDepsGraphTest`:
  - **Waves.** Every dependency of one depth is decided, in order, before any dependency of a
    library taken at that depth is read: a refusal met deciding precedes one met reading, and
    a library replaced within its own wave still has its dependencies read, then turned away
    (`ClojureDepsFetchCliTest`'s `words 1.0`). Every top-level coordinate is made canonical
    before anything is opened, a written one where written even if then turned away; a `nil`
    one is refused when decided, so not below its parent's own exclusion.
  - **Routes.** A library keeps, per version, every route it was taken on; none is removed. A
    dependency is considered only while its parent's selected version was taken on its route
    AND the parent hangs, on some route, from a library whose selected version holds it: one
    step, not the whole route.
  - **Replacing.** Every OTHER library whose selected version was taken only on routes through
    the replaced one loses its selection and with it its claim: an older version arriving later
    is taken, the same version offers its dependencies again. A library with another route
    keeps its selection, but its dependency already taken below the dead route is gone, one
    decided afterwards served (the test's `q4b`, `q4`). The replacing library stays selected
    even when its own route came through the version it replaces (a cycle), bringing nothing
    until another route reaches it.
  - **Exclusions.** Own `:exclusions` drop direct dependencies when read; inherited ones turn a
    dependency away when decided. A version met again offers what every earlier coordinate of
    it excluded and this one does not, own exclusions only (`a (excl y) -> x -> y` +
    `b -> x`: no `y`); the count restarts for a library taken again after losing its selection.
  - **Equal versions spelled differently** (`1.0`, `1.0.0`) are the first.
  - **Order**: by each selected version's routes, by length, then library by library, the
    library itself last; a library sits at its first, even a route that no longer stands.
    `Lib.compareTo` is by namespace, then name, not the `ns/name` string.
  - **Versions**: Maven by `ClojureMavenVersions`, a port of `GenericVersionScheme`
    (maven-resolver-util 1.9.27, Apache-2.0) from its class files, pinned by the oracle's
    121x121 sign matrix; local roots equal or refused, two types `Unable to compare versions`;
    git commits by descent (the first stays while unfetched).
  - **`:override-deps`/`:default-deps`**: the override, else the written coordinate, else the
    default, wherever the library appears, top level included, made canonical when first used
    (an unused one is never read); the override's own exclusions hold, the replaced
    coordinate's do not (`ClojureDepsAliasesTest`).
  - Libraries to prepare are refused together, in the order the walk first took them.
  - Rejected, with the graphs each got wrong of the first 940: no "parent is held" half (37);
    that half over the whole route (2) or met by any selected version of the grandparent (1);
    on a replacement, also counting routes through versions replaced earlier (4), or
    unselecting whatever has no standing route left (4-5).
- **Procurer** (`ClojureDepsProcurer`): `:local/root` made canonical and checked; a manifest
  `:deps`, `:jar`, `:pom` or none (`clj` reads no lein project); `:deps/prep-lib` checked after
  every contribution, local or git, never run.
- **Maven** (`ClojureRepositories.mavenDependencies`): a POM's `*:*` exclusion names no lib, so
  it excludes nothing; `:extension "pom"` brings children, no root; a `:classifier` key is the
  oracle's `Invalid library spec`. A repository keeps its `:releases`/`:snapshots` policy
  whole, so one with releases disabled still serves snapshots (`.kb/maven-resolver.md`,
  "Per-repository policies"). A dependency's own `:mvn/repos` is never read.
- **A jar's own `pom.xml`** (the first `META-INF/**/pom.xml`): classifier AS WRITTEN, type
  ignored, OPTIONAL KEPT; a test-jar typed dependency is the plain jar, a pom typed one the
  oracle tries as a jar and fails on.
- **A `pom.xml` project** (`pomRoots`; `MavenProjectTest`): `${basedir}/x` stays literal
  under the root, `${project.basedir}/x` is `x`; when ANY plugin is the build-helper, its
  directories come from the FIRST plugin's executions (as Maven merges them), so a child
  plugin listed before the build-helper it shares with its parent leaves none. Both POMs
  validate at the oracle's level, STRICT (`.kb/maven-resolver.md`, "Validation levels").
- **git** (`ClojureDepsProcurer.canonicalGit`): written from the deps.edn reference (its forge
  table) and `clj`'s answers over a `file:` repository, not from the source's names or order.
  A coordinate is refused, in the oracle's words, at the first of: `:sha` with `:git/sha`,
  `:tag` with `:git/tag`, no URL given or inferred, a tag the repository lacks, no sha, a short
  sha without a tag, a sha and a tag that are not one commit (also a short sha naming nothing).
  Inference, here only (`GitFetcher` has none), takes ANY character between a forge's words
  and a dot-less owner (`ioXgithubYfoo/bar` is `https://github.com/foo/bar.git`). Two unrelated
  commits are refused naming both, where the oracle throws an EMPTY message for two of one
  repository. Tags and commits are asked once per resolution.
- **A jar is read in place** (`SourceLoader.listArchive`), fetched Maven jars too: extracting
  would need a cache keyed by content and invalidated when the jar changes, for nothing a read
  in place lacks. A namespace a jar holds only as `__init.class` is refused by name.
- **Who fetches**: the command line alone (`RontoLispCli` sets
  `SourceStandards.clojureRepositories`); an embedder, the tests and the browser
  (`SourceStandards.DEFAULT`) fetch nothing. There a non-built-in Maven or git coordinate, a
  jar's `pom.xml` dependencies and a `:pom` project add no root but a note
  (`Contribution.unread`) that a lookup finding nothing appends to its refusal; a lookup steps
  past an unfetched library's place, so an earlier unfetched jar holding the same namespace
  goes undetected.
- **Resolved when a lowering starts** (`ClojureLowering.resolveProject`), before `declare`, a
  refusal positioned at the first datum, so a dependency jar's classes are the lowering's (an
  `(:import ...)` before any `require` sees them), as the oracle's classpath does.
- **The Java class path**: each selected archive root holding a `.class` entry joins it
  (`ClojureFiles.addJavaClassPath`; `.kb/java-interop.md`); a directory never does (a `src`
  tree would be copied beside every jar, two `src` names collide), so a prepped library's
  `target/classes` goes to `--java-classpath`. On wasm the classes reach lowering and macro
  time only (`ClojureDepsFetchCliTest`).
- **`data_readers.clj`**: every root's, then every root's `.cljc`, read when the project
  resolves (`ClojureSourcePath.dataReaders`; "Data readers").
- **No resolved-graph cache** (no `.cpcache`): the caches under the resolution already make a
  second run network-free (repository files, checkouts, a tag checked against the clone, a
  missing POM not asked again within the daily policy; `.kb/maven-resolver.md`,
  "Repositories"); a warm re-resolution costs little more than reading an empty `deps.edn` and
  passes with both repositories unreachable. A cache keyed by `deps.edn` content would save
  that margin for an invalidation scheme (local roots' manifests, jar times) whose failure is a
  stale classpath.
- **Built-in coordinates** (`ClojureBuiltinLibs`): `org.clojure/clojure` at any Maven version
  is the front end, its children ordinary libraries ("clojure.spec.alpha (g90)");
  `ring/ring-core` up to 1.15.5, `ring/ring-codec` up to 1.3.0 and `riddley/riddley` up to
  0.2.2 ("riddley (j27)") are the shipped files, standing in for an older version as
  newest-wins assumes. A newer version, or a local or git coordinate lacking the namespace, is
  refused when the built-in file would load (`refuseBuiltinStandIn`); with no coordinate the
  files load anyway (the oracle would fail).
- **`clojure.*`**: a contrib namespace (outside `ClojureBuiltinNamespaces.LANGUAGE`) loads from
  the roots like any library; clojure.jar's own never comes from a root (a project
  `clojure/walk.clj` breaks the oracle's own startup, a cyclic load through spec).
- **User-level map** (`SourceStandards.clojureConfigDir`): read from the environment by
  `RontoLispCli` alone; `SourceStandards.DEFAULT` (tests, `JvmSourceCompiler`, the playground)
  reads none. A relative path in it resolves against the project's directory, as the oracle
  merges it. More than eight top deps iterate in the oracle's hash order, here in file order.
- **Aliases** (`ClojureBasis`, `ClojureDepsEdn.argMap`): merged like the `:argmap` of the basis
  `clj -Spath` writes, identical over 300 generated alias sets but for a map merged into a
  vector under a key nothing reads; a `nil` `:paths`/`:replace-paths` merges to `[]`, emptying
  the project's paths. `ClojureDepsEdn.tool` replaces the PROJECT map's `:deps`/`:paths` only;
  `:classpath-overrides` re-roots or drops a selected lib, its children staying.
- **Command line** (`ClojureMain`): `-M`/`-X` end the options; aliases in clj's order (the M/X
  ones, then every `-A`'s). `-X` (`ClojureMain.exec`) is written from the CLI reference's
  grammar and clj's answer to each shape of command line, not from the CLI's exec namespace.
  The arguments are baked as
  `(set! *command-line-args* (seq (concat [..] *command-line-args*)))`, so `-o` keeps them
  ahead of the artifact's own. `(System/exit n)` lowers to `(%host-exit (logand n 255))` on
  every backend.
- Pins: `ClojureDepsEdnTest`, `ClojureDepsGraphTest`, `ClojureMavenVersionsTest`,
  `ClojureDepsAliasesTest`, `ClojureMainTest`, `CliOptionsTest#theAliasFlags...`,
  `ClojureDepsCommandLineTest` (`-M`/`-X`/`test` as processes, four backends),
  `ClojureDepsProjectTest` (four backends over local roots and jars),
  `PlaygroundReplTest#aClojureRequireReadsTheUploadedDepsEdnLikeEveryOtherRoute`,
  `ClojureDepsFetchTest` (Maven and git, in memory), `ClojureDepsFetchCliTest` (the CLI over
  `file:` Maven and on-disk git repositories, four backends, the oracle's output; a second run
  offline), `GitFetcherTest`, `MavenRepositoryTest#aPomGivenAsBytes...`.

## Library probes

**Widely used libraries load verbatim through `deps.edn`, and what blocks one is fixed as
infrastructure, never special-cased.** Harness: per library a directory with
`{:deps {lib {:mvn/version "v"}}}` (the newest release) and a `probe.clj` printing a dozen
calls, run on the interpreter and as `-o P.class --class-name P`, `-o p.wasm` and
`--component`, each diffed against `clj -M probe.clj`; to see past a blocker, patch the jar's
sources copied under a `:local/root`. `=` is the oracle's output line
for line. The first probes' gaps are pinned by clojure-spec cases named after each
(`a-vector-pattern-takes-as-after-its-rest` ... `a-clojure-core-alias-spells-core-names`).

| library | interp | JVM | P1 | component | what stops it |
|---|---|---|---|---|---|
| medley 1.10.0 | = | = | = | = | |
| tools.cli 1.4.256 | = | = | = | = | the probe's `:parse-fn` is `read-string`: `Integer/parseInt` is host-only on wasm |
| camel-snake-kebab 0.4.3 | = | = | = | = | |
| honeysql 2.7.1479 | = | = | = | = | |
| hiccup 2.0.0 | = | = | = | = | wasm warns for the unreached `URI.`/`URLEncoder/encode`; a big `(h/html ...)` literal needs method outlining (`.kb/jvm-method-size-limits.md`) |
| reitit-core 0.11.0 | = | = | = | = | `reitit.Trie` is Java over `clojure.lang` ("Classes compiled against `clojure.lang`"): `=` with `clojure-trie-compiler`; wasm warns `JAVA:STATIC`+`JAVA:CALL` off the path |
| data.json 2.5.2 | = | = | = | = | |
| math.combinatorics 0.3.2 | = | = | = | = | the probe prints the subsets' counts: their empty list is `nil` here (the pinned empty-list deviation) |
| data.priority-map 1.2.1 | = | = | = | = | the probe sorts the tie lines: a priority's item set walks in insertion order here (the pinned set-order deviation) |
| flatland.ordered 1.15.12 | = | = | = | = | |
| data.generators 1.1.1 | = | = | = | = | wasm: the off-path `BigInteger.`/`UUID.`/`Date.` warn `JAVA:NEW` and refuse if called |
| test.check 1.1.3 | = | = | = | = | needs `*`/`+` to wrap mod 2^64 under `*unchecked-math*` (clojure-spec `unchecked-math-wraps-star-and-plus-for-splittable-random`) and a lazy `int-rose-tree` ("Laziness"; clojure-spec `a-recursion-through-a-lazy-verb-walks-lazily`); wasm warns `JAVA:STATIC`+`JAVA:NEW` off the path. A `defspec` under `run-tests` prints `report is not a multimethod` for the oracle's progress, `:file`/`:line` naming host frames; on wasm a failing one reports as an error, still counted (`Thread/currentThread`) |
| hive-test 0.5.1 | = | = | = | = | the pure `tcheck.*`, `stateful` and `properties` namespaces only (one stderr line, as test.check's); the JVM-only ones unprobed |
| core.cache 1.2.999 | = | = | = | = | transitive data.priority-map 1.2.1; `soft-cache-factory` (host `SoftReference`s) is a call-time error on wasm, so the probe skips it |
| core.memoize 1.2.281 | = | = | = | = | transitive core.cache 1.2.254 -> 1.2.999 by newest-wins; wasm warns `JAVA:NEW`+`JAVA:CALL` from core.cache, off the path |
| potpuri 0.5.3 | = | = | = | = | |
| core.unify 0.7.3 | = | = | = | = | |
| integrant 1.0.1 + weavejester/dependency 1.0.0 | = | = | = | = | `load-hierarchy`/`resources` refuse when called: no class loader exists here |
| cc.qbits/ex 0.1.3 | = | = | = | = | |
| spec.alpha 0.5.238 + core.specs.alpha 0.4.74 | = | = | = | = | no `deps.edn` needed; gaps in "clojure.spec.alpha (g90)" |
| tools.reader 1.3.6 | = | = | = | = | on the interpreter and the JVM the host Calendar's `print-calendar` stops at `format`'s `%t` ("The lowering table", `format`) |
| component 1.2.0 (stuartsierra) | = | = | = | = | wasm warns `JAVA:CALL` off the path |
| extend-clj 0.1.0 (tonsky) | = | = | = | = | |
| editscript 0.8.0 | = | = | = | = | |
| data.csv 1.1.1 | = | = | = | = | wasm warns `JAVA:NEW` off the path |
| aero 1.1.6 | = | = | = | = | wasm: `System/getenv` reads the WASI environment, `System/getProperty` of a computed key answers like a host without the property ("HashMap construction") |
| core.logic 1.1.1 | = but 1 | = but 1 | = but 1 | = but 1 | `appendo`'s line prints `nil` for the oracle's `()` (the pinned empty-list deviation); wasm warns `JAVA:CALL`/`JAVA:STATIC` off the path |
| rewrite-clj 1.3.59 | = | = | = | = | wasm warns `JAVA:STATIC`/`JAVA:NEW` off the path |
| core.match 1.1.1 | = | = | = | = | a 7-line probe (vector, rest, map, `:or`, guard patterns), 2026-11-26 |
| schema 1.4.2 | = | = | = | = | an 8-line probe (`validate`, `check`, `s/defrecord`, `s/defn` with fn validation, `explain`), 2026-11-26; wasm warns `JAVA:STATIC` off the path |
| persistent-sorted-set 0.3.1 | = | = | = | = | read from its `.cljs` port ("ClojureScript ports"); `seek` of `(seq set)` takes no `Iter` here (the seq view), so the probe seeks a `slice` |
| datascript 1.8.1 | = | = | = | = | its indexes are persistent-sorted-set's port, `datascript.storage` its storage-less `.cljs` ("ClojureScript ports"); wasm warns `JAVA:CALL`/`JAVA:NEW`/`JAVA:STATIC` off the path |
| specter 1.1.6 | = | = | = | = | riddley is the built-in one ("riddley (j27)"); without a host, `MutableCell` and `Util` are read from their class files ("Portable reading of a dependency's Java class"); wasm warns `JAVA:NEW`/`JAVA:FIELD` off the path. A path closing over a local compiles through `eval` at its first call, refused on wasm ("Lowering at run time"); `setval` of `NONE` over a lazy seq answers it reversed on every backend (`(empty (range 3))` is nil here, so its `list?` arm misses) |
| malli 0.20.3 | = | = | = | = | needs every site-independent core value shared program-wide (`%clojure-shared-value`); `type` of a pattern is the host class, the designator where no host is. Print probe lines with `pr-str`: bare `println` shows an undecoded `"1"` as `1` |

### core.async and tools.analyzer.jvm (g87)

Probe `{:deps {org.clojure/core.async {:mvn/version "1.6.681"}}}`,
`(let [c (a/chan)] (a/go (a/>! c 1)) (println (a/<!! c)))` (oracle `1`; the newest release,
1.9.865, has the same `go`). Measured 2026-10-29; the remaining chain is `.todo/g87`'s plan.

- The channel half (`impl.protocols`, `.buffers`, `.channels`, `.timers` ...) loads verbatim
  and prints the oracle's lines on the interpreter and the JVM.
- **What stops the probe**: `clojure.core.async` requires `impl.ioc-macros`, whose `go` calls
  tools.analyzer.jvm 1.2.3's `analyze` at macro time on every body, the namespace's own
  `go`/`go-loop` bodies (`pipe`, `mult` ...) while it loads (its computed `extends?` before
  them: the lowering table's type-predicates row).
- **tools.analyzer.jvm**, each blocker patched in a `:local/root` copy to see the next:
  `clojure.tools.analyzer` loads verbatim, `clojure.reflect` ships ("clojure.jar namespaces"),
  but (1) `jvm.utils`' `type-reflect` stops at `(RT/baseLoader)` (`No such class:
  clojure.lang.RT`); with tools.reader the analyzer loads and `(analyze 1)` runs to (2)
  `Compiler/LOADER` bound to `(RT/makeClassLoader)` (`No such class: clojure.lang.Compiler`),
  then (3) `build-ns-map`'s `ns-map` over `(all-ns)`, refused for a computed namespace at macro
  time and re-run after every expansion of a macro outside `ns-safe-macro`.
- **Class values no longer block the analyzer** (2026-11-26, "Class values"). `analyze` run at
  run time on the interpreter (a computed `ns-map` lowers there, so (2) and (3) are out of the
  way) over a copy with `RT/baseLoader`, `RT/classForName` and `Compiler/LOADER` patched to host
  calls answers the oracle's op and tag for all 18 forms of the measured list: `1`,
  `(let [x 1] x)`, `(:a {:a 1})`, `(throw (Exception. "x"))`, `'(1 2)`, a var, `case`, `letfn`, a
  string, keyword, vector, map, set and fn, `(try 1 (catch Exception e 2))`,
  `(let [x 1] (instance? String x))`, `Math/PI` and map destructuring (its
  `PersistentArrayMap/createAsIfByAssoc` a stub's member). Two blockers besides the class values:
  `ns-map` mapped an import to a symbol (now its class value), and a `defmulti` dropped its
  docstring and attr map, so `box`'s and `warn-on-reflection`'s `:pass-info` were missing and
  `schedule` ran `warn-on-reflection` ahead of `validate` (clojure-spec
  `var-quote-metadata-and-test`).
- **Cost of the namespace maps** (interpreter, run-time lowering): every `ns-map` holds
  clojure.core's ~770 referred vars, each a var site lowered and evaluated:
  `(ns-map 'clojure.core)` 173 ms literal, 205-290 ms computed, a small program's 11
  namespaces 1.7 s. `build-ns-map` over the analyzer's ~45 namespaces is 7-10 s, run once per
  `go` and again after every expansion of a macro outside `ns-safe-macro` (`doseq`'s analysis
  spent 30 s in three rebuilds): the macro-time maps need a cache keyed by each namespace's
  state.
- Read off the code, not yet reached: (4) `macroexpand-1` calls a macro's var as
  `(apply v form (:locals env) (rest form))`, which a macro's var root answers ("Macros", the
  core macros); (5) `go` reads each `&env` value as a `Compiler$LocalBinding` ("riddley
  (j27)", the `&env` contract); (6) at macro time `all-ns`, `find-ns` and `the-ns` answer the
  namespaces created above their call site, which predates `clojure.core.async`; (7) the
  emitted machine calls `Var/getThreadBindingFrame` and `resetThreadBindingFrame`, builds an
  `AtomicReferenceArray` and embeds the analyzer's `Class` objects in `.`/`new`/`instance?`/
  catch forms; (8) every `go` body runs the analyzer in the macro-time interpreter.
- No other Clojure runs tools.analyzer.jvm: cljw stops at `Long/getLong`, ClojureScript and
  ClojureCLR port the analyzer, babashka maps `go` to `thread`.

### clojure.spec.alpha (g90)

Probe (no `deps.edn`): `valid?`/`conform`/`unform`/`explain-*`/`form`/`describe` over `s/def`,
`s/keys`, the combinators and regex ops, `int-in`/`double-in`/`conformer`/`nonconforming`,
`check-asserts`/`assert` and bare core predicates: 35 lines, the oracle's on all four
backends, spec.alpha 0.5.238 loading verbatim from its jar; `clojure.core.specs.alpha` and
`clojure.spec.test.alpha` load too.

- **The jar**: `org.clojure/clojure` stays built in with no root, but its children are
  spec.alpha and core.specs.alpha (`ClojureBuiltinLibs.children`), so the tree is the oracle's
  `clj -Stree`. Every program selects them and few read them, so each is a
  `ClojureDepsGraph.Deferred` read when a lookup first asks for one of its namespaces, fetched
  where the host fetches; where nothing fetches, its note joins that lookup's refusal only. A
  program requiring none fetches nothing, so an offline first run stays offline (rejected:
  fetching the 637 KB jar before every program). The jar never joins the Java class path: its
  classes are the oracle's AOT output of namespaces lowered here from source. The four
  namespaces are not in `ClojureBuiltinNamespaces.LANGUAGE`, so a project's
  `clojure/spec/alpha.clj` shadows the jar, as on the oracle's classpath. Pins:
  `ClojureDepsFetchTest#theProjectResolvesBeforeTheProgramLowersAndItsNamespacesLoad`,
  `ClojureDepsProjectTest#aNamespaceNoRootHoldsIsRefusedNamingWhatIsNotSearched`.
- **What runs where spec computes a name** ("Lowering at run time"): `res` at run time and
  gen's `dynaload` (`(require (c/symbol ns))`, then `resolve`) lower as the program runs on the
  interpreter and in a JVM class (`.kb/jvm-run-time-lowering.md`); a wasm module refuses each
  by name. `(c/symbol ns)` is a computed spec (`ClojureNamespaceLowering.computesSpecs`): a
  qualified call head never opens a prefix list.
- **`instrument`, `unstrument` and `check`** (test.check 1.1.3 in the `deps.edn`): a 10-line
  probe with seeded `stest/check`s is the oracle's on the interpreter (about 8 s) and the JVM
  (about 8.8 s: 41 `resolve`s and 28 `require`s lower as it runs, 1.48 MB of fragments;
  `.kb/jvm-run-time-lowering.md`, "Limits"). A function whose qualified name a quote spells is
  redefinable in an open program altering a computed root (`s/fdef`'s registry key; "Vars and
  metadata", with-redefs). Rejected after
  measuring: every `defn` of such a program redefinable, since three corpus cases lost the
  class-dispatch-function shape a direct `defn` keeps
  (`named-class-dispatch-hits-the-nil-method`).
- **Gaps** (`doc/en/clojure/deviations.md`): `clojure.main/ex-str` of spec problems and
  `clojure.repl/doc` of a keyword stay refused: those namespaces load at startup or with every
  session, and requiring spec there would fetch and lower it for every program that loads
  them.

### riddley (j27)

**`riddley.walk` and `riddley.compiler` are built in, and `riddley/riddley` up to 0.2.2 is a
built-in coordinate** (`ClojureBuiltinLibs`, `lib/riddley/*.clj`; user page
`doc/en/clojure/reference/riddley.md`). riddley's jar drives the Clojure compiler's internals
from outside (a `proxy` of `Compiler$ObjMethod`, `Compiler/LOCAL_ENV` & co., and
`riddley.Util`, a Java class building `Compiler$LocalBinding`s).

- Rejected: making the jar's namespaces work. Even with `Compiler/LOCAL_ENV` & co. as dynamic
  vars and a proxy stub, `register-arg` calls `riddley.Util/localArgument` outside a `try`, a
  class that cannot link here ("Classes compiled against `clojure.lang`"), so every `fn*` with
  parameters refuses: it would take the rejected `clojure.lang` bridge or a special case of
  riddley's class. Claiming nothing leaves specter and potemkin red. riddley 0.2.2's babashka
  branch (`if-bb`) is no route: specter pins 0.1.12, which has none, and this front end claims
  no `:bb`.
- Census (the 1,281 jars in `~/.m2`): riddley's users are specter (`macroexpand-all`),
  potemkin 0.4.7 (`macroexpand`, `macroexpand-all`, `walk-exprs`, `locals`), manifold 0.4.3
  (`walk-exprs`, `macroexpand-all`, `locals` with marker metadata on its keys) and, through
  potemkin, clj-http 3.13.x. No other jar reads `Compiler/LOCAL_ENV`; core.async's `go` and
  flatland/useful's `assoc-record` read `&env` values as `Compiler$LocalBinding`s, core.match
  `Compiler/LOOP_LOCALS`' `.sym`.
- The files are riddley 0.2.2's documented behaviour (riddley is MIT; nothing copied).
  `riddley.compiler`'s locals are the lowering's locals in reach (`%clojure-local-env`,
  "Macros"), through the kernel namespace `rontolisp.internal.locals`. Deviations: the user
  page; of them, a key's lost metadata ("Vars and metadata") is what manifold's `let-flow`
  reads, and `walk-exprs`' `(eval x)` of a picked `(var x)` makes the program a JVM host
  ("Lowering at run time"), refused on wasm.
- **The `&env` contract, decided with g87**: `&env`, `macroexpand-1`'s environment and
  `riddley.compiler/locals` are one value, `%clojure-local-env`, keyed by each local's symbol
  and valued by the symbol, written by `ClojureMacroLowering.envValue` and by
  `register-local`/`register-arg`. A `Compiler$LocalBinding` face (`.sym`, `.tag`,
  `.hasJavaClass`, `.getJavaClass`; `.todo/g87` step 3) replaces the value at both places
  together; no consumer measured here reads it (specter, potemkin and manifold read the keys).
- A 25-line riddley probe (every special form, nested `&env`, `tag-of`, `locals`) is the
  oracle's on all four backends. Past riddley (interpreter): potemkin 0.4.7's `deftype+`
  expands, and clj-http 3.13.1 stops at `Unable to resolve classname:
  potemkin.collections.PotemkinMap`; potemkin 0.4.9 at its self-import (`unknown name:
  potemkin.namespaces`); manifold 0.5.0 before riddley, at tools.logging's `logp` expansion
  holding `*ns*` (`an unreadable symbol: :C%NS-OBJECT`).
- Pins: clojure-spec `riddley-walks-code-with-the-locals-in-reach` (host; four backends),
  `macro-env-and-form-ns-resolve-and-a-require-that-runs-later` (the nested-expansion lines),
  `ClojureDepsProjectTest#theBuiltInRingAndRiddleyNamespacesStandInForAnOlderVersionOnly`,
  `NativeImageResourceConfigTest` (the `lib/riddley` directory).

## Classes compiled against `clojure.lang`

**A class that cannot link is a catchable refusal naming the missing class, on the
interpreter and the JVM.** Why: reflection over a dependency's class compiled against
`clojure.lang` (reitit-core 0.11.0's `reitit.Trie`, over `IPersistentMap`/`Keyword`; no
`clojure.lang` class exists here) throws `NoClassDefFoundError`, an uncatchable JVM crash.

- `Cannot link class C: missing class M`, taken by `(catch Exception e ...)`, comes from
  `eval/JavaInterop.unlinkable` (every `java:` entry point), the bridge's (`JavaBridgeTemplate`,
  whose initializing `loadClass` throws where the interpreter's non-initializing one loads)
  and a direct call's `memberFailed` handler (`_junlinkable`; the resolver emits the call from
  class files alone, a clean signature needing no language). Only `NoClassDefFoundError`
  converts; any other `LinkageError` propagates. The lowering keeps every host form over such a
  class (its `LinkageError` guards), so the refusal comes at run time; a `reify`/`deftype` face
  over an unlinkable interface keeps it and refuses the same way (`ClojureJavaFaces.methods`,
  `plainThrowable`).
- A static initializer needing the missing class (a static field of its type) fails with
  `ExceptionInInitializerError`; a `NoClassDefFoundError` in its cause chain gives the same
  refusal (`eval/JavaInterop.initializerFailed`, the bridge's, `_junlinkableCause`, whose range
  covers the `new` too: making the object verifies the class). A genuinely broken initializer
  propagates.
- A class whose own supertype is missing never loads (`ReflectiveJavaClasses.find` answers it
  absent), so the interpreter's `loadClass` retries on a miss to answer the refusal, as the JVM
  class does. The lowering's hint reads (`castClass`, `ClojureValueMethodLowering.declares`)
  guard the `LinkageError` too (datascript's `^PersistentSortedSet` hints would crash it).
- Census (top 120 Clojars artifacts by downloads, 2026-10-13; constant-pool scan, each hit
  loaded without the language): 11 jars ship 99 classes naming `clojure/lang/` types, 93
  through a signature or superclass (reflection breaks), 6 through a static field only
  (initialization breaks); none only in a method body.

  | jar | classes | kind |
  |---|---|---|
  | hawk 0.2.11 | 42 + 1 field | AOT fns |
  | selmer 1.13.5 | 12 + 2 field | AOT fns, deftypes |
  | shadow-cljs 3.5.7 | 10 + 2 field | build tooling |
  | http-kit 2.9.0-beta4 | 9 | `ClojureRing`, `AFn`s |
  | jsonista 1.0.2 | 7 | Jackson serializers |
  | reitit-core 0.11.0 | 5 | `reitit.Trie` |
  | nrepl 1.8.0 | 3 | threads over `IFn` |
  | potemkin 0.4.9 | 3 | `PersistentMapProxy` |
  | cider-nrepl 0.63.1 | 1 | `DebugSupport` |
  | riddley 0.2.2 | 1 | `Util` (jar never fetched: "riddley (j27)") |
  | sci.impl.reflector 0.0.5 | 1 field | `FISupport` |

- No `clojure.lang` bridge, no new reader branch: a bridge would reimplement the runtime types
  as host classes, and no value here is an `IPersistentMap` for dependency code to read; the
  reader already takes a library's `:rontolisp` branch where one names it first, and such
  libraries ship a portable path (reitit's `clojure-trie-compiler`, `=` on all four: "HashMap
  construction"). User doc: `guides/java-interop.md`, `clojure/deviations.md`. Over reitit's
  default compiler P1 and the component compile (`java:` call-time warnings) and refuse
  catchably too.
- Pins: `ClojureInteropTest#aClassThatCannotLinkIsACatchableRefusalNamingTheMissingClass` (a
  fixture class over a type its jar omits: static call, construction, `Sub extends Missing`),
  `#aCompiledProgramFindsADependencyJarOnItsRunClasspath` (`-o Prog.class`: the jar on the run
  class path the refusal, else `No such class`),
  `#aClassWhoseInitializerNeedsAMissingClassIsACatchableRefusal`,
  `#aGenuinelyBrokenInitializerKeepsPropagating`.
- **persistent-sorted-set (datascript's indexes)**: its `.clj` wraps classes extending
  `APersistentSet`; read from it, datascript 1.8.1's first `create-conn` stops at
  `Cannot link class ...PersistentSortedSet: missing class clojure.lang.IEditableCollection`
  (P1: `The function JAVA:NEW is undefined`). Options (measured 2026-11-09): (a) the real
  `clojure.lang` on the host: a 4.2 MB jar whose `RT` init costs 618-964 ms per program (a
  loaded host), the set host objects read through `java.util`, a comparator crossing per
  comparison, wasm still red: the rejected bridge; (b) the library's own `.cljs` port in its
  jar: every function datascript calls but storage, over 24 `cljs.core` protocols
  (`clojure.lang` interfaces under other names) and the `arrays` macros' `:clj` branches; (c)
  datascript red; (d) a shipped portable namespace: one library special-cased (the probes' rule
  is infrastructure). Chosen: (b), the author's portable path, on every backend ("ClojureScript
  ports"); it also swaps `datascript.storage`, whose `.clj` reads `.-_storage` off the port's
  set, refusing every `create-conn`. Both are `=` on all four ("Library probes").

## ClojureScript ports

**A namespace whose Clojure file's `ns` imports a class that cannot load here, and whose root
ships a `.cljs` of the namespace beside it, is read from the `.cljs`, ClojureScript's names
read as this front end's Clojure** (persistent-sorted-set 0.3.1, datascript 1.8.1's
`datascript.storage`). Why: "Classes compiled against `clojure.lang`", the
persistent-sorted-set bullet. Surface and deviations: user doc `clojure/deviations.md`.

- Trigger (`ClojureSourcePath.portFor`): an `:import` in the `.clj`/`.cljc`'s first form naming
  a class with a missing supertype (`ClojureJavaClasses.missingSupertype`; on the native binary
  the class files walked in the JVM's order). Not a class absent altogether (a `clojure.lang`
  class the lowering answers itself, any class the binary sees without a JDK), nor one whose
  members name a missing type (`reitit.Trie`). No per-namespace table: the rule reaches every
  library shipping a ClojureScript half beside a Java half over `clojure.lang` (the swapped
  `datascript.storage` is what datascript's ClojureScript build runs). A var the port lacks
  refuses where called, naming the port (`ClojureScriptPort.missingVarCall`).
- `cljs.core` is `rontolisp.internal.cljs-core` (`lib/rontolisp/internal/cljs_core.clj`),
  referred at the port's `ns` and holding the protocols no `clojure.lang` interface stands for,
  the functions of those one does (`-first`/`-next`/`-rest` call the seq's own methods, so a
  port's seq type steps to the value it makes) and the helpers ports call. Each is written from
  what a port calling the name expects, ClojureScript's source unopened.
- In a `deftype`/`reify` a `cljs.core` protocol an interface stands for is that interface, its
  methods renamed (`ClojureScriptPort.STANDINS`; `-equiv` is `equiv` where a collection
  interface is among the body's, else `equals`), so the core verbs read the port's types
  ("Host interfaces", "Collection interfaces"); any other `cljs.core` protocol refuses by name.
  An `IPrintWithWriter` type prints through `-pr-writer` ("Dispatch"). A persistent set is also
  a `java.util.Set`, as every set class of the oracle's is, so a core set compares equal to one.
- `js/Math.m` and the bare `Math/m` (`java.lang.Math`'s, through `js-number`) answer an integer
  where the double is whole within 2^53, as ClojureScript prints it (the port's bit operations
  take `(js/Math.pow 2 n)`); `js/Error.` is a `RuntimeException`, what the Java half throws;
  `:require-macros` stays refused in a Clojure file, as the oracle's `ns` spec refuses it.
  Beyond the user doc: `Math/floor` reads a ratio whole; an `IPrintWithWriter` method gets nil
  `opts`.
- Pins: `ClojureProjectNamespacesTest#aNamespaceWhoseClassesCannotLoadReadsItsClojureScriptPort*`
  (`ClojureScriptPortFixture`: a `.clj` over a type the jar omits, a `.cljs` spelling the
  surface; four backends), `ClojureClassFilesLoweringTest` (`cljs-port`: reflection and class
  files choose the port alike).

## Portable reading of a dependency's Java class

**Where no host is (wasm), a class a source root holds as a class file -- a Clojure library's
own helper class -- is read as the Clojure it amounts to when its every member is within the
reading's subset** (`ClojureJavaClassReading` over `java.lang.classfile`,
`ClojurePortableClasses`). Why: specter caches compiled paths in a
`com.rpl.specter.MutableCell` and `fast-object-array` is `Util/makeObjectArray`, Java no wasm
module runs. Chosen, as it reaches every such library with no per-library table, over
specter's `:bb` branch (babashka is a JVM with its classes; the reader takes `:clj` first), its
`:cljs` branch (one namespace here serves both of ClojureScript's readings, and specter's
macros call `intern*`, which `:cljs` refuses) and red.

- The subset and deviations: user doc `clojure/deviations.md`; `Unreadable` names each refusal.
  Instance fields must be private (a deftype's mutable field answers no `.-f`); arrays are
  vectors. Any other class stays the host's: a call-time refusal (`JAVA:NEW` warns).
- The namespace `rontolisp.java.<binary name>` holds a `deftype*` named as the class, so
  `class`, `instance?`, the printer and a protocol row read the oracle's class; a static field
  is a var `field-NAME` (no Java name has a hyphen, so it never meets a method's). A value
  keeps its Clojure spelling at every boundary and the JVM's on the stack.
- Loaded by top-level datums only (`ClojurePortableClasses.loaded`), never where the host is,
  never for a `java.`/`javax.`/`jdk.`/`sun.`/`com.sun.`/`clojure.` class. A construction takes
  the reading's `<init>` of the count, never the type's positional constructor
  (`ClojurePortableClasses.typeConstruction`); a hint `^C` still names the host class, so
  `castClass` keeps the oracle's `ClassCastException`, which specter's cache catches ("Host
  arms where the host is not"). A macro body uses the reading too: the macro-time image runs
  the program's lowering ("Macros").
- Pins: `ClojureProjectNamespacesTest#aDependencysPlainJavaClasses*` (`ClojureJavaClassFixture`:
  four classes spelling the subset, a macro building a cell while it expands, a string
  concatenation refused where no host is; the interpreter and the JVM run the host's).

## Ring adapter

`ring.adapter.rontolisp/run-server` lowers to the SAME transport function the Clack
`:server :rontolisp` shim's `run` calls, `rontolisp::%http-serve` (`.kb/clack.md`,
"Transport selection"), so it serves on every transport the shim serves (user doc
`doc/en/clojure/reference/ring.md`). Built in: Clojure has no documented way to call a Common
Lisp function. The call site names `%http-serve` itself, so `HttpServeLibrary` (first pass of
`expand`) splices it before the passes reading its legs.

- Request `:body` is a `clojure.java.io` byte stream (`%clojure-ring-input`) drained by
  `read-byte`, which both bivalent bodies answer; `read-sequence` would put the Gray dispatch
  helper in every Gray-using program ("Prelude trap"). The Ring app (`ClojureRingLowering.APP`)
  is an IO-family producer, so every Ring program carries the io arms. Its class is Jetty's
  `HttpInput` (`ClojureIoLowering.RING_INPUT`) and a close leaves it readable, all as in the
  oracle (ring-jetty-adapter 1.15.3) but `class`'s keyword. A computed method keyword is a
  fresh charvec that `get` and `=` match on all four; `ClojureLibraryTest`'s namespace-map
  census lists the keyword builders slashless (HTTP tokens admit no `/`).
- Response: a File or byte-stream body is one `(unsigned-byte 8)` vector
  (`%clojure-io-ring-body`), in io and host arms a program making no io value folds. A missing
  File is the oracle's `FileNotFoundException`, a 500, where Jetty commits an empty 200. The
  transport computes `Content-Length`, dropping the map's, so never two. `:port` defaults to 80
  (`ring.adapter.jetty`'s); a truthy `:async?` is refused.
- `examples/cloudflare-workers/ring-hello-one-source/` compiles
  `examples/clojure/ring-hello.clj` unedited (`--no-wasi --optimize=size --emit-js-glue`); its
  glue lacks the entropy seed (`__ronto_seed_random`), the program drawing nothing
  (`.kb/wasm-export-no-wasi.md`); `HostGlueEmitterTest` pins it against this program itself,
  since a stand-in of its declarations cannot show what the Clojure library's shake keeps
  (`.kb/library-defun-pruning.md`, "unqualified bundled name"). A urlencoded POST `/echo` answers `""`: `wrap-params`
  consumed the body, as under Jetty.
- Not done: `ring.adapter.jetty` as an alias (it would claim Jetty's options; refused by name),
  `stop-server` (Jetty's is `(.stop server)`, interop on the handle).
- Pins: `ClojureRingAdapterTest` (interpreter, JVM, the `--no-wasi` export via node, the war's
  registration, refusals, `run-server` as a value, `/octets` `/relay` `/copy` `/latin`;
  `#aNativeExecutableServesARingHandlerThroughItsRunner`), `ServeRingComponentE2eTest`,
  `WarE2eTest#aRingHandlerServesFromTheWarOnTomcat` (both opt-in), `ClojureHttpClientTest`'s
  proxy `/upload`, clojure-spec `slurp-and-the-reader-take-an-open-stream`,
  `examples/clojure/ring-hello.clj` (four compile legs), `HostGlueEmitterTest` (the Worker's
  checked-in glue).

## Ring util namespaces

**The pure-function subset of ring-core 1.15.5 / ring-codec 1.3.0 ships as Clojure source**
(`src/main/resources/am/ik/rontolisp/clojure/lib/ring/**`, `ClojureBuiltinNamespaces`; user
doc `doc/en/clojure/reference/ring-util.md`). `ClojureSourcePath.find` reads one AFTER every
source root, so a project file of the name wins, as `src` precedes a jar on the oracle's
classpath; a `deps.edn` ring-core newer than the shipped one refuses them ("deps.edn"). Each
loads like a project namespace.

- **Shipping mechanism**: pure-Clojure ports were oracle-identical but heavy wherever they
  process bytes or strings (generic `conj`/`apply str`/`throw` and the regex engine per
  program), and `\p{L}` of `wrap-keyword-params` has no cheap test (`alpha-char-p` is ASCII on
  wasm; a regex `\p` costs a per-program table, "Regex"). So the namespaces stay Clojure (the
  oracle's own code where map-shaped) over Common Lisp kernels (`clojure.lisp`, "The ring.util
  kernels") behind `rontolisp.internal.ring`, which only a built-in file may require.
  Rejected: lowering rows per var (the `clojure.set` shape), a cost map-shaped vars do not have
  as Clojure.
- **Init statements carry code**: a `def` of a regex in a required namespace compiles the regex
  engine into every program requiring it, so `ring.util.parsing` is not shipped; the kernel
  `content-type-charset` spells out the oracle regex's search order.
- **Oracle behaviour, pinned against the JDK code the oracle runs** (`ClojureRingUtilTest`,
  seeded random inputs): percent-decode is `new String(bytes, charset)` per `%XX` run,
  malformed input replaced as that decoder does ("The JDK decoder"); `form-decode-str` is
  `URLDecoder.decode`, the encoders `getBytes` and `URLEncoder.encode`, the charset match
  `re-charset`; a charset name is checked only where the oracle checks it; `\p{L}` is a
  generated table (`%clojure-ring-letter-table`) the test regenerates from
  `Character.isLetter`. Differential runs (460 codec inputs, 3,000 content types, 3,000
  parameter names): identical but `(mapv int s)` of a surrogate pair (code points here).
- **base64 is a PART** (`lib/ring/util/codec_base64.clj`), as are the file responses
  (`lib/ring/util/response_files.clj`): `ClojureLowering.loadPartOf` loads a part into its
  namespace where a top-level datum first names one of its vars (a `:refer :all` refers them
  without loading). Why: the arm scan is by name over every spliced form, dead defuns included
  (Clojure defuns are user program, never pruned), so base64's byte array or the file defns'
  io producers would keep those arms in every program requiring the namespace. Pins:
  `ClojureRingFileResponseTest#theFileResponsesLoadOnlyWhereAProgramNamesOne` (no
  `%CLOJURE-IO-` in a plain program), clojure-spec
  `ring-util-codec-base64-encodes-and-decodes-a-byte-array` (four backends),
  `ClojureRingUtilTest#theBase64PairAnswersWhatTheJdksBase64Answers` (against
  `java.util.Base64`).
- The file part is ring-core 1.15.5's code over clojure.java.io's values; `canonical-path` is
  `%real-path` (links resolved, a relative path kept relative, so wasm needs no cwd). Checked
  against the oracle: `..` refused but `dir/../a.txt` served; a `jar:` URL's `Last-Modified` is
  the jar FILE's mtime; a link out of `:root` nil unless `:allow-symlinks? true`.
- A left-out var (`form-encode*`, `FormEncodeable`) is
  `ns/var is not built in: <why>` (`refuseLeftOut`, only for the built-in file). Deviations:
  user doc; `body-string` is a `cond` because `class` of a string is no class object on wasm.
  `resource-config.json` registers `clojure/lib/ring/...` for the native and web images
  (`NativeImageResourceConfigTest`).
- Pins: `ClojureRingFileResponseTest` (four backends: a directory, a resources directory, a
  jar), `ClojureRingAdapterTest`, `ServeRingComponentE2eTest`,
  `WarE2eTest#aRingHandlerServesFromTheWarOnTomcat` (file bodies served octet for octet, a
  missing File's 500), `ClojureRingUtilTest` (JDK differentials, the letter table, refusals, a
  shadowing project file, a POST through `wrap-params`), clojure-spec `ring-util-*` and
  `ring-middleware-*` (four backends), `examples/clojure/ring-hello.clj`.

## HTTP client

**`rontolisp.http-client` is babashka.http-client's API (0.4.23) as a built-in Clojure
namespace whose every request is the program's own `rontolisp:fetch` call: no transport is
new, and the five fetch has (the JDK's on the interpreter and the JVM, `wasi:http`, the
`--host-fetch` reactor's `env.fetch`, the `--native` runner) all carry it.** User doc:
`doc/en/clojure/reference/http-client.md`.

- The namespace (`clojure/lib/rontolisp/http_client.clj`, kernels `rontolisp.internal.http`)
  passes a transport calling `kernel/fetch`, so the PROGRAM names fetch: the transport splices
  (`HostFetchLibrary`, `processForRunner`, `HttpLibrary`) run before `ClojureLibrary` and never
  see the library's own references, and that name makes plain P1 and `--no-wasi` without
  `--host-fetch` refuse at compile time (`WasmFetchCompiler.reject`).
- babashka.http-client, not hato or clj-http: the smallest API over the `java.net.http` client
  fetch's JDK leg uses. A built-in file, not a lowering slice: the verbs are real vars and a
  wrong count is the oracle's words ("Arity refusals"). `babashka.http-client` itself is
  refused (`ClojureBuiltinNamespaces.notShipped`): it would promise options with no value kind
  on wasm (`:client`, `:interceptors`, a `java.net.URI` `:uri`).
- A request runs the oracle's request interceptors in their order (`clojure.lisp`
  "rontolisp.http-client"), quirks included: `prefer-string-keys` drops a keyword header name
  beside a string one; `:form-params` sets the content type unless the KEYWORD key names one.
  `:timeout` is open work. `:as :bytes` makes the request kernel a byte-array producer ("Byte
  arrays"). A body goes as octets (`.kb/fetch-http.md`, "The request body"); anything else, a
  reader included, is the oracle's `Don't know how to convert class Xto body` (sic); a missing
  File is `ofFile`'s `<path> not found` (a multipart part's, io's words). `:multipart` is the
  oracle's interceptor (hato's format) octet for octet; a File part's type is
  `Files.probeContentType` as Debian answers it (`%clojure-http-file-type`).
- The exchange is plain defuns answering an `async-lambda`'s future, never `async-defun`s:
  `LibraryDefunPruner` keeps every top-level form but an unreached DEFUN, so an async-defun in
  `clojure.lisp` would ride every Clojure program
  (`ClojureLoweringTest#aProgramPassingRealFunctionsSplicesNoDispatcher`). The response runs
  the oracle's response interceptors in their order. Decompression (`.kb/fetch-http.md`,
  "Decompression") reads at the call what the oracle's constructor reads, probing deflate as
  zlib, then raw, as `inflate` does; data cut short is an `EOFException` built with `c%e-new`,
  since a cut gzip header or trailer has no message.
- The future and stream are arms of `ClojureArms.Family.FETCH` ("clojure.java.io"); its alias
  `%clojure-future-or-host-p` -> `%clojure-host-future-p` is folded by HOST in turn, so FETCH
  precedes HOST in the enum. `deref`'s clause precedes the host one: on the JVM the future IS a
  `CompletableFuture`, whose settled payload only `_await` reads. Timed `deref` polls
  `rontolisp::%future-settled-p` between `(sleep 0.001)`s; on a component that sleep forces a
  timer, which drives the scheduler, so `WaitForLibrary` splices it where
  `ClojureArms.sleepsOnAFuture`. `realized?` stays the oracle's `ClassCastException`; a Clojure
  future ("State") differs: its host task cancels, so `future-cancel` wins and `realized?`
  reads its done flag.
- **`:as :stream` is a `clojure.java.io` byte stream** of the oracle's classes, so the request
  kernel is an IO-family producer too (`ClojureArms.ioProducers`). A synchronous read goes
  through `%clojure-io-next-chunk`, which forces again what a `--component` read of an
  in-flight chunk settles to (a 300,000-octet `.transferTo` met `#<FUTURE>` without it). A
  reader decodes as the reply arrives ("Readers over a fetched reply" under "clojure.java.io").
- Oracle (0.4.23 against the corpus origin): identical on the four legs but the user doc's
  differences, the User-Agent and map key order; a transport failure's text is the
  transport's (`java.net.ConnectException`,
  `the WIT call answered its error arm: :CONNECTION-REFUSED`, `fetch: cannot connect to ...`).
- **Redirects** (`%clojure-http-redirect`; resolution in `eval/uri.lisp`, shared with the Java
  subset's `java.net.URI`: `.kb/java-frontend.md`, "java.net.http"). Written from RFC 3986
  section 5 and the JDK's answers; nobody read OpenJDK's source. Probed on JDK
  25 (`HttpClient`, `Redirect.NORMAL`, a local server): 301, 302, 303, 307, 308 are followed
  (not 300, 304-306, 309, 399), at most four in a row (the fifth reply is answered), to the
  same scheme in any case or to `https`; 303 makes every method a bodiless GET, HEAD included,
  301/302 only a POST, any other keeps method and body; off the origin (scheme case-blind,
  authority as written: `LOCALHOST`, `user@`, `:0port` differ) `authorization`, `cookie`,
  `origin`, `referer` stay behind, the rest go on; no Location is `Invalid redirection`, an
  empty one the base's directory. Over 22,703 generated pairs the code answers what
  `URI.resolve` answers (the old one missed 2,939); the JDK's departures from RFC 3986 are in
  the docstrings. Known differences: a Location that is no URI (`g h`, `1g:h`, `//`) is
  followed where the oracle fails; and `clojure.java.io`'s `http:` reads resolve the same way,
  where the oracle's `HttpURLConnection` uses `java.net.URL` (differing on `./g` against a
  pathless base, `a//b`, `///g`).
- Pins: `FetchSpecE2eTest#clojureHttpClient` (`clojure-http-spec.yaml`: interpreter, JVM,
  `--native`, component), `ClojureHttpClientTest` (lowering, refusals, the FETCH strip, a
  binary Ring proxy, a second-origin redirect dropping the credential headers, which the
  one-origin corpus cannot show), `ClojureHttpClientHostFetchE2eTest` (node over the glue's
  `defaultHost()`: the client, an octet body, a Ring proxy),
  `ServeRingComponentE2eTest#wasmtimeServeRelaysAFetchedReplyByteForByte` (opt-in), the
  `http-client.md` examples (`DocExamplesTest`, against its local origin).

## Host boundary

**`rontolisp.wasm` and `rontolisp.wit` LOWER to the Common Lisp directives
(`rontolisp:wasm-import`/`wasm-export`, `rontolisp:wit-import`/`wit-export`/`wit-provide`);
no backend gained a path.** Built in (`ClojureWasmLowering`, `ClojureWitLowering`) because
Clojure cannot name a CL function and a directive must be a top-level form. User docs:
`doc/en/clojure/reference/wasm.md`, `doc/en/clojure/reference/wit.md`.

- **The hook**: `compiler` (the WIT parser, `BoundaryType`) may not be imported here, so
  `eval/ClojureHostBoundary` implements `ClojureBoundary` over the directives' `describe`
  (`.kb/wit.md`, "The naming hook"), injected through `Clojure.read(..., boundary)` and
  `ClojureSession.setBoundary`; without it both namespaces refuse.
- The host sees a name as written (`:as`, a `:names` table), never `c%ns/name`. `:bool`
  converts because `false` is a non-NIL symbol here. A declaration with no converting crossing
  lowers to exactly the hand-written directive; otherwise, as for an export that is no plain
  `defn` of its arity (user doc), a wrapper stands between (`importWrapper`, `exportWrapper`).
- `:bytes` (`Crossing.BYTES`) crosses octets uncopied. An import answering `:bytes` is the CL
  read(2) shape (one more byte-array parameter the host fills, counted by the host stub's
  arity; the call answers the length), keyed on the `:bytes` designator: `:octets`
  (`.kb/wasm-import.md`) is the same crossing as a plain value. Pins:
  `ClojureWasmBoundaryTest.aByteArrayCrossesAsItsOctetsUnderNode` (four directions, `ff`, the
  refusal), `theInterpreterAndTheJvmCallABytesExportAndStubABytesImport`,
  `anOctetsImportTakesAndAnswersAByteArrayUnderNode`.
- Order: `defimport` registers its name in pass one (like `deftest`), so a call above it is
  direct; its interpreter and JVM stub throws `UnsupportedOperationException` in Clojure's
  words (the directive's would name the mangled symbol). A `wit/import` reads its WIT in pass
  two, so its vars exist below it. `ClojureWasmLowering.flush` resolves every export once the
  file (a session: the buffer) has lowered. `:wasm/export` metadata is QUOTED (`defnExport`): a
  literal map built at run time would make an exporting program differ by that store.
- **Byte identity**: a `:wasm/export` program equals the Common Lisp program under the
  hand-written directive (default, `--optimize=off`, `--component`); `wit/import` on P1 equals
  `defimport` of the members it calls; a `wit/export` world equals the hand-written block (P1,
  `--component`). So `ClojureArms.scan` skips the three function-naming directives, whose
  quoted `|c%ns/f|` would keep the `#:ns{...}` printer arm (`Family.NAMESPACE_MAP`).
- **`wit/import`** (`namespaceOf`; unbound members refused at lower time, `refusalOf`): only a
  member the program names gets a wrapper (`referencedWrappers`), and `WitImportInliner`
  filters a `:names` directive by reference on P1 too (`.kb/wit.md`, "The naming hook"), so a
  member the core import cannot carry (a record, an option, `char`, `s64` ...) fails the build
  only where the program calls it.
- Rich values (the Clojure spelling of the settled CL tier, `.kb/wit.md`; user doc "What
  crosses"): no oracle, the reference page is the contract; `[:tag value]` is
  `clojure.spec`'s conform shape of an `s/or`. Types become descriptors
  (`ClojureWitLowering.descriptor`, a global per distinct type) that one `clojure.lisp` walker
  reads both ways, so a deep type costs a constant, not code. `bool`, `option<bool>` and a bare
  `list<u8>` keep the inline crossing, so a member without rich types stays byte-identical.
- `list<u8>` is a byte array on every target: the directive always carries `:octets t`
  (`.kb/wit.md`), so a component lifts it octet for octet (`_string_from_mem`'s UTF-8 decode
  would mangle `ff 00 41`), and P1 declares the member `:octets` (`.kb/wasm-import.md`). The
  descriptor global spells `:BYTE-ARRAY` in the program, so the scan keeps the byte-array arms.
  Pins: `ClojureWitBoundaryTest.aListOfOctetsIsAByteArrayBothWaysOnTheInterpreterAndTheJvm`,
  `aListOfOctetsCrossesAPreview1ModuleExactUnderNode`, `aComponentImportsWasmtimesKeyvalueStore`,
  `aComponentReadsBackOctetsThatAreNoUtf8Exact`.
- The error arm is an `ExceptionInfo` holding the value under `:rontolisp.wit/error`.
  Rejected: the CL `wit-error` condition (no Clojure reader reaches its payload; `DNS-error`
  cannot be recovered from `:DNS-ERROR`); `{:payload ...}` (a provider's own exceptions may
  hold that key). The CL tier signals the arm on the host but answers an envelope on wasm, so a
  WASM wrapper calls the RAW binding (`<bound>%raw`) and throws itself (a catch would put every
  result-calling module in EH mode, `.kb/error-handling.md`; `%wit-result` would splice all of
  wit.lisp); a host wrapper catches `rontolisp:wit-error` and re-raises it as the arm. The key
  is spelled in the program, so the scan keeps `NAMESPACE_MAP`.
- **Trap: `clojure.lisp` must name nothing of wit.lisp** (`rontolisp:wit-error`,
  `wit-error-payload`, `%wit-call`, `%wit-result`, `wit-provide`): `WitLibrary.process` runs
  after the Clojure splice and before the pruner, and would splice wit.lisp (not prunable) into
  every Clojure program. What signals or catches the condition is lowered into the program.
- `wit/export` resolves each world label's var as `wasm/export` does (`flushWorlds`); the CL
  directive keeps the contract and type checks and `--emit-wit`. `wit/provide` binds through an
  adapter (`ClojureWitLowering.adapter`) that hands the provider Clojure values and signals a
  thrown error key as `rontolisp:wit-error`. The language boundary IS the CL tier: each side
  converts to and from it, and what the WIT does not carry (other `ex-data` keys) does not
  cross.
- **A world's compound export** crosses in the import tier's spelling, the other direction
  (`exportConversion`): the wrapper reads each parameter IN and the answer OUT through the same
  walker. **Its descriptors are QUOTED in the wrapper, never the import's globals**: a host
  `--invoke`ing an export of a command component runs no top level, where a global is set
  (`.kb/wasm-export-no-wasi.md`, "`wit-export :reactor t`"). A top-level `result` is the envelope
  the wrapper makes: `(handler-case (cons :ok ...) ((and error (satisfies %clojure-wit-arm-p))
  ...))`, the arm's value by `%clojure-wit-arm-value`; any other exception traps. The directive
  always carries `:octets t` and `:envelope t` (`.kb/wit.md`), which a flat world's bytes ignore.
  What no export carries is `describe`'s refusal at the WIT line, before a var is read; a core
  module and `--no-gc` refuse in the compiler. Sizes (2026-11-26, `--component`, raw / gzip):
  a `u32` export throwing an `ex-info` 191,530 / 62,415; the same as `result<u32, string>`
  211,050 / 68,341, as `list<u32>` 206,692 / 66,775 (the walker's whole `out`); a lone
  `flip: func(point) -> point` 104,711 / 35,734. Byte-identical before and after: the old
  greeter (P1, component), host-boundary, page-hits, macros, text, `examples/wit` shapes, app and
  counter, the Java counter.
- A world's export reads the program's top level, so a command component traps under
  `wasmtime --invoke` (no top level ran) while the same program's `run` answers (measured
  2026-11-27): `Integer/parseInt` through `%clojure-decimal-digit-starts`, a `(defvar)` list the
  top level assigns, and a top-level `def` a `defn` closes over. `wit/export` takes
  `{:reactor true}` (`exportCall`, `PendingWorld.reactor`), which passes `:reactor t` to the
  directive; a WASM command module refuses it there. The default stays the command: a program
  that also works from its top level is a script, and the hand-written block a world's bytes
  equal is a command.
- WIT paths resolve against the naming file (`ClojureFiles`); the entry file's directive keeps
  the path as written, so the CL inliner, resolving against the entry's directory, reads the
  same file; a dependency jar's namespace names `<jar>!/<entry>` (`WitFiles.read`).
  `--scaffold-wit` reads no source, so it has no Clojure form.
- Pins: `ClojureWasmBoundaryTest`, `ClojureWitBoundaryTest` (interpreter, JVM, P1 under node,
  `--component` under wasmtime with its real `wasi:keyvalue`, `wasi:sockets/types`,
  `wasi:http/types`; `everyRichValueCrossesInClojuresSpellingOnTheInterpreterAndTheJvm`,
  `eachLanguageSeesItsOwnSpellingOfOneInterface`,
  `aPreview1ModuleRefusesARichMemberOnlyWhereTheProgramCallsIt`), `WitNamingHookTest`,
  `ClojureHostBoundaryTest`, `WitImportInlinerTest.aNamesTableBindsOnPreview1TheMembersTheProgramNames`;
  `ClojureWitBoundaryTest.aWorldIsImplementedAsAComponent` (every compound kind, the error arm,
  the greeter example invoked, a reactor world's top-level read under `--invoke` and the command
  module's refusal),
  `ExamplesE2eTest` over `examples/clojure/host-boundary/`, `examples/clojure/greeter/`,
  `examples/wit/keyvalue/page-hits.clj` (a record answer, an error arm).

## clojure.jar namespaces

**The map-shaped namespaces of clojure.jar ship as Clojure source written for this front
end** (`src/main/resources/am/ik/rontolisp/clojure/lib/clojure/**`, the
`ClojureBuiltinNamespaces` mechanism of "Ring util namespaces"; `instant`/`uuid`: "Instants and
UUIDs", `java.io`: "clojure.java.io"). Only those no measured library names stay `unknown
namespace`; the spec jars' namespaces load from their own jars ("clojure.spec.alpha (g90)").
- **Licensing**: clojure.jar is EPL-1.0, this project Apache-2.0, so nothing of it is copied or
  translated -- no code, no docstring (`.kb/java-frontend-provenance.md`). Each file is written
  from the documented behaviour (the API documentation, the docstrings `doc` prints) and from
  what the oracle ANSWERS, its source closed: a rule the documentation lacks is the oracle's
  answer to a probe, and the shape is ours (`demunge`'s table of spellings, one slot comparison
  for both diffs, `ex-str`'s per-phase word list, `fold-vector` over index bounds). A one-line
  var dictated by its contract (`(prewalk-replace smap form)`) reads like the oracle's because
  the contract allows no other shape.
- **Library use** (72 jars, a library counted once per namespace it names): `clojure.string`
  43, `java.io` 20, `set` 19, `walk` 18, `pprint` 13, `edn` 13, `spec.alpha` 10,
  `core.protocols` 6, `test` 4, `datafy` 3, `stacktrace` 2, `zip`, `xml`, `uuid`, `template`,
  `repl`, `math`, `instant`, `core.reducers` 1, `data`, `main`, `java.shell` 0.
- **Startup namespaces** (`ClojureBuiltinNamespaces.STARTUP`): `clj -M` has loaded
  `clojure.walk` (with `core.protocols`, `core.server`, `edn`, `instant`, `java.io`, `main`,
  `spec.alpha`, `spec.gen.alpha`, `string`, `uuid`) before the program, so a qualified name
  reaches one with no `require`, and its `require` reads no project file. Here the first
  qualified name loads it (`ClojureNamespaceLowering.preload`), only inside a top-level datum:
  the definitions must be `hoisted` ahead of the datum, and the pre-scan resolves names outside
  it. An unaliased qualified name of a lowering namespace reaches only the oracle's boot set
  (`string`, `edn`) and our own; a `set`/`test` name needs its namespace required somewhere in
  the program, else it refuses at run time (`ClojureBuiltinNamespaces.isBootLoaded`). Any other
  shipped namespace follows the source path like a dependency: a project file of the name wins.
- Left out by name, each with its reason: `ClojureBuiltinNamespaces.replLeftOut`,
  `mainLeftOut`, `xmlLeftOut`. `HOST_ONLY` (`clojure.java.shell`) refuses its load while
  lowering for a target without the host, not at the first call.
- `clojure.walk`: `walk` re-attaches the form's metadata itself (`with-meta-of`), only when there
  is some, since `into` here starts a derived value without metadata ("Deviations"); a strict
  seq stays a list. `macroexpand-all` expands only the program's macros and the core macros
  expanded as data ("Macros"; user doc).
- `clojure.template`: `do-template` substitutes at expansion time ("Macros": a body sees the
  definitions above it, a required namespace's too). `apply-template` asserts that `argv` is
  a vector, then that its members are symbols: the oracle's two messages, in its order.
- `clojure.pprint`: the API is Clojure source, the layout the kernel namespace
  `rontolisp.internal.pprint` (`clojure.lisp`, "The clojure.pprint kernels"): the dispatch prints
  into a capture stream that records layout marks beside the text, laid out afterwards
  (`%clojure-pp-lay-out`). All of it is written from the HyperSpec (22.2, 22.3), the namespace's
  documentation and clj as a black box; nobody reads Clojure's pprint sources
  (`.kb/java-frontend-provenance.md`).
  - The marks are taken in the order they were made, each knowing only those before it. A cut
    measuring each stretch to the end of the record matched clj on 1,499 of 1,500 generated
    layouts, and its misses (`~T` after a line end, a trailing newline, a 72-column `let`)
    showed that clj's answers depend on what had been written when. Generated layouts are
    identical to clj on the interpreter and the JVM class, the feature probes on all four.
  - `simple-dispatch` has an exact method per common value class beside `:default`: a value
    without one tests `isa?` against every method once per value printed (no multimethod cache,
    "Dispatch"). Cost (2026-10-08): faster than the oracle's on the JVM and the wasm legs,
    slower on the interpreter.
  - `code-dispatch` layouts were read off clj at margin 1 and 72, with and without miser mode; a
    run of directives is a control string of our own `cl-format`, so it stops at the first
    object `*print-length*` cuts. Quirks kept: `(defn \n  f`, `(let [a 1] )`, `((let x))`, the
    ns docstring unescaped. 2,200 generated forms are identical to clj on the interpreter, 1,500
    on all four. Deviations: `(ns)` alone prints as a list (the oracle overflows its stack); an
    empty list in a reference prints `nil` ("Deviations").
  - A print in progress is joined only when `*standard-output*` is its capture stream
    (`%clojure-pp-active`; the oracle asks whether `*out*` is a pretty writer), so `with-out-str`
    inside a dispatch writes plain text. A joining `pprint`/`write` ends with a `:sync` mark
    closing the record so far, else `(cl-format nil "~@w ~:w" x y)` breaks `x` where the oracle
    breaks `y`. `fresh-line` outside a pretty print always writes a newline (the oracle's writer
    keeps no column). Off the default `*print-base*`, `255` is `ff` from the dispatch but `255`
    under a bare `~W` (`*radix-pr*`; the oracle rebinds `pr`).
  - `cl-format`, `formatter`, `formatter-out` are Clojure source too: the run-time `format`
    (`.kb/format.md`) prints CL notation and iterates lists, so no conversion layer could serve.
    Oracle mechanics kept, each measured: `v`/`#` parameters resolve last-first; the integer and
    English spellings walk a double in Java's double arithmetic; `~F ~E ~G ~$` work on the
    number's Java spelling, a ratio through the double of its sixteen digits, a decimal with all
    its digits, `~$` every number as a double (12,000 generated cases identical); `~(...~)`
    converts each write apart (`~:(~a-~a~)` of "foo" "bar" is `Foo-bar`), its state a CL
    special, so a program formatting nothing carries none; past the end `~*` hands back one nil
    (the oracle's truthy empty `LazySeq`); inside clauses `~^` reads the enclosing directive's
    arguments (`~{~a~:^,~}` never exits).
  - The macros name their function in a plain template (`` `(#'formatter-fn ~format-in) ``), an
    unspelled quote ("Macros"), so a run-time expansion keeps no executor; not
    `(symbol "clojure.pprint" "formatter-fn")`, which armed the dispatch gate in every program
    with live expanders. Compiled formats are cached by control string (a compile is pure;
    emptied at 512): a repeated nine-directive format runs several times faster.
  - Fidelity, identical to clj on all four: 2,200 random `~F ~E ~G ~$`, 1,400 integer and text
    directives, 1,700 random structural control strings, 1,300 pretty layouts. Deviations: user
    doc `doc/en/clojure/reference/clojure-pprint.md`; a format that is no control string runs
    only as the private `compile-format`'s (a map holding `::pieces`).
- `clojure.data`: the `Object` arm sends a `map?` value to the map diff, so a record reaches
  neither the `java.util.Map` nor a vector row (the oracle's record is a `java.util.Map`). One
  slot comparison serves maps and sequences; probed: `(diff {:a [nil]} {:a []})` is
  `({:a [nil]} {:a nil} nil)`, `(diff [1 2 3] [1 2 3 nil])` `[nil [nil nil nil nil] [1 2 3]]`.
- `clojure.zip`: a loc is the oracle's own shape (`[node path]`, the path map, the zipper
  functions in its metadata, the end loc `[node :end]`), since programs print and destructure
  locs; the error words are the oracle's.
- `clojure.core.protocols`: loading it links the `CollReduce` protocol, so even
  `(prn (clojure.core.protocols/datafy 1))` carries it.
- **`reduce` consults `CollReduce` and `reduce-kv` `IKVReduce` for a record, deftype or reify
  whose type has its own row** (body or extension), behind a body's `IReduceInit`/`IReduce`/
  `IKVReduce`, which the oracle asks first ("Host interfaces"); any other value takes the verb's
  own walk. An extension to nil, `Object` or a core kind is reached only through
  `coll-reduce`/`kv-reduce` (deviation, user doc). `into`, `transduce`, `cat`, `run!`, `mapv` of
  one collection, `filterv`, `group-by`, `frequencies` and `update-vals`/`update-keys` follow;
  `vec`, `set`, `seq`, `count`, `apply`, `sequence` stay refused, like the oracle's.
  - The arms are the reducible family (`ClojureArms.Family.REDUCIBLE`); its producers
    (`%clojure-coll-reducer-row`) store a typed row in a library global, so the library names no
    mangled program name. The namespace's own nil/`Object` rows and a core-kind extension are no
    producer, and the run-time registry carries the method's keyword, never the row writer: a
    datafy program keeps every arm folded.
  - An `eduction` over such a value reduces it at the `eduction`; the init-less `reduce` and
    `seq`, which the oracle refuses, answer (deviation, user doc).
- `clojure.datafy`: the oracle's `Throwable` and `IRef` rows, through the walk ("Dispatch",
  walked classes); `:clojure.datafy/class` is `%clojure-datafy-class-name`'s oracle class name,
  since `class` answers a kind keyword. Pin: clojure-spec
  `clojure-datafy-of-an-exception-and-a-ref-like-the-oracle`.
- `clojure.stacktrace`: `print-throwable` spells the class through `%clojure-datafy-class-name`
  (`ClojureInteropTest#printThrowableSpellsTheClassOfACaughtHostException`). A throwable has no
  frames: ` at [empty stack trace]` (deviation, clojure-spec
  `clojure-stacktrace-prints-no-frames`); `print-trace-element` over a host `StackTraceElement`
  is the oracle's (`ClojureInteropTest#printTraceElementSpellsAHostStackTraceElementLikeTheOracle`).
- `clojure.math`: a double function's kernel (`rontolisp.internal.math`) is lowered in place
  (`ClojureKernelLowering.Kernels.inline`, `STRICT_MATH`) to one `%strict-math`, as the oracle's
  wrapper is one `Math` call: a call costs the `defn` and the primitive, `StrictMath`'s bits on
  all four backends (`.kb/transcendentals.md`, "%strict-math"). The long arithmetic goes through
  `%clojure-long-cast`, the oracle's `RT.longCast` (2^63 the largest long, as Java's `(long)`
  saturates), shared with `int`/`long`. Each var links alone. Deviations: user doc
  `doc/en/clojure/reference/clojure-math.md`. Pins: clojure-spec `clojure-math-*` (all four
  backends), `ClojureLanguageNamespacesTest`
  (`clojureMathLowersEachDoubleFunctionToOneStrictMathCallAtItsRequire`,
  `everyStrictMathFunctionIsAClojureMathKernelOfItsArity`: the kernel table against
  `StrictMathFunction`, which this package may not import).
- `clojure.core.reducers`: the reducers are `reify`s of `CollReduce` (folders also of
  `CollFold`), so they need multi-arity protocol methods ("Dispatch") and the reducible arms.
  `fold` splits as the oracle's answers show (`(r/fold 2 (fn ([] []) ([a b] (conj a b))) conj
  [1 2 3 4 5])` is `[1 2 [3 [4 5]]]`; one member is always a leaf, where the oracle overflows
  its stack for an `n` below one) but on the calling thread; a map folds as one `kv-reduce` (the
  oracle's hash map past eight entries also combines its trie's parts). `(cat l r)` of two non-empty
  collections copies both into a fresh accumulator, an adjustable vector: the oracle's `Cat`
  tree counts and seqs through interfaces a deftype here cannot implement; the cost is one copy
  per combine level (deviation, user doc).
- `clojure.repl`: `doc` expands to `(#'print-doc (meta (var name)))`, printing the metadata the
  lowering recorded ("Vars and metadata"); a special form's `:forms` and text are clojure.jar's
  words, not copied; no `resolve` or `find-ns` runs at expansion time (user doc
  `doc/en/clojure/reference/clojure-repl.md`).
- `clojure.main`: `ex-triage` reads the frames the data gives, so triage of `Throwable->map`
  data is oracle-identical when the data is; a throwable here has none (`(REPL:1)`). Its rules
  are the oracle's answers to probes over every phase; the 22 `core-namespaces` an execution
  error is never blamed on were found by asking about each namespace of clojure.jar; `demunge`
  was checked on 30 spellings. Deviations: user doc `doc/en/clojure/reference/clojure-main.md`
  (`:clojure.error/path` is never made relative to the working directory, which wasm has not).
- `clojure.java.shell`: Clojure source over `ProcessBuilder` interop (interpreter and JVM;
  `uiop:run-program` instead signals on WASM, `.kb/uiop.md`). stdin comes from a temporary file,
  stderr goes to another, stdout is read by `transferTo` into a host `ByteArrayOutputStream`: no
  pipe fills while another is read, without the oracle's futures. That stream is param-tagged
  (`(^[] java.io.ByteArrayOutputStream/new)`), since `ByteArrayOutputStream.` makes the
  byte-array family's stream. `:out-enc :bytes` answers `(byte-array (.toByteArray out))`. Pin:
  `ClojureInteropTest#clojureJavaShellRunsAHostProcessLikeTheOracle`.
- `clojure.reflect` (for tools.analyzer.jvm): Clojure source over `java.lang.reflect` interop
  (interpreter and JVM). On WASM it loads and every reflection throws
  `UnsupportedOperationException` when called: each host branch is an `if` over the literal
  `rontolisp.internal.reflect/hosted?`, which folds (a `when` or `if-not` does not: a
  `JAVA:STATIC` warning in every program requiring it). Diffed against clj over a dozen classes
  (`ArrayList` with `:ancestors`: 203 members): identical on the interpreter and the JVM.
  Deviations: user doc `doc/en/clojure/reference/clojure-reflect.md`;
  `(reflect nil)` is the front end's `IllegalArgumentException` wording. Pins: clojure-spec
  `clojure-reflect-describes-a-host-class-like-the-oracle` (interpreter, JVM),
  `clojure-reflect-refuses-to-reflect-where-the-host-is-not` (both WASM legs),
  `ClojureWasmHostArmsTest` (no `java:` warning).
- `clojure.xml`: `(parse s)` reads the document itself on every backend (the kernel
  `rontolisp.internal.xml/events`, `clojure.lisp` "clojure.xml: rontolisp.internal.xml"): no
  external DTD or entity, like the oracle's `startparse-sax-safe`. Its events are the ones the
  host route's `ContentHandler` proxy records, so one `tree` builds both; attributes go into an
  `array-map` in reverse document order, so the oracle's key order prints. A malformed document
  is `%clojure-sax-parse-exception` with Xerces' message for 33 known mistakes. A host object
  source and `(parse s startparse)` take the host route, so clj-http's own SAXParserFactory
  startparse works on the interpreter and the JVM
  (`ClojureInteropTest#clojureXmlHandsAHostSourceAndAStartparseToTheHostsSaxParser`).
  Deviations: user doc `doc/en/clojure/reference/clojure-xml.md`; a wasm program using it
  compiles with the host functions' `java:` warnings.
- Pins: clojure-spec `clojure-walk-*` (all four backends; the first loads `clojure.walk`
  through a qualified name only), `clojure-template-substitutes-per-group-of-values`,
  `clojure-pprint-*` (the four `clojure-pprint-cl-format-*` for `cl-format`),
  `clojure-data-diff-compares-like-the-oracle`, `clojure-zip-moves-and-edits-like-the-oracle`,
  `clojure-datafy-and-core-protocols-like-the-oracle`,
  `clojure-stacktrace-prints-throwables-like-the-oracle`, `clojure-repl-*` and `clojure-main-*`
  (`-of-a-special-form-and-a-core-var`, `-pst-prints-no-frames` and
  `-reports-an-error-without-its-location` the deviations), `clojure-xml-*` (files under `/tmp`,
  the wasm legs' preopen), `reduce-and-the-verbs-built-on-it-reach-a-coll-reduce-row`,
  `reduce-kv-and-update-vals-reach-an-ikv-reduce-row`, `clojure-core-reducers-*` (the last the
  deviations); `ClojureLanguageNamespacesTest` (the startup load, no project file shadowing a
  startup namespace, a contrib `clojure.*` namespace on the source path, the refusals,
  `clFormatIsClojureSourceOverTheNumberAndCaseKernels`),
  `ClojureLibraryTest#aProgramStoringNoReducerRowSplicesTheReduceVerbsWithoutTheirProtocolArms`,
  `ClojureArmsTest#theReducibleFamilyIsMadeByATypedRowOfCollReduceOrIKVReduce`,
  `ClojureLoweringTest#aTypedRowOfCollReduceOrIKVReduceIsStoredThroughTheLibrary`.

### The jar's own files, measured (2026-11-25)

**clojure.jar's `.clj` of a namespace, read ahead of the front end's own file, runs as that
file on all four backends for five of the eighteen shipped namespaces; the other thirteen
are written against the JVM run time (`clojure.lang` classes, host interop) and fail, differ
or are refused.** None of the five leaves the rontolisp jar for the program's own
`org.clojure/clojure` (user, 2026-11-25; the reasons in `.kb/java-frontend-provenance.md`).

How: clojure.jar 1.12.6 (the candidates also 1.11.1, 1.12.0, 1.12.3: the same verdicts), one
namespace's files in the program's root and every other namespace the front end's own, over
the clojure-spec cases naming it and, for the candidates, a probe of every public var, each
against `clj` 1.12.6. The sources were given to the front end, not read.
Size is own -> jar over the cases, JVM class / wasm / component.

| namespace | with the jar's file | size |
|---|---|---|
| `zip` | as own | +1.5% / +1.0% / +1.0% |
| `template` | as own | +0.0% / +0.1% / +0.1% |
| `stacktrace` | as own | +18..24% / +21..29% / +20..28% |
| `uuid` | as own | +48% / +15% / +15% |
| `data` | as own but a map diff's key order, the oracle's where own has the documented deviation; wasm warns `JAVA:CALL` off the path | +3% / +8% / +7% |
| `walk` | a rebuilt vector, map or set loses its metadata (`into` starts without it here, "Deviations") | +0.1% (probe), +31% (corpus) |
| `datafy` | a class prints as its kind keyword | +130% / +81% / +78% |
| `core.protocols` | `No implementation of method :kv-reduce`: the reducible arms are rows of the front end's own file | +32% / +8% / +7% |
| `core.reducers` | `fold` and `cat` fail (`Method fold ... not supported`, a `Cat` printed as a type) | +41% / +43% / +42% |
| `instant` | the host legs stop at `format`'s `%t` | +42% / +41% / +40% |
| `math` | wasm: every case fails (`Math` interop); host legs: `floorDiv`, `round` find no overload | +65% / +38% / +34% |
| `repl` | 2 of 4 cases as own | +231% / +155% / +152% |
| `xml` | `No such class: clojure.lang.XMLHandler` | |
| `java.shell` | fails on the host legs too | |
| `pprint` | refused: `a proxy method takes a parameter vector` | |
| `reflect` | refused: `unknown name: Type` | |
| `java.io` | refused: `extend takes a type name` | |
| `main` | refused: `unknown name: init-dispatch` | |

- The lowering namespaces, indicative only (the switch is crude there): `set` as own (+32% /
  +21% / +20%); `string`, `edn` and `test` fail (`Pattern.split`, `clojure.lang.EdnReader`,
  the report multimethod's table).
- What moving the five would cost, for the choice: a compiled program naming one carries
  clojure.jar's EPL-1.0 code where it carries the front end's Apache-2.0 file today; the
  command line fetches the 4.2 MB jar of the selected version the first time (the `Deferred`
  roots of `ClojureBuiltinLibs` are the mechanism); the playground, an embedder and the
  tests fetch nothing ("deps.edn", "Who fetches"). `uuid` is a startup namespace, read for
  every `#uuid`, so in practice four: 14.9 KB of the 139 KB shipped under `clojure/`.

## Byte arrays

- **The value**: `(:C%BYTES octets)` over a packed `(unsigned-byte 8)` vector, what the byte
  streams read into and a transport's body is, so a body or a file's contents becomes one
  uncopied; stored as octets, read signed. No collection (`coll?`, `vector?`, `sequential?`
  false; `=` identity) but seqable; `class` `:[B`; a protocol dispatches it to `Object`.
- **The verbs** (`ClojureBytesLowering`, `clojure.lisp` "Byte arrays"): a charset is UTF-8,
  ISO-8859-1 or US-ASCII ("Charset values"); any other is the oracle's
  `UnsupportedEncodingException` on every backend, the host's other charsets never consulted.
  UTF-8 decodes as "The JDK decoder" ("clojure.java.io") does. `aset` takes any integer
  -128..127: no Byte kind here tells `(byte 5)` from `5`.
- **The family** `ClojureArms.Family.BYTES` goes ahead of `HOST`, since a String
  construction's alias is `java:new`, which `HOST` reads as a producer. A program making no
  byte array splices the library without the arms. Producers include the HTTP client's request
  kernel (`:as` may name `:bytes` at run time, so a minimal `rontolisp.http-client` GET carries
  the arms) and, where the host is, every `java:` operator (`Family.hostProducers`): any host
  call may answer a `byte[]`, while on wasm a `java:` call makes nothing.
- **Byte streams over and into one**: neither array stream closes (the JDK's). A file `read`
  into an array is bounded by `file-length` minus `file-position` (a second read past the end
  traps on the component). `io/output-stream` of a BAOS is the JDK's 8192-octet buffer, whose
  `close` flushes and never closes. The oracle's `(.available bais)` after one wrapper read is 0
  (its buffer reads ahead; here the array stream keeps its position), and a mark's read limit is
  not kept here. The array paths loop `read-byte`/`write-byte`: naming `read-sequence`,
  `write-sequence` or a two-argument `file-position` in a function a Ring program splices brings
  in the prelude's Gray dispatchers (it changed `ring-hello.clj`'s output).
- **Consumers elsewhere**: clojure.java.io; the Ring adapter's body; "HTTP client"; ring-codec's
  base64 ("Ring util namespaces"); `clojure.java.shell` ("clojure.jar namespaces",
  `.toByteArray` as it comes back); a `java:` member's argument ("Java interop"); a WIT
  `list<u8>` ("Host boundary").
- **Deviations**: user docs `byte-array.md`, `deviations.md`. Java holds the byte array's own
  octets on both backends, not a copy written back on return, so an array a Java object keeps
  is the byte array, and a fn reads what Java stores while it runs
  (`ClojureInteropTest#anArrayJavaKeepsIsTheByteArrayItself`).
- **Pins**: clojure-spec `byte-array-makes-a-mutable-array-of-signed-bytes`,
  `a-byte-array-is-no-collection-yet-seqs-its-bytes`,
  `strings-and-byte-arrays-convert-in-a-charset`,
  `byte-arrays-flow-through-clojure-java-io-streams`,
  `ring-util-codec-base64-encodes-and-decodes-a-byte-array`,
  `a-byte-array-prints-as-the-host-object-its-hash-left-out` (four backends; the last the
  deviation); clojure-http-spec `the-body-is-a-byte-array-under-as-bytes`,
  `the-stream-body-reads-into-byte-arrays`, `a-byte-array-body-and-part-are-sent-as-they-are`;
  `ClojureBytesTest` (`.getBytes` and `String.` against the JDK over seeded random inputs),
  `ClojureArmsTest#theByteArrayFamilyFoldsItsArmsAliasesAndSetterToTheFormsBeforeByteArrays`,
  `ClojureLibraryTest#aProgramMakingNoByteArraySplicesTheLibraryWithoutItsByteArrayArms` (a
  `java:call` keeps the arms on a host target, not on wasm),
  `ClojureInteropTest#aByteArrayCrossesTheJavaBoundaryAsItsBytes`,
  `#aByteArrayAJavaMemberAnswersIsAByteArray`, `#aByteArrayReachesJavaAsTheByteArrayJavaStoresInto`,
  `#aByteArrayJavaHandsAProxyIsOneItStoresInto`, `#aByteArrayJavaHandsAFnIsOneItStoresInto`,
  `#aProxyOrTypeIsHandedAByteArrayAndAnswersJavaTheOraclesObject`,
  `#clojureJavaShellRunsAHostProcessLikeTheOracle`, `ClojureRingAdapterTest` (`/bytes`).

## Portable parsers

- **The verbs** (`ClojureCoreLowering`, `clojure.lisp` "Parsers"): `parse-long`, `parse-double`,
  `parse-boolean` (user doc `doc/en/clojure/reference/parse-long.md` and its siblings) run on
  every backend: wasm has no host `Long/parseLong`. `parse-long` reads every Unicode
  decimal-digit block, like `Character.digit`; `parse-double` reads ASCII only, like the oracle,
  and coerces the exact ratio the grammar spells with `float`, correctly rounded on all four
  backends.
- Infinity is `(/ 1.0 0.0)` and NaN `(/ 0.0 0.0)`: building them from
  `most-positive-double-float` would cost a `parse-double` program far more wasm. The scanners
  name only core forms, strings and the refusal carriers (`find`, `subseq` or `=` would each
  link hundreds of KB; `eql` is no cheaper), so a program naming none compiles byte-identically.
- **Pins**: clojure-spec `parse-long-parses-decimal-digits-like-long-value-of`,
  `parse-double-parses-like-double-value-of`, `parse-boolean-parses-true-and-false` (four
  backends, oracle-identical); `ClojureLoweringTest#parsersAndTypedArraysLowerToTheirWorkers`.

## Portable number statics

- **The verbs** (`ClojurePortableStaticsLowering`, `clojure.lisp` "Portable number statics"):
  where the host is not (wasm) the pure number statics a library's read path calls (data.json,
  malli, data.generators, test.check, persistent-sorted-set, datascript, tools.reader, aero)
  lower to portable workers answering the oracle's values and refusals; where the host is, and
  for any other arity or a computed radix, the host's call stays.
- `clojure.lang.Numbers/compare` is `compare` on every backend ("Collection interfaces"). Where
  no host is, `.compareTo` of a number or a string is `compare`, and `.getClass` of a number,
  string or character what `class` answers (`ClojureValueMethodLowering.valueArm`; clojure-spec
  `string-getChars-subSequence-and-char-array-strings-read-on-every-backend`). `.isInfinite`/
  `.isNaN` of a double answer a Clojure boolean; `.longValue`/`.intValue` of an integer are its
  low 64/32 bits on every backend.
- An untagged `(BigInteger. s)`/`(BigInteger. s radix)` (`%clojure-big-integer-new`) is written
  from `BigInteger(String,int)`'s documentation (one optional sign, then digits of the radix as
  `Character.digit` maps them); the refusals' words and order are a JDK's answers, compared with
  a JDK over 4M random strings in every radix: radix range, empty string, a sign anywhere but in
  front, a sign alone, then a non-digit, named by the piece of the string it stands in -- the
  digits after the leading zeros cut into groups of the most digits that always fit an int (9
  in radix 10, 7 in 16; computed by `%clojure-big-integer-group-width`), the first group short
  (`(BigInteger. "12345678901x")` is `For input string: "45678901x"`). With the byte-array and
  signum constructions, data.generators' `bigint`/`bigdec` print the oracle's lines on wasm.
- The decimal-digit table (`%clojure-decimal-digit-starts`, also `parse-long`'s) holds JDK 25's
  `Character.isDigit` blocks, its eight supplementary ones included (checked over every code
  point on 25.0.4).
- Deviations: an `N` literal in the long range is a plain integer here, so `(Math/min 5 10N)`
  and `(Math/abs 5N)` answer where the oracle refuses a `BigInt`. The host backends keep the
  host's own coercions, looser and stricter than the oracle's (`(Double/valueOf 5)` is `5.0`; a
  double under `toHexString` is `No matching method`); rejected: lowering every backend, a host
  behavior change for no oracle gain. A param-tagged call keeps the host's call on every
  backend, like every tagged form ("Java interop").
- **Pins**: clojure-spec `long-value-of-reads-like-the-host-static`,
  `long-parse-long-reads-like-the-host-static`,
  `double-value-of-and-parse-double-read-like-the-host-statics`,
  `parse-int-reads-like-the-host-static`,
  `character-statics-and-big-integer-strings-answer-like-the-host`,
  `integer-to-hex-string-writes-like-the-host-static`,
  `math-min-of-two-answers-like-the-host-static`,
  `math-floor-ceil-and-log-read-like-the-host-statics`, `math-abs-answers-like-the-host-static`,
  `long-bit-count-and-leading-zeros-answer-like-the-host-statics`,
  `isInfinite-and-isNaN-of-a-double-answer-like-the-host-methods` (four backends, the
  host∩oracle lines; the rest on both wasm backends in
  `ClojureWasmInteropRefusalTest#portableNumberStaticsRefuseLikeTheHostStaticsOnBothBackends`
  and `#portableRandomDrawsAndRefusesLikeTheHostOnBothBackends`),
  `ClojureLoweringTest#longParseLongOfOneStringIsThePortableReaderWhereTheHostIsNot`,
  `#mathFloorCeilAndLogLowerToTheirWorkersWhereTheHostIsNot`,
  `#mathAbsBitCountAndLeadingZerosLowerToTheirWorkersWhereTheHostIsNot`.

## Portable ThreadLocal

- **The cell** (`ClojurePortableThreadLocalLowering`, `clojure.lisp` "Portable ThreadLocal"):
  where the host is not (wasm) a `(proxy [ThreadLocal] [] (initialValue [] ...))` is a
  single-threaded cell, not a `java:subclass`, which wasm refuses at call time: it would kill the
  module's start function while test.check's seedless `next-rng` loads. One thread runs there,
  so no thread key is kept. Constructor arguments keep the `java:subclass`; any method but a
  parameterless `initialValue` is refused by name.
- **Pins**: clojure-spec `proxy-of-thread-local-is-a-portable-cell` (all four backends),
  `ClojureLoweringTest#aThreadLocalProxyLowersToThePortableCellWhereTheHostIsNot` (the cell,
  its gates, fall-through and refusal; `java:subclass` and `java:call` where the host is).

## Portable Random

- **The generator** (`ClojureInteropLowering`, `clojure.lisp` "Portable java.util.Random"):
  where the host is not (wasm) an untagged `java.util.Random` construction is the JDK's 48-bit
  LCG as a list mutated in place (no seed: host entropy), its draws behind a
  `%clojure-random-p` gate and bit-identical with the oracle's, so data.generators' `*rnd*` root
  loads on wasm and prints the oracle's lines. Where the host is it stays `java:new`.
- **Pins**: clojure-spec `java-util-random-draws-answer-the-oracle-bits` (all four backends),
  `ClojureWasmInteropRefusalTest#portableRandomDrawsAndRefusesLikeTheHostOnBothBackends` (draws
  and refusal texts), `ClojureInteropTest#aHostRandomDrawAnswersTheOraclesDraws` (the host legs
  keep the host's own),
  `ClojureLoweringTest#aRandomConstructionLowersToThePortableGeneratorWhereTheHostIsNot` and
  `#aRandomDrawLowersToThePortableWorkerWhereTheHostIsNot`.

## Portable LinkedList and ArrayList

- **The list** (`ClojurePortableLinkedListLowering`, `lib/rontolisp/internal/linked_list.clj`):
  where the host is not (wasm) an untagged zero-argument `(java.util.LinkedList.)` is a deftype
  in the INTERNAL shipped namespace `rontolisp.internal.linked-list`
  (`ClojureBuiltinNamespaces.INTERNAL`, like the core macros: no program requires it, no project
  file shadows it), preloaded where the construction first lowers (tools.reader's pending-forms
  queue). Where the host is, and outside top-level datums (no namespace loads there), it keeps
  `java:new`, as does a construction from a collection, whose `ClassCastException` could not
  spell the oracle's class names.
- **The simple-name leniency** (`ClojureLowering.typeKeyOf`'s last resort) skips an internal
  namespace's types: else tools.reader's imported `LinkedList` resolves to the internal deftype
  once loaded. An imported name never reaches the leniency at all ("Records" above).
- **The ArrayList** (schema's collection checker): the same namespace's `ArrayList`, the list
  body plus `java.util.RandomAccess`, so `nth` reads it and refuses a `LinkedList`, as the
  oracle does. The two bodies repeat the members rather than share a macro: an internal
  namespace lowers wherever a construction does, a lowering with no macro-time evaluator
  (`ClojureLoweringTest`'s) included.
- Deviations (wasm only, user doc `doc/en/clojure/deviations.md`): `class`, `instance?` and
  `print` read the deftype; `.remove` of an integer is always remove-at-index.
- **Pins**: clojure-spec `a-linked-list-construction-is-a-mutable-list-where-the-host-is-not`
  (all four; the imported spelling pins the leniency skip),
  `a-weak-hash-map-atomic-reference-and-array-list-are-portable-where-the-host-is-not` (the
  ArrayList lines).
- Native image: `resource-config.json`'s `lib/rontolisp` pattern must take subdirectories, or
  `lib/rontolisp/internal/` is absent from the binary; `NativeImageResourceConfigTest` lists the
  directory.

## Portable IdentityHashMap and WeakHashMap

- **The map** (`ClojurePortableIdentityMapLowering`, `clojure.lisp` "Portable
  java.util.IdentityHashMap"): an untagged zero-argument `(java.util.IdentityHashMap.)` is an
  `eq` table on EVERY backend, its methods behind a `%clojure-identity-map-p` gate, the outermost
  arm of the instance call (a `^IdentityHashMap` hint is treated as unknown);
  `ClojureArms.Family.IDENTITY_MAP` folds the gate in a program making no map.
- **Why every backend, not wasm only**: on the interpreter and the JVM the host map missed EVERY
  lookup of a Clojure key (measured), since each crossing of the `java:` boundary makes a new
  Java object of a Clojure value, a string too (editscript stayed right only because its maps are
  caches: quadratic where the library is linear). `HashMap` and `WeakHashMap` key by
  `equals`/`hashCode`, which the crossing preserves.
- `(String. s)` of a string is `(copy-seq s)` on every backend, so two constructions are never
  `identical?`.
- Deviations: a number or a character keys by value (`eq` is `eql`, `.kb/eq-numbers.md`);
  `class`, `instance?` and `print` read the list.
- **The WeakHashMap** (schema's class registry): where the host is not, the same list over an
  `equal` table, keyed by `=`, a second producer of `IDENTITY_MAP`; nothing drops a key. A
  one-argument `java.util.Collections` synchronized wrapper is its argument there
  (`ClojurePortableIdentityMapLowering.synchronizedWrapper`): no other thread exists, and an
  immutable map's `.put` refuses as the oracle's wrapper of it does.
- **Pins**: clojure-spec `an-identity-hash-map-keys-by-identity-and-nano-time-reads-a-clock`
  (all four), `a-weak-hash-map-atomic-reference-and-array-list-are-portable-where-the-host-is-not`
  (the map lines).

## Portable StringJoiner

- **The joiner** (`ClojurePortableStringJoinerLowering`,
  `lib/rontolisp/internal/string_joiner.clj`): where the host is not (wasm) an untagged
  `StringJoiner` construction is the deftype of the INTERNAL namespace
  `rontolisp.internal.string-joiner`, like "Portable LinkedList" (editscript's character-level
  string `patch` builds one); a receiver known to be a `StringJoiner` is treated as unknown
  there.
- **Load order**: a `definterface` row exists only once the namespace loads, and editscript's
  `(fn [^StringJoiner joiner edit] ... (.add joiner s))` lowers BEFORE the construction it
  reduces from; with no row the hinted call compiled the oracle's `ClassCastException`. So a
  receiver hinted `StringJoiner` loads the namespace too (`preloadForHint`). An unhinted `.add`
  lowered before any construction or hint still has no row (not measured in a library).
- Deviations (wasm only): `class`, `instance?` and `print` read the deftype; no `merge`.
- **Pins**: clojure-spec `a-string-joiner-construction-is-a-joiner-where-the-host-is-not` (all
  four; its first line is the hinted-before-construction order).

## Portable AtomicReference

- **The cell** (`ClojurePortableAtomicReferenceLowering`,
  `lib/rontolisp/internal/atomic_reference.clj`): where the host is not an untagged
  `AtomicReference` construction is the deftype of the INTERNAL namespace
  `rontolisp.internal.atomic-reference`, like "Portable StringJoiner" (`compareAndSet` by
  `identical?`, the JDK's `==`). schema keeps its fn-validation switch in one, read at macro
  time too; a `^AtomicReference` hint loads the namespace (`preloadForHint`).
- Where the host is the construction keeps the host's object, the cell a program's threads
  share, though each value crossing into it is a new Java object (the IdentityHashMap
  measurement): `.compareAndSet` of a keyword or a collection never finds the one stored.
- Deviations (wasm only): `class`, `instance?` and `print` read the deftype.
- **Pins**: clojure-spec
  `a-weak-hash-map-atomic-reference-and-array-list-are-portable-where-the-host-is-not` (all
  four; values whose identity survives the host's crossing).

## Portable GregorianCalendar

- **The chain** (`ClojurePortableCalendarLowering`, `clojure.lisp` "Portable GregorianCalendar";
  tools.reader's `construct-calendar`): where the host is not (wasm) an untagged
  `(GregorianCalendar. y m d ...)` is the existing Calendar kind in UTC, the `Calendar`
  constants their integers, and `TimeZone/getTimeZone` of `GMT`, `UTC` or a JDK custom `GMT`
  offset id a zone; any other id is refused by name (the JDK answers `GMT` for an unknown id,
  but no tzdb exists here to tell unknown from named).
- **The methods, every backend** (`ClojureTimeValueLowering.methodArm`, ahead of the by-name
  refusal, so a `clojure.instant` Calendar takes them on the host legs too). A receiver known to
  be a `Calendar` is treated as unknown, since tools.reader's `^GregorianCalendar` return hint
  would otherwise compile `java:call`.
- **Laziness, the JDK's** (`clojure.lisp`): MS is kept eagerly at what `getMillisOf` answers, so
  the printer, `compare` and hash read it; `%clojure-inst-equal` compares a Calendar's three
  slots, not the pending-fields tail. The arithmetic is the JDK's `calculateFixedDate` without
  the `calsys` overlap case, which no field values reach.
- A program making no Calendar sheds the arms (`%clojure-calendar-new`/`-now` join the instant
  family). Deviations: user doc `doc/en/clojure/reference/instants.md`.
- **Pins**: clojure-spec `a-gregorian-calendar-is-the-oracles-lenient-lazy-calendar` (all four;
  no line reads the default zone, the machine's on the host legs),
  `ClojureWasmInteropRefusalTest#aDateMadeHereRefusesOnlyAHostOnlyMemberOnBothBackends` (UTC
  default and the two refusals, both wasm backends).

## Boxed-integer limits, class designators, number classes and date builder chains

User-facing: `deviations.md`. Where the host is not (wasm), a `java:field` or `java:static` is
refused, so load-time `def`s need these:

- **The limits** (`ClojureNumericLimitsLowering`): the `Long`/`Integer`/`Short`/`Byte`/`Double`
  `MAX_VALUE`/`MIN_VALUE` (and `Double`'s infinities and `NaN`) are themselves (malli's
  `+max-size+`, test.check's double bounds); where the host is, the field read stays.
- **The designator** (`ClojureCoreLowering.classDesignator`): a bare loadable class name is its
  `(:C%CLASS name)` designator (malli's class-keyed `class-schemas`); where the host is, the
  literal keeps its `forName` object. A designator is a class value with no host object, on every
  backend: printed and hashed by its name, `identical?` to every other of its name, `class?`,
  `java.lang.Class` from `class`. Its arms (the `CLASS_DESIGNATOR` family) fold in a program
  making no class value ("Class values"). Traps: a producer spelled inside an arm makes
  the family, so no arm spells the tag (`class` of a designator answers
  `(%clojure-designator-class)`, the value-method arm tests through the family's function); the
  printer's arm reads `(cadr x)`, since `(car (cdr x))` breaks the `cond`'s aligned layout and the
  formatter re-indents the whole printer.
- **The chain** (`ClojureDateBuilderLowering`): a `DateTimeFormatterBuilder` chain, `ofPattern`,
  `ZoneId/of` and the `ChronoField` constants a load reads answer markers (malli's formatter
  `def`s); a use keeps the oracle-words refusal.
- **Number classes** (`%clojure-number-class`): a number's dispatch tag is the keyword of the
  oracle's class (`:java.lang.Long` ...; "Decimals"), chained like the oracle's `bases`
  (`ClojureClassBases.NUMBER_SUPERS`) up to `java.lang.Number`, so `Number`/`Comparable` methods
  and a protocol extended to `Number` reach it; `class` answers that class's class value, and so
  does `Long`/`Double` in value position ("Class values"). `Integer`/`Short`/`Byte`/`Float` are
  no class a value has (casts answer plain integers), so a method or extension under one is
  reached by nothing. Pin: clojure-spec
  `class-dispatch-maps-host-spellings-to-kind-keywords` (all four, oracle-identical).
- **Pins**: clojure-spec `boxed-integer-limits-answer-themselves-where-the-host-is-not`,
  `class-literals-and-date-builder-chains-evaluate-where-the-host-is-not` (all four);
  `ClojureLoweringTest` `boxedIntegerLimitsAnswerThemselvesWhereTheHostIsNot` /
  `doubleLimitsAnswerThemselvesWhereTheHostIsNot` /
  `aClassLiteralIsItsDesignatorWhereTheHostIsNot` /
  `dateBuilderChainsThreadTheirMarkerWhereTheHostIsNot`.

## Class values

**`class`, `type`, a class literal, `Class/forName` and `ns-map`'s imports answer ONE class value
per class and program: the host's `java.lang.Class` where the program reaches the host (the
interpreter; a JVM program naming a `java:` operator), the `(:C%CLASS name)` designator
elsewhere (wasm; a JVM program naming none, where no Java API can tell them apart).** The
dispatch tags stay keywords: `class` is `%clojure-class-of` over the tag `classTagForm` answers
(inside a `defmulti` dispatch function the tag itself), and a class value reads back as its tag
where a table is keyed (`%clojure-dispatch-kind`, `%clojure-kind-of-class`,
`%clojure-class-designator-key`). Why one value per program: a JVM program's reflection answers
real `Class` objects, so a class value of another shape would be unequal to them; a
`java:`-free program gets the designator from the `HOST_ARMS_WITHOUT_JAVA` stand-in of
`%clojure-host-class-named`, so `class` costs it no bridge (a `java:static` there was +122 KB).

- **`clojure.lang` classes are stub classes** (`runtime/RontoStubClasses`,
  `rontolisp::%host-stub-class`; the JVM output carries it only where the primitive is
  referenced): a member-less class file (JVMS 4.1) with the oracle's name, modifiers,
  superclass and interfaces, defined in ONE loader per process that is no parent of the
  program's class path, so a dependency compiled against `clojure.lang` keeps failing to link
  ("Classes compiled against `clojure.lang`") and the ClojureScript-port trigger is unchanged.
  The rows (`%clojure-lang-classes`, read off clj 1.12.6 by reflection) are the classes a value
  here has, the classes whose statics `ClojureLangClasses` answers, and their supertypes; every
  row is stubbed at the first stub (`%clojure-lang-stub`) so the member signatures' types
  resolve. A stub declares, as `native` methods and fields, exactly the statics this front end
  answers (`PersistentArrayMap/createAsIfByAssoc` ...), so reflection -- tools.analyzer.jvm's
  `validate` above all -- sees what a call can reach. `PersistentQueue` stays out: it is a
  deftype of the shipped `clojure.lang` namespace, whose class value is its tag.
- Traps: a native image defines no class (`org.graalvm.nativeimage.imagecode`) and registers no
  reflection a class value's readers ask, so there every class value is a designator
  (`%clojure-host-classes-p`, probed by defining the `IFn` stub): a host class in the binary's
  REPL crashed on `Class.getInterfaces` (`MissingReflectionRegistrationError`, an `Error` no
  `handler-case` takes) before the probe. The rows are literal strings
  (an interface list is one space-separated string), since on the JVM a string `format` builds
  is no `java.lang.String` and the runtime class reads quoted strings only.
- **Readers**: a `Class/forName` of a `clojure.lang` row name answers the stub, one or three
  arguments (`langClassForName`); `.isInstance` of a host class is `instance?`, since the host's
  takes a fixnum as its narrowest box; `instance?` of a host class value over a Lisp value is
  `isAssignableFrom` of what `class` answers for it (`%clojure-host-class-assignable-p`), a
  condition by its chain. A class value in a walk that is not the host's reads as its kind or
  class keyword (`%clojure-class-designator-key`; on the interpreter a `java:`-free program still
  holds host classes); a `class` call sets `classObjects`, so the readers answer class values.
  `methods` keys a row a class spelling stored by its class value: the defmethod notes it
  (`%clojure-note-class-row`), a statement of `METHOD_CLASS_ROW`, which only a `methods` call
  keeps.
- **A fn's class** is the class the oracle compiles it to (`%clojure-fn-class` over
  `%clojure-fn-class-name`), what `.getClass` answers too (it lowers to `class`): where the host
  makes class values a stub of that name extending the `AFunction` stub (flags 17, the
  oracle's), else its designator. `%clojure-fn-class-names` records every name answered, so
  `%clojure-kind-of-class` reads one back as `:function`. `class` (`CLASS_OF`) is a producer of
  `Family.FN_CLASS`, so a program calling `class` keeps the per-`defn` registrations. Trap: a
  core fn's name is built (`concatenate`), and on the JVM a built string is a character vector,
  so `RontoStubClasses.text` reads one.
- **`supers`/`bases`** (`%clojure-supers`/`%clojure-bases`): a host class by the host's
  `getSuperclass`/`getInterfaces` (host arm `%clojure-host-class-bases`); a designator by
  `%clojure-class-bases-text`, one line per class: the 106 stub rows' and 147 JDK classes' (the
  closure of the stub rows' supertypes and of the classes `ClojureClassBases`/`ClojureThrowables`/
  `ClojureValueClasses` name, read off the oracle by reflection), pinned by
  `ClojureClassBasesTest#theDesignatorTableIsReflectionsAndTheStubRowsClosedUnderTheirBases`.
  One string, not a quoted list: as the two row lists `(println (bases Long))` was 63,275 B of
  wasm P1, as the string 56,374 (the printer of class values is 20 KB of it). A fn class
  `AFunction`, a record's or deftype's what its definition noted, a program-defined interface
  none; any other designator (a JDK class past the rows on wasm, a `reify`'s) is refused by name.
  The note (`%clojure-note-type-bases`: `Object`, the body's interfaces in body order -- the
  oracle's are hash-set ordered -- then `IRecord`'s eight or `IType`) is a statement of
  `Family.TYPE_BASES`, kept only where `supers`/`bases` is read.
- Size, measured 2026-11-26 (wasm P1 / `--optimize=size` / component / JVM class):
  `examples/clojure/demo.clj`, `collections.clj`, `state.clj`, `text.clj`, a record program and a
  `class` multimethod byte-identical; `(println (class "a") (class 1) (class [1]))` +21,963 /
  +18,436 / +21,990 / +7,994 (a fn's class name: the per-`defn` registrations and
  `%clojure-fn-class-name`; munging through `munge`'s printer and `apply` of `concatenate` was
  +40 KB more, hence `%clojure-munge-string`); `java-interop.clj` +5,308 / +4,331 / +5,226 /
  +7,572; `(.getName (.getClass inc))` 103,558 -> 61,143 P1.
- Deviations (user doc `reference/class.md`, `reference/bases.md`): one class per kind's value --
  every anonymous fn `AFunction$fn`, a variadic fn under `AFunction` (the oracle's `RestFn`),
  every strict seq `PersistentList`, a two-member vector `PersistentVector`; records and
  deftypes keep their designators on every backend, so Java sees no class of theirs (a
  handle); a stub's `getMethods` lists the answered statics alone; a designator of an
  interface `str`s as `class X` (the oracle's `interface X`); a `definterface` name evaluates
  to its name string (`.todo/m87`).
- Measured 2026-11-26 (oracle `clj` 1.12.6): tools.analyzer.jvm 1.2.3 analyzes all 18 forms of
  "core.async and tools.analyzer.jvm"'s list to the oracle's op and tag (interpreter, run-time
  `analyze` over the copy with `RT`/`Compiler/LOADER` patched); core.match 1.1.1 and schema
  1.4.2 probes print the oracle's lines on all four backends.
- Size, measured 2026-11-26 (wasm P1 / `--optimize=size` / component / JVM class):
  `examples/clojure/demo.clj` byte-identical; `(println (class "a") (class 1) (class [1]))`
  +1539 / +1337 / +1565 / +2936; a keyword multimethod -508 P1 / -777 JVM and a `class`
  multimethod -60 / -643 (the dispatcher's class read folds where no class value exists); a
  `java:` program calling `class` +34 KB on the JVM (the rows 23 KB, the stub classes 11 KB).
- Pins: clojure-spec `class-dispatch-maps-host-spellings-to-kind-keywords`,
  `type-answers-a-host-class-designator-where-the-oracle-answers-a-class`,
  `instance-of-a-class-value-tests-what-class-answers`,
  `instance-of-an-interface-or-a-host-class-tests-the-classes-of-each-kind`,
  `methods-answers-the-method-table-as-a-map`,
  `ancestors-of-a-kind-hold-the-classes-its-values-implement-and-class-names-read-as-keywords`
  (all four); `ClojureLoweringTest#typeAndClassAnswerClassValuesAKindDispatchesBy`,
  `ClojureInteropTest#classOfAnExceptionIsItsClassWithOrWithoutInterop`,
  `ClojureSessionTest#aSessionReadsAClassKeywordsSupersInALaterBuffer`,
  `JvmRuntimeClassFilesTest` (the stub classes travel); `supers`/`bases` and a fn's class:
  clojure-spec `parents-and-ancestors-of-a-class-add-its-java-supers` (all four,
  oracle-identical), `ClojureClassBasesTest`.

## Record and deftype class values

- **The decision**: a record's or deftype's dispatch tag is the `(:C%CLASS "ns.Name")` designator
  (`ClojureProtocolLowering.typeTagForm`), and that tag is its class value: what `class`, `type`,
  the name in value position (after a local and a var of the name), `resolve`, `Class/forName`
  and the definition answer. It prints `user.R` like the oracle's (so does `PersistentQueue`'s
  class). Not the simple name's keyword `:R`: `=` to a user keyword, one tag for two namespaces'
  `R`s.
- **Why the tag itself, not a class value beside a keyword tag**: every row a type has (protocol,
  inline, interface, multimethod, hierarchy, `print-method`, registry) is keyed by the tag through
  `equal` tables, so a computed class reaches each row as the name does, with no mapping per
  consumer (`[(class a) (class b)]` would need one per element). `%clojure-dispatch-value` and
  `%clojure-extends-key` keep a registered tag as itself, not a kind keyword. A record's class
  stays the designator where the core kinds' classes are host classes ("Class values"); a
  generated class per record would replace it at `typeTagForm`.
- **Across the `java:` boundary** (interpreter, JVM): one `java:handle` per class name
  (`%clojure-designator-handles`), class `java.lang.Class`, so a Java map -- schema's
  `WeakHashMap` registry too -- keys a record's class like the oracle's one `Class` object; Java
  hands it back as the designator.
- **`R/create`** (schema's `s/record` expansion) is `map->R`, like `R/new` is `->R`
  (`ClojureInteropLowering.qualifiedMember`; a deftype has none).
- **Kept lenient**: a local or a var of the name wins (the oracle refuses `(def R ...)` beside
  `(defrecord R ...)`); a reference above the definition reads the class (the oracle's `Unable to
  resolve symbol`). `supers`/`bases` answer from the bases its definition noted ("Class values").
- **Libraries**: specter's `dynamic-param?` and schema's `record*` `(class? klass)` run verbatim
  on this. Trap:
  probe the oracle on the verbatim jars; with rewrite-clj's `make-printable!` or schema's
  `register-schema-print-as-explain` patched out it prints records, not `(maybe Int)`.
- **Pins**: clojure-spec `a-record-or-deftype-name-is-its-class-value` (all four,
  oracle-identical), `type-answers-a-host-class-designator-where-the-oracle-answers-a-class`
  (printing the class names);
  `ClojureLoweringTest#protocolsRecordsAndTypesLowerToTables` (the name's designator),
  `ClojureWasmInteropRefusalTest#classValueAnswersTheDesignator*`,
  `ClojureInteropTest#aSetAKeywordAndARecordCrossTheJavaBoundaryAsTheOraclesDo` (a record's class
  as a `WeakHashMap` key).

## Typed arrays

- **The decision**: the typed constructors answer plain vectors, `make-array`'s stand-in, not a
  family like the byte arrays': a wrapper per type needs arms in the printer, `str`, `class`, `=`,
  the seq view, `count`/`nth`/`get`/`contains?`, the `java:` crossing and `instance?`, each a
  shape every program not naming it must strip. The stand-in adds no arm, so a program naming
  none compiles byte-identically, by construction. Deviations: `deviations.md`, reference pages.
- **The verbs** (`ClojureCoreLowering`, `clojure.lisp` "Typed arrays") coerce like the oracle's,
  failing in its classes with the front end's own messages where the JDK's vary. `into-array`'s
  class resolves at lowering time to a `(:C%CLASS name)` designator
  (`ClojureCoreLowering.intoArrayType`), so no backend needs the host; its primitive conversions
  are the oracle's own inconsistent matrix.
- **The Java crossing**: a typed array is stamped with the Java component it stands for
  (`%array-java-component`; `LispArray.javaComponent` on the interpreter, header slot 4 compiled,
  none on wasm), so a `java:` member takes `int-array` as `int[]`, never the primitive array its
  element costs would choose, and a Java array comes back stamped. The compiled stamp is a
  quote-framed Lisp string, which separates it from a remembered element type's raw name.
  Write-back: `.kb/java-interop.md`, "Java arrays and Clojure typed arrays". Pin: clojure-spec
  `a-java-array-answers-a-typed-array-and-crosses-back-as-its-component` (host-only).
- **Pins**: clojure-spec `object-array-to-array-and-into-array-answer-vectors`,
  `typed-array-constructors-coerce-like-the-oracles`,
  `typed-arrays-are-vectors-to-aget-aset-alength-aclone-and-the-verbs` (four backends);
  `ClojureLoweringTest#parsersAndTypedArraysLowerToTheirWorkers`,
  `ClojureLibraryTest#aProgramNamingNoParserOrTypedArrayPrunesAwayTheirWorkers`.

## Remaining array surface

- **The verbs** (`to-array-2d`, `aset-int` ..., `ints` ...) are worker calls
  (`ClojureCoreLowering.callSeqHead`); behavior and deviations: `deviations.md`, the reference
  pages.
- **The order** (the oracle's): index, then value, then the array checks. A nil array and an index
  past either end signal message-less through the program's exception runtime
  (`ClojureStateLowering.exInfoRuntime`, spliced for a program naming a checked store). A byte
  array is refused past its end first (bounds before component, like the oracle). The index
  refuses a character (the oracle's reflection takes no `Character` for an `int`). A literal
  out-of-range double value coerces as the object cast does, where the oracle's inline cast
  refuses it.
- **Pins**: clojure-spec `to-array-2d-answers-a-vector-of-vectors`,
  `aset-star-stores-cast-the-value-and-answer-it`, `plural-array-casts-answer-the-array-itself`
  (four backends), `new-array-verbs-keep-the-vector-stand-in-deviations`;
  `ClojureLoweringTest#parsersAndTypedArraysLowerToTheirWorkers` (worker shapes, nested `aget`,
  arities), `ClojureLibraryTest#aProgramNamingNoParserOrTypedArrayPrunesAwayTheirWorkers`,
  `ClojureInteropTest#typedArraysStoreCheckedValuesThroughAsetStar` (interpreter and JVM).
- **Array statics** (`ClojurePortableStaticsLowering.arrayCall`, `clojure.lisp` "Array statics";
  persistent-sorted-set's `arrays` macros): `System/arraycopy` of five is
  `%clojure-system-arraycopy` on every backend: its `Object` parameters take a vector as a `List`
  view, so the host's call refused every object array. It refuses in the JDK's order and words
  (`ArrayStoreException` the carrier `ARRAY_STORE`) but copies two vectors of other components,
  where the JDK checks. `java.util.Arrays`' `copyOf`, `copyOfRange`, `sort`/`parallelSort`
  (stable like the JDK's object sort) and `fill` are workers where no host is; there no array
  records its component, so a copy of an `int-array` pads nil where the host pads 0 (hence the
  crossing's spec case is host-only). `clojure.lang.RT/EMPTY_ARRAY` is a fresh
  `(object-array 0)` (the oracle's is one shared array). Pin: clojure-spec
  `typed-arrays-are-vectors-to-aget-aset-alength-aclone-and-the-verbs`.

## Charset values

- **The value** (`ClojureCharsetLowering`): `(:C%CHARSET name class)`, an io-family value. The
  six `StandardCharsets` fields and `Charset/forName` of a string literal lower to it, name and
  implementing class resolved by the JDK that lowers the program, so wasm reaches no
  `java:field`; a literal the JDK rejects lowers to the oracle's refusal, `forName` of anything
  else stays host-only. `class` is the implementing class's keyword, the chain
  `UTF_8 -> Unicode -> Charset` flattened to `Charset` (`ClojureClassBases.IO_SUPERS`). It crosses
  to a `java:` member as `Charset.forName(name)` (`%clojure-io-host`), which answers every other
  method; one named for the member alone is that host `Charset` at once
  (`ClojureCharsetLowering.hostConstruction`, the `#inst` precedent).
- **A String construction takes the value as it is** (`hostCall`,
  `ClojureBytesLowering.unviewed`): the io view's `java:static` would make the program name a host
  operator, arming the whole HOST family (the JVM spec harness has no `JavaBridge`), and fail on
  wasm.
- Trap: `%clojure-io-array-stream-p` checks the tag, not the shape: a charset is a three-element
  wrapper too.
- Deviations: `byte-array.md`, `deviations.md`; also `identical?` of two reads is false.
- **Pins**: clojure-spec `standard-charsets-and-charset-for-name-of-a-literal-are-charsets` (four
  backends), `a-charset-prints-as-the-host-object-its-hash-left-out` (the deviation);
  `ClojureInteropTest#aCharsetNamedHereCrossesTheJavaBoundaryAsTheHostCharset`;
  `ClojureLoweringTest#aStandardCharsetsFieldAndCharsetForNameOfALiteralLowerToTheCharsetValue`.

## Transients

- **The value** (`clojure.lisp` "Transients"): `transient` copies once and `persistent!` hands the
  data over (uncopied when full), so N bang verbs are N edits. A map that has held more than eight
  entries names its class `PersistentHashMap$TransientHashMap`, like the object the oracle's
  `assoc!` answers. After `persistent!` every verb is the oracle's `IllegalAccessError` (a carrier
  of its own).
- `=` and `hash` are identity (the box is a simple vector, `equal` by `eq`). `indexed?` goes
  through `%clojure-is-indexed-transient`, the family's alias of `%clojure-is-indexed`, so
  `TRANSIENT` stands ahead of `INDEXED`. Refusals are the oracle's words and classes, a wrong kind
  of transient checked before liveness like the oracle's cast.
- Every arm is the `TRANSIENT` family's; producers `transient`, `-v` and `conj!`'s value
  (`(conj!)` lowers to `transient`), so a program making none strips every arm.
- Deviations: `transient.md`, `deviations.md`; also a conjoined map entry is refused in `conj`'s
  words. Pins: clojure-spec `transients-edit-one-copy-and-persistent-hands-it-over`,
  `a-transient-refuses-like-the-oracle`,
  `a-transient-is-no-collection-and-prints-as-the-host-object`,
  `a-type-implementing-the-transient-interfaces-answers-through-its-methods`;
  `ClojureLoweringTest#transientVerbsLowerToOneWorkerCallAfterTheOraclesArityCheck`,
  `ClojureArmsTest#theTransientFamilyIsMadeByTransientAndFoldsItsArmsAndIndexedsHelper`.

## Persistent queues

- **The value** (`ClojureLangClasses`): `clojure.lang.PersistentQueue` is a `deftype` of the
  shipped namespace `clojure.lang` (`lib/clojure/lang.clj`) with the oracle's fields and
  interfaces (`java.util.Collection` but `toArray`: no object arrays here), so every verb reads it
  through the collection-interface families ("Collection interfaces") and none carries an arm of
  its own; the namespace gives the oracle's class name. `ClojureLowering.loadLangClass` preloads
  the namespace where a top-level datum first names the class, so a program naming none lowers
  unchanged; it is in `SHIPPED`, not `STARTUP`, so `(require 'clojure.lang)` loads it too (the
  oracle has no such namespace). `str`'s hex is a `quot`/`rem` loop (no bit verbs here).
- Deviations: user doc `persistent-queue.md` (the hash-less `#object`; `class` is the
  oracle's, measured 2026-11-25), plus `(= q '())` false (nil is the empty list), `(rest EMPTY)`
  nil, `nth` past the end nil, the refusal texts of `contains?`, `assoc`, a call, `rseq`,
  `realized?`.
- **Pins**: clojure-spec `a-persistent-queue-conjs-at-the-rear-and-pops-from-the-front` (all four,
  oracle-identical), `a-persistent-queue-prints-as-the-host-object-its-hash-left-out` (the
  deviation);
  `ClojureLanguageNamespacesTest#persistentQueueIsADeftypeOfTheClojureLangNamespaceLoadedWhereAProgramFirstNamesIt`.

## clojure.java.io

**`clojure.java.io` is a built-in, startup namespace of Clojure source**
(`clojure/lib/clojure/java/io.clj`) over the kernel namespace `rontolisp.internal.io`
(`ClojureIoLowering.kernels()`). User-facing behavior and deviations: user doc
`clojure-java-io.md`.

- **Values of the front end's own** (`:C%FILE`, `:C%URL`, `:C%URI`, `:C%INPUT-STREAM`,
  `:C%OUTPUT-STREAM`), since `java:` is a call-time error on wasm. A reader or writer over a byte
  stream is registered in `%clojure-io-streams` under `%clojure-io-registry-guard`: a Ring handler
  runs a thread per request, and a burst lost writers' registrations, so their text, on the
  interpreter and the JVM (`ClojureIoConcurrentRequestsTest`). A wrapper is a list, so `=`
  compares by spelling: no `=` arm (one made the interpreter's `=` measurably slower).
- **Arms** (`ClojureArms.Family.IO`): a program making no io value compiles byte-identical
  (`ClojureLibraryTest#aProgramMakingNoIoValueSplicesTheLibraryWithoutItsIoArms`). A session's
  library keeps every arm, so `str` of a non-io value pays one `%clojure-io-p` call there; a
  whole program on the interpreter strips it ("Sorted collections").
- **Prelude trap**: a `clojure.lisp` parameter named `write` grew every Clojure program: any
  symbol of the library, even in a defun nothing calls, counts for `LispPreludeLibrary`
  selection, and `WRITE` pulled the printer renderer. Renamed `for-write`; check a new library
  symbol against the prelude's names.
- **The `java:` boundary**: `ClojureIoLowering.crossing` hands every computed `java:` operand
  over as the host File, URL or URI (`%clojure-io-host`); one a member answers stays host,
  `slurp`/`spit`/`line-seq` taking it through the HOST view `%clojure-host-file-path` (not an
  `or` of io arms there, which would weigh every `java:`-naming spit program down). A protocol
  extended to File/URL/URI keys the exact keyword (`ClojureIoLowering.EXTENDABLE`, never walked);
  one extended to `InputStream`/`Closeable` walks; a dispatch value spelling an io class is its
  keyword ("Dispatch"). Members outside `ClojureIoLowering.METHODS` are the host object's
  (interpreter, JVM).
- **`(java.io.StringReader. s)` and `(java.io.StringWriter.)`** lower to string streams
  (`ClojureInteropLowering.stringReaderConstruction`): the oracle's `IOFactory` extends
  `Reader`/`Writer` (data.csv's `read-csv`, aero's `read-config`), and a host object does not
  open for `slurp`, nor can wasm make one. A genuine host `Reader`/`Writer` from a `java:` member
  has no arm. Pin: clojure-spec `slurp-and-the-reader-take-an-open-stream`.
- **Resources** (`resource` is a PART, `clojure/java/io_resource.clj`):
  `ClojureIoLowering.literalResource` answers a literal-name call in `ClojureLowering.lowerInner`
  ahead of the macro check, whose `resolveVar` would load the part. Lookup is a class loader's,
  root by root, from the entry file's directory made canonical (`ClojureSourcePath.computeRoots`;
  the user doc says why). A loader argument is ignored (the oracle's nil loader is an NPE).
  - Literal: embedded as octets by statements ahead of the program
    (`ClojureLowering.keptResources`), not where the call runs, so no request thread writes the
    table (`ClojureJavaIoTest#aLiteralResourceIsKeptBeforeItsCallRunsOnEveryBackend`); a name
    only a macro-time `eval` finds is kept too.
  - Computed: looked up as the program runs, a jar's by the jar reader (`clojure.lisp`, "Jars
    read when the program runs"), its cache under `%clojure-io-jar-guard` (a thread per Ring
    request), inflating with `RontoInflate` (interpreter, JVM) or `inflate.lisp` (wasm, which
    needs a preopen). A `jar:` read refuses as `JarURLConnection` does, a missing `!/` at the
    read rather than the construction.
  - **The jar family** (`ClojureArms.Family.JAR`) owns every definition named `%clojure-io-jar-`:
    the reader names `read-sequence` and a two-argument `file-position`, which
    `GrayStreamsLibrary` rewrites onto dispatch helpers it splices unpruned into any Gray-using
    program, from a dead defun too ("Prelude trap"). Pins:
    `ClojureLibraryTest#aProgramReadingNoJarSplicesTheLibraryWithoutTheJarReader`,
    `ClojureJavaIoTest#aResourceLookupIsCarriedOnlyWhereAProgramComputesAName`. Rejected: a jar's
    entries embedded below a literal prefix (Ring's `:root`: a request path is any name, and the
    inflater was on every backend anyway); the lookup in `io.clj`'s `resource` (every io program
    with a jar on its path would carry the reader).
  - **The seek family** (`ClojureArms.Family.SEEK`): `mark`/`reset` over a file use the
    two-argument `file-position`, owned for the same reason
    (`ClojureLibraryTest#aProgramCallingNoResetSplicesTheLibraryWithoutTheFileSeek`; clojure-spec
    `a-stream-over-a-file-marks-the-position-it-had-and-reset-returns-to-it`,
    `input-stream-and-output-stream-wrap-a-byte-array-stream-in-a-buffered-one`, four backends).
- **Charsets** ("Charset values"): a non-UTF-8 reader decodes the rest of its byte stream at once
  (a fetched reply's a chunk at a time).
- **The JDK decoder**: every UTF-8 decode the oracle's JDK does (`String.` of octets, the Ring
  codec, the client's `:string` body, reply readers) is `rontolisp::%octets-to-string-replacing`:
  `new String(bytes, UTF_8)`, each malformed sequence one U+FFFD over as many octets as that
  decoder is seen to take, a truncated tail one. The rule is the Unicode standard's (Table 3-7,
  "maximal subpart") with one difference a JDK shows: an encoded surrogate counts as begun and,
  whole, as ONE sequence (`ED A0 80` is one U+FFFD, the standard's practice three). Written from
  the table. The interpreter binds the JDK decoder itself (`Environment`), an interpreted loop
  over octets being far slower; the compile paths compile the Lisp, which validates strictly and
  decides only a malformed sequence. Pinned Lisp against JDK:
  `LispPreludeLibraryTest#theReplacingDecoderIsTheJdksOnTheCompilePathsAndOnTheInterpreter`
  (every malformed shape, a seeded sweep).
- **Readers over a fetched reply** (`clojure.lisp`, same name): a reader over a reply's body is
  the prelude's text pull stream (`rontolisp::%make-text-pull-stream`), a Gray character stream
  (`.kb/gray-streams.md`) pulling the next chunk only when a read needs it; UTF-8 waits for a
  sequence a chunk ends inside where the JDK's decoder would (`%clojure-reply-utf-8-end`; an
  encoded surrogate's `ED A0` too, where the strict table answers one U+FFFD too many). So reads
  answer as their text arrives (not `read-line` with `*in*` bound on a component, which refuses
  that binding around `rontolisp:await`), and `line-seq` is lazy like the oracle's (other
  readers' are strict). `.close` closes the body through `%clojure-io-close-stream`'s arm (a Gray
  stream has no `close` of its own, close being ownable program-wide), and a later read refuses
  like the oracle through `%clojure-open-reader`'s arm, since `open-stream-p` is true for every
  Gray stream. `REPLY_READER` owns `%clojure-reply-` and is made only beside `FETCH`
  (`Family.requires`): the Gray class brings gray.lisp and the call-site rewrite of every stream
  call, so a client program opening no reader, and a reader program fetching nothing, compile
  without it (`ClojureHttpClientTest#onlyAProgramOpeningAReaderOverAReplyCarriesTheGrayStreams`,
  `ClojureLibraryTest#aReaderDecodesAReplyAsItArrivesOnlyInAProgramThatFetchesAndOpensOne`).
  Rejected: a reader kind of the io kernels' own (`read`, `*in*` and every other CL read would
  need its arm); a stream kind in each backend's runtime (four runtimes for what a Gray stream
  gives all four).
- **slurp/spit through `IOFactory`**: io.clj's last form installs its `reader`/`writer`, which
  `slurp`/`spit` of a non-string ask when no kernel opens the value; a program not loading the
  namespace keeps the path-only `slurp`, byte-identical. Once the namespace has loaded or fetch
  is installed, a string spelling a URL (a scheme `java.net.URL` knows, no space) is that URL, any
  other a path, as the oracle's `RT.toUrl` falls back to a File (`(slurp "http://h/a b")` opens a
  file).
- **`http:` and `https:` URLs** (`clojure.lisp`, same name): read through the
  program's own `rontolisp:fetch` as `java.net.HttpURLConnection` reads one, down to
  `Accept: */*`, the fragment left out and a `Location`-less 3xx answered as it is; not a 305
  (fetch cannot use the proxy). A transport failure is the IOException carrier over the
  transport's words. Verified on the four legs of `FetchSpecE2eTest`: identical but the
  transport's words and the fields it adds.
- **Which program names fetch** (the gate): naming `rontolisp:fetch` brings every transport splice
  and the Preview 1 refusal, so the startup namespace's kernels must not (every io program would
  import `wasi:http` on a component and be refused on Preview 1). `ClojureLowering.readsHttpUrls`
  is set by an `http:`/`https:` string literal in a read's first argument
  (`ClojureIoLowering.readCall`) or `.openStream`'s receiver, or by loading `rontolisp.http-urls`;
  then `ClojureIoLowering.installFetch` runs at index 1 of the program (a session: ahead of the
  buffer that first reads one), so a read above the literal reads too. Rejected: any http literal
  anywhere (a Ring handler printing a URL would be refused under `--no-wasi` without
  `--host-fetch`, a component would need `-S http=y`); `rontolisp.http-client` as the opt-in (its
  request kernel rides along); the namespace installing it itself (a late `require` would leave
  the reads above it refused).
- **Cost** (measured): an io program reading a File carries the http: refusal's words and the
  URL-spelling check; `.write` on a `StringWriter` pays the instance call's io arm.
- **`extend` of a computed map** (`default-streams-impl`; `ClojureProtocolLowering.computedRows`):
  nil removes the row, a map replaces it with its entries naming the protocol's methods (others
  skipped: the oracle stores but never reads them), anything else replaces it as-is (the oracle's
  `assoc-in`). It answers nil; a literal map's `extend` answers the last lambda.
- **Deviations** beyond `clojure-java-io.md`: a multi-release jar's versioned entry and an entry
  past 4 GB (zip64's extra field) are not read; `.openStream` of a `jar:` URL is a
  `BufferedInputStream` (the oracle's `JarURLConnection$JarURLInputStream`).
- Pins: clojure-spec `clojure-java-io-*`, `a-java-io-file-prints-as-the-host-object-*`,
  `java-io-files-are-made-renamed-and-deleted`, `extend-takes-a-map-computed-at-run-time` (all
  four, oracle-identical but the hash/`class` case); clojure-http-spec
  `clojure-java-io-reads-an-http-url-through-fetch`,
  `an-http-url-read-follows-and-refuses-what-http-url-connection-does`,
  `an-http-url-stream-closes-and-fails-in-its-classs-words`,
  `a-reader-over-a-streamed-reply-reads-as-it-arrives`, `a-reply-is-text-in-the-oracles-decoding`
  (`FetchSpecE2eTest`'s four legs);
  `ClojureArmsTest#theReaderFamiliesAreMadeByTheirReadersAndTakeTheirDefinitionsWithTheirArms`,
  `LispPreludeLibraryTest#aTextPullStreamPullsThePieceAReadNeedsAndReadsLinesAcrossPieces`;
  `ClojureHttpUrlsTest` (the gate, its refusals, host reads against a local origin);
  `ClojureJavaIoTest` (files, directories, directory and jar resources on all four, the
  refusals);
  `ClojureArmsTest#theJarFamilyIsMadeByALookupOverAJarAndTakesTheJarReaderWithItsArms`;
  `ClojureInteropTest#aJavaIoFileCrossesTheJavaBoundaryAsTheHostFile`;
  `ClojureArmsTest#theIoFamilyIsMadeByClojureJavaIoAndFoldsTheArmsOfAProgramMakingNone`;
  `ClojureLoweringTest#javaIoIsABuiltInNamespaceLoadedAtItsFirstQualifiedName`;
  `ClojureWasmFileRefusalTest` (no preopen: the oracle's `FileNotFoundException`).

## Macros

- `defmacro` lowers to an expander lambda, run at lower time by `eval/ClojureMacroTime`, plus a
  `c%name%macro` table global. A program macro's var root is the oracle's macro function over
  `(&form &env & args)` (`ClojureMacroLowering.programMacroRoot`); a redefinition validates and
  notifies like the oracle's `bindRoot` ("State", validators and watches).
- **A var the program makes a macro by METADATA is a macro from the next form on** (`^:macro`,
  `alter-meta!`, `.setMacro`): a top-level write runs in the macro-time image once its datum
  lowered; a computed reference or a write in a local scope decides nothing at lower time. The
  expander calls the root the oracle's way, so a var holding a var object (potemkin's and
  specter's re-export idiom) expands. Clearing the flag unmakes only a metadata-made macro.
  Pins: clojure-spec `a-var-made-a-macro-by-its-metadata-expands-like-the-oracle`,
  `a-macro-alias-referred-from-another-namespace-expands-its-target`.
- **A top-level `do` is its members, one datum at a time** (whole program and required file),
  like the oracle's top-level `eval`, so a definition or var-metadata write reaches the members
  below.
- **A `defmacro` name** is refused only where no call site could reach the macro table: a
  reader head, a leading-dot or `/`-qualified name, a trailing-dot name over a class spelling
  (`ClojureInteropLowering.isConstructionHead`: `<->.` defines, `String.` does not). A special
  form names a macro reached only qualified (spec.alpha's `s/def`), the bare head staying the
  special form as in the oracle's compiler. Pins: clojure-spec
  `a-macro-may-take-a-trailing-dot-name-without-a-class`,
  `syntax-quote-qualifies-unresolved-symbols-with-the-defining-namespace`;
  `ClojureLoweringTest#defmacroAcceptsATrailingDotNameWithNoClassBase`,
  `#defmacroRefusesCoreFormsAndEnvironments`.
- **`&form` and `&env`** (`doc/en/clojure/semantics.md`) are bound at lower time only for a
  macro whose body reads either (`ClojureLowering.envMacros`); generated destructuring locals
  are not in `&env`. A run-time expansion binds both through the macro's root.
- **The locals in reach, the oracle's `Compiler/LOCAL_ENV`**: every lower-time expansion binds
  `%clojure-local-env` to the call's `&env`; `C%MACROEXPAND-1` passes it on (nil where the
  program runs) and leaves a form whose head names a local unexpanded (the oracle's
  `isMacro`), so a body's `macroexpand` expands among the call's locals and riddley's walk
  grows the map ("riddley (j27)"). Cost: an `&env` map per program-macro call with a local in
  reach. Pins: clojure-spec
  `macro-env-and-form-ns-resolve-and-a-require-that-runs-later`,
  `ClojureLoweringTest#aMacroBodyReadsItsCallAndTheCallsLocalsThroughFormAndEnv`.
- **The run-time table of a whole program holds an index, not the expander**:
  `(setq |c%ns/m%macro| 3)`, the expanders in one `C%MACRO-EXPANDER` behind `C%MACRO-FN`,
  reached only from `C%MACROEXPAND(-1)` and `C%MACRO-ROOT-OF`. A program taking a macro's var
  root but expanding nothing at run time keeps only the expanders of the macros whose var it
  takes outside a macro body, so another macro's body runs only while the program lowers
  (`.kb/jvm-run-time-lowering.md`, "What opens a program"). A program lowering no
  `macroexpand(-1)` call or value carries none of them (`ClojureLowering.expandsAtRunTime`):
  `ClojureLowering.programMacroRuntimeForms` keys the runtime in `LibraryDefunPruner`, so a
  dead expansion loses every expander before Pass 2, with what their bodies call and their
  templates spell. Not left to the wasm/JVM shakers: the dispatch gate records spellings while
  Pass 2 emits ALL functions, so a template's `'|c%ns/helper|` would still arm the helper's
  registry row. A session stores the expander itself, since a later input may expand.
  `clojure.walk/macroexpand-all` is a separate part, so walking data expands nothing. Pins:
  `ClojureMacroRuntimeTableTest`,
  `ClojureLoweringTest#aWholeProgramsTableHoldsAnIndexIntoTheOneFunctionHoldingTheExpanders`,
  `ClojureSessionTest#aLaterBufferExpandsAMacroAnEarlierOneDefined`, clojure-spec
  `clojure-walk-macroexpand-all-loads-where-a-program-names-it`.
- **A body sees the program's top-level definitions above the call site** (the oracle's
  form-by-form load, a required namespace's too; deviations: `doc/en/clojure/deviations.md`).
  `ClojureMacroLowering.handOver` gives the macro evaluator each datum's lowered definitions
  once it lowered, a var's root as a LAZY root (`LispEvaluator.defineLazyGlobal`: its value
  runs only when an expansion reads the var), since an eager `(defonce server (run-server
  ...))` above a macro call would start at every compile. A form that fails is dropped. The
  evaluator holds every per-program runtime from its start, since a helper may use one before
  the lowering met a use. Its protocol walk grows with each class a program extends a protocol
  to, so a body dispatching on a seq takes the `ISeq` row, not `Object`'s; a pass whose
  dispatchers lowered without the walk ("Collection interfaces") restarts, and meanwhile
  `ClojureProtocolLowering.walkAtMacroTime` hands the evaluator walking dispatchers, else the
  pass can fail before it restarts (core.match's `emit-pattern`). A body runs once per pass and
  the last pass's expansion is the program's (a printing body prints once per pass, the
  oracle's once). Pins: clojure-spec
  `a-macro-body-calls-the-programs-definitions`,
  `a-protocol-extended-to-a-collection-interface-reaches-a-type-implementing-it`;
  `ClojureLoweringTest#aMacroBodyCallsAHelperDefinedAboveIt`,
  `#aDefValueRunsAtMacroTimeOnlyWhenAnExpansionReadsIt`,
  `#aRedefinitionReadsTheRootItSupersedesAtMacroTime`;
  `ClojureProjectNamespacesTest#aRequiredNamespacesMacrosCallItsFunctions`;
  `ClojureSessionTest#aMacroOfALaterBufferCallsWhatAnEarlierOneDefined`.
- **A definition a top-level wrapper holds is handed over whole, its bindings included** (a
  `defn` in a `let`/`when`/`binding`/`letfn`), since the oracle interns at compile time and
  sets the root when the form runs. `holdsDefinition` takes only a definition the datum's run
  makes (not inside a `lambda` or `labels`/`flet` function, not a `setq` of a `let`-bound
  name): else `(prn (rh #(dissoc ...)))` would run at lower time and, in the native binary,
  reach the unregistered `Class.getSuperclass`. A wrapper-defined `defmacro` expands through the
  global `c%name%macro-closure` the wrapper sets, since its datum names locals a back end
  refuses to capture outside the wrapper. Consequence: a wrapper's other statements run at
  lower time too (core.match's sentinel `let`), into the null output stream. Pins:
  clojure-spec `a-macro-body-calls-a-definition-a-top-level-wrapper-made`,
  `ClojureLoweringTest#aMacroBodyCallsAHelperDefinedAboveIt`.
- **`eval` and `resolve` run while the program lowers** (the `compile-if` of data.priority-map
  and instaparse; at run time: "Lowering at run time"): `ClojureMacroTime` redefines them in
  the macro-time evaluator as calls back into the lowering (`ClojureMacroEvaluator.Lowering`),
  which lowers the value detached (`ClojureLowering.lowerDetached`: the current namespace,
  nothing of the form being expanded) and evaluates it there; a lowering error is an ordinary
  error the body may catch. The value realizes first, and so does every macro's answer
  (clojure-spec `defmacro-unless-expands-before-backends`). A quoted `resolve` or `ns-resolve`
  resolves at lower time (an unknown namespace failing in place like the oracle's), nil for
  what this front end lacks, so `compile-if` picks the branch that lowers; a computed one is
  called back at macro time. Deviations: `doc/en/clojure/deviations.md`, plus the `used*`
  flags an eval'd form sets stay set for the program. Pins: clojure-spec
  `a-macro-body-evaluates-a-form-while-it-expands` (oracle-identical but its `evm-defines`
  line, the AOT compile's),
  `ClojureLoweringTest#aMacroBodysEvalChoosesTheCodeThisFrontEndLowers`,
  `#aMacroBodysEvalSeesNoLocalOfTheCallSite`, `#evalAndResolveRefuseWhatTheyCannotTake`,
  `ClojureSessionTest#evalLowersThroughTheSessionsOneLoweringInAMacroBodyAndAsAnInputRuns`.
- **A var a macro body interns while it expands is the program's from there on, unbound when
  the program starts** (specter's `path` caches; `ClojureMacroTime.tellingInterns`): the
  oracle's AOT compile's semantics (`compile`, loaded in a fresh JVM; `clj -M` keeps the
  root). Carrying the root was weighed and not made: it is no constant a program can hold (a
  `MutableCell`, a fn any closure), it would split values by whether they have a literal form,
  and every other change a body makes stays at compile time. Specter's cache is AOT-safe:
  `(.get ^MutableCell c)` of the unbound root is the oracle's `ClassCastException` ("Java
  interop"), `bound?` false, `alter-var-root` fills it. The image's var keeps the interned
  root, so code the image runs sees the oracle's compile-time var. A gensym names the var of
  its spelling (the oracle's gensym is an ordinary symbol), so `(def ~(gensym) v)` and its uses
  meet in any namespace. Pin: clojure-spec
  `a-var-a-macro-interns-while-it-expands-starts-the-program-unbound` (oracle-identical but its
  last line, the AOT compile's).
- **What a macro body runs where the target has no host**: the image runs the program's own
  lowering for the target, so a body sees the program's values: the portable readings of JDK
  classes ("Portable ..." sections) and of a dependency's plain classes ("Portable reading of a
  dependency's Java class"). The image itself is a host: a `java:new`/`java:static` of a class
  with no portable value makes a host object there, so the image keeps the host-object arm the
  program strips ("Host arms where the host is not") and a body's host member answers at
  compile time as on the JVM, the ClojureScript model. Weighed and not made: a second lowering
  of every handed-over datum for the host (every wasm compile with a macro would lower twice,
  for a divergence that is only the host object); the image refusing `java:new` (a body's host
  use would fail on wasm only). Pins:
  `ClojureProjectNamespacesTest#aDependencysPlainJavaClasses*`.
- **A program macro wins over every lowering row of its name from its definition on; above it
  the core meaning holds**, like the oracle's form-by-form compile (`lowerInner`; above the
  definition `lookupVar` hides a `pendingCoreMacro`). Reserved (`isReservedHead`): the special
  forms and the heads the reader spells. `fn` is not: `#(...)` and a `defn`'s recorded
  dispatch datum are spelled `fn*`, so a `fn` macro never captures them. A definition head a
  macro above shadows pre-declares nothing.
- **A local or program definition shadows every lowering row but the special forms'**
  (`lowerRow`, `ClojureMacroLowering.SPECIAL_FORMS`): a parameter named `fn` is called (malli's
  `-proxy-schema`), while `do`, `if`, `recur` ... stay specials under same-named locals, like
  the oracle. Deviation: a bare `do` as a whole `fn`/`let`/`loop` body answers nil in the
  oracle (an analyzer quirk), the local here.
- **A shadowing definition is invisible above itself**: the pre-scan (`declareOne`) skips a
  `def`/`defn`/`defonce`/`defmulti`/`defstruct`/`deftest` of a core name (a `defmacro` is
  timed through `pendingCoreMacro`), so a use above keeps the core meaning like the oracle
  (malli's `(@c k)` above its `defn deref`). An explicit `declare` still pre-registers, and the
  definition under it takes its key (`preDeclareUnlessCoreShadow`), so uses between reach the
  definition (tools.reader.edn's `(declare read)`; clojure-spec `declare-forward-declares`).
  Non-core names keep the pre-scan's forward visibility (the pinned leniency).
- `clojure.core/name` (`ClojureCoreNames.coreSpelling`) always lowers the core row; the
  lowering's own datum rewrites spell their heads this way, so a program macro never captures
  them.
- Syntax-quote qualifies like the oracle (`doc/en/clojure/deviations.md`); `quote` and
  syntax-quote build data symbols (`dataSym`), so `'*out*` is `*out*`, not the stream alias. A
  template form's reader metadata travels as `(%with-meta form meta)`
  (`ClojureMacroLowering.syntaxQuotedMeta`, "Vars and metadata"), a marker that must never
  qualify (core.async's `(^:once fn* ...)`, a hinted gensym parameter). Pin: clojure-spec
  `syntax-quote-qualifies-unresolved-symbols-with-the-defining-namespace`.
- **The syntax-quote heads may name a macro** (`SYNTAX_QUOTE_HEADS`, which the oracle's reader
  never spells; tools.reader's `syntax-quote`): a qualified call expands it, the bare head
  stays the reader's form, so a backquote below the definition is still a template. Pin: the
  same clojure-spec case.
- **A template's qualified symbol is an unspelled quote** (`ClojureMacroLowering.unspelled`):
  `(%unspelled-quote |c%ns/f|)` spells nothing for the dispatch gate
  (`.kb/optimize-dead-code-elimination.md`): the symbol spells `ns/f`'s defun name, but a
  Clojure symbol is never a function designator by name in a compiled program (run-time
  `eval`/`resolve` are refused, a called symbol looks itself up). So a run-time-expanding
  program keeps its expanders and what their bodies call, not every function their templates
  name. Bare special forms, `x#` gensyms and a plain `'sym` keep `quote`. Pins:
  `ClojureMacroRuntimeTableTest`, clojure-spec
  `a-run-time-expansion-reads-template-symbols-as-data`.
- **An argument datum travels quoted and its answer decodes back (`decodeDatum`)**, so every
  reader form, reader metadata included, must survive the round trip. `#()` READS as `fn*` over
  plain symbols, so it needs no decoding (a marker matched by identity broke in every
  expansion). Pins: clojure-spec `a-macro-argument-holding-a-regex-literal-expands`,
  `anon-fn-reads-as-fn-star-over-generated-parameters`,
  `a-macro-argument-holding-an-anonymous-fn-expands` (oracle-identical but the parameter
  numbers).
- **A class value decodes to a constant** (`ClojureMacroLowering.classSpelling`): a host
  `java.lang.Class` a body evaluated and a `(:C%CLASS name)` designator alike decode to what the
  class's source spelling lowers to (`classValue`, `classKeyword`: `%clojure-class-value` or the
  `forName` object), else -- a record's or deftype's class -- the designator's construction
  under `%embedded` (a `quote` round trip would turn its head into a `:C%KEYWORD` wrapper). One
  form for both, since the binary's macro-time class values are designators ("Class values")
  and its IR must be the JVM's (`ClojureNativeLoweringE2eTest`). So datascript's `defn+` `:tag`
  is the oracle's class on the interpreter and the JVM, a designator on wasm.
  Pins: clojure-spec `a-macro-expansion-holding-a-class-value-lowers`,
  `a-macro-expansions-tag-metadata-holds-the-class-value` (the host legs),
  `ClojureLoweringTest#aMacroExpansionHoldingAClassValueLowersAsAConstant`.
- `macroexpand` stops where an expansion answers its form itself (`eq`, the oracle's
  `identical?`); a lazy seq, or a cons over one, is read as a seq first, like the oracle's
  `ISeq`. **The head resolves in `*ns*` when the expansion runs**, like the oracle:
  `C%MACRO-FN` asks `C%MACRO-SCOPE` (`ClojureMacroLowering.macroScope`), per namespace the
  spellings the table cannot spell itself (own macros but `user`'s, refers, alias-qualified
  names), so a run-time expansion keeps the `NS_SWITCH` arms. A session redefines the scope
  after a buffer that changed it. Pins: clojure-spec
  `a-run-time-expansion-resolves-a-bare-head-in-the-current-namespace`,
  `ClojureLoweringTest#aRunTimeExpansionResolvesItsHeadThroughTheScopeOfTheNamespaceItRunsIn`,
  `ClojureSessionTest#aRunTimeExpansionResolvesInTheNamespaceALaterBufferSwitchedTo`.
- **The core macros expand as data**: `macroexpand(-1)`, `clojure.walk/macroexpand-all` and a
  core macro's var root answer the oracle's expansion of the macros in
  `ClojureCoreMacros.EXPANDERS` (list and deviations: `doc/en/clojure/deviations.md`; also a
  BigDecimal `case` test hashes as its ratio); a program's own call sites keep their lowering
  rows. The expanders are Clojure source in the shipped internal namespace
  `rontolisp.internal.core-macros` (`lib/rontolisp/internal/core_macros.clj`, refused to a
  program's `require`); diffed against the oracle, gensyms renumbered, every shape matches. A
  core macro outside the list answers its call, so a walker stops there as at a function call.
  **Provenance**: written from the macros' documentation and the oracle's printed expansions,
  nobody opening clojure.core's source (`core.clj`, `core_deftype.clj`: EPL), and assembled
  differently from its private helpers (`case` merges colliding tests before it picks the
  mask; `doseq` hands each step the forms to carry on with; `for` folds its levels from the
  innermost out; the small macros are their printed expansion as a template). What `case*`
  takes was read off the oracle's expansions of chosen tests. Checked by hand: 107
  `case`/`doseq`/`for` shapes and 57 generated `case` tables print the oracle's line but for
  refusal words.
  - Loading (`ClojureCoreMacros.need`): code the program runs (a `macroexpand` call or value, a
    core macro's var root, `destructure`) loads the namespace; a macro body's use does not
    (the macro-time evaluator holds the expanders), unless a program macro's root reaches the
    bodies at run time; an open lowering loads at once. The library pruner keys the
    namespace's definitions: `#'when` keeps `expand-when` and what it calls.
  - Cost, measured 2026-10-29 (P1): a program never expanding at run time, its macro bodies'
    expansions included, is byte-identical with and without the expanders (275,776 B); one
    calling `macroexpand-1` 421,290 -> 772,631 B, under `clojure.pprint` 1,442,255 ->
    1,672,481 B. The expanders funnel every vector `conj` through one `push`, which took that
    from 972,318 B: a `conj` of n items lowers every collection arm n times at its site.
  - The special forms the expansions spell lower (`lowerRow`): `let*`/`loop*` through the
    `let`/`loop` rows over names only, with the oracle's refusals; `letfn*` as `letfn`
    entries; `case*` as the `case` of its `[test then]` entries, each test a constant compared
    by equality (the hashes only steer the oracle's jump table), a skipped hash's `condp =`
    then contributing its tests. Map destructuring's `PersistentArrayMap/createAsIfByAssoc`
    and `/EMPTY` lower in place.
  - **The type special forms `deftype*` and `reify*`**, what `deftype`, `defrecord` and
    `reify` expand to, lower in `ClojureTypeSpecials` as the datum of the macro each underlies,
    every method a row, so a program's macro may build them: datascript's
    `defrecord-updatable` rewrites a record's `macroexpand-1`, dropping the methods its body
    overrides; extend-clj's `deftype-atom` spells `deftype*` over the reference interfaces
    ("Host interfaces"; its syntax-quote spells `extend-clj.core/__extmap`, so field names
    unqualify).
    **A record's own methods are written here**: only the skeleton is the oracle's (what a
    program taking the expansion apart relies on: the `declare`s, the `deftype*` over the
    fields and `__meta`/`__extmap`/`__hash`/`__hasheq`, the interfaces, one method per
    interface method ahead of the body's, `->Name`/`map->Name`); each method's BODY is spelled
    from what the oracle's record answers through it (a 46-line probe), not from its text.
    Checks: the oracle's compiler, building a class from this expansion, answers all 46 lines
    like its own `defrecord`; so does `deftype*` here.
    The expanders read a protocol group's var through `rontolisp.internal.types/protocol-var`
    (nil in the program, the lowering's `resolve` in the image), so no run-time expansion
    reaches a lowering verb: with `resolve` in the expander the JVM corpus compiled as a host
    (`.kb/jvm-run-time-lowering.md`). Pin: clojure-spec
    `the-type-special-forms-define-what-deftype-defrecord-and-reify-expand-to`.
  - Pins: clojure-spec `core-macros-expand-to-the-oracles-forms-as-data`,
    `a-macros-var-root-is-its-function-of-form-env-and-args`,
    `let-star-loop-star-letfn-star-and-case-star-are-special-forms`;
    `ClojureMacroRuntimeTableTest#aProgramExpandingNothingAtRunTimeKeepsNoExpander`;
    `ClojureCoreNamesTest` (the table against the file's expanders);
    `ClojureLoweringTest#letIsSequentialAndLoopIsALabelsSelfCall`,
    `#letfnLowersToLabelsWithEveryNamePreScanned`.

## Lowering at run time

**Invariant: the interpreter keeps a Clojure program's lowering for its run, and a JVM class
carries its program and lowers it again, so a lowering verb over computed arguments lowers
when it runs; a wasm module carries none and refuses by name** (`.kb/jvm-run-time-lowering.md`;
the verbs: `doc/en/clojure/deviations.md`). Each `clojure.lisp` entry hands its verb and
arguments to `%clojure-lower-at-run-time`, which refuses on both wasm backends; the
interpreter replaces it (`LispEvaluator.installClojureRunTime`), a JVM class's library splices
in the hand-off to the compiler it carries.

- **The datum** (`ClojureRunTime.datum`) is core-spelled, `(clojure.core/verb 'arg ...)`, so no
  program macro or definition captures the head; `require`/`use`/`refer` sit inside a
  `(clojure.core/let [] ...)`, so a missing library is the oracle's run-time
  `FileNotFoundException`.
- **Decoding a run-time value** (`ClojureLowering.decodingFor`) happens only around the
  argument decode, never while the datum lowers: a macro's answer is a macro-time value.
- **Open, incremental, repl** (`ClojureLowering`'s flags, documented there): a session is all
  three, a run-time datum `open` and `incremental`.
- **A program lowers open when its run-time code reaches an entry** (`ClojureRunTime.ENTRIES`;
  `ClojureLibraryTest#everyFunctionReachingTheRunTimeLoweringIsAnEntry` keeps it equal to the
  library's). Reach runs from the top-level forms through every function a reached form names
  (`ClojureRunTime.reachesAny`), so a verb named only by macro bodies or their helpers runs
  while the program lowers (`.kb/jvm-run-time-lowering.md`, "What opens a program"). The
  program lowers again `open` for the interpreter and the JVM; the misses still re-lower the
  whole program. A run-time datum emits only the runtimes the open program did not carry (one
  emitted twice starts its state over). Such a program takes the whole library, unstripped.
- **Where it runs**: the interpreter (command line, REPL, playground, nREPL: `.kb/nrepl.md`)
  and a JVM class (`ClojureSpecE2eTest`'s two legs); not a `.clj` a Common Lisp `load` reads
  (its lowering is dropped).
- **Computed require specs** (`ClojureNamespaceLowering.computesSpecs`): an unquoted symbol
  naming a local or var, or an unquoted call, is computed; a call is told from a prefix list
  by its head, a local, var, core name or any qualified name (spec.alpha's `(c/symbol ns)`).
  A bare unknown symbol, vector or prefix list stays the leniency's spec.
- Redefinition: a datum's `defn` over a defn re-points every cell the program's calls name, so
  it reaches every caller like the oracle's var: on the interpreter at no cost per call
  (measured: `.kb/nrepl.md`, "Redefinition in an open lowering"), in a JVM host through the
  cell each function's class keeps (`.kb/jvm-run-time-lowering.md`, "Redefinition").
- Pins: clojure-spec `the-interpreter-lowers-at-run-time`, `intern-var-set-and-with-redefs-fn`
  (oracle-identical), `eval-and-resolve-of-a-computed-symbol-refuse-at-run-time` (wasm);
  `ClojureLoweringTest#evalAndResolveRefuseWhatTheyCannotTake` (the open decision);
  `ClojureSessionTest#evalLowersThroughTheSessionsOneLoweringInAMacroBodyAndAsAnInputRuns`,
  `#aDeclaredNameStaysOpenForALaterBuffer`.

## The IFn dispatcher stays at the call site

**A spliced runtime worker funcalls its function argument; the lowering hands it a real
function.** `rontolisp::%clojure-call` (the IFn dispatcher) drags the structural-key runtime,
a sizable wasm runtime, behind it, so one worker naming it would put it in every program using
the verb. The workers: `map`, `reduce`, `juxt`, ..., the transducers, a regex `replace` with a
function replacement.

- `ClojureBindingLowering.realFnValue` passes an argument as itself when `holdsRealFun` (a
  `ClojureLowerUtil.yieldsFun` form, or a variable bound to one); anything else goes through
  `%clojure-as-fn`, which wraps a non-function in a rest lambda over the dispatcher. A worker
  added to `FUNCTION_WORKERS` must answer a `lambda` on every path. A verb used as a value
  wraps its parameter at run time, so it carries the dispatcher. The same test makes a
  call-site invocation direct; `update` lowers its function form in place (no effect to order
  against the map and key).
- The inline loops (`every?`, `sort-by`, `comp`, `memoize`, ...) take a
  `ClojureBindingLowering.FnArg` decided from the DATUM. Never ask the scope from a generated
  symbol: a value lambda's `c%NAME-fn` parameter can spell a user local's name, so a value
  form's `FnArg` is real only for a `yieldsFun` form. A local bound to a real function feeds
  the loop directly: `ClojureLoweringTest#inlineLoopsFuncallALocalBoundToARealFunction`,
  clojure-spec `locals-bound-to-functions-feed-the-inline-loops`.
- Kept on the dispatcher: `test` (the `:test` metadata value) and a `defn` parameter used as a
  function anywhere; `update` keeps the structural keys `get` needs. A program passing only
  real functions sheds the dispatcher.
- A named `fn` reads its own name as the function in value position too (the `labels` cell),
  so a macro's gensym-named self-passing fn (core.unify's) recurses; any other bare `#:...`
  symbol lowers to itself. Pin: clojure-spec `a-gensym-fn-name-reads-its-function-as-a-value`.
- Pins: `ClojureLoweringTest#aRuntimeWorkerTakesARealFunctionWrappedAtTheCallSite`,
  `#aProgramPassingRealFunctionsSplicesNoDispatcher` (the pruned IR); clojure-spec
  `a-collection-as-the-function-of-a-seq-worker-answers-like-ifn` (every backend).

## Dispatch

- `defmulti`: a method table, a default, a prefers table and an `Object` slot plus a rest-args
  dispatcher; `defmethod`'s tail is `fn`'s, like the oracle's. `dispatchKeyForm` keys a method: a
  class spelling by the keyword `class` answers, `nil` by the `(:C%NIL)` marker the dispatcher maps
  a true nil to (no table keys on nil; a literal `:nil` keeps its row).
- The six introspection verbs (`methods`, `get-method`, `remove-method`, `prefer-method`,
  `prefers`, `remove-all-methods`; answers: `doc/en/clojure/deviations.md`) take a `defmulti`
  name. A computed multimethod and the six as values read the run-time registry
  `%clojure-multimethods` (`ClojureDispatchLowering.computedCall`), keyed by the dispatch function,
  so a re-executed `defmulti` reads its new tables. `methods` is a `builtin` row: a program var or
  local of that name shadows it. A program naming no `defmulti` and none of the six lowers
  byte-identically; a `defmulti` carries the registry store. Pins: clojure-spec
  `prefers-and-remove-all-methods`,
  `multimethod-value-arms-run-where-calls-run` (four backends);
  `ClojureLoweringTest#multimethodIntrospectionTakesTheMultimethodName`,
  `#lowerTimeOnlyVerbsRefuseAtRunTimeAsValues` (both registries).
- `:import`/`import` take a `[pkg A B]` vector like the list: the reader's `VECTOR` marker leading
  the items is skipped (`importSpecs`, as `referNames` does).
- A `defmulti` of a var holding a multimethod lowers to `nil`, like the oracle's (methods and
  default stay): `ClojureLowering.multimethods`, by var key, survives buffers and is cleared by
  every other definition, never by the pre-scan. Static, so a `defmulti` run repeatedly inside a
  function still redefines.
- An exception or a runtime error answers its class, its tag a keyword, ahead of the host arm
  ("Catching").
- `class`'s last arm, `%clojure-host-class`, is `(java:call x "getClass")` under `handler-case`:
  `java:call` refuses every non-host value, so its refusal is the host test. Where the program
  names no `java:` operator, `ClojureLibrary.process` splices a refusal-only body, since a `java:`
  reference changes the JVM output; the interpreter always has the host body. A host object
  reaches `Object`, then `:default`, like the oracle. The printer's and `str`'s host arms are of
  the same kind (stand-ins in `ClojureLibrary.HOST_ARMS_WITHOUT_JAVA`) and test
  `%clojure-lisp-value-p` first, so a Lisp value in a `java:` program never pays the `getClass`
  refusal.
- A `class` call in the dispatch function maps nil onto the marker (user doc), also through a
  named function (the `defmulti` re-lowers its recorded definition) or a call nested inline
  (inlined at the call site; a name already being inlined keeps its direct call, so recursion
  terminates).
- **`print-method`** (behavior and deviations: `doc/en/clojure/reference/print-method.md`). A
  `defmethod` of it (`ClojureDispatchLowering.isPrintMethod`) stores into `clojure.lisp`'s
  `%clojure-print-methods` when it runs, keyed by type tag, `:type` keyword or host class binary
  name; a local, var, call or protocol interface as the dispatch value is keyed at run time
  (`%clojure-print-method-store`; a computed host class refuses, since no table here tells a host
  class's kinds at run time). `%clojure-write` calls the method under `%clojure-print-readably`.
  The store produces `ClojureArms.Family.PRINT_METHOD` and `PRINT_FLAGS`, whose readable view the
  nested `pr` reads, so such a program carries the cut/deep arms too; one making no interface value
  and no run-time store folds the interface and run-time arms
  (`ClojureArmsTest#thePrintFlagAndPrintMethodFamiliesFoldTheirArmsInAProgramMakingNone`). A host
  class's method takes host objects only (a host arm walking superclasses, then interfaces,
  stopping at the four classes the oracle prints itself): the oracle's and every library's methods
  for the instant and UUID classes write the `#inst`/`#uuid` the printer writes, while
  tools.reader's runs `SimpleDateFormat`, a call-time error on wasm. `print-dup` rows are never read
  by `:type`: the oracle's `print-dup` dispatches on `class` alone. A protocol interface's row
  (`%clojure-print-method-walk`) follows MultiFn's selection over the printer's own rows and the
  interface rows in store order; where preferences settle only some rows the oracle follows its
  hash order (with only `Q > IRecord` preferred one oracle program printed through `Q`, another
  threw `Multiple methods`), hence the fixed order here. Pins: clojure-spec
  `print-method-of-a-record-or-deftype-is-how-every-printer-writes-it`,
  `print-method-of-a-type-keyword-prints-its-carriers-first`,
  `print-method-of-a-protocol-interface-prints-the-types-implementing-it`;
  `ClojureInteropTest#aHostDateOrUuidIsEqualToAndComparesBesideOneMadeHere`,
  `ClojureLoweringTest#printMethodStoresUnderTheRowItsDispatchValueNames`.
- `type` (`ClojureDispatchLowering.typeForm`; `doc/en/clojure/reference/type.md`): the `:type`
  entry, else what `class` answers (inside a dispatch function the tag); malli's `-lookup` falls
  back to `(type x)` and misses with a class value like the oracle's. Pins: clojure-spec
  `type-reads-the-type-metadata-else-the-class`,
  `type-answers-a-host-class-designator-where-the-oracle-answers-a-class`,
  `a-type-designator-dispatches-and-instances-as-its-kind`, `type-of-a-pattern-is-the-pattern-class`
  (all four).
- Lookup: exact hit, else `C%H-DISPATCH` over the hierarchy (strictly most specific wins,
  `prefer-method` breaks ties), then the `Object` slot (ahead of the default, like the oracle;
  never for the nil marker), then the default. `get-method` and the fn value-method rows
  `.getMethod`/`.dispatchFn` (spec.alpha's `multi-spec`) read the registry, whose finder
  `C%H-GET-METHOD` shares the dispatcher's search, so `get-method` answers the fn a call runs; the
  finder tries the dispatcher's row before `%clojure-dispatch-value`, which alone turns a no-kind
  host class object into a keyword no row holds. Pins: clojure-spec
  `multimethods-dispatch-over-a-table`,
  `ClojureInteropTest#aMultimethodDispatchesOnAHostClassOfNoKind`.
- The global hierarchy `C%H-GLOBAL` is rebound by two-argument `derive`/`underive`; the hierarchy
  verbs as values are rest-arg lambdas over the same runtime calls (clojure-spec
  `core-functions-resolve-as-values`, `ClojureLoweringTest#hierarchyVerbsNameValues`).
- `defprotocol`: a method table plus one dispatcher per method over `C%PROTOCOL-TAG` (exact tag,
  the walked classes, the `Object` row, else a signal): the multimethod shape over runtimes all
  four backends already run, not a per-backend value model. A marker protocol is the table and
  registry alone (clojure-spec `extends-reads-the-protocol-table`).
- **A method declares one parameter vector per arity** (`ProtocolDef.arities`). The dispatcher
  stays `&rest` + `apply`, so a row holds ONE lambda: a single arity its own (a single-arity
  program lowers byte-identically), several `arityDispatch`'s dispatch on the count. An inline body
  names only counts the protocol declares, each once (the oracle's compile error); an extension
  spells `fn` clauses, a repeated method keeping the later one like the oracle's map of fns. Pins:
  clojure-spec `protocol-methods-of-several-arities-dispatch-by-count`,
  `ClojureLoweringTest#aProtocolMethodOfSeveralAritiesStoresOneLambdaApplyingTheArityOfTheCall`.
- `extend-protocol`/`extend-type`/`extend` add rows under the target's tag: the class-keyword
  kinds, `nil`, `Object`, records and deftypes, the instant and UUID classes ("Instants and
  UUIDs"), each number class ("Number classes"), any other class `instance?` resolves. Row rules:
  `doc/en/clojure/deviations.md`. The three answer nil like the oracle (clojure-spec
  `extend-forms-answer-nil`, `ClojureSessionTest#theExtendFormsEchoNil`).
- **A computed type or protocol** (a type operand that is no literal name, a protocol operand
  that is a local or a var) lowers to the run-time `%clojure-extend`, keyed exactly
  (`%clojure-extends-key`): a computed concrete class or kind reaches its row, a computed interface
  or superclass row does not, since only a literal name registers the walk (`noteWalk`; a
  deviation). An all-literal program carries no `%clojure-extend` (`ClojureLoweringTest`).
  `Class/forName` of a literal name is a designator where no host is, so wasm can extend an array
  class. Pin: clojure-spec `extend-takes-a-computed-protocol-and-a-computed-type`.
- **Protocol introspection**. A protocol var holds the oracle's protocol map
  (`doc/en/clojure/reference/defprotocol.md`), `:impls` there from the definition where the oracle
  adds it on the first extend. The registry `%clojure-protocols` maps that map and the method
  table to getters over the protocol's current tables, so a re-executed `defprotocol` and a
  computed protocol read the new tables like the calls do; a record or deftype registers its tag
  in `%clojure-protocol-type-tags` with the protocols its body names. A program naming no
  `defprotocol`, record or deftype and none of `satisfies?`/`extends?`/`extend` as values lowers
  byte-identically. Pins: clojure-spec `protocol-extenders-and-find-protocol-impl-and-method`,
  `ClojureLoweringTest#protocolIntrospectionReadsTheMethodTables`. `create-struct` refuses no keys
  at run time, so a catch takes it (clojure-spec
  `struct-definitions-create-and-read-through-an-accessor`,
  `ClojureLoweringTest#structLeftoversLowerToWorkersWithOracleArities`).
- **Walked classes**. A protocol extension target no value's tag names exactly -- a throwable, an
  interface or abstract class over core kinds (`IRef`/`IDeref`/`ARef`/`IExceptionInfo`), a stream
  or host class, `java.util.Date` (a Timestamp's tag is its own) -- stores its row under its binary
  name's keyword and makes the protocol WALK: past the exact row its dispatchers and `satisfies?`
  ask `C%PROTOCOL-SUPER` before the `Object` row, testing the walked classes in the oracle's
  `find-protocol-impl` order (`walkOrder`): classes first, then interfaces, each ahead of its
  supertypes (two `clojure.lang` ones by their kinds, user doc). So an `ex-info` takes
  `Exception` over `IExceptionInfo`, a Timestamp `Date`'s row. `extends?` reads the exact key
  only, like the oracle. Whether a protocol walks must be known before its dispatchers lower: an
  extension found later records it in `walkMisses` and `ClojureLowering.lower` starts over (the
  `with-redefs` restart), so a program extending no protocol to one lowers byte-identically. A
  session's dispatchers all walk, and a buffer adding a walked class re-emits the runtime. Pins:
  clojure-spec `a-protocol-extended-to-throwables-and-refs-walks-the-oracles-classes`,
  `ClojureLoweringTest#onlyAProtocolExtendedToAWalkedClassWalksPastAnExactMiss`,
  `#theWalkTriesSuperclassesThenInterfacesEachAheadOfItsSupertypes`,
  `ClojureSessionTest#aLaterBufferExtendingAProtocolToAWalkedClassReachesItsValues`.
- `defrecord`/`deftype`: a record carries its printed class name (`-` munged to `_`) through every
  rewrap; a parameter shadows its field. Map verbs and record literals:
  `doc/en/clojure/reference/defrecord.md`.
- `reify`: `(:C%REIFY tag methods)`. The rows are the SITE's, like the oracle's one class per form
  (`ClojureProtocolLowering.reifyForm`): a constant tag `"reify N"` (the space keeps it apart from
  any type's or other site's), rows stored once into `ctx.hoisted` ahead of the top-level datum
  (and `define`d at macro time), each a trampoline into the evaluation's own `methods` vector of
  lambdas closing over its locals. No evaluation writes a process-wide table: a tag and a row
  store per evaluation raced a Ring handler's threads and grew every table. `with-meta`'s
  `copy-list` keeps the vector; `iteration` is the same shape over one library tag. Pin:
  clojure-spec `one-reify-site-evaluated-again-keeps-each-instances-methods`.
- `instance?` (`doc/en/clojure/reference/instance-p.md`): a class is a disjunction over the bound
  value (`ClojureDispatchLowering.instanceOf`), one test per kind whose oracle class is or
  implements it (`ClojureValueClasses.Kind`, tables read off the oracle since `clojure.lang` is not
  on this class path), plus stream, throwable and host-object arms. The host arm belongs to
  `ClojureArms.Family.HOST`, so a program naming no `java:` operator sheds it. The value is bound
  to a temp unless every arm can read it as is: a family test needs a variable or literal
  argument, else `(instance? Boolean (f))` evaluates `(f)` twice. An unresolvable name is
  `unknown name: X`, `clojure.lang.PersistentQueue` too, where the oracle answers false. Checked
  against the oracle: 59 classes x 29 values on all four backends, x 5 host objects on the
  interpreter and the JVM, identical but the user doc's deviations.
  `(instance? Number x)` and `examples/clojure/demo.clj` lower byte-identically. Pins: clojure-spec
  `instance-of-an-interface-or-a-host-class-tests-the-classes-of-each-kind`,
  `ClojureInteropTest#instanceOfAHostClassTestsTheValuesKindAndTheHostObjectsClass`,
  `ClojureArmsTest#theHostFamilyFoldsInstanceOfAHostClassInAProgramNamingNoJavaOperator`.
  A protocol's interface (`ClojureProtocolLowering.interfaceName`, munged like the oracle's) is
  tested by `%clojure-implements-p`: a reify by its site's row in the protocol's body table, a
  record or deftype through the tag registry read when the test runs, so a type defined after the
  site (a later file, REPL buffer or `eval`) is an instance like the oracle's.
  The registry is keyed by the simple name, like the protocol rows: two types of one name in two
  namespaces share an entry. An extension target is no instance (its rows share the tag with the
  body's), so the test reads the protocols the body names; a body naming a protocol without
  methods stores an empty row. Size, measured 2026-10-04 before the registry read (wasm P1 /
  `--optimize=size` / component / JVM class): two `(instance? user.P x)` sites +694 / +622 / +701
  / +1015 bytes against one `(instance? R x)`, an empty body group +498 / +498 / +502 / +118; a
  protocol program without either byte-identical. Pins: clojure-spec
  `instance-of-a-protocol-interface-tests-the-body-implementations`,
  `ClojureProjectNamespacesTest#aReifyBelowItsProtocolsExtensionsInsideALetfnStoresIntoItsTable`
  (a later file's deftype), `ClojureSessionTest#aTypeDefinedInOneBufferShowsJavaItsFaceInTheNext`
  (a later buffer and an `eval`).
  **The interface as a class value** (`ClojureProtocolLowering.interfaceValue`; what reads it:
  `doc/en/clojure/reference/defprotocol.md`) is a `(:C%CLASS name)` designator registered in
  `%clojure-protocol-interfaces`, its readers arms of `ClojureArms.Family.PROTOCOL_INTERFACE`, made
  by the construction. `:on`/`:on-interface` stay class keywords, so `(= (:on-interface P) user.P)`
  is false (the oracle's true): the protocol map is a quoted constant of every `defprotocol`, and a
  designator there would make every protocol program carry the designator family.
  A class VALUE (no symbol, a local's or var's, or a symbol that does not read like a class:
  `ClojureDispatchLowering.namesClass`) lowers to `%clojure-class-instance-p`, referenced only from
  such a site, so a program with none is unchanged. Pins: clojure-spec
  `instance-of-a-class-value-tests-what-class-answers`,
  `ClojureInteropTest#instanceOfAHostClassObjectAsksTheHost`,
  `ClojureLoweringTest#instanceOfAClassValueTestsTheValueAgainstItAtRunTime`.
- **Class chains**. A class spelling as a dispatch value or a hierarchy verb's argument lowers
  (`classKey`) to the keyword `class` answers for its values: a core class its kind, a record its
  tag, a throwable, a stream class, an interface among their supers or `Object` its own name. Like
  the oracle's, `descendants` of a class refuses and the three readers answer nil for nothing
  (`not-empty`). Here `C%H-SUPERS` holds class rows `(name base ...)` from `ClojureClassBases`
  (tables pinned to reflection by `ClojureClassBasesTest`), read by `clojure.lisp`'s "Class
  chains". Every class row `isa?` `Object`, an interface too (`isAssignableFrom`), while an
  interface's supers stop short of it. A record, deftype or `reify` is a row `(name . t)`: its host
  classes are no one value here, so its supers are `Object` alone (the documented deviation; a
  protocol interface its body implements answers ahead of the rows, "The interface as a class
  value"). A core kind's row holds the supers of its values (`ClojureValueClasses.kindSupers`, read
  off the oracle on JDK 25). Any class of the oracle's jar (`ClojureLangJar`: 715 `clojure.lang`
  classes, nested ones included) reads in value position as a keyword no value answers, so methods
  on it load and never match, while a typo stays `unknown name` (pin: clojure-spec
  `create-with-check-builds-checking-duplicate-keys`). Rows are recorded per class a program
  names plus the classes a value may have unnamed (`ClojureClassBases.implicitClasses`);
  `Object` or a hierarchy reader records them all, since a class keyword can always reach the
  readers. `usedClassChains` gates everything and the readers set it too, so a program spelling no
  class still walks the rows. A session sets the rows ahead of the first buffer spelling one and
  appends later buffers'
  (`ClojureSessionTest#aBufferSpelling*`, `#aSessionReadsAClassKeywordsSupersInALaterBuffer`). A
  class keyword with no row asks the host arm `%clojure-host-class-rows`, so a host throwable no
  construction names walks too on the interpreter and the JVM
  (`ClojureInteropTest#theClassOfAHostThrowableNoConstructionNamesTakesItsSupersFromTheHost`),
  reflecting on every miss, uncached: only a dotted name of a `java:` program pays it.
  `descendants` of a class keyword is a `C%E-NEW` exception, so the walk carries the refusal and
  the exception runtime only where `descendants` is read (or in a session).
- **Host classes**. `class` of a host non-throwable (interpreter, JVM) is its host class OBJECT.
  A program using a hierarchy and naming a `java:` operator (a session cumulatively) sets
  `hostClassWalk`, which swaps the walk's arms for `clojure.lisp`'s "Host classes". A class object
  stands under three keys: itself, its name's keyword, and the kind keyword of its simple name
  (`java.util.List` spells `:list`, so `ArrayList` reaches it -- and `IPersistentList`, also
  `:list`: the documented deviation). A non-chained spelling (`java.util.AbstractList`) is a class
  object, in a dispatch value too: the exact lookup hits it (`equal` is identity on two class
  objects), the miss search walks its supers, so `AbstractList` beats `java.util.List` for an
  `ArrayList`. Its `defmethod` makes the program a `java:` program (wasm: a call-time
  `JAVA:STATIC` error). Rejected: storing under the class keyword (`:java.io.File`) -- no row
  relates two such keywords, so `AbstractList` and `AbstractCollection` methods tie where the
  oracle picks the subclass; lowering every class spelling to a keyword -- `parents` would print
  `:java.util.AbstractList` and not `=` the class literal, where class objects match the oracle;
  registering the chain at run time where
  `class` reads a condition -- every program calling `class` on one would carry the table and the
  write, while the rows are known at lowering time for every class but a host-returned
  throwable's.
- **Class values in the readers**. In a program that can hold a class value
  (`ClojureLowering.classObjects`: a class literal, a `class` call, a `Class/forName`),
  `parents`/`ancestors`/`descendants` answer each class keyword of `C%H-CLASS-OBJECTS` -- the
  emitted rows' classes, names and bases, but a record's -- as its class value
  (`%clojure-class-value`, "Class values"). So `(contains? (ancestors Long) Number)` holds and
  the set prints like the oracle's. The list asks the host nothing: a row's class is one of the
  tables. Where the walk is not the host's, `isa?` and the readers read a class value of such a
  class as its keyword first (`%clojure-class-designator-key`), so a member walks on. A program
  holding none keeps the keywords. A kind's
  row leaves out its values' own class when that class spells the kind (`kindSupers`: `String`
  is `:string`), so `(parents String)` is the oracle's six. Size, measured 2026-11-25 (wasm P1 /
  `--optimize=size` / component / JVM class): `examples/clojure/demo.clj`, a program of class
  literals and no hierarchy, and a `class` multimethod byte-identical; a program with a
  hierarchy reader and no class literal -200 / -200 / -200 / -142 (the kinds' own classes);
  `(contains? (ancestors Long) Number)` +1705 / +1432 / +1706 / +2174.
- `.getClass` of a receiver of unknown class (`getClassForm`) answers a condition's or stream's
  class ahead of the host call, both arms shed where no condition or stream can reach it.
- Size, measured 2026-10-04 (wasm P1 / `--optimize=size` / component / JVM class): programs
  without `java:` stay byte-identical or move by bytes (`examples/clojure/demo.clj`, the keyword,
  `Exception`, `java.io.Writer` and `class` multimethods, a catch reading `class`); a class
  multimethod spelled with the class object costs a little over the keyword spelling; a keyword
  `parents`/`ancestors`/`descendants` program pays the `not-empty` fix; `java:` programs pay the
  hierarchy rows. The walks are hand-written: `assoc`/`member` `:test #'equal` and
  `nreverse` in the library cost the stream program dearly in wasm.
- Pins: clojure-spec `a-multimethod-dispatches-on-an-exception-class-by-inheritance`,
  `a-multimethod-dispatches-on-a-stream-class-by-inheritance`,
  `parents-and-ancestors-of-a-class-add-its-java-supers`;
  `ClojureInteropTest#aHostClassObjectWalksJavaInheritanceInAHierarchy`,
  `ClojureSessionTest#aSessionWalksAHostClassObjectOnceABufferNamesTheHost`,
  `ClojureLoweringTest#theClassWalkTakesTheDescendantsRefusalAndTheHostOnlyWhereTheProgramNeedsThem`.

## Host interfaces

**A `reify`, `deftype` or `defrecord` body implements the `clojure.lang` interfaces whose
methods a core verb reads, each group behind an arm family that only the store of its row
makes, so a program naming none compiles as before** (`ClojureInterfaces`; checked against the
oracle; the user-facing table: `doc/en/clojure/reference/reify.md`).
- Which, by a census of 85 libraries (those whose bodies name each): `Object` 21, `IFn` 14,
  `ILookup` 13, `IObj` 12, `IDeref` 11, `Counted` 11, `Seqable` 10, `Indexed` 10, then
  `IPersistentCollection`, `IHashEq`, `Associative` 9 each ... ("Collection interfaces") and
  `IReduceInit` 5. `java.util.Comparator` is in the
  `Comparable` row's family (`sort`, `sort-by`, the sorted `-by` constructors). Any other interface
  of the jar (`CLOJURE_LANG`) is refused by name, a class or an unresolved name in the oracle's
  words; any other public host interface takes the row below.
- **Host interfaces no core verb reads** (core.async's `ThreadFactory`, `Lock`, `Delayed`, g87;
  tools.reader's `Closeable`): `ClojureInterfaces.reflected` reads the interface's own methods,
  abstract and default, and its supers from the host's classes into `Family.HOST_INTERFACE`, a row
  under the binary name like a `definterface`'s. `instance?` of a host interface tests the row,
  and EVERY instance call of a method no core verb answers asks
  `(%clojure-host-interface-method-p recv "m/N")`, by name and count, so a site needs no
  knowledge of the type, which a file lowered later may define (channels.clj's `(.lock handler)`
  reaches core.async.clj's `fn-handler`); a program storing no such row folds both arms. The store
  writes a key per abstract count and per default count the body defines, so a wrong count keeps
  the oracle's `No matching method` refusal instead of the lambda's arity error. Java sees the
  value through its face ("Java faces"): an executor calls a `ThreadFactory` reify's `newThread`, a
  `DelayQueue` orders `Delayed` deftypes. Pins: clojure-spec
  `a-definterface-method-on-a-reify-refuses-foreign-receivers`,
  `ClojureArmsTest#theDefinedInterfaceFamilyIsMadeByTheStoreOfItsRow`,
  `ClojureInteropTest#aDeftypeOrReifyReachesJavaAsAnImplementationOfItsInterfaces`; the store and
  folding tests are in the last Pins below.
- **The primitive `IFn` interfaces** (datascript's comparators): `IFn$OOL` and the jar's other 357
  (`ClojureLangJar.isPrimitiveFn`) load no class, so `ClojureInterfaces.primitiveFn` synthesizes
  one `invokePrim` at the letters' count as a host interface no core verb reads; a call of the
  value goes through its `IFn`'s `invoke`, as the oracle's does. Pin: clojure-spec
  `an-ifn-type-is-called-through-its-invoke-and-applied-through-its-apply-to`.
- **The reference interfaces** (`IReference`, `IRef`, `IAtom`, `IAtom2`, read off the oracle by
  reflection; extend-clj's `deftype-atom`, datascript's `Conn`): where the value is no atom,
  `swap!`, `reset!`, `compare-and-set!`, `swap-vals!` and `reset-vals!` (calls and values), the
  watch and validator workers and `alter-meta!`/`reset-meta!` call its method (an arm at each
  worker's head; `ClojureStateLowering.withAtom`), the body's lowered arguments copied, no shared
  structure. `IBlockingDeref` joins `DEREFABLE` for the three-argument `deref`. A method
  answering the wrong type is not cast (deviation, user doc). Pins: clojure-spec
  `a-type-implementing-the-reference-interfaces-answers-the-atom-verbs`; extend-clj's
  `deftype-atom` verbatim ("Library probes").
- **The interfaces the type special form names** (`IType`, `IRecord`, `IKeywordLookup`,
  `ILookupThunk`; `Family.MARKER`): `instance?` and instance calls read them; a type implementing
  `IRecord` (a `deftype*` of a record's expansion, "Macros") is `record?` (an alias of the marker
  family, so every other program's `record?` is unchanged) and prints as a record. A `defrecord`
  naming `IRecord`/`IKeywordLookup` or a `deftype` naming `IType` is the oracle's `Duplicate
  interface name`.
- Parse (`ClojureProtocolLowering.typeBody`): a method is matched by name and count across every
  group, like the oracle's class, so a `toString` under a protocol group overrides `Object`'s. A
  syntax-quoted body's qualified method names are read past the `/`, as the oracle strips them
  (core.cache's `defcache`). An interface method takes fixed parameters (the oracle reads `&` as a
  parameter's name; refused here). An interface the form's class implements itself stores no row
  (the `Duplicate` refusals: `doc/en/clojure/reference/defrecord.md`); a method of those
  interfaces the class leaves to the interface is refused by name, since the record's own verbs
  answer those interfaces and no row would hold it.
- Rows (`ClojureInterfaces.rowForms`): one store per family under the type's tag into
  `%clojure-interface-rows`. A method declared at several counts is one lambda dispatching on the
  count; a declared method the body leaves out is the oracle's `AbstractMethodError`. A deftype's or
  record's methods see its fields like inline protocol methods.
- Families (`ClojureArms.Family`), what a body implementing each reaches:
  - REDUCE_INTERFACE (`IReduceInit`, `IReduce`, `IKVReduce`; its store makes REDUCIBLE too):
    `reduce` with an init takes `IReduceInit` ahead of a `CollReduce` row, without one `IReduce`,
    else the `CollReduce` row, else the oracle's `ClassCastException`; `kv-reduce` takes an
    `IKVReduce` protocol row ahead of the interface; `vec` and `set` walk its view.
  - SEQABLE: `seq` (an answer that is no seq is the oracle's `ClassCastException`), `seqable?`, and
    the lazy verbs (`map`, `filter`, `for`, `concat` ...), which stay lazy over an endless
    Seqable. Their lazy arms step it through `%clojure-seq` and never hold it as a seq's tail:
    `%clojure-cons` and `%clojure-sequence` keep `%clojure-lazy-p`, since a typed value as a cdr
    would be walked as list structure.
  - COUNTED: `count`, `empty?` (the oracle's asks `counted?` first), `counted?`.
  - INDEXED: `nth` of two and three arguments, destructuring (the oracle's `(nth v i nil)`),
    `indexed?`.
  - LOOKUP: `get` (`valAt` at two or three, as the call gives a default), a keyword's or symbol's
    call.
  - INVOKABLE: a call and every function argument (`%clojure-as-fn`), `ifn?`, `apply` through
    `applyTo`; `clojure.lang.AFn/applyToHelper` lowers to the dispatcher over the argument seq.
  - DEREFABLE: `deref`. META_INTERFACE: `meta`, `with-meta`.
  - OBJECT_METHODS: `str`, printing (`#object[class "text"]`, after the record clause, so a record
    still prints its literal), `=` ahead of identity (the value itself is equal, a collection on
    the right is not: the oracle asks the collection; else `equals` by truth).
  - CHAR_SEQUENCE (instaparse's `Segment`), in the oracle's order among the other interfaces
    (`countFrom`, `seqFrom`, three-argument `nth`): `count`, `seq` (strict, through `charAt`),
    `nth` (two arguments share the shape, so past the end is nil where the oracle calls `charAt`),
    `seqable?`, the instance calls (`isEmpty`, the one portable default, stored as a zero length
    where the body leaves it out), `re-matcher`/`re-find` (as far as a match needs: the lazy
    matcher, "Regex"), and `re-seq`, `re-matches`, `clojure.string` and String's
    `.contains`/`.replace` through the view `%clojure-char-sequence-text`, read once per call.
    The view is not for `re-matcher`/`re-find`: instaparse's per-terminal `(re-matcher re
    (sub-sequence text i))` read the rest of the text each time, 35 s of the interpreter's 76 s on
    `instaparse.abnf`'s grammar, 29% of the JVM's 4.5 s. Not read through it, as in the oracle:
    `subs`, `re-pattern`, `vec`, `get`, `=`, `compare`, `string?`, `counted?`, `sequential?`. A
    face ("Java faces") hands Java a `CharSequence` with no further code.
  `instance?` of a supported interface adds its test (never over a literal or quoted value, so no
  binding changes), and an instance call of a declared method calls the row's method
  (`ClojureInterfaces.instanceTest`).
- `iteration` (`ClojureCoreLowering`, `%clojure-iteration`): the oracle's reify in `clojure.lisp`,
  a fresh `:C%REIFY` tag whose `Seqable` and `IReduceInit` rows the worker stores through the
  families' stores, so it produces SEQABLE, REDUCE_INTERFACE and REDUCIBLE. `seq` steps from
  `initk` on every call; each element's `somef`/`vf`/`kf` run when it is built, the next `step`
  when the lazy rest is realized; `reduce` stops at `reduced` before `kf`. Pin: clojure-spec
  `iteration-seqs-lazily-and-reduces-through-its-step`.
- Deviations: `doc/en/clojure/deviations.md` (the `#object` without identity hash or reify
  number, typed map keys compared as the oracle's hash map does ("Structural keys"), `sort` and
  `distinct` of a `Seqable`-only type).
- Pins: clojure-spec `a-type-implementing-ireduceinit-ireduce-or-ikvreduce-reduces-through-it`,
  `a-seqable-type-seqs-through-its-seq`,
  `a-counted-or-indexed-type-counts-and-indexes-through-its-methods`,
  `an-ilookup-type-reads-through-the-valat-of-the-calls-count`,
  `an-ifn-type-is-called-through-its-invoke-and-applied-through-its-apply-to`,
  `an-ideref-type-derefs-through-its-deref`,
  `a-type-overriding-object-answers-str-and-equals-through-it`,
  `a-deftype-implementing-iobj-carries-its-own-metadata`,
  `a-char-sequence-type-counts-seqs-matches-and-reads-as-its-characters` (all four backends, the
  oracle's but the `#object` line);
  `ClojureLoweringTest#aBodyImplementingAnInterfaceStoresItsRowThroughTheFamilyOfEach` (the stores
  and every refusal), `ClojureArmsTest#anInterfaceFamilyIsMadeByTheStoreOfARowOfItsInterfaces`,
  `ClojureLibraryTest#aProgramStoringNoInterfaceRowSplicesTheVerbsWithoutTheirInterfaceArms`
  (`iteration` too), `#aProgramStoringNoCharSequenceRowReadsItsStringsWithoutTheView`,
  `ClojureInteropTest#aCharSequenceTypeReachesJavaAsACharSequence`.

## Defined interfaces

**A `definterface` defines an interface with no host class: the name is bound to
the binary name, and a `deftype` or `reify` body naming it stores its methods in
the type's row under that name, which `instance?` tests and a `.method` call
dispatches on** (`ClojureProtocolLowering.parseInterface`). The rows join the "Host
interfaces" machinery under the interface's own name.
- The pre-scan registers it with the var, like a protocol, and an ordinary `def` binds the
  name, so `resolve`, redefinition and a later session buffer see it; the short name, the
  binary name (`my.p.I`) and an imported short name resolve alike (`definedInterfaceOf`). A
  repeated signature is the oracle's `ClassFormatError` text, its descriptors included.
- `instance?` reads the row ahead of the var's own reading, so the var holding the name still
  tests the interface; a local of the name is evaluated, like the oracle's class position. A
  `.method` call the interface declares at the count dispatches on the row ahead of the
  refusal (`ClojureValueMethodLowering`, both arms). Any other receiver (`No matching field
  found` with no argument, else `No matching method ...`), an omitted method
  (`AbstractMethodError`) and an extension (`interface user.IA is not a protocol`) get the
  oracle's words; `supers`/`bases` answer nil, like the oracle's. User doc:
  `doc/en/clojure/deviations.md`, `doc/en/clojure/semantics.md` (`gen-class`).
- Pins: clojure-spec `a-definterface-defines-methods-a-deftype-implements-and-calls-dispatch`,
  `a-definterface-method-on-a-reify-refuses-foreign-receivers`,
  `a-definterface-resolves-by-its-binary-name-and-an-import` (four backends, the oracle's lines
  but two commented ones);
  `ClojureLoweringTest#aDefinterfaceDefinesAnInterfaceADefTypeImplementsThroughItsOwnRow`
  (stores, `instance?` and call shapes, every refusal);
  `ClojureArmsTest#theDefinedInterfaceFamilyIsMadeByTheStoreOfItsRow`.

## Collection interfaces

**A body implementing a collection interface is a collection to the core verbs, each group of
interfaces behind an arm family its row's store makes (`ClojureArms`, `COLLECTION` through
`MARKER`), so a program storing no such row lowers without their arms** (`ClojureInterfaces`'
`collectionTable`/`javaTable`, methods and supers read off the oracle's jar).
- What a verb asks, in the oracle's order (RT, core; a list is asked left to right):
  `conj`/`into`/`merge` `IPersistentCollection.cons`; `assoc` (`update`, `assoc-in`)
  `Associative.assoc`; `dissoc` `IPersistentMap.without`; `disj` `IPersistentSet.disjoin`;
  `contains?` `Associative.containsKey`, `IPersistentSet.contains`, `Map.containsKey`,
  `Set.contains`; `get` `ILookup.valAt`, `Map.get`, `IPersistentSet.get`; `find`
  `Associative.entryAt`, `Map`'s; `count` `Counted.count`, an `IPersistentCollection`'s seq
  walked, a `Collection`'s or `Map`'s `size`; `seq` `Seqable.seq`, `Iterable.iterator`,
  `Map.entrySet`; `=` `IPersistentCollection.equiv` on either side (a core collection on the
  left reads a vector type by `count`/`nth`, a sequential by its seq, a map type as a
  `java.util.Map` only with `MapEquivalence`, a set type as a `java.util.Set`); `reduce`
  `IReduceInit`, an `Iterable`'s iterator (`iter-reduce`, stopping at `reduced`), the seq;
  `peek`/`pop` `IPersistentStack`; `rseq` `Reversible`; `realized?` `IPending`;
  `subseq`/`rsubseq` `Sorted`'s `seqFrom`/`seq`/`comparator`/`entryKey`; `compare`, `sort` and
  a sorted collection's default order `Comparable.compareTo`, and `sort`, `sort-by` and the
  sorted `-by` constructors a `Comparator` type's `compare`; `empty` its `empty`;
  `keys`/`vals`/`reduce-kv`/`select-keys` a map type's entries; the printer by kind
  (`%clojure-typed-print-kind`; a `java.util` one under `pr`).
- The strip goes in enum order, and the predicates' helpers (`coll?`, `map?`, `seq?` ...) are
  `%clojure-is-*-type` aliases, so these families stand ahead of `SORTED`, whose aliases they
  rename into. A strip fold position is a `cond` clause test, an `if` test or an `or`
  disjunct, never inside an `and` (`%clojure-counts-agree` nests `if`s for it).
- The `clojure.lang` static methods the run-time library answers on every backend are one
  table, `ClojureLangClasses.staticCall`, asked ahead of every host static. `RT/nextID`
  promises only uniqueness. `Compiler/CHAR_MAP` is a constant in the oracle's walk order, so a
  macro answering it (schema's `char-map`) expands to the literal. `RT/checkSpecAsserts` is a
  global, `ClojureLangClasses.RUNTIME_STATICS`. Pins: clojure-spec
  `create-with-check-builds-checking-duplicate-keys`,
  `rt-check-spec-asserts-is-a-boolean-static-a-program-sets`, `munge-and-namespace-munge`
  (four backends, the oracle's).
- A receiver known to be a `java.math.BigInteger` counts as unknown, so `negate`/`bitLength`
  reach the value arms (`ClojureValueMethodLowering.bigIntegerMethod`): a BigInteger is an
  integer at the `java:` boundary. A dotted class in call position
  (`ClojureValueClasses.isCalledClass`) is the oracle's call-time `ClassCastException`,
  arguments unevaluated (the oracle casts first).
- `clojure.lang.LineNumberingPushbackReader.` (aero's `read-config`) pulls ONE character per
  piece from the inner reader, so its counters see exactly what left it, which is what the
  oracle's `LineNumberReader` counts (it read and unread a peeked delimiter).
  Line: LF, CR, CR LF once, the end once after a non-terminator (not of an empty stream):
  JDK 25's rule, measured. Column: the pulled count minus the character in hand. Its state
  entry is never removed: a closed reader must still answer `%clojure-open-reader`'s
  IOException arm. Its own family, `LINE_READER`, because the text pull stream is a Gray
  class. Pins: clojure-spec `a-line-numbering-reader-counts-what-left-the-reader-it-reads`
  (four backends, the oracle's),
  `ClojureArmsTest#theReaderFamiliesAreMadeByTheirReadersAndTakeTheirDefinitionsWithTheirArms`.
- `java.util` default methods are declared (`HostInterface.defaults`) but stored only when the
  body defines them; a call of one the row lacks is refused by name
  (`%clojure-default-method`): its body is Java.
- An extension to an interface spelled like the one core kind whose values implement it
  (`IPersistentVector`, `IPersistentMap`) keeps the kind key and also stores its lambda under
  the binary name, which a non-walking dispatcher asks behind guards; a guard the dispatchers
  lacked restarts the lowering (`ClojureLowering.guardMisses`). One spelled like a kind whose
  interface other kinds' values implement too (`Sequential`, `java.util.List`, `IFn` ...:
  `reachesOtherKinds`) is a walked class alone, so the walk picks the most specific
  interface, like the oracle's `pref` (`ISeq` ahead of `IPersistentCollection` for a list),
  where a kind key would answer first. Any other interface (`Counted`, `IDeref`) is a walked
  class.
- Deviations: `doc/en/clojure/reference/reify.md`, `doc/en/clojure/deviations.md`.
- Pins: clojure-spec `a-collection-type-conjs-empties-counts-and-compares-through-its-methods`,
  `a-map-type-assocs-dissocs-reads-and-prints-as-a-map`,
  `a-set-type-disjs-contains-and-prints-as-a-set`,
  `a-vector-type-indexes-stacks-reverses-and-prints-as-a-vector`,
  `a-seq-type-walks-through-its-first-and-next`,
  `an-iterable-type-seqs-and-reduces-through-its-iterator`,
  `pending-comparable-and-sorted-types-answer-through-their-methods`,
  `a-protocol-extended-to-a-collection-interface-reaches-a-type-implementing-it`,
  `a-protocol-extended-to-an-interface-reaches-every-value-implementing-it` (four backends,
  the oracle's lines);
  `ClojureLoweringTest#aBodyImplementingACollectionInterfaceStoresTheRowsOfItsWholeClosure`
  (stores, refusals),
  `#anExtensionToAnInterfaceKeyedByACoreKindReachesATypedValueBehindItsTest`,
  `#anExtensionToAnInterfaceOtherKindsImplementTooIsAWalkedClass`;
  `ClojureArmsTest#aCollectionInterfaceFamilyIsMadeByTheStoreOfARowOfItsInterfaces`;
  `ClojureLibraryTest#aProgramStoringNoCollectionInterfaceRowSplicesTheVerbsWithoutTheirArms`;
  `ClojureInteropTest#aHostIteratorStepsThroughIteratorSeqAndAnIterableTypesVerbs`.

## Java faces

**A deftype or reify whose body implements a Java interface of the table ("Host interfaces":
`Runnable`, `Callable`, `Comparable`, `java.util.Comparator`, `Iterable`, `Iterator`, the
`java.util` collections, `Serializable` -- never a `clojure.lang` one, which no Java code here
is written against) or overrides an `Object` method crosses into Java as its FACE: an object
implementing those interfaces that stands for the value** (`clojure/ClojureJavaFaces`; its
`java:reify` with `:value`: `.kb/java-interop.md`).
- The maker is built when the type is registered, so a program it stays out of keeps its
  temporaries. It names a method only where the body defines it AT ITS PARAMETER COUNT (the
  target counted) or it is abstract with a row entry; a default the body leaves out keeps
  Java's body even beside an overload of its name the body defines (a `Map` type's
  `remove(k)` leaves `remove(k, v)` Java's).
- Every face is a `Comparable`, so a TreeSet of a type implementing none says the oracle's
  `ClassCastException` words on both `java:` backends, not a JDK message naming a `Proxy` or
  `$Reify` class; `instance? Comparable` in Java is true where the oracle's is false (a
  user-doc deviation).
- A file's faces are wrapped in only when `hostTarget && namedHost`: a program naming no
  `java:` operator, and every wasm one, lowers without them. A reify registers per
  evaluation, like its rows. Gap: a CL program loading two `.clj` files lowered apart gives a
  type of the file naming no `java:` operator no face in the other.
- Records: one whose body names a Java interface (a `clojure.lang` one with a Java super
  counts: `IFn`'s `Runnable`) gets a face over `java.util.Map` first, then its interfaces,
  `Comparable`; one naming none (an `Object` `toString` alone included, which the view's `str`
  text already reads) crosses as its `:map` view, whose `%clojure-host-member` clause stands
  behind the face clause. Each abstract `Map` slot the body does not define at its count, and
  `equals`/`hashCode`/`toString`, calls the view through a `java:call` typed `java.util.Map`
  (`viewCall`), so it resolves on the JVM and Java's `Map` defaults keep their bodies; equality
  is the view's `AbstractMap` rule, so a face and a plain map of its entries are equal in a
  HashSet both ways.
- A Clojure seq iterator passed straight to a member (not from a face's `iterator`) is an
  identity handle.
- The pins' rows are the oracle's on the interpreter and the JVM but the user doc's deviations
  (an IFn type takes an unhinted `ExecutorService.submit`'s `submit(Callable)`; the oracle
  refuses the ambiguity).
- Pins: `ClojureInteropTest#aDeftypeOrReifyReachesJavaAsAnImplementationOfItsInterfaces`,
  `#aRecordReachesJavaAsAMapImplementingItsBodysInterfaces`,
  `#aTypeImplementingNoComparableRefusesATreeSetInTheOraclesWords`;
  `ClojureLoweringTest#aTypeShowsJavaItsFaceOnlyWhereTheProgramNamesTheHost`;
  `ClojureSessionTest#aTypeDefinedInOneBufferShowsJavaItsFaceInTheNext`;
  `ClojureArmsTest#anInterfaceFamilyIsMadeByTheStoreOfARowOfItsInterfaces` (the JAVA_FACE case);
  `ClojureLibraryTest#aProgramRegisteringNoFaceHandsJavaItsValuesWithoutTheFaceArm`.

## Host arms where the host is not

**A wasm compile warns of a `java:` operator only where the program's own call needs the
host; the host ARMS -- what answers a host object no wasm program can hold -- never reach
the backend.** Muting library-spliced forms' warnings instead would hide a real call too.
Every piece is keyed on `hostTarget` (`Clojure.read`, `ClojureLibrary.splice`):
- `ClojureArms.needsHost`: `HOST` (host objects) joins `HOST_EXCEPTION`, so its arms strip on
  wasm whatever `java:` the program names (a `java:` call makes no host object there).
- A wasm program splices `ClojureLibrary.formsWithoutHost`, the host arms' stand-ins, whatever
  it names.
- An instance call on a receiver of no known class (`ClojureInteropLowering.hostlessCall`)
  keeps its `java:call` arm only when a class such a receiver may be an object of there
  (`HOSTLESS_RECEIVERS`; `String`/`Writer`/`Reader` past their own arms) has the method at the
  count, so `(.byteValue x)` still warns; else the oracle's refusal, or its
  `ClassCastException` for a hinted receiver (`castClass`), behind a
  `%clojure-host-object-p` test. That test is a `HOST` arm: stripped from the program (bytes
  unchanged), kept by the macro-time image, which does make host objects, so a macro body's
  host member answers there as on the JVM ("Macros"). The string arm is built ahead of the
  host arm on wasm only, keeping the host's temp numbering.
- The hierarchy's host class walk needs `hostTarget` (`ClojureLowering.noteHost`).
- A Clojure `if` whose lowered test is the literal `true`, `nil` or `false` keeps only its
  branch (both are still lowered, so a dead branch's refusals stand). Bundled sources ask the
  target through it (`rontolisp.internal.xml/host?`, `hosted?`): `clojure.xml`'s SAX parser
  and `host-events` throw `UnsupportedOperationException` on wasm instead of compiling a
  `java:` form.
- Pins: `ClojureWasmHostArmsTest` (library host forms and two examples, both targets, the
  `(.byteValue x)` warning),
  `ClojureWasmInteropRefusalTest#aMemberNoValueHereHasIsTheOraclesRefusalOnBothBackends`.

## Host classes

**What a host form lowers to is the same on the native binary as on the jar: the lowering
asks `ClojureJavaClasses`, never `Class`.** A host form's meaning is the named class's
(`(Class/m)` method or field, a plain throwable's constructors, a face's interface methods,
whether a member may answer a `byte[]`, a catch's chain), so the lowering asks
`ClojureHostClasses.find`, the classes of the lowering in progress (`ClojureFiles.javaClasses`,
a ThreadLocal); only `ClojureReflectedClasses` touches reflection.

- JVM: reflection over the program's class loader.
- Native image: class files (`cli/JavaClassPath.classFiles()`, `JvmClassFileLookup.forJdk`) of
  the found JDK's OWN release -- the API the JVM running the jar reflects, never
  `--java-release` -- and the program's class path, remade when a `deps.edn` jar joins. No
  JDK found: the image's own reflection (`ReflectiveJavaClasses`).
- Why: the image holds only what its build registered (`Exception`'s `(String)` constructor
  alone, no `List`/`Comparable`/`Thread` method) and `Class.forName` finds only its classes.
  Asking it, the binary lowered `(Exception. "outer" (ex-info ...))` to `java:new` (undefined
  on wasm), `(Thread/currentThread)` to `java:field`, a `Comparable` face without `compareTo`,
  and refused `clojure-spec.yaml` (GraalVM 25.0.4, `--dump-ir`). A type the
  reachability metadata registers answers every member (probe image).
- The class files answer as reflection: `getMethods()` = `publicMethods()` (bridges included),
  the superclass, interface and public-field questions alike
  (`JvmClassFileLookupTest#theLoweringsQuestionsHaveReflectionsAnswers`). A class's "public" is
  its class file's (a protected member class is), on both.
- Per lowering, never a static cross-program cache (it would hand one host's answers to
  another): `ClojureTimeValueLowering`'s member scan. `ClojureIoLowering.declaredByAClass` asks
  the io values' `java.`/`javax.` API types: `sun.nio.cs.UTF_8` and the fetched streams' impl
  classes are not in `ct.sym`, and their callable methods are their API supertypes'.
- Open: `Charset/forName` of a non-standard literal (`"windows-1252"`) lowers to a refusal on
  the binary: the image holds the standard charsets, a JVM all of them.
- No JDK's class files (the binary with none found, the browser playground): the image's
  reflection answers, and its build registers the JDK classes the front end names in its own
  code -- `ClojureThrowables.PARENTS`, `ClojureInteropLowering.HOSTLESS_RECEIVERS`,
  `ClojureValueMethodLowering`'s host kinds, `ClojureTimeValueLowering`'s kinds,
  `ClojureIoLowering`'s API types, `Object`, `Throwable` -- as `reflection` types in
  `reachability-metadata.json`, so each answers every member query as a JVM does;
  `PARENTS`' `clojure.lang` throwables stay out (no host here holds them). A class outside the
  list stays partial without a JDK, and the binary warns when it finds none
  (`JavaClassPath.ClassFiles`). The list stays at what the front end names, bare `{"type"}`
  entries, weighed for the web image the playground downloads.
- Macro-time evaluation runs on the image's reflection even WITH a JDK found: the lowering
  reads class files, but a macro body runs in the interpreter. A bare `{"type"}` entry answers
  the queries but refuses the call (`MissingReflectionRegistrationError`), so the members
  macro-time evaluation invokes are registered too
  (`ClojureInteropLowering.HOST_STATIC_CLASSES`). Recorded over the whole corpus, only those
  run, and no macro body builds a proxy or asks a class outside a lowering. The run members
  left the binary byte-identical (measured 2026-10-14, GraalVM 25.0.4, Linux x86-64).
- `parseInterface` keeps declaration order: `Map.copyOf`'s iteration order is unspecified and
  differs between the JVM and the binary (observed reversed).
- Pins: `ClojureClassFilesLoweringTest` (reflection vs class files over
  `e2e/ClojureLoweringCorpus`: the spec program, the manifest's Clojure examples, a host-form
  program, a no-JDK host-form program), `ClojureHostImageTypesTest` (the registered list
  against the constants above), `ClojureNativeLoweringE2eTest` (the binary's `--dump-ir` vs
  this JVM's, CI's native-image-verify job; the no-JDK program again with the binary seeing no
  JDK).

## Java interop

The `java:` surface (`.kb/java-interop.md`); interpreter and JVM only -- wasm compiles `java:`
to a call-time error (`ClojureWasmInteropRefusalTest`), a warning only for a call the host
alone answers ("Host arms where the host is not"). User-facing behavior and deviations ("user
doc"): `doc/en/clojure/reference/interop.md`, `doc/en/clojure/reference/dot-name.md`,
`doc/en/clojure/deviations.md`. The verbs' host arms below (seq, map, printer, `deref`, future
verbs) sit behind a HOST-family test the strip folds: a program naming no `java:` operator is
byte-identical (wasm P1, `--optimize=size`, component, JVM class with its runtime classes).

- Classes resolve dotted, imported or by default import (`ClojureNamespaceLowering.JAVA_LANG`,
  the oracle's `java.lang` list read off `(ns-imports 'user)`; `DEFAULT_IMPORTS` adds
  `Callable`, so `^Callable` hints, `BigInteger` and `BigDecimal`, not `clojure.lang.Compiler`,
  no class here). `(instance? clojure.lang.BigInt 1N)` is false where the oracle's is true (an
  in-range `N` literal is a long, "Decimals"). A plain throwable construction is an exception
  condition ("Exceptions"), a Date/Timestamp/UUID one the `#inst`/`#uuid` value ("Instants and
  UUIDs"), on every backend. `Class/new` is `hostConstruction`, the one path `(Class. ...)`/`new` share; `R/new` of a
  record or deftype is `->R`; `Pattern/compile`/`quote`/`matches` are the regex runtime's
  ("Regex"). `String/valueOf` of one value is its toString where no host is (nil the oracle's
  NPE) and the host call where one is, so there nil arrives as Java false (`"false"`; the
  oracle's reflector picks `valueOf(char[])` and throws).
- Qualified members (`ClojureInteropLowering.memberCall`/`memberValue`): `Class/.m` is an
  instance call typed by the class, but a class a Clojure value may be an instance of
  (`ClojureValueClasses.mayHoldValue`: `Object`, an interface, a kind's class) is dropped
  first, so `(Runnable/.run f)` takes the value arms, not `java:call`; a `..` step's declared
  return likewise. `^[types]` param tags become the designator `m(T1,T2)` and fix the arity,
  only where `ClojureLowering.hostMemberName` holds (no local/var/library/project namespace
  claims the name); `constructedClass` strips the `(...)` so a tagged construction still
  records the local's class.
- `.isInstance` of a class keyword or designator (a record's or deftype's class too) is
  `instance?` (core.logic's deftypes ask it in `equals`); an integer's `.toString` of a radix
  is BigInteger's (rewrite-clj's `int->str`). `.isArray` of a designator (a fn's class) tests a
  leading `[`; of a class keyword it is true only of `[B` (a char array is a character vector,
  a host array a list). Pins: clojure-spec
  `instance-of-an-interface-or-a-host-class-tests-the-classes-of-each-kind` (the default
  imports too), `create-with-check-builds-checking-duplicate-keys`.
- A string receiver answers the mapped core operation (`ClojureInteropLowering.stringMethod`,
  every backend): `.equals` only a string of the same characters (not CL `string=`, which
  refuses a number and equals a symbol), `.compareTo` as `compare`, `.indexOf` of an int its
  character, `.subSequence` the substring; `.getChars` and `(String. chars off len)` take a
  char array (a character vector). Other methods reach `java:call`, which calls a string /
  number / character as its `String` / narrowest box / `Character` (`.kb/java-interop.md`, "A
  Lisp value as a `java:call` receiver"). `.toString` of a number, character, symbol, cons,
  array, table or function is the oracle's `toString` on every backend (`valueToString`). Pins:
  `ClojureInteropTest#unmappedMethodsCallAStringNumberOrCharacterAsItsHostObject`; clojure-spec
  `string-getChars-subSequence-and-char-array-strings-read-on-every-backend`.
- Stream receivers run on every backend; `.toString`'s clause tests the exact tag
  `%clojure-stream-p`, never `streamp`, which is true of `t` (Clojure's `true`) and, on the
  JVM, of a host object whose `equals` answers true (under `streamp` the proxy of
  `ClojureInteropTest#proxyOverAClassSeesThisAndSuper` printed `true`). A `PrintWriter`/
  `PushbackReader`/`BufferedReader` construction over a stream is that stream ("Reading");
  `StringWriter`, `StringBuilder` and `StringBuffer` constructions are one string output stream
  on every backend (one argument: the stream producers `%clojure-string-builder-1`/
  `-writer-1`). Pins: clojure-spec `string-builder-builds-through-append-and-answers-its-text`,
  `string-builder-construction-with-an-initial-text-or-capacity`,
  `string-writer-construction-with-an-initial-capacity`,
  `to-upper-and-lower-case-with-a-locale-map-like-without-one`,
  `intern-of-a-string-is-the-string`; honeysql 2.7.1479 and hiccup 2.0.0 `=` on wasm ("Library
  probes").
- A collection, keyword, symbol, ratio or atom has no host object:
  `ClojureValueMethodLowering.valueArm` gates an unknown receiver's host call on
  `%clojure-value-receiver-p`, a row per `method/arity` over a `clojure.core/`-spelled verb
  datum (the core meaning, whatever the program defines), its kind tests
  `ClojurePredicateLowering.rawTest`s so the sorted strip still folds them. Other kinds and
  arities refuse in the oracle's words, fixed at lowering (`%clojure-no-method` appends the
  class; docstrings and `format` arms weighed more). `.meta`/`.withMeta` of a collection, fn,
  var or symbol are the metadata verbs; `.key`/`.val`/`.getKey`/`.getValue` answer on any
  2-vector (a map entry here), where the oracle refuses. `.equals` is the oracle's
  `Util.equals`, not `=` (`%clojure-java-equal`: maps, sorted maps and records by each key
  found by the other's own lookup, the record tag ignored; a double only a double of its bits;
  a deftype/reify its own `equals` or identity). `(.equals 1 1N)` is `true` here, `false` in
  the oracle (`1N` reads as `1`): a number or character receiver keeps `java:call`, a call-time
  error on wasm like every method of a number there. Pins: clojure-spec
  `instance-calls-on-collections-keywords-symbols-and-ratios`,
  `equals-of-a-clojure-value-is-its-class-s-java-equals-not-equiv`;
  `ClojureInteropTest#collectionKeywordSymbolAndRatioReceiversAnswerTheirCommonMethods`.
- A method no row names (`unmappedArm`) reaches the value's Java object where the oracle's
  class has it through a JDK interface (`HOST_KINDS`, read by reflection at lowering like
  Clojure's reflector); on wasm the same test guards the refusal, so the words agree on all
  four backends: `Method m taking N args is not supported` when some oracle value class has
  `m/N` that its Java object here lacks (`ClojureValueMethods`, read off clj 1.12.6 on JDK 25,
  the rule in its javadoc), else the oracle's words. A seq iterator's `.remove` is the
  message-less UOE, its class `clojure.lang.SeqIterator`; `.toArray` answers a vector
  (deviation). Pins: `ClojureInteropTest#aMethodNoRowNamesReachesTheValuesJavaObject`,
  `#collectionKeywordSymbolAndRatioReceiversAnswerTheirCommonMethods` (`.hashCode2`,
  `.reduce`),
  `ClojureWasmInteropRefusalTest#aMethodOfAValuesJavaObjectIsUnsupportedOnBothBackends`,
  clojure-spec `instance-calls-on-a-fn-are-its-ifn-callable-runnable-and-comparator-methods`.
- A record, deftype or reify passes the same gate; its class also has its body's protocol
  methods and its declared fields. For a name that is some protocol's method or some known
  type's field, `typedMembers` makes the arm try the inline method per protocol declaring it at
  the arity (`%clojure-inline-method-p`: no extension stores under the tag of a body naming the
  protocol, none reaches a reify's), then the mapped rows (a record is `map?`: `(.count r)` is
  the map's), then an immutable declared field (zero arguments), then the oracle's refusal. A
  deftype carries its class name at index 4 like a record, for the refusal: the only bytes a
  program with no such site gains. Pin: clojure-spec
  `instance-calls-reach-a-record-deftype-or-reify-method-or-field`.
- Host booleans: every host call the lowering builds ends in `:java-false`
  (`ClojureInteropLowering.hostCall`, `fieldCall`, a `proxy`'s), so Java's false comes back as
  the false object (`.kb/java-interop.md`, "Markers"); the shared unmarshal stays nil for a CL
  program. The library's host DATA reads end in it too; its host PREDICATES (`containsKey`,
  `hasNext`, `isDone`, ...) must not: a CL `if` reads `|false|` as true. The other way, the
  false object IS the symbol `java:` passes as `Boolean.FALSE` (`LispNames.JAVA_FALSE`,
  `.kb/java-interop.md` "Java's false and hash tables"). Pin:
  `ClojureInteropTest#aHostFalseComesBackAsFalse`.
- Clojure values as the oracle's own objects: `hostArgument` wraps a host call's argument in
  `%clojure-host-value`; a fn and a condition stay themselves (the call makes the fn an
  interface; `java:` hands the host exception), anything else is `%clojure-host-member`, which
  also converts a host-map lookup's key and every collection's members. Java hands each back as
  the value (`identical?`).

  | Value | Java receives |
  |---|---|
  | nil, booleans, string, character, a number but a ratio | itself |
  | keyword, symbol | a `java:handle` of class `clojure.lang.Keyword`/`Symbol`, hashing as the oracle's, sorting no namespace first, then namespace, then name; the two never compare |
  | ratio | a `Number` handle of class `clojure.lang.Ratio`, ordered by value |
  | vector; list, seq, lazy seq (realized); set; map, sorted map, record | a `:vector` (`RandomAccess`, `Comparable`) / `:list` / `:set` / `:map` `java:view` of the oracle's class name, printing as `str`, members converted alike |
  | Date, Timestamp, UUID; a host object | the host object |
  | byte array | a `:bytes` view: the `byte[]` wherever one fits (`Object` too), whose stores come back (`.kb/java-interop.md`, "Handles and views") |
  | fn | the object it is ("A fn or a `proxy` body Java calls") |
  | deftype or reify naming a Java interface or overriding an `Object` method; record naming a Java interface | its face ("Java faces"; a record's a `Map`) |
  | anything else (atom, pattern, var, other deftype or reify, a condition in a collection) | an identity handle (`%clojure-host-object`): text `Object`'s spelling (`clojure.lang.Atom@hex`) for an atom, a reduced or a type overriding no `toString`, else `str` |

  Traps: a String construction's arguments go through `%clojure-bytes-host-value`, so a byte
  array reaches `%clojure-string-new` as itself; `%clojure-to-string-p` stays a bare `cond`
  test (the library strip requires it). Not wrapped, so the site keeps resolving on the
  argument's kind: a literal, a fn form, a construction, a `make-array` (it must convert to the
  Java array, never to a vector's List) and a `let` local bound to one (`isPlainForm`); nothing
  on wasm. Views, not copies: no site changed resolution status, and Java's run-time overload
  choice is the oracle's (`String/valueOf` of `[\a \b]`). `demo.clj` (no interop) is
  byte-identical; a converting JVM program carries the view and handle runtime and one more
  reflection warning. Pins: `ClojureInteropTest#aClojureValueReachesJavaAsTheOraclesOwnObject`,
  `#aSetAKeywordAndARecordCrossTheJavaBoundaryAsTheOraclesDo`,
  `#aKeywordOrSymbolHashesAndSortsInJavaAsTheOraclesDo`,
  `ClojureLoweringTest#aHostArgumentThatMayHoldAValueJavaLacksGoesThroughTheHostValue`.
- A `byte[]` a member answers is a byte array: a host call whose answer may carry one ends in
  `:octets` and answers through `%clojure-host-answer` (uncopied; a Java array, a list here,
  mapped when it holds one at any depth). `ClojureInteropLowering.answersBytes` leaves a call
  bare only when the class loads and every method the call may choose, or the field, declares a
  type no `byte[]` fits (`carriesBytes`: `byte[]`, `Object`, `Cloneable`, `Serializable`, an
  array of one), so the next site keeps resolving on the declared type; unknown receivers wrap,
  constructions never, `unmappedArm`'s calls and `proxy-super` always. Pins:
  `ClojureInteropTest#aByteArrayAJavaMemberAnswersIsAByteArray`,
  `#aByteArrayReachesJavaAsTheByteArrayJavaStoresInto` (interpreter and JVM).
- A fn or a `proxy` body Java calls is called THROUGH `%clojure-host-call` (a proxy body
  `%clojure-proxy-call`/`%clojure-subclass-call`), named by the `java:` marker `:through`
  (`.kb/java-interop.md`, "Markers"): Java's arguments through `%clojure-host-answer` (a
  `byte[]` a byte array whose stores Java reads), the answer converted for the KIND Java takes
  it as -- `%clojure-host-member` only for `:object`, a cast for a primitive, nothing for none.
  Only the `java:` layer knows the slot's return type, so this is the one place the answer can
  be converted without converting one a void method drops (a lazy seq realized, a growing
  vector walked per `forEach` call: quadratic).
  - A fn's primitive answer is cast as the oracle's functional interface adapter casts it (read
    off clj 1.12.6's `Compiler$FISupport`, `FnInvokers`, `Reflector.boxArg` /
    `coerceAdapterReturn` and `RT` with javap): a `boolean` by the compiled adapter's
    `(Boolean)` cast where `java:`'s `resolved` says the call chose its member before it ran,
    else by the reflector's `RT.booleanCast`; `java:`'s resolution (`.kb/java-interop.md`, "One
    resolution model") is the closest model of the oracle compiler's, and every mismatch errs
    lenient (user doc). Not modeled, as no JDK interface reaches them (`ScanFI` over the JDK's
    modules): a method of more than two parameters answering an `int` (the oracle's
    `invokeOO..` adapter refuses a `Long` there), and the reflector's Proxy routing a default
    method to the fn (here its body runs).
  - A proxy body's primitive answer is unboxed as the oracle's proxy class does (core_proxy's
    `gen-method`, ASM `unbox`): a `(Boolean)`/`(Character)` cast, else `((Number)
    x).intValue()` etc. unchecked; a `Comparator` proxy's `compare` is such an int, no
    `AFunction.compare`. A fn constructor argument of a proxy over a class goes through the
    subclass call; the oracle's reflector agrees for `Runnable`, `Callable` and `Comparator`,
    the only constructor parameters measured.
  - A fn Java takes as an `Object` (in a collection too, or answered by a fn or proxy body) is
    the object the fn is (`.kb/java-interop.md`, the function object): a `Runnable`, `Callable`
    and `Comparator` standing for the fn. `%clojure-host-member`'s `functionp` arm carries the
    call sites' markers (`:functional :java-false :octets :through #'%clojure-host-call`), so a
    compiled program reuses their shell (`.kb/java-interop.md`, `java:handle` of a function);
    not the identity handle, which implements none of the three.
  - A fn argument: `ClojureInteropLowering.hostCall` ends a call with a non-literal argument in
    those markers where the host is. The fn reaches Java as ITSELF, so a converted
    `Runnable`/`Callable`/`Comparator` stands for the program's fn
    (`JavaImplementation.standsForItsFunction`: `(.comparator (TreeSet. f))` is `f`, as in the
    oracle); an adapter lambda between could stand only for itself.
  - A `proxy`: `java:proxy`/`java:subclass` end in `:octets` and `:through` (`proxyThrough`);
    the slot's own return type decides the conversion per overload, not reflection over SOME
    method of the name. A face: `:octets` per parameter that `carriesBytes` ("Java faces"), its
    own answer conversions, no `:through`.
  A `String` construction keeps the call's markers (its alias is `java:new`):
  `%clojure-string-new` skips `:through` and its function. Wasm is unaffected. Pins:
  `ClojureInteropTest#aFnsAnswerReachesJavaAsTheOraclesOwnObject`,
  `#aFnsPrimitiveAnswerReachesJavaCastAsTheOraclesAdapterCastsIt`,
  `#aProxysPrimitiveAnswerIsUnboxedAsTheOraclesProxyClassUnboxesIt`,
  `#aFnWhereJavaExpectsAnObjectIsTheFnItself`,
  `#aFnInsideACollectionJavaReceivesIsTheFnItself`,
  `#aByteArrayJavaHandsAProxyIsOneItStoresInto`, `#aByteArrayJavaHandsAFnIsOneItStoresInto`,
  `#aProxyOrTypeIsHandedAByteArrayAndAnswersJavaTheOraclesObject`,
  `ClojureLoweringTest#aFnOrProxyJavaCallsIsCalledThroughTheHostCall`.
- A fn receiver is the oracle's `AFunction` (`ClojureValueMethodLowering.functionRows`) on all
  four backends: `invoke` and `applyTo` (of any `ifn?` value), `call`, `run`, and `compare` as
  `AFunction.compare` (`%clojure-fn-compare`: true -1, false 1 when the reversed call is true
  else 0, a number its `intValue`); a fn Java uses as a `Comparator` answers the same way
  (`ClojureInteropTest#aFnPassedAsAComparatorComparesLikeTheOraclesAFunction`). `Comparator`'s
  defaults (`reversed`, `thenComparing*`) are a fn `HOST_KINDS` row, called on the fn's object
  where the host is, refused by name on wasm, and out of `ClojureValueMethods` (only
  `AFunction` had them), so a map's `.thenComparing` is the oracle's `No matching method`.
  Pins: `ClojureInteropTest#aFnReceiverAnswersComparatorsDefaultMethods`, the wasm refusal pin
  above.
- `.getClass` of a fn (spec.alpha's `fn-sym` demunges `(.. f getClass getName)`) is the
  `(:C%CLASS name)` designator of the class the oracle compiles it to
  (`%clojure-fn-class-name`): a registered definition's `ns$name`, a core function in the
  shared-value table `clojure.core$<munged name>`, else `clojure.lang.AFunction$fn` (user doc),
  so also a core value lowered to a `(function worker)` (`hash`). `.getName` of a designator is
  its name, so the pair runs on every backend.
  - **Registration**: every `defn`, and a `def` of a `fn`/`fn*`/`#()` literal, ends in
    `(%clojure-note-fn-class (function f) "ns$name")`
    (`ClojureValueMethodLowering.noteFnClass`). Family `FN_CLASS`: the strip drops the notes of
    a program reading no fn's class, in a namespace init's lambda body too (`walkLambda`), and
    a note weighs nothing when the init is cut into chunks (`ClojureArms.weighsNothing`). The
    macro-time hand-over skips the note (it would force a `def`'s lazy init). Pins:
    clojure-spec `munge-and-namespace-munge`,
    `ClojureProjectNamespacesTest#aRequiredNamespacesMacrosCallItsFunctions`,
    `ClojureArmsTest#theFnClassFamilyIsMadeByAFnsClassAndDropsTheRegistrationsOfAProgramReadingNone`.
  - **`class` keeps the one `:function` kind**: of the 1377 `.clj`/`.cljc` files in the 148
    Clojure jars of the local Maven repository, only clojure's own `print-dup` puts a
    multimethod method on a fn class, the other fn-class sites (reitit 3, tick 4, exoscale 1)
    extend a protocol to `clojure.lang.Fn`, which reads the kind, and only spec's `fn-sym`
    reads a fn's class name; a per-function `class` would move dispatch and the class rows off
    the one kind for every program calling `class` on a fn, for no corpus program.
  `.name`/`.getName` of a namespace are its name symbol (spec's `ns-qualify` reads `(.name
  *ns*)` while `s/def` expands). Pin: clojure-spec
  `ns-is-the-current-namespace-and-the-load-specials-have-their-values`.
- A fn passed where an interface is expected implements every abstract method by the method's
  arguments, defaults keeping their bodies (`.kb/java-interop.md`, `:functional`; the oracle
  converts only to a `@FunctionalInterface`: user doc), and prefers a functional interface's
  overload (`TreeSet(Comparator)` over `TreeSet(Collection)`: the oracle's fn IS a
  `Comparator`); `(proxy [Super] [fn] ...)` constructor arguments convert the same way. Pins:
  `ClojureInteropTest#aFnPassedWhereAnInterfaceIsExpectedImplementsItsMethodByItsArguments`,
  `#falseAMapAndAFnCrossTheJavaBoundaryAsTheOraclesDo`.
- `proxy` of interfaces is `java:proxy`, a name-dispatching lambda over the Java arguments (no
  `this`); `toString`/`equals`/`hashCode` are refused there (`java:proxy` keeps `Object`'s, so
  the body would never run). With a superclass it is `java:subclass`: `this` bound,
  `proxy-super` through the generated `super$` accessor, an unnamed abstract method an
  `UnsupportedOperationException`. Multi-arity methods, a second class, a final superclass are
  refused.
- `set!` of a field form (`(.f x)`, `(.-f x)`, `(. x f)`) is a write decided when it runs
  (`ClojureProtocolLowering.hostFieldSetOf`), target then value, like the oracle's reflective
  `InstanceFieldExpr` -- not a lower-time refusal, which stopped schema's `macros.clj` load
  (its protocol-method cache sync). Where the host is, a host object's field is written by
  `(java:field obj "f" value)`, a static field likewise; where it is not, a static field's
  write is an `UnsupportedOperationException` (user doc). Pins: clojure-spec
  `set-of-a-host-field-writes-it` (wasm legs skipped),
  `set-of-a-field-form-decides-when-it-runs` (four backends).
- Argument type hints: `ClojureInteropLowering.hintedDesignator` spells an untagged member
  `m(^C,_)` when an argument carries a class `:tag` (a local's through
  `ClojureLowering.localHint`); primitive, array, record/deftype and unknown tags hint nothing;
  never where no host is. The `java:` hint designator (`.kb/java-interop.md`, "Argument hints")
  narrows a site resolved before it runs, as the oracle's compiler reads `:tag`s; a reflective
  site reads none, as its reflector. A receiver's hint is dropped (user doc) but for its
  refusal: where the hinted class is a host class no Clojure value may be an instance of, with
  one public method of the name and count (`castClass`), the oracle's compiler casts, so the
  value arms' final refusal is `%clojure-cast-failure`'s `ClassCastException`, not
  `%clojure-no-method`'s `IllegalArgumentException` (`ClojureLowering.receiverCast`, read only
  by `castOrRefusal`: no test on a path an answer takes). Specter's AOT-safe cache catches that
  `ClassCastException` ("Macros"). Pin: the fn-to-interface `ClojureInteropTest` method above
  (its `.submit` rows).
- Host collections under the seq verbs: a host `Iterable`, `Map` or `CharSequence` is seqable,
  counted, looked up and `contains?`-tested as the oracle's `RT` does, refusals in its words.
  One family test, `%clojure-host-seqable-p` (`ClojureCollectionLowering.HOST_SEQABLE_P`),
  heads a clause before each verb's fall-through. Another `Iterable` reads through `iterator`
  (a JDK non-public iterator class trips a `java:call` gap). `get`/`contains?` ask the host's
  own lookup (the oracle's `equals`) of the key `%clojure-host-member` makes -- not a walk by
  `=`, which pulled `%clojure-equal`'s closure into every `get` and is O(n). Speed (JVM, a
  `java:` program): `count` one call more per call, `keys`/`get` a few percent. The interpreter
  keeps these, the map and the printer arms in every program; the inline
  consp/arrayp/symbolp/numberp exit at the head of `%clojure-host-seqable-p` spares lists and
  vectors the `%clojure-lisp-value-p` call. Pins:
  `ClojureInteropTest#aHostCollectionSeqsCountsAndLooksUpLikeAClojureOne`,
  `ClojureLibraryTest#aProgramNamingNoJavaOperatorSeqsAndCountsWithoutTheHostCollectionArms`.
- Host maps under the map verbs: `find`, `select-keys`, `reduce-kv` and `conj` onto a map take
  a host `Map`, and `kv-reduce`/`cons` any seq of `Map.Entry`, as the oracle's `RT` does,
  behind the same test (the deviation `seq-entry-plist` keeps: a `[k v]` vector is an entry
  too). Two views, since a test there would not fold to the old form: `select-keys`'
  (`%clojure-host-select-keys`; not an `if` arm, which needs the key form twice, nor a snapshot
  table, which looks up by `=`, not `equals`) and `merge-with`'s (`%clojure-host-table`).
  Traps: the views are asked of EVERY `select-keys`/`merge-with` map, so they answer
  `hash-table-p` first; a view body keeps the family test a bare `if`/`cond` test, never inside
  `and` (the strip signals `a host-object test where it cannot fold`); `format nil` in a host
  arm pulls the whole `format` runtime in, so refusal words go through `error`'s own arguments.
  Speed: `select-keys` one view call per call (a few percent on the interpreter, `merge-with`
  too), the rest within noise. Pins: `ClojureInteropTest#aHostMapIsAMapToTheMapVerbs`,
  `ClojureLibraryTest#aProgramNamingNoJavaOperatorRunsTheMapVerbsWithoutTheHostMapArms`.
- A host `Future` under `deref` (as the oracle): `@f` is its `get`; the three-argument `deref`
  is `get(ms, MILLISECONDS)`, answering the third argument on `TimeoutException`; any other
  value, after both extra arguments ran, is a `ClassCastException` (nil an NPE) on all four
  backends (clojure-spec `deref-with-a-timeout-refuses-a-value-that-is-no-future`;
  `%clojure-deref-other`, timed `%clojure-host-future-get-within`). `deref` as a function value
  stays one argument: a variadic lambda would change the bytes of every program naming it. A
  `java:` program reading an atom grows by the arm's closure (JVM `.toUpperCase` + `@a`: class
  103,872 -> 105,633 B; wasm +66 B). Pin:
  `ClojureInteropTest#derefOfAHostFutureIsItsGetWithAnOptionalTimeout`.
- A host `Future` under the future verbs (as the oracle; `ClojurePredicateLowering`): `future?`
  is true of one; `future-done?`, `future-cancelled?`, `future-cancel` are its `isDone`,
  `isCancelled`, `cancel(true)` after a cast to `Future`, so any other value is a
  `ClassCastException` (nil an NPE) on all four backends (clojure-spec
  `future-verbs-refuse-a-value-that-is-no-future`); `realized?` of one is a
  `ClassCastException`. `future?` is `(%clojure-host-future-p x false)`, a HOST alias of
  `progn` the strip leaves as `(progn x false)` -- not a `let*` over a temporary, which changed
  the bytes of every program calling `future?` and shifted the temp names. A `java:` program
  calling it grows by the arm (JVM class 99,607 -> 99,862 B around one
  `CompletableFuture/completedFuture`). Pin:
  `ClojureInteropTest#theFuturePredicatesReadAHostFuture`.
- A Clojure future under `deref` and the future verbs: `future`/`future-call` answer
  `(:C%FUTURE task failure-cell)`, `promise` `(:C%PROMISE cell)` ("State"); `deref` and the
  verbs read the task like a host future's, except a failure, which `deref` wraps in
  `ExecutionException` over the stored body condition (the host's `get` would hand the
  converted crossing); `realized?` reads the done or delivered flag.
  `ClojureArms.Family#FUTURE` folds the arms where the program makes neither. Pin:
  `ClojureInteropTest#futuresPromisesAndDeliverMatchTheOracle`.
- Host collections under the printer (user doc): `%clojure-write-host`, a clause ahead of
  `%clojure-write`'s fall-through, builds the Clojure value and writes it at the same depth, so
  the print-flag arms apply; the members skip `%clojure-store-key`, whose key representatives
  would pull `%clojure-equal`'s closure into every printing program. The interpreter pays a
  call per value reaching the fall-through, so the cheap kinds go first: an integer right after
  the `nil` arm (no metadata, no collection, no label), which more than halves its integer
  printing, then a keyword, string, character and float (one `(floatp x)` arm), ahead of the
  library tests. The integer arm's size: a program printing a non-literal value grows by it on
  wasm, a JVM program linking the printer by a few hundred bytes of class, one linking no
  printer not at all. Pins: clojure-spec
  `an-integer-and-a-ratio-print-as-their-digits-under-every-flag`,
  `a-keyword-string-character-and-float-print-the-same-under-every-flag` (four backends);
  `ClojureInteropTest#aHostCollectionPrintsReadablyLikeItsClojureKind`,
  `ClojureLibraryTest#aProgramNamingNoJavaOperatorPrintsWithoutTheHostCollectionArm`.

## HashMap construction

**A `(java.util.HashMap. ...)` construction is the map itself where the host is not (wasm), a
mutable `java.util.HashMap` where it is.** reitit's `fast-map` (`(HashMap. ^Map m)`) is its
`:cljs` branch's identity on a host-less target: the map needs only `.get`, which the
value-method row answers. Where the host is not, zero arguments are the empty map
(`ClojureInteropLowering.hashMapEmpty`), one untagged argument the argument itself, evaluated
once (`hashMapCopy`), and two number datums (`(HashMap. 1024 0.25)`, malli's load-time
registry) the empty map too, since dropping number literals evaluates nothing away. Any other
pair, a tagged construction (it names the host overload), any other arity and the
value-position `HashMap/new` of two arguments (they lower to indexed reads, never datums) keep
`java:new` everywhere. Where the host is every spelling keeps `java:new`: the host tests pin
the mutation (`ClojureInteropTest`: `doto` + `.put` + `.get`), which no immutable stand-in
answers.

`.put` on a map where the host is not is the oracle's message-less
`UnsupportedOperationException`, like the seq iterator's `.remove`
(`ClojureValueMethodLowering.unmappedArm`'s `put/2` answer); where the host is, the read-only
`Map` view's `AbstractMap.put` throws the same. `.putAll` of a map where the host is not stores
the entries into the target's table and answers nil (`rontolisp::%clojure-hash-map-put-all`):
malli's `fast-registry` fills its map this way at load. A record or sorted-map target throws
`.put`'s exception (where the host is, the view's `AbstractMap.putAll` does); any other
receiver keeps the oracle's words on every backend.

Deviations, wasm only (`doc/en/clojure/deviations.md`): `.putAll` into a non-empty map literal
stores into it (no portable lowering can tell the stand-in table from a literal's), and a
one-argument stand-in shares the mutation with its argument, where the oracle's copy does not
(a fresh two-argument construction has no such alias). The canonical empty map
(`%clojure-empty-map`) refuses the mutation, so a shared empty map never gains an entry.

`System/getProperty` of a literal key runs where the host is not
(`ClojureInteropLowering.staticCall`), nil or its default as on a host without the property
(malli.registry reads `mode`/`type` at load); a computed key is `%clojure-system-get-property`;
`(System/getenv name)` reads the WASI environment (`%clojure-system-getenv`; aero's
`#prop`/`#env`).

A program naming no `HashMap` construction, `.put`, `.putAll` or `System/getProperty` lowers
byte-identically on every backend (all `!hostTarget`-gated). Size (exec jar): a mutation
carries the message-less construction's exception runtime, so a program whose only host-map use
is a mutation grows to about the size of the same program over the map literal; a `.get`-only
program, and the JVM class of a `.put` program, are byte-identical. Probes: malli.registry
prints the oracle's lines on both wasm backends warning-free, reitit-core 0.11.0's seven-line
probe on all four, P1 and the component warning `JAVA:STATIC` (`URLDecoder/decode`,
`URLEncoder/encode`, in `reitit.impl`'s load) and `JAVA:CALL` (one site in `reitit.core`'s
load) off the probe's path.

Pins: clojure-spec `hash-map-construction-builds-the-map-itself-where-the-host-is-not`,
`put-on-a-map-refuses-as-the-oracles-unsupported-operation`,
`system-get-property-answers-like-a-host-without-the-property`,
`hash-map-capacity-and-load-build-the-empty-map-where-the-host-is-not`,
`put-all-stores-into-the-hash-map-where-the-host-is-not` (four backends);
`ClojureWasmHashMapTest` (what only wasm answers: `.put` on the construction, the nil
construction, the two-argument fill, `System/getProperty`); `ClojureLoweringTest`
`aHashMapConstructionLowersToTheMapItselfWhereTheHostIsNot` /
`putOnAMapThrowsTheOraclesUnsupportedOperationWhereTheHostIsNot` /
`aTwoArgumentHashMapConstructionOfTwoNumbersIsTheEmptyMapWhereTheHostIsNot` /
`systemGetPropertyIsNilOrItsDefaultWhereTheHostIsNot` /
`putAllOnAMapStoresIntoItsTableWhereTheHostIsNot`; `ClojureWasmHostArmsTest` (the construction,
the fill and `System/getProperty` compile warning-free).

## Object construction

A zero-argument `java.lang.Object` construction where the host is not (wasm) is a fresh
uninterned symbol (`ClojureInteropLowering.objectToken`): dynaload's `dynalock` builds one at
load, and `locking2` only needs the lock to evaluate; a fresh symbol per evaluation keeps the
oracle's identity (never `=` or `identical?`). Where the host is it keeps its `java:new`, whose
object the host tests pin; any other count, and a tagged one, keeps it everywhere. Pins:
clojure-spec `object-construction-answers-a-fresh-token-where-the-host-is-not` (four backends),
`ClojureLoweringTest#aZeroArgumentObjectConstructionIsAFreshTokenWhereTheHostIsNot`.

## Laziness

A lazy seq is `(:C%LAZY cell)` over a zero-argument thunk, run at most once per object; a
realized one is a cons whose tail is another wrapper, so a CL list operation over the one-level
view walks wrapper cells as members: a consumer using one takes `%clojure-seq-all` (every tail
realized, a wrapper-free spine not copied), one answering a value steps with
`%clojure-seq-rest` (an infinite input answers). `next` realizes the tail it answers, so a lazy
seq with nothing past its head answers nil like the oracle's.

**Every verb answering a seq follows the lazy-or-strict rule** (`%clojure-lazy-or-strict`): a
lazy seq when an input is lazy, the strict list otherwise (the verbs
`doc/en/clojure/semantics.md` lists, plus `drop-last` and `eduction`). A strict input keeps a
direct loop: through the lazy machinery a strict `partition` took 10.1 s against 5.1 s (3 x
100,000 members, interpreter).
- **A lazy input** (`%clojure-lazy-input-p`) is the wrapper, a plain list whose spine reaches
  one (what `seq`, `nthnext`, a `&` rest or a `cons` onto one answers; else a verb walks a
  realized view of an endless seq whole), or a Seqable/Iterable/`java.util.Map` (a program
  storing no such row aliases the test to `%clojure-lazy-seq-p`, `ClojureArms.Family.SEQABLE`).
  The spine test is one builtin `(member :C%LAZY tail)`; a CL `do` loop per tail cost the
  interpreter 0.3-0.8 us per member (`map` +8..27%, `drop-while` +120..220%, 2026-11-09),
  `member` nothing measurable. `drop-while`'s strict arm steps with `cdr` (through
  `%clojure-seq-rest`: 1,521/1,570 -> 2,118/1,788 ms).
- `iterate` computes `(f prev)` when its own cell realizes, like the oracle's `Iterate`, never
  a step ahead (`(take-while identity (iterate zip/right loc))` must not call `right` on nil);
  but `next`, `nthnext` and a `&` rest realize the cell they reach, so
  `(let [[a b & more] (iterate f 0)] ...)` calls `f` twice, the oracle once.
- Over a lazy input `take`/`take-while`/`reductions` realize one member per cell,
  `drop`/`drop-while` step over in one realization when first read, and `mapcat` realizes only
  what is read (the oracle's `(apply concat ...)` runs up to four mapped members ahead).
  Internal eager callers take `%clojure-take-strict`/`%clojure-nthnext`.
- `partition-by` over a lazy input makes each run lazy, and the run's last realization hands
  the rest over in a cell instead of the oracle's `(drop (count run) s)`, so no member is
  walked twice (50,000 members in runs of 1,000: 2,111/2,054 ms against 3,451/3,521
  re-walking).
- `interleave`/`interpose` emit one wrapper per element, so `rest` never exposes a strict cons
  holding a wrapper. The lazy arms of the dropping and round-robin verbs (`remove` `keep`
  `keep-indexed` `map-indexed` `distinct` `interpose` `partition` `interleave`) grew a one-verb
  strict program's raw wasm at most 2%, but `interleave` 33,960 -> 37,663 B.
- Pins: clojure-spec `dropping-and-stepping-verbs-answer-over-infinite-inputs` (endless inputs
  and realized views), `lazy-seqs-realize-only-what-a-consumer-reads` (call counts),
  `fnext-and-nnext-step-past-the-head`; `ClojureLoweringTest#seqVerbsLowerOverTheSeqView`,
  `#seqsCoerceCollectionsThroughOneSharedView`, `#destructuringBindsSequentialPatterns`,
  `ClojureArmsTest#anInterfaceFamilyIsMadeByTheStoreOfARowOfItsInterfaces` (the alias).

- `for` (`ClojureLoopLowering.forStep`, one step closure per level) runs on one shared runtime,
  `%clojure-for`: inlined per site, every element paid a thunk and a wrapper (a strict
  200,000-element `for`: 8.95 s vs 4.18 s). `%clojure-concat-step` lets its last member answer
  its own seq: re-wrapping it made a multi-level `for` walk each element through one layer per
  outer element.
- **A skip never nests a realization.** `%clojure-realize-chain` forces a chain of wrapper
  answers in a loop (the oracle's `LazySeq.seq`) and memoizes the final seq into every cell it
  forced; a cell reads realized-and-empty from the moment its thunk answers, so a
  self-answering body and a throw further down leave `nil`, the oracle's answers. A runtime
  producer skips in a loop inside one realization (nested, a long skip overflowed every
  backend). The chain loop is its own defun (256 B raw wasm, 2026-10-03): inlined into
  `%clojure-realize` it widened the analysis of `%clojure-strict-seq`'s argument (+688 B in a
  vector-free program). Pin: clojure-spec
  `long-skips-and-wrapper-chains-run-in-constant-stack`.
- **A recursion through a lazy verb walks lazily**: a `map`, `filter`, `remove`, `keep`,
  `keep-indexed`, `map-indexed`, `mapcat` or `for` whose function datum (a `for`'s bindings or
  body) spells an enclosing `defn` or named function outside a quote
  (`ClojureLowering.reentersDefinition`) takes each collection as a lazy input, as the oracle's
  `map` is always lazy. Under the plain rule a strict input built the whole structure at once:
  test.check's `(core/map int-rose-tree (shrink-int value))` held about k^k nodes for a value
  near 2^k (631,314 for 26: 110 s on the interpreter). A local spelled like the definition
  makes a verb lazy too; a mutual recursion through a helper is not seen. Pins: clojure-spec
  `a-recursion-through-a-lazy-verb-walks-lazily`,
  `ClojureLoweringTest#seqVerbsLowerOverTheSeqView`.
- The printer realizes a wrapper where it stands; the cycle walk keeps a wrapper a leaf.
- **Delays**: `(:C%DELAY #(thunk value failure mutex))` (`ClojureLazyLowering.delayOf`), forced
  once under the delay's own `make-mutex` (the oracle's `synchronized` deref: a Ring handler's
  threads force one delay once), a failure re-signalled on every force (the oracle caches the
  exception); `realized?` and the printer read the thunk slot alone (the oracle's non-blocking
  `isRealized`). Family `ClojureArms.Family.DELAY`; `%clojure-force`/`%clojure-is-delay` alias
  `progn`, so a program making no delay compiles `force` to the value. A per-delay mutex leaks
  on the interpreter (`Environment.registerMutexes` keeps every lock), not on the JVM or wasm.
  Printing: `doc/en/clojure/deviations.md`. Pins: clojure-spec
  `delay-runs-its-body-once-and-force-reads-it`,
  `ClojureArmsTest#theDelayFamilyIsMadeByDelayAndFoldsForceAndDelayPToProgn`,
  `ClojureLibraryTest#aProgramMakingNoDelaySplicesDerefAndThePrinterWithoutTheirDelayArms`,
  `ClojureLoweringTest#aDelayIsAMemoCellOverItsBodysThunkAndForceTheFamilysHelper`.

## Transducers

**A transducer is the oracle's: a function from a reducing function to a reducing function**,
built by a spliced `%clojure-xf-*` worker (`ClojureTransducerLowering.xformCall`), not a tagged
closure the consumers interpret, so `comp` composes them and a program's own transducer runs
beside the core ones. Per-reduction state lives in variables the `(lambda (rf) ...)` closes
over, so completion is not skipped (`partition-all` flushes its tail after an early `take`
stop). `transduce`, `into` and `cat` reduce through `%clojure-reduce-init`, so a type's own
`CollReduce` row reduces under them too ("clojure.jar namespaces"). Every transducer, reducing
function and `completing` argument is a real function ("The IFn dispatcher stays at the call
site"): every consumer wraps a set, map or keyword argument first, but a transducer a program
calls BY HAND with one signals where the oracle invokes it.

Deviations (`eduction`, `take-nth`: `doc/en/clojure/deviations.md`): `random-sample`'s
transducer arity is absent. `halt-when` lowers like the oracle's, but its marker is the private
`(:C%HALT value)` wrapper, not the oracle's `{::halt value}` map, so a final result that is a
map holding `:clojure.core/halt` answers itself where the oracle unwraps it, and a
`reduce`/`transduce` over an eduction that halts with a value answers the computed seq's
reduction, not the re-run's.

## Case mapping

The case verbs (`upper-case`, `lower-case`, `capitalize`, `.toUpperCase`/`.toLowerCase`, the
uppercase `format` conversions, `cl-format`'s `~(`) call `%clojure-upper-case`/`-lower-case`/
`-capitalize` (`clojure.lisp`, "Case mapping"): `String.toUpperCase`/`toLowerCase` in the
oracle's default locale (en_US; the tr/az/lt rules are not taken), SpecialCasing included.
Common Lisp's `string-upcase` stays a fold per code point (`.kb/characters-code-points.md`).
User-facing: `doc/en/clojure/deviations.md`.
- Tables generated from the JDK (`clojure/ClojureCaseMapping`, pruned like any definition): the
  102 code points whose `toUpperCase` is not `Character.toUpperCase`, U+0130 for `toLowerCase`,
  and 2,236 runs of the word class (delta-encoded, decoded on first use; skipping the 959,049
  code points with no case mapping: 650 -> 280 ms cold, `.kb/jvm-run-time-lowering.md`, "The
  first verb's read"). An ASCII string takes Common Lisp's fold, an ASCII literal folding while
  compiling.
- **Final_Sigma is the JDK's, not Unicode's** (`scheme.lisp`'s is Unicode 3.13's):
  `ConditionalSpecialCasing.isFinalCased` wants a cased character before the sigma and none
  after it within its WORD as the legacy `RuleBasedBreakIterator` draws one (`WordBreakRules`),
  cased being Lu/Ll/Lt plus a hard-coded list: `"Α1Σ"` is `"α1ς"` (a digit joins the word),
  `"Α..Σ"` `"α..σ"`. The walk reads `ClojureCaseMapping.wordKind`'s classes; modelled in Java
  it matches `String.toLowerCase(Locale.ROOT)` (JDK 25) on BMP strings, over every code point
  and random strings; `wordKind`'s javadoc names where the derivation differs from the JDK's
  compiled table.
- Deviation (user doc): in a word holding a character past the BMP the JDK sees a word end that
  is not there (`isBoundary(i)` is `following(i - 1)`, which starts INSIDE the surrogate pair
  and backs up through the backward state table); emulating it needs that table, not done.
- `capitalize` is `clojure.string`'s source over UTF-16 units, the rest lowered as a string of
  its own (`"ΑΣ"` -> `"Ασ"`); a supplementary first character stays as it is.
- Pins: `ClojureCaseMappingTest` (against the JDK: the first 1,024 code points, both sides of
  every run and special mapping, nine sigma templates, 4,000 random words), clojure-spec
  `the-case-verbs-map-as-java-strings-do` and the `~(` line of
  `clojure-pprint-cl-format-walks-its-arguments-like-the-oracle`.

## Regex

**Invariant: a pattern compiles, refuses (with the oracle's message) and matches as the
oracle's `Pattern` does, on every backend, through the engine every front end shares**
([regex-engine.md](regex-engine.md): `eval/regex.lisp`, written from the `Pattern`
documentation and a JDK's answers). `clojure.lisp`'s "Regular expressions" is what Clojure's verbs make of it
(user-facing: `doc/en/clojure/reference/regex.md`; the flags overload of `Pattern/compile`
stays the host's).
- `#"..."` reads to `(%regex source)`; past the read-time refusals
  (`ClojureReader.readRegexSource`) a refusal is the engine's when the literal evaluates (the
  oracle's at read time).
- **What a pattern may name decides what a program carries**:
  `ClojureStringLowering.reCompiler` picks a literal's entry, and so the engine providers it
  hands, by a textual over-approximation of its source (a backslash and `p`/`P`, or `(?` and a
  `U`: Unicode and category; else a backslash and `b`/`B`: category; else none; plus grapheme
  where it may name `\X` or `\b{g}`); a pattern made at run time is `%clojure-re-compile-any`,
  with every provider. `LibraryDefunPruner` drops the tables no entry reaches.
- `\N{name}`: `Character.codePointOf`'s ~35,000 names are too big to ship, so lowering a
  literal holding `\N` resolves every `{...}` text (with and without its `\Q`/`\E`) through
  `codePointOf` and hands the hits to the parse (`ClojureStringLowering.characterNames`), the
  engine matching a name's exact text; a pattern made at run time refuses `\N{..}` as
  `unsupported regex`.
- Deviations: [regex-engine.md](regex-engine.md), "Deviations".
- Pins: `ClojureRegexTest`, clojure-spec `a-regex-looks-ahead-and-behind`,
  `a-regex-takes-inline-flags`, `a-regex-names-its-groups`,
  `a-regex-parses-as-java-util-regex-does`, `a-regex-reads-unicode-properties`,
  `backslash-g-holds-where-the-last-match-ended`,
  `a-regex-matches-grapheme-clusters-and-character-names`,
  `a-class-escape-inside-brackets-matches-its-class` (the oracle's lines),
  `ClojureReaderTest#regexLiteralsReadAsMarkedSourceStrings`,
  `ClojureArmsTest#aRegexLiteralIsParsedByTheEntryItsSourceCallsFor`,
  `#aLiteralsCharacterNamesAreLookedUpWhileLowering`,
  `ClojureLibraryTest#aProgramCarriesTheRegexEnginesTablesOnlyWhereAPatternMayNameWhatReadsThem`.

**A matcher over a type implementing `CharSequence` reads it only as far as a match needs**
(`%clojure-re-lazy-matcher`, the CHAR_SEQUENCE family's second test): the search runs over the
prefix read so far; one that read where the prefix ends reads on (16 characters, then doubling)
and runs again from the matcher's state as it stood, so it answers the search over the whole
input. "Read the end" is the engine's mark (slot 2 of the search state): a character or a run
reaching the length, an anchor within two characters of it, a back reference past it, `\R` at
it, a whole match ending at it, a cluster reaching it, a find that tried every start. Marking
too much costs a longer prefix, never an answer. The groups come off the prefix, where the
oracle reads them back through `subSequence` (user doc). Pins:
`ClojureRegexTest#aMatcherOverACharSequenceTypeReadingAPrefixAnswersAsOverTheWholeInput` (a
random corpus, prefixes seeded at 1 and 3), clojure-spec
`re-matcher-and-re-find-read-a-char-sequence-type-only-as-far-as-a-match-needs` (a
billion-character type, instaparse's `Segment` terminal),
`ClojureLoweringTest#reMatcherAndReFindReadACharSequenceTypeThroughTheLazyMatcherOnlyWhereOneIsStored`.

**`nth` of a matcher reads `Matcher.group(n)`** (`%clojure-matcher-nth`, behind
`%clojure-nth`'s refusal clause), so destructuring a matcher binds its groups. Its test
`%clojure-matcher-value-p`, an alias of `%clojure-re-matcher-p` (used outside arm positions
too), is the test of `ClojureArms.Family.MATCHER`, produced by `%clojure-re-matcher` alone: a
program naming no `re-matcher` has the arm folded (`--dump-ir` of the 465 programs of
"Refusals" identical); the arm costs +338 B on wasm P1 and the component, +560 B in the JVM
class (2026-10-05, the engine of that day). Deviations: an index below zero or past the groups
answers the default where the oracle throws; without a default the call is the nil-default
shape, so a groupless pattern reads group 0 unless a non-nil default is given (the oracle's
three-argument `nth` reads no group of it). Pins: clojure-spec
`nth-of-a-matcher-reads-its-group`,
`ClojureArmsTest#theMatcherFamilyIsMadeByReMatcherAndFoldsNthsGroupArm`,
`ClojureLibraryTest#aProgramMakingNoMatcherSplicesNthWithoutItsMatcherArm`.

**An instance call of a `java.util.regex.Matcher` method on a matcher answers it on every
backend** (`ClojureValueMethodLowering.matcherMethod`, an arm of the MATCHER family, so a
program making no matcher keeps the plain refusal; the methods:
`doc/en/clojure/reference/re-matcher.md`; `hitEnd`, regions and `toMatchResult` stay refused).
Pin: clojure-spec `a-matcher-answers-the-java-matcher-methods`.

## Reading

**A hash past a token's first character is a constituent** in both readers
(`ClojureReader.readAtom`, `%clojure-rd-ends-token-p`; the oracle's non-terminating macro
character): `:div#main.box` (hiccup's tag syntax) and `a#'x` are one token; a number still
stops at it. Pins: `ClojureReaderTest#aHashInsideASymbolOrKeywordIsAConstituent`,
`#gensymSuffixReadsAsOneIdentifier`, clojure-spec
`a-hash-inside-a-symbol-or-keyword-is-a-constituent`.

**`##NaN`, `##Inf`, `##-Inf` read as doubles** in both readers, `##` reading the NEXT FORM like
the oracle's (`## Inf` reads); printing and the NaN key: `doc/en/clojure/syntax.md`.

**A double's exponent marker is uppercase** (`1.0E19`, like `Double.toString`): the
`%clojure-write` float arm upcases the Common Lisp printer's text, which stays unchanged. Pin:
clojure-spec `a-double-prints-its-exponent-marker-in-uppercase`.

**`read-string`/`read` run one reader in `clojure.lisp` (`%clojure-read-from`) over the source
reader's language, answering what a quote of the same text answers** (user doc:
`doc/en/clojure/reference/read-string.md`); a read map or set keys through "Structural keys",
and `::kw` resolves against the context each call site passes plus the namespaces
`isKnownNamespace` names and the startup ones, mirrored in `%clojure-rd-alias`.

The source reader refuses duplicate keys at read time comparing the READ forms, as the oracle's
`createWithCheck` does (`{[1] :a (1) :b}` is refused, `{:a 1 ::a 2}` is not). `1M` reads as a
decimal ("Decimals"), so `{1 :a 1M :b}` reads, like the oracle's. A `deps.edn` read leaves
maps to `ClojureDepsEdn.duplicateKey`, for its wording.

**Literal keys equal once evaluated** (`ClojureCollectionLowering.mapLiteral`/`setLiteral`, the
oracle's `MapExpr`/`SetExpr`; user-facing: `doc/en/clojure/syntax.md`): when every key is a
constant (`constantKey`: the oracle's `LiteralExpr`, which takes a NON-empty collection literal
of constants without metadata, `[]` and `()` being its `EmptyExpr`), two `=` ones are a
lower-time `LispReadException` (a set of constants dedupes); otherwise a literal of two or more
entries builds through `%clojure-map-literal`/`%clojure-set-literal` (the oracle's
`RT.map`/`RT.set` `createWithCheck`), where a table count short of the pair/member count reruns
the reader's builders over the evaluated forms to name the key: a map's EARLIER key, a set's
LATER member. One entry is never checked (the oracle's `mapUniqueKeys`). Not reproduced: past 8
entries the oracle names the later key, in its hash order. `()` IS `nil` ("Values"), so
`(get {[] 1} ())` is nil where the oracle answers `1`; the empty vector and `nil` stay two
keys, since one would merge `{nil 1 [] 2}` (two entries in the oracle) and make
`(let [a nil b []] {a 1 b 2})` a `Duplicate key` (clojure-spec
`structural-keys-find-equal-collections` pins `(get {[] :e} nil)` -> `nil`). Pins: clojure-spec
`a-literal-refuses-keys-equal-once-evaluated`,
`ClojureLoweringTest#aLiteralOfConstantKeysEqualOnceEvaluatedIsRefusedWhenLowered`.

A list's metadata of only `:line`/`:column`/`:end-line`/`:end-column` (what `nrepl.cmdline`
sends under `*print-meta*`) is dropped as read, its place kept as the datum's position
(`ClojureReader.readMeta`, `ClojureReaderTest#derefMetaVarAndDiscard`). `load-string` of a
literal string lowers where the call stands (`ClojureCoreLowering.loadStringOf`), of a computed
one: "Lowering at run time".

**Shared by `read-string`/`read` and clojure.edn**, like the oracle: metadata ("Vars and
metadata"), namespace maps, token and character-literal rules; the source reader's `#::` spells
a keyword key `::k` for the lowering and refuses a symbol key (no auto-resolved symbol spelling
exists). Size (2026-10-08, wasm P1): the reader machinery is roughly a tenth of a read-string
program, the metadata side table the largest part; the Unicode case table would have dominated,
so the `\ud800` message's hex digits are spelled by hand. A program that reads nothing is
byte-identical. Pins: clojure-spec `read-string-metadata-namespace-maps-and-token-refusals`,
`ClojureReaderTest#aNamespaceMapQualifiesItsKeys`.

**Reader macro classes** (`LispReader$StringReader`/`$RegexReader`, which instaparse's
`safe-read-string` calls) lower in `ClojureInteropLowering.clojureLangConstruction` (user doc:
`doc/en/clojure/reference/read.md`). Pin: clojure-spec
`a-reader-macro-class-reads-a-literals-body-from-a-stream`.

**clojure.edn** (`ClojureEdnLowering`) reads through the same reader in EDN mode, every clause
the EDN grammar decides behind `(%clojure-rd-edn-p)`, the arm test of `ClojureArms.Family.EDN`,
so a `read-string` program carries none (grammar: `doc/en/clojure/reference/clojure-edn.md`;
`.5` is a symbol only there, the source reader keeping its leniency). A tag reader runs outside
the read in progress (`%clojure-rd-edn-call` rebinds the read specials). Pins: clojure-spec
`clojure-edn-reads-data-only`, `clojure-edn-tagged-literals-options-and-streams`,
`ClojureLibraryTest#aProgramReadingNoEdnSplicesTheReaderWithoutItsEdnClauses`.

- **No `unread-char`** in the run-time reader: `ClojureLibrary` splices `clojure.lisp` whole
  before `UnreadCharLibrary` runs, so naming it would route every Clojure program's character
  reads through the pushback cell. One character of lookahead suffices because a token ends
  where the oracle's does; the source reader's `a#'x` split looks two ahead.
- A session buffer ending in a `#_` waits (`endsInDiscard`); `ClojureSession.isComplete` skips
  the character after a literal's backslash.
- **A double is `float` of the exact rational its digits spell** (the prelude's
  `%decimal-double`, shared with Scheme): every backend rounds once, ties to even
  (`.kb/wasm-bignum.md`, "Ratios"), so the run-time reader answers the double the source
  reader's `parseDouble` compiled; a value surely past the double range is decided from the
  mantissa's bit length. Not built from IEEE bits in Lisp: a log10 guard misjudged a long
  mantissa (a 903-digit `1e305` read as infinity). Verified against `parseDouble` on all four
  backends. `M` decimals stay exact ratios.
- A reading program registers every record/deftype class (`ClojureReadLowering.registration`).
- `(java.io.PushbackReader. x)`/`BufferedReader.` is `x` itself when it surely is a stream
  (`ClojureInteropLowering.isStreamForm`), else a `streamp` test around the `java:new`; over a
  `(StringReader. s)` argument `(%clojure-string-reader s)`; a `StringReader` alone stays the
  host class (a SAX `InputSource` takes one).
- Built messages go through `(error "~A" ...)`: a `~` in user text is no directive.
- Cost (2026-10-03, a read-string program against the literal): the reader roughly triples both
  artifacts, chiefly the regex parser (a `#"..."` in the input compiles at read time), the
  ratio and bignum arithmetic, and `intern`.

## Reader conditionals

**`#?(...)`/`#?@(...)` read like the oracle's `LispReader.ConditionalReader` where the oracle
reads them -- a `.cljc` file (`Compiler.load` decides by the file name, so
`ClojureReader(source, file)` does), a session (clj's REPL reads `{:read-cond :allow}`), and
`read-string`/`read` under `{:read-cond :allow}` -- and are `Conditional read not allowed`
everywhere else (a `.clj` file, text without a file, `deps.edn`).** Both readers implement one
algorithm (`ClojureReader.readConditional`, `%clojure-rd-conditional`):
- After a feature not taken, and after the taken form, every form up to `)` reads SUPPRESSED
  and drops, unpaired: a tagged literal and `#=` read their form and build nothing, every other
  syntax error still signals. A taken-nothing conditional is a discard.
- A splice pushes the members onto the PENDING forms every read takes first (the oracle's
  `pendingForms`): a list read shares its enclosing one, a quote/deref/var/meta/discard makes
  one at the top level and drops what is left there, so `'#?@(:clj [x y])` is `(quote x)`.
  About 80 oracle probes are pinned by `ClojureReaderTest` (`aReaderConditional*`,
  `aSplicing*`) and clojure-spec `read-string-takes-reader-conditionals-under-read-cond-allow`.
- **Features: `:rontolisp`, `:clj`, `:default`** (`ClojureReader.FEATURES`), plus a `:features`
  hash set at run time; the form's order decides (`#?(:clj a :rontolisp b)` is `a`). Decided on
  seven Clojars libraries' 339 conditionals (`:clj` 288 keys, `:cljs` 242, `:bb` 11): the
  `:clj`-only branches hold the `defmacro`s a cljc library defines for itself, the `:cljs`-only
  ones `:require-macros`, `goog` and `js/`, so a `:cljs` front end is structurally wrong;
  `:clj` keeps the oracle's answer for every branch. The own key follows babashka's `:bb`
  (malli writes `#?(:bb ... :clj ...)`): the one way to say "not the JVM branch here".
- A cljs-only libspec option outside a `:cljs` branch (`:include-macros`, `:refer-macros`) is
  accepted and ignored, as the oracle takes any require option it does not know (clojure-spec
  `cljs-only-libspec-options-are-accepted-and-ignored`).
- **`{:read-cond :preserve}`**: `%clojure-rd-preserved` reads the whole list into
  `(:C%READER-COND form splicing)` with `%clojure-rd-cond` rebound to `:C%PRESERVING`, so a
  nested `#?@` is one more member and a top-level splice reads; only there (the oracle's
  `READ_COND_ENV`) does any tag read as `(:C%TAGGED form tag)`. The values:
  `doc/en/clojure/reference/reader-conditional.md`; deviations: `doc/en/clojure/deviations.md`,
  plus the set order and `Duplicate key` text following `%clojure-hash`. Family
  `ClojureArms.Family.READER_VALUE` (producers: the opts entries and the constructors; the
  predicates alias `progn`), so `(read-string s)` carries no preserve clause. Pins:
  clojure-spec `read-cond-preserve-reads-reader-conditionals-and-tagged-literals`,
  `reader-conditional-and-tagged-literal-values-look-up-compare-and-print`,
  `ClojureArmsTest#theReaderValueFamilyFoldsThePredicatesAndTheReaderArmsOfAProgramReadingWithoutOptions`,
  `ClojureLibraryTest#aProgramReadingWithoutOptionsSplicesTheReaderWithoutItsPreserveClauses`.
- A symbol's `'` is a constituent (`coll'`) and a number stops at it, as in the oracle
  (`ClojureReaderTest#aQuoteInsideASymbol*`).

## Instants and UUIDs

**`#inst` and `#uuid` read through the oracle's default data readers in source, under
`read-string`/`read` and in `clojure.edn`, into values of this front end's own on all four
backends** (against the oracle on JDK 25 in time zone UTC): `(:C%INST ms)` a `java.util.Date`,
`(:C%TIMESTAMP ms nanos)`, `(:C%CALENDAR ms offset zone)` (`zone` the oracle's id `GMT-00:00`,
which `=` compares), `(:C%UUID msb lsb)`. `(= date timestamp)` is true by milliseconds and the
reverse false (the oracle's one-sided `equals`). User-facing:
`doc/en/clojure/reference/instants.md`.
- Timestamps (`clojure.lisp` "Instants and UUIDs" and its Java twin `ClojureDefaultReaders`):
  the oracle's pattern matched by hand, deepest component level first, backing off while what
  follows is no offset reaching the end (`2020-05:30` is 2020 at -05:30); the instant as the
  oracle's lenient `GregorianCalendar` computes it (`23:59:60` carries; in 1582 Julian before
  the cutover day -141427, so `1582-10-10` is the Julian date of `10-20`); year 0 prints
  `0001`. `str` of a Calendar is its printed form (deviation: the oracle dumps its fields). A
  UUID reads as `UUID.fromString`'s lenient parse.
- A source literal reads into the marker `(%inst ms)`/`(%uuid msb lsb)`; the lowering
  (`ClojureDefaultReaders.construction`) builds the Date of read(print(ms)), as the oracle's
  compiler embeds a `Date` constant by `RT.printString` and `readString`: a BC date comes back
  AD (`#inst "0000-01-01"` is year 1), one past 9999 fails when the code runs. A macro's
  Timestamp, Calendar or HOST `Date`/`Timestamp`/`Calendar`/`UUID` answer decodes the same way
  (`ClojureMacroLowering.decodeHostValue`, after the oracle's `print-dup`).
- Families `INSTANT` and `UUID` (`ClojureArms`): inside the runtime a type test is inline
  (`(eq (car b) :C%UUID)`), never a family test in a position the strip cannot fold; a program
  naming none of the value kinds is byte-identical (wasm P1, `--optimize=size`, the component,
  the JVM class).
- `ClojureClassBases` tables the four classes and `Calendar` (`TIME_VALUE_SUPERS`): a
  `defmethod` on `java.util.Date` dispatches a Timestamp through `isa?`, and `ancestors` of a
  class the program never spells is nil (a deviation). A protocol extended to `java.util.Date`
  reaches a Timestamp through the walk ("Dispatch").
- **The host members are the values made here** (`ClojureTimeValueLowering`): the constructions
  and statics build the values, never a `java:` call; `(UUID. a b)` long-casts both arguments,
  as the oracle's compiler emits `RT.longCast` for the one constructor; `System/nanoTime` is
  `%nano-time` (`.kb/time-environment-builtins.md`). A receiver known to be one of the three
  classes is treated as unknown, so the value arms take it.
  - Interpreter and JVM: an argument to a `java:` member crosses as the host object
    (`%clojure-host-value`, a family test per clause, so a UUID-only program builds no host
    Date); an argument that IS such a construction is rewritten to the host construction. A
    method no row answers is the host object's (`methodArm`, by reflection), a mutator refused
    by name; on wasm it is refused by name but `.toInstant` of a Date or Timestamp, which
    answers the instant itself ("The ISO_INSTANT formatter"). A Calendar never crosses
    ("Portable GregorianCalendar"). A host value a member answers stays host (user doc),
    ordered through a host `Comparable` arm in `%clojure-compare`.
  - Need (census of the local Clojars/contrib jars): constructions and statics in a dozen
    libraries, own Dates handed to Java, host Dates coming back. A program naming no `java:`
    operator, or making no instant or UUID, is byte-identical; a `java:` program making none
    pays the host `Comparable` arm.
  - Rejected: printing a host Date or UUID as `#inst`/`#uuid` through the host's
    `SimpleDateFormat` (it would weigh every printing `java:` program down); a Calendar
    crossing as a `GregorianCalendar` (a whole conversion for a value only
    `read-instant-calendar` makes and no measured library hands to Java); converting a host
    value back at every call result (`java:` may not learn a Clojure shape, and a Lisp-level
    wrap is a call on every `java:` result).
- `clojure.instant` and `clojure.uuid` ship as startup namespaces ("clojure.jar namespaces").
- Pins: clojure-spec `inst-literals-*`, `uuid-literals-*`, `inst-and-uuid-*`,
  `read-string-and-clojure-edn-read-inst-and-uuid-like-the-oracle`,
  `clojure-instant-parses-validates-and-reads-three-instants`; `ClojureDefaultReadersTest`
  (both halves against the JDK over seeded random inputs); `ClojureReaderTest#instAndUuid*`,
  `ClojureArmsTest#theInstantAndUuidFamilies*`,
  `ClojureLibraryTest#aProgramMakingNoInstantOrUuid*`,
  `ClojureInteropTest#instMsReadsAHostDateOrInstant*`. The host members: clojure-spec
  `date-and-uuid-constructions-and-statics-make-the-values-read-here`,
  `ClojureInteropTest#aDateOrUuidMadeHereCrossesTheJavaBoundaryAsTheHostObject`,
  `#aHostDateOrUuidIsEqualToAndComparesBesideOneMadeHere`,
  `ClojureWasmInteropRefusalTest#aDateMadeHereRefusesOnlyAHostOnlyMemberOnBothBackends`,
  `ClojureLibraryTest#aDateOrUuidConstructionMakesTheValueUnlessHandedStraightToAMember`.

## The ISO_INSTANT formatter

- **`DateTimeFormatter/ISO_INSTANT` where the host is not (wasm) is a portable value, which
  `.format` answers over an instant of this front end's own in ISO-8601**
  (`ClojureDateFormatterLowering`, `clojure.lisp` "The ISO_INSTANT formatter"; data.json's
  defaults read the field at load); where the host is, it stays the host's object. The
  `.format` arm rides inside the value branch (`ClojureValueMethodLowering.valueArm`, ahead of
  the rows: the host arm past the hostless refusal would otherwise take the value), so a
  program naming no one-argument `.format` compiles as before.
- The text is the oracle's `appendInstant`: the ISO chronology (proleptic Gregorian, no Julian
  cutover), the astronomical year, `Z`, the fraction in three, six or nine digits when not
  zero.
- Deviations: `doc/en/clojure/reference/instants.md`. Rejected: lowering every backend (a host
  `Instant` a member answers would then meet the wrapper, where it meets the host formatter
  through the host call data.json's write path needs).
- **Pins**: clojure-spec `iso-instant-is-a-portable-value-where-no-host-is`,
  `writer-append-of-a-range-writes-it-and-answers-the-writer`,
  `to-instant-of-a-date-or-timestamp-formats-through-iso-instant`;
  `ClojureWasmInteropRefusalTest#isoInstantFormatsInstantsOnBothBackends`,
  `#aDateMadeHereRefusesOnlyAHostOnlyMemberOnBothBackends` (`.toInstant` answers, another
  host-only member is refused by name);
  `ClojureInteropTest#writerAppendOfARangeWritesItAndAnswersTheWriter`.

## Data readers

**A tag a `data_readers.clj`/`.cljc` maps calls its var while the source is READ, after the
datums above it lowered, like the oracle's form-by-form `load`; its answer stands in the
literal's place, decoded like a macro's.** User-facing: `doc/en/clojure/syntax.md`, "Tagged
literals". Like the oracle, a tag in a `#_` discard still calls its reader (unbound before its
namespace loads), and a `binding` of `*data-readers*` reaches `read-string`/`read`, not a
literal inside the `binding`.
- **Discovery** (`ClojureSourcePath.dataReaders`, `ClojureDataReaders.of`) runs in
  `resolveProject`, so a refusal of the files is the program's first; the files read with
  `Tags.NONE` (only the defaults).
- **Two reads** (`ClojureLowering.Datums`): the first read feeds the pre-scan, a tag other than
  `#inst`/`#uuid` (or one whose form the default reader refuses) reading as
  `(%pending-tag tag form)`, unique for the duplicate-key check. When one is pending, or a
  default tag is mapped, pass two reads the text again a datum at a time, each after the
  lowering handed over the one above -- entry file, required file and session buffer alike; a
  datum whose first read was all pending is pre-scanned again. A pending tag reaching the
  lowering is `No reader function`. A program without one is read once.
- **The call** (`ClojureDataReaders.read`) runs in the macro-time evaluator under the reading
  file's `*ns*`/`*file*`, so it may call Java even for a wasm target, which only receives the
  decoded answer. An unmapped tag's `No reader function` adds `ClojureSourcePath.notSearched()`
  (an unfetched library may map it).
- **Run time**: `ClojureArms.Family.DATA_READERS` (producers: the two specials' symbols) guards
  the reader's data-reader clause, called outside the read in progress. A program with data
  readers that reads at run time or names `*data-readers*` gets the files' map as the special's
  root through a `defparameter` (`ClojureLowering.dataReadersRoot`), since a compiled program's
  library `defvar` runs first.

Deviations (`doc/en/clojure/deviations.md`): an answer needs a datum here (`decodeDatum`: a
deftype has no literal, a host value no spelling on wasm), so a function, a deftype instance
and a host object other than a `UUID`/`Date`/`Calendar` are refused where the oracle compiles
its `print-dup`; a `set!` of the specials reaches run-time reads only (the macro-time evaluator
runs no statement); a refusal names `in data reader` where the oracle prints the bare message.
Pins: `ClojureDataReadersTest` (a project on all four backends: answer kinds, refusal
positions, a session), clojure-spec `data-readers-read-a-tag-at-run-time-like-the-oracle`,
`ClojureReaderTest#aFirstReadLeavesATaggedLiteralToTheDataReaders`,
`#aTagIsARecordClassOnlyWhenItsNameIsDotted`,
`#theDataReadersReadATagAfterItsFormLikeTheOracle`,
`ClojureLibraryTest#aProgramNamingNoDataReaderSplicesTheReaderWithoutItsDataReaderClause`.

## Read-eval (`#=`)

`#=form` evaluates while the program lowers, on the compile host, and the answer stands as the
datum, so a compiled artifact carries the RESULT as a literal (motivated by
`examples/clojure/serve-html.clj`: what `net/serve-html.lisp`'s `#.` embeds, spelled the
oracle's way; user-facing: `doc/en/clojure/syntax.md`). The reader only marks it
(`(%read-eval form)`); `ClojureReadEval.resolve` evaluates the markers post-order over the next
top-level datum (`ClojureLowering.Datums.next`), after the datums above handed their
definitions over and before any macro sees the enclosing form. It reuses the macro evaluator,
quoting each raw argument like a data reader's form and decoding the answer like a macro's
(`ClojureMacroLowering.decodeDatum`, plus a `(:C%VAR ...)` answer back to `(var ns/name)`). The
rule is the oracle's `LispReader$EvalReader`, read by decompiling it. `*read-eval*` is a
lowering field (`readEvalEnabled`) flipped by a top-level `set!` of a literal, like
`assertEnabled`. A branch not taken reads it suppressed; a `#_` discard drops the marker
unevaluated. Divergences: `doc/en/clojure/deviations.md`; a var defined below the site is
`Can't resolve`, as in the oracle's load.

Pins: `ClojureReadEvalTest` (a project on all four backends: literal kinds, `*read-eval*`, a
data reader over `#=`, a session, the refusals), clojure-spec
`read-eval-embeds-computed-literals-at-compile-time`,
`ClojureReaderTest#readEvalReadsAsAMarkerForTheLowering`.

## Vars and metadata

**A var is `(:C%VAR "ns/name" cell)`, interned per name in `%clojure-var-table`**
(`ClojureVarLowering.varOf`). A `#'` site re-points what the cell holds, never the list, so `=`
holds and a var keys a map or set by identity (not a getter in the list: a map keyed by the var,
such as spec.test.alpha's `instrumented-vars`, missed it once another site re-pointed it). A var
takes a site's metadata on first use, then only from a site of a later definition (GEN), so a
site never undoes an `alter-meta!` of the definition it saw. `lookupVar`, not `resolveVar`: a
private var is reachable, like the oracle. Pin: clojure-spec `var-get-answers-the-root-of-a-var`.
User-facing deviations of this section and the next: `doc/en/clojure/deviations.md`.

- **Thread bindings through var objects**: `with-bindings`, `with-bindings*` and a
  `push-thread-bindings` popped in the `finally` of the next form's `try` (the oracle's `binding`
  expansion, which hiccup and honeysql write; `ClojureStateLowering.pairThreadBindings`) bind
  through each var's site binder, the `let` pairs `binding` lowers to. Sites carry binders only
  when `ClojureLowering.threadBinders` is on (the pre-scan saw a name, or a pass met one and the
  program lowers again; a session always), so a program naming none lowers byte-identically. Any
  other `push-`/`pop-thread-bindings` is refused: a frame left pushed has no dynamic extent to
  live in (as values at run time, `ClojureLoweringTest#lowerTimeOnlyVerbsRefuseAtRunTimeAsValues`);
  `get-thread-bindings` signals when called, `bound-fn*` snapshots instead. Pin: clojure-spec
  `thread-bindings-bind-vars-through-var-objects`.
- **Bound functions**: `bound-fn*` pushes the creation-time bindings of every program dynamic var
  and core special thread-bound where the call stands, plus the lexically enclosing
  `with-local-vars` cells. A core special is
  captured by its `clojure.core/` var, so a program var shadowing its name (tools.reader's
  `*suppress-read*`) is captured as itself. The capture enumerates `ClojureLowering.dynamicVars`,
  so a pass declaring more after a capture site lowers again; depth counters start at zero before
  anything runs, so a capture ahead of a `def` skips that var. Pin: clojure-spec
  `bound-fn-captures-the-creation-bindings`.
- **Local vars**: `with-local-vars` runs the inits after every cell exists and lowers the body
  behind the `try` barrier. Pin: clojure-spec `with-local-vars-binds-fresh-var-cells`.
- **`find-var`** of a computed symbol refuses at run time but resolves at macro time
  (`ClojureMacroTime`), so hiccup's `compile-multi` binds its `*html-mode*` while `html` expands.
  Pins: clojure-spec `thread-bindings-bind-vars-through-var-objects`,
  `ClojureLoweringTest#varQuoteAnswersTheInternedVarWithItsDefinitionsMetadata`.
- **Library vars** (`libraryVarOf`): `#'`, `resolve` and `find-var` reach a var of a namespace the
  lowering implements (camel-snake-kebab resolves `clojure.string/capitalize` while it expands).
- **Core vars** (`coreVarOf`): a name no program var claims, or a `clojure.core/` spelling, is a
  var over the core value (a macro's the delegate a bare core-macro name answers, so its deref
  hands back the fn), META `:name`/`:ns`/`:macro` only (the oracle's `:arglists`, `:doc`,
  `:added` and position would be a table per core name). The oracle's `clojure.main` binds `*ns*`,
  `*file*`, `*command-line-args*`, `*1`..`*e` and the print/compiler flags, not the streams or the
  rarer flags (`*print-dup*`, ...), so `thread-bound?` of `#'*out*` is false at the root and true
  under `binding`, `with-out-str`, `with-in-str` and an agent action, and of a main-bound flag
  always true. So a special `clojure.main` does not bind has a depth counter those forms rebind
  in a `let` pair, an arm of `ClojureArms.Family.STREAM_DEPTH`: a program reading no counter
  compiles byte-identically. Pins: clojure-spec
  `core-vars-read-their-core-value-and-the-stream-binding-depth`,
  `core-flag-specials-read-bind-and-assign`, `err-is-the-error-stream-and-rebinds-like-out`;
  `ClojureArmsTest#theStreamDepthFamilyDropsTheRebindingPairsOfAProgramReadingNoCounter`,
  `ClojureLibraryTest#aProgramReadingNoStreamDepthShedsTheRebindingPairs`.

- Metadata is recorded at lower time (`ClojureVarLowering.record`, kept across session buffers),
  in the oracle's order.
- **`clojure.lang.Var` members** (`ClojureValueMethodLowering.varRows`) are those tools.analyzer
  and tools.analyzer.jvm read; `.isDynamic` reads the site's depth reader, so a core special's var
  is dynamic.
- **Unbound vars.** A declared-never-defined name and a value-less `def` hold
  `(:C%UNBOUND "ns/name")`, truthy. A file is closed, so `ClojureLowering.lower` stores every such
  root right after the false binding and the site lowers to nothing: a `declare` never touches a
  bound root, and a definition below still finds it. A session stores it at the site and reads a
  declared name either way, since a later buffer may define it as a function or a value. A
  function's or macro's name declared too gets no root, so a `#'f` deref above the `defn` answers
  the function where the oracle's is unbound (kept). A macro body reads the var unbound, like the
  oracle's compile ("Macros"). The root's tests are arms of `ClojureArms.Family.UNBOUND`, producer
  `%clojure-unbound`, so a program making no unbound var compiles byte-identically; `bound?` pays
  a `:macro` read so that no IFn program tests for a NIL-getter macro var. Pins: clojure-spec
  `def-without-a-value-is-unbound-and-defn-is-called-directly`;
  `ClojureSessionTest#aDeclaredNameStaysOpenForALaterBuffer`,
  `ClojureLibraryTest#aProgramMakingNoUnboundRootSplicesTheLibraryWithoutItsUnboundArms`,
  `ClojureArmsTest#theUnboundRootFamilyStripsOnlyItsOwnArms`.
- **Streams as values.** At the root `*standard-output*`/`*standard-input*` hold the `t`
  designator, the same object as Clojure's `true`, so a value read of `*out*`/`*in*`
  (`ClojureLowering.specialRead`) lowers to the bound stream, or at the root to a library stream
  value (`:standard-output`/`:standard-input`) whose handle is `t` itself, which every operation
  resolves back to `t`: no backend learned a handle (not a `%STREAM` over handles 1/0: the JVM's
  `emitStderrBranch` treatment for both, a second stdin buffer on wasm). Trap:
  `LispMacroExpander.mayCreateStreamValues` counts a literal `(%obj-new '%STREAM ...)` as a
  producer, since the library value names no constructor. A `StringWriter`'s toString is read
  before anything is written: the target may be the stream itself. The stream arms
  (`ClojureArms.Family.STREAM`) are made by the Clojure-only wrappers a stream reaches a program
  through (`ClojureIoLowering.STREAM_PRODUCERS`, "clojure.java.io"), never by `open` or
  `make-string-output-stream`, which `ClojureLibrary.references` would read as a library reference
  in a Common Lisp program; `with-out-str`'s own stream reaches a value only through a read of
  `*out*`. `defmethod` on a stream class spelling is still refused (a keyword dispatch value
  works). Pins: clojure-spec `a-stream-prints-as-the-host-object-of-its-kind`,
  `class-of-a-stream-answers-the-host-class-of-its-kind`;
  `ClojureArmsTest#theStreamFamilyFoldsThePrinterArmOfAProgramMakingNoStream`,
  `ClojureLibraryTest#aProgramMakingNoStreamSplicesTheLibraryWithoutItsStreamArms`,
  `#classOfAStreamIsAnArmAProgramMakingNoStreamSheds`,
  `ClojureInteropTest#filesRoundTripThroughReaderAndLineSeq` and `ClojureWasmFileIoTest` (the file
  reader), the direction lines of `StringStreamPrograms`.
- **with-redefs.** A `defn` is a direct `defun` call, so a replaced root would never reach a call
  site: a redefinable var opts out, like the oracle's direct linking, its calls going through the
  value cell. Redefinable (`ClojureLowering.redefinable`): a `^:redef` name; a simple name some
  `with-redefs` binds (`scanRedefinitions` over every lowered file, namespace dropped, so a test's
  `(with-redefs [alias/f ...])` reaches the namespace it requires next); in an open program
  ("Lowering at run time") altering a computed root, a function whose qualified name a quote spells
  (spec.test.alpha's `instrument` resolves and alters those); a name built at run time is not
  seen, and its `alter-var-root` refuses. Every function of such a program
  redefinable was measured and rejected: the corpus's `named-class-dispatch-hits-the-nil-method`
  and two siblings lost a direct `defn`'s class-dispatch shape. A program naming no such var lowers
  byte-identically. A target already lowered as a direct call (its namespace loaded before the
  file naming it was scanned) goes into `redefMisses`, and `ClojureLowering.lower` lowers the
  program again with those keys seeded. Trap: the macro evaluator sees the first pass's
  definitions again, so a macro printing while it expands prints twice. A session cannot re-lower
  an evaluated input, so there such a target is refused, naming `^:redef`. A `clojure.core` var is
  refused: its verbs lower inline (the oracle's inlined `inc` ignores the redef too). Under a
  `binding` of a `^:dynamic` var the store changes the binding: CL has no portable global-value
  write under a dynamic binding. Pins: clojure-spec `with-redefs-replaces-var-roots-for-the-body`;
  `ClojureProjectNamespacesTest#aWithRedefsReachesTheCallsOfANamespaceLoadedBeforeIt*` (the
  reseed), `ClojureControlLoweringTest`.
- **alter-var-root** runs `f` under the var's `%clojure-monitor` (the `locking` mutex), as the
  oracle's `alterRoot` synchronizes on the reference. A literal `#'x` uses its site's setter, a
  literal `#'f` of a `defn` makes it a `with-redefs` target, any other var needs a setter its
  sites registered, which they do only when `ClojureLowering.rootSetters` is on (a computed
  `alter-var-root`, or `alter-var-root` as a value; then every function whose var the program
  takes is redefinable): a program with neither lowers its var sites unchanged. Without a setter
  it is a run-time `UnsupportedOperationException`.
- **alter-meta! / reset-meta!** take the oracle's IReference kinds (`%clojure-reference-p`).
  Pins: clojure-spec `alter-var-root-replaces-the-root-of-a-literal-var`,
  `alter-meta-and-reset-meta-replace-a-reference-metadata`; `ClojureAlterVarRootE2eTest`
  (computed vars, the expansion-only pass, the refusals),
  `ClojureSessionTest#aLaterBufferAltersTheRootOfAVarAnEarlierOneDefined`,
  `ClojureVarRootConcurrentRequestsTest` (request threads, `.kb/concurrent-served-requests.md`).
- `with-meta`/`vary-meta` record a shallow copy in the eq table `%clojure-meta-table` under
  `%clojure-meta-guard`, as the var and namespace tables are under theirs (all three made eagerly,
  `.kb/concurrent-served-requests.md`).
- **Symbols carrying metadata.** A Clojure symbol is an interned CL symbol, so no metadata can sit
  on it: a symbol carrying metadata is a wrapper `(:C%SYMBOL sym meta)`, which every verb taking a
  symbol reads as its bare symbol through an arm of `ClojureArms.Family.SYMBOL_META` (`=`'s before
  `equal` but past every kind that tells a wrapper apart, the printer's after the `print-meta`
  clause). Any `with-meta`/`vary-meta` makes the family live; `:arglists` drops its parameters'
  hints, so a hinted `defn` behind a `#'` makes no wrapper. Pins: clojure-spec
  `a-symbol-carrying-metadata-reads-as-its-bare-symbol`,
  `ClojureArmsTest#thePrintMetaFamilyAndTheSymbolMetaFamily...`.
- Reader `^m x` reads as `(%with-meta x m)` and drops everywhere except where the oracle keeps it:
  a collection literal, a `fn`/`reify` form (malli's `^{:type ::schema} (reify ...)`), a quote (a
  list on a `copy-list` of its constant) and a macro argument, which so sees a symbol or vector,
  not a `%with-meta` list. A syntax-quote template keeps it as the oracle's
  `(with-meta form (sq meta))` does, an unquoted form answering its value without it (the
  oracle's early return), so `` `(def ^:private ~n 1) `` defines a public var. A macro expansion
  decodes a symbol or collection carrying metadata back to `(%with-meta datum ...)`,
  evaluated on a literal like the oracle's `MetaExpr`.

## State

- `binding` is a sequential `let*`. A `^:dynamic` var adds a zeroed depth counter special
  (`%bound-depth`, for `SpecialVarCollector`) each `binding` rebinds one deeper; a `^:dynamic`
  `defn` keeps its `defun`, but its calls go through a `defparameter`. Only a dynamic var's `#'`
  site carries the depth reader `thread-bound?` reads, so a program with no dynamic var is
  unchanged. The counter has dynamic extent, so a `set!` in a callee outside the binding's lexical
  extent still sets it; at depth zero it is the oracle's root-binding refusal. A main-bound flag
  is a plain `setq` anywhere, like the oracle's top-level `set!`; the flags hold the oracle's
  `clj -M` roots (`ClojureCoreSpecials`).
- **The load's specials.** In the oracle `*ns*` is the namespace `ns`/`in-ns` switch to where
  they run (a function reads its caller's), and a required file runs with `*ns*`, `*file*` (its
  root-relative path) and `*source-path*` rebound. Here a require site binds the three around the
  init (`ClojureLowering.loading`) and a macro expands under them bound to its site.
  `the-ns`/`find-ns` know only names created above the call: namespaces exist only at lower time.
  The switches, pairs and defvars are arms of the `NS_SWITCH`/`FILE_SWITCH`/`SOURCE_PATH_SWITCH`
  families, so a program reading none of the three compiles byte-identically (an init holding
  only a switch is no init; init chunks are cut by the switch-free print length). Pins:
  clojure-spec
  `ns-is-the-current-namespace-and-the-load-specials-have-their-values`;
  `ClojureProjectNamespacesTest#aLoadingFileReadsItsOwnNamespaceAndPath*`,
  `ClojureArmsTest#theSwitchFamiliesDropTheSwitchesOfAProgramReadingNoLoadSpecial`,
  `ClojureLibraryTest#aProgramReadingNoLoadSpecialShedsTheirSwitches`.
- **Mappings and wirings.** `intern` of a name carrying metadata gives the var that map (the
  oracle's `setMeta`; tools.analyzer.jvm's `create-var` needs it). The six namespace mappings of a
  literal namespace answer what the lowering knows and share one program-wide
  `%clojure-shared-value` cell, so two references are `=` and `identical?` like the oracle's var
  roots. Past a hundred pairs a mapping is built in per-`defun` pieces merged at run time, so no
  method outgrows the backends, each piece also handed to the macro-time evaluator, so a macro
  body reads the mapping while it expands (tools.reader's `resolve-symbol`). A `*ns*` in a
  function body designates the namespace the body lowers in (the oracle reads it when the call
  runs). Pins (like the oracle): clojure-spec `all-ns-*`, `create-ns-*`, `ns-publics-*`,
  `ns-map-*` (`ns-map-answers-interns-refers-and-core-vars`), `alias-refer-*`, `intern-var-set-*`
  (`intern-var-set-and-with-redefs-fn`), `requiring-resolve-*`,
  `namespace-mapping-values-are-shared-across-references`;
  `ClojureLoweringTest#namespaceMappingsAnswerWhatTheLoweringKnows`,
  `#hostOnlyVarsAreRefusedWhenCalled`.
- `assert` reads `*assert*` where it expands, like the oracle's macroexpansion: a top-level
  `(set! *assert* literal)`, of a required namespace too, sets `ClojureLowering.assertEnabled`.
  Pins: clojure-spec `assert-reads-the-assert-flag-where-it-expands`,
  `ClojureSessionTest#aSetOfAssertInOneBufferSwitchesOffTheAssertsOfTheNext`.
- Print flags: the library defvars the flag specials too, since the interpreter loads it lazily,
  after the program's own; each flag's family (`PRINT_FLAGS`, `PRINT_META`) is made by its
  special. `*print-level*`'s test runs before a lazy seq realizes; `*print-length*`'s cut
  realizes a seq only that far. `*print-meta*`'s clause writes `^m ` and answers NIL, so X is
  written at its own level, before `*print-level*`'s `#`, like the oracle's `[^# #]`. Trap: its
  library root is the literal false object `'|false|`: a compiled program splices the library's
  defvars ahead of its own, so a `nil` root would print `nil`. `*print-namespace-maps*`' root is
  true, so `NAMESPACE_MAP` is made by a qualified key: a `(:C%KEYWORD "ns/name")` wrapper, a
  quoted `c%ns/name` symbol (a qualified name in code is a call), or a library function building
  one from a computed spelling, or calling one: the list
  `ClojureLibraryTest#everyLibraryFunctionBuildingAKeywordOrSymbolFromAComputedSpellingMakesANamespaceMap`
  pins by walking the library's call graph. Pins: clojure-spec
  `print-length-level-and-readably-shape-the-printer`,
  `print-meta-writes-the-metadata-ahead-of-the-value`,
  `a-map-whose-keys-share-a-namespace-prints-it-lifted`;
  `ClojureArmsTest#thePrintFlagFamilyFoldsTheCutTheLevelTheDepthAndTheReadableSwitch`,
  `#thePrintMetaFamily...`, `#theNamespaceMapFamilyIsMadeByAQualifiedKeywordOrSymbol`;
  `ClojureLibraryTest#aProgramNamingNoPrintFlagSplicesTheLibraryWithoutItsPrintArms`,
  `#aProgramNamingNoPrintMetaAndMakingNoQualifiedKeySplicesThePrinterWithoutTheirArms`.
- deftype mutable fields sit in a slot vector an inline method reads through `symbol-macrolet`
  (`.kb/symbol-macrolet.md`), so every read is live. The oracle compiles
  `fn`/`#()`/`letfn`/`reify`/`lazy-seq`/`for`/`dosync`/`proxy` to classes that copy the fields
  they read, so `capturingMutableFields` rebinds those as plain locals around them.
- STM (`clojure.lisp` "Refs and agents across request threads"): a `dosync` body runs holding no
  lock, and its writes only stage, so a concurrent body never reads an uncommitted write. At the
  commit, writers of ensured refs wait for the holder without sleeping (a sleep needs the
  component wait shim; with no other thread nothing is waited for); then, under
  `%clojure-stm-guard`, a changed read retries the body (its side effects run again, like the
  oracle's), else the writes install, each `commute` re-applies onto the latest value (twice
  uncontended, like the oracle's) and the validators and watches run. Only writing transactions
  contend; a read-only one still validates, like the oracle's. A validator rides in the ref's
  cell, not a registry. The spec's refs and agents are single-threaded, none relying on a
  retry.
  Agents: a send inside a transaction or an action is held until that succeeds and dropped if it
  fails (the oracle's `releasePendingSends`). A send appends under `%clojure-atom-guard`, and the
  sender finding the agent idle hands it to the shared pool
  (`clojure.lisp` "The shared agent pool"): up to four workers, actions outside every lock, so
  agents cannot deadlock and a slow action never blocks its sender. Workers start on demand and
  end at the empty take, not a fixed polling crew: a spawn that runs its body at once (the browser
  playground's) never returned, so a `send` hung the page. Futures have no pool to share, so the
  pool is a small executor behind the same `rontolisp:make-thread` seam. On wasm the
  sender-thread drain replaces it at the splice (`eval/ClojureLibrary`), so no thread primitive or
  `sleep` reaches a module. A failing action never signals; `send`/`await` then refuse through
  `C%E-NEW`, so they set `usedExInfo`. Deviation: `(await-for 0 a)` answers true (the oracle's
  races its pool). Pins (like the oracle): clojure-spec
  `transactions-commit-whole-and-agents-hold-sends`,
  `commute-applies-at-commit-and-ensure-pins-the-read`,
  `a-failing-action-fails-the-agent-until-restart-agent`,
  `agents-run-on-a-shared-pool-where-threads-exist` (the WASM legs skip),
  `agents-run-queued-actions-in-send-order`; `ClojureRefAgentConcurrentRequestsTest`,
  `ClojureStmConflictRetryTest` (concurrent bumps converge, an ensured ref's writer waits),
  `ClojureAgentPoolTest` (sends return at once, FIFO, error modes, wasm legs;
  `synchronousSpawn...` runs a spawn's body at once).
- **Futures and promises** (`clojure.lisp` "Futures and promises"). `future-call` runs the
  function `bound-fn*` conveys through a host `FutureTask` on its own thread, so done, cancelled,
  `cancel(true)` and `get` are the oracle's own. The body's condition is stored before it crosses
  the host call, and `deref` wraps it in the oracle's `ExecutionException`; a cancelled task stays
  cancelled whatever the runner stored while the interrupt arrived. The arms
  (`ClojureArms.Family#FUTURE`) are made by the runner and the promise constructor; on wasm the
  calls refuse at call time like every refusal of a construct the oracle accepts. Pins (like the
  oracle): clojure-spec
  `futures-deliver-their-value-and-the-predicates-read-them`,
  `promises-block-until-delivered-and-deref-takes-defaults`,
  `cancelled-and-failed-futures-answer-like-the-oracle` (the WASM legs skip);
  `ClojureInteropTest#futuresPromisesAndDeliverMatchTheOracle`,
  `ClojureLoweringTest#futuresAndPromisesLowerToHostThreadsAndRefuseOnlyWithoutThem`
  (+ `hostOnlyVarsAreRefusedWhenCalled`),
  `ClojureWasmInteropRefusalTest#futuresAndPromisesRefuseAtCallTimeOnBothBackends`.
- **Ref histories** (`clojure.lisp` "Ref histories"). Refs track a history only where the program
  names a history verb (the pre-scan's `usedRefHistory`; always in a session; the `REF_HISTORY`
  family). A commit bumps each tracked ref it wrote once, to `min(commits, min-history)`, the
  oracle's retained chain field by field (several writes in one transaction count once, a
  rollback never, `max-history` never), ahead of the watches, so a watch reads the new count.
  Pins (like the oracle): clojure-spec `ref-history-count-and-the-min-max-bounds`;
  `ClojureLoweringTest#refHistoryVerbsLowerToWorkersAndTrackRefs`,
  `ClojureArmsTest#theRefHistoryFamilyKeepsOnlyProgramsNamingAHistoryVerb`.
- **Validators and watches** (`clojure.lisp` "Validators and watches"). The watches are a fresh
  list per change, in the order the oracle's watch map (a `PersistentHashMap`) walks its keys (by
  the `%clojure-hasheq` trie index). A validator runs ahead of the store and a watch after it, on
  the storing thread, outside `%clojure-atom-guard`, which never runs program code. A ref's
  watches run after its commit's lock, ahead of the held sends; an agent's after each action, so
  `await`'s marker notifies with old = new, like the oracle's count-down action.
  `ClojureArms.Family.WATCH` is made by `add-watch`, `set-validator!` and any `atom` option
  (`atom` as a value too); without it atoms and refs keep the one-long cell. A later definition
  or `with-redefs` replacing a var's root validates and notifies like the oracle's `bindRoot`; the
  store's getter answers the unbound root while the cell is unbound, so the first binding never
  reads an unbound variable.
  `set-validator!` of a macro var checks the expander, since the var's site reads no value. A
  program naming no watch verb lowers byte-identically (the notify statements drop at top level
  and in `progn`); the macro evaluator skips the arms (`ClojureMacroLowering.handOverForm`). A
  session keeps the arms, so a later input's watch or validator sees every earlier root. Pins
  (like the oracle): clojure-spec
  `atom-options-and-validators-guard-every-store`,
  `swap-vals-and-reset-vals-answer-the-old-and-the-new-value`,
  `add-watch-calls-a-watch-after-every-change-of-an-atom`,
  `refs-agents-and-vars-call-their-watches-and-validators`,
  `a-def-with-redefs-and-a-validator-notify-a-var-s-watches`,
  `a-redefined-defn-and-a-dynamic-def-notify-a-var-s-watches`,
  `a-redefined-defmacro-notifies-a-var-s-watches`,
  `set-validator-of-a-computed-macro-var-answers-nil`,
  `a-redefined-deftest-notifies-a-var-s-watches`,
  `await-for-answers-whether-the-actions-ran-in-time`;
  `ClojureLibraryTest#aProgramGivingNoReferenceAValidatorOrWatchCallsThePlainAtomHelpers`,
  `#aProgramNamingNoWatchVerbLowersItsVarStoresUnchanged`,
  `#aProgramNamingNoWatchVerbLowersItsRedefinedMacroAndTestUnchanged`;
  `ClojureSessionTest#aLaterBufferRedefinitionNotifiesTheWatchesAnEarlierOneAdded`,
  `#aLaterBufferMacroRedefinitionNotifiesTheWatchesAnEarlierOneAdded`,
  `#aLaterBufferSetValidatorOfAComputedMacroVarAnswersNil`,
  `#aLaterBufferTestRedefinitionNotifiesTheWatchesAnEarlierOneAdded`;
  `ClojureAtomConcurrentRequestsTest` (its second round watches and validates under a burst).

## clojure.test

The shapes are the oracle's macro expansions, lowered; the runtime is `clojure.lisp`
(`%clojure-test-*`), one code path on all four backends. User-facing deviations:
`doc/en/clojure/deviations.md`.

- The pre-scan registers a `deftest` name as a function, so `(name)` calls it; a redefined one
  versions its functions like a `defn` and notifies ("State", validators and watches).
- `is` (behind the `try` barrier) picks its assertion kind like the oracle's `assert-expr`
  dispatch on the exact head spelling: a lower-time `assert-expr` method (test.check's `check?`),
  then `thrown?`/`thrown-with-msg?`, then a function-var predicate whose failure shows
  `(not (f values...))`, else the value kind.
- Reports go to `%clojure-test-out` (the oracle's `*test-out*`), so `with-out-str` never captures
  them. `report` and `assert-expr` are real multimethods (a `defmethod` on `assert-expr` also
  registers at lower time for `is`). `*testing-vars*` holds `:name` maps, not vars.
- A `defn` whose name or attr map carries a `:test` `(fn ...)` registers under `run-tests`, so
  test.check's `defspec` counts and reports; a macro varying the name's metadata keeps it
  ("Vars and metadata", symbols carrying metadata).
- `thrown?` matches like `catch` ("Catching"): a `#^Class` hint is dropped and a reflective
  refusal carries `IllegalArgumentException`, so a `thrown?` over one matches on the interpreter
  and the JVM. A `thrown? StackOverflowError` over a deep non-tail call passes on the JVM alone,
  the only backend catching a host `StackOverflowError`; the interpreter and WASM end after the
  `Testing ...` header. Over a deep named self tail call it fails everywhere, since every
  backend runs one in constant stack ([jvm-self-tail-calls.md](jvm-self-tail-calls.md)). Pins:
  `ClojureProjectNamespacesTest` (`aDeepNonTail...`), `RontoLispCliStreamsTest`
  (`aClojure...StackOverflow...`). Not fixed: a
  catchable depth guard on every call would have to track JIT-varying frame sizes
  (`interpreter-stack.md`); revisit only if depth guards become a product feature.
- `report`'s silence for every type but `:pass`/`:fail`/`:error`/`:summary` keeps test.check's
  progress quiet; its namespace loads with one stderr line (`report is not a multimethod`, the
  oracle's own message for a rebound report). On wasm test.check's file-and-line refuses, so a
  failing `defspec` reports as an error, still counted. Pins: clojure-spec
  `do-report-counts-and-prints-like-is`,
  `report-answers-nil-for-check-types-and-reads-testing-vars`, `assert-expr-methods-extend-is`,
  `a-defspec-shaped-macro-registers-its-test` and the real defspec runs below.

## A session

`ClojureSession` keeps the lowering across buffers: each buffer declares its top-level names into
the session first, and its inits evaluate against the OLD binding (like one file). The runtimes a
buffer first needs travel ahead of it, and the false binding ahead of them all, since a special's
root may be the false object (`*print-meta*`;
`ClojureSessionTest#theFalseBindingGoesAheadOfTheRuntimesOfTheFirstBuffer`). User-facing
behaviour: `doc/en/clojure/repl.md`.

Each input evaluates under one `handler-bind` (`ClojureLowering.evaluated`): a condition is stored
as `*e` and declined, so the REPL's report is unchanged; a lowering refusal records nothing (the
oracle's compiler exception does). `class` of `*e` needs the exception reader, which a session
emits like a catching file's (`needsExceptionReader`). Pins:
`PlaygroundReplTest#aClojureSessionKeepsItsLastResultsAndItsLastExceptionInTheHistoryVars`,
`ClojureSessionTest#anInputRecordsItsValueAndAnNsInputNil`.

A REPL refer (`ClojureLowering.referReplRequires`) loads its namespace only where an input first
names it, not eagerly: required up front, they cost the first input seconds cold and hundreds of
ms warm (measured 2026-10-09, interpreter, one JVM per case). A definition of the name replaces
the refer silently (the oracle throws `already refers to` for a non-core refer). Pins:
`ClojureSessionTest#userRefersWhatTheOraclesReplRequires*`, `#aReplRefer*`,
`PlaygroundReplTest#aClojureSessionRefersWhatTheOraclesReplRequiresAndAFileNone`.

A `def` nested in a session datum answers its var (`ClojureLowering.nestedDefAnswersVar`); a
file's keeps answering the value (oracle divergence, kept): the var per site would carry the `VAR`
runtime and a per-site cost into every program with a nested `def`, whose value is read almost
never.

## Tests

- `clojure-spec.yaml` via `ClojureSpecE2eTest`: one case per table row or builtin group,
  concatenated into one program and sliced back per case, on all four backends.
- `ClojureControlLoweringTest` (`case`/`condp`/`if-some`/`when-some`/`while`/`locking`/
  `with-redefs`: refusals, the per-kind `case` tests, the host-only monitor, the redefinable
  `defn`, the session refusal).
- `ClojureLoweringTest` (lowered shapes and refusals; `aLiteralScalarKeySkipsTheStructuralKeyRuntime`,
  `clojureSetWiresLikeClojureString`), `ClojureThrowablesTest` (class chains), `ClojureRefusalsTest`
  (the refusal carriers' chains), `ClojureClassBasesTest` (class rows), `ClojureReaderTest`,
  `ClojureSessionTest`, `ClojureProjectNamespacesTest` (a `deps.edn` project, all four backends,
  `aCljcFile*` a `.cljc` entry and namespace, `aNamespaceWhoseClassesCannotLoad*` a library read
  from its ClojureScript port over `testsupport/ClojureScriptPortFixture`; `MemoryClojureFiles`
  for the unit tests, `ClojureLoweringTest#aNamespaceLoadsItsCljFromAnyRootAheadOfItsCljc`).
- Reading: the `read-string-*`/`read-takes-*`/`str-spells-*` spec cases,
  `ClojureLoweringTest#readingVerbs*`/`#aReaderWrapper*`, `ClojureReaderTest#aDiscard*`/
  `#aCharacterLiteral*`, `ClojureSessionTest#aBufferRegisters*`,
  `ClojureInteropTest#readTakesBackWhatSpitWrote`, `ClojureWasmFileIoTest`;
  `ClojureDefaultReadersTest` (`#inst`/`#uuid` against the JDK, both halves);
  `ClojureDataReadersTest` (`data_readers` files, four backends).
- Interop and host IO: `ClojureInteropTest` (interpreter and JVM),
  `ClojureWasmInteropRefusalTest`, `ClojureWasmFileIoTest`, `ClojureWasmFileRefusalTest`,
  `ClojureJavaIoTest` (clojure.java.io's directories and resources, four backends).
- `ClojureArmsTest` (the sorted-collection, unbound-root, matcher, reducible, interface, refusal
  and fn-class strips).
- `ClojureRingAdapterTest`, `ClojureRingUtilTest` (the Ring namespaces), `ClojureHttpClientTest`,
  `ClojureHttpClientHostFetchE2eTest` and `FetchSpecE2eTest#clojureHttpClient` (the HTTP client),
  `ClojureWasmBoundaryTest`, `ClojureWitBoundaryTest` (the host boundary),
  `ClojureLanguageNamespacesTest` (where the clojure.jar namespaces come from).
- `ClojureLibraryTest` (the splice; `everyLibraryNameIsDefinedOnce`: a second `defun` of a
  `clojure.lisp` name replaces the first for every caller, which a wrong arity then shows only as
  a JVM compile warning), `SourceLanguageTest`, `RontoLispCliTest` and `PlaygroundReplTest` (the
  `clojure>` transcript, `--no-gc`), `examples/clojure/demo.clj` through `ExamplesE2eTest`.

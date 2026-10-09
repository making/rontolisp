# The EXPERIMENTAL Clojure front end (`am.ik.rontolisp.clojure`)

**Invariant: a Clojure program is read case-sensitively and LOWERED to the Common Lisp
core forms the pipeline already consumes; no backend learns a Clojure name.** There is
no IR beneath those forms to target instead: both backends dispatch on the canonical
operator NAME and `FreeVarAnalyzer` is a hand walker over the same names, so an unknown
head would be a silent three-way divergence. Status is experimental -- a small subset
by design, no subset or compatibility promise -- and every user surface says so
(`--source-language` help, the title of `doc/*/clojure/index.md`). `--no-gc` is refused
by name (`CompileFrontend.run`): it has no cons cell, no symbol and no closure.

The oracle is `clj` 1.12.6.1673: a behavior is decided by running it there, and every
`clojure-spec.yaml` line is diffed against it. The corpus is the shcloj4 book code
(`.todo/artefacts/b16-shcloj4-inventory/NOTES.md`: download, harness); its 27
`code/test/**` namespaces run as drivers `(require 'ns) (clojure.test/run-tests 'ns)` from
`test/`, under the project's own `deps.edn`.

## Where it sits

- `ClojureReader` (text -> datums) and `ClojureLowering` (the hub: datums -> core forms;
  the named cycle root in `PackageCycleTest`), plus one `Clojure*Lowering` slice per
  feature, each taking the hub first and re-entering it for subforms. The package sees
  only the AST types and `reader`; what it needs of `compiler` (the WIT reader, the boundary
  vocabulary) comes through `ClojureBoundary`, which `eval/ClojureHostBoundary` implements
  and the seam injects like `ClojureFiles` ("Host boundary").
- The run-time helpers are Lisp in `src/main/resources/am/ik/rontolisp/eval/clojure.lisp`,
  spliced by `eval/ClojureLibrary` (the `SchemeLibrary` shape). Each runtime (printer,
  STM, hierarchy, regex, ex-info, `clojure.test`, transducers) is referenced only when
  used and the pruner drops it otherwise, so a program without it pays nothing. Macro
  bodies run at lower time through `eval/ClojureMacroTime`.
- Reached ONLY through the seam: `eval/SourceLanguage.CLOJURE`, picked per FILE for `.clj`
  and `.cljc` ("Reader conditionals") or by `--source-language clojure` (`clj`) (`.kb/source-language.md`). A Common Lisp
  file may `(load "lib.clj")` and call `(c%name ...)` (`(|c%my.ns/name| ...)` for a
  namespaced one).
- The browser needs no Java-side change: `RontoPlayground` splices `ClojureLibrary`,
  `PlaygroundRepl` is language-agnostic, doc ` ```clojure ` fences are Run cells
  (`data-lang="clojure"`, `.kb/documentation-site.md`) checked by `DocExamplesTest`.
- A whole FILE is lowered at once. Pass one pre-scans every top-level definition name
  (`def`/`defn`/`declare`/`defmacro`/`defrecord`/`deftype`/`deftest`, following `ns`), so
  a form may use a definition below it. A REPL lowers through a session ("A session").
  Where a tagged literal waits for the data readers, pass two reads the text again a
  datum at a time ("Data readers").
- Errors name the innermost form's source position (`.kb/source-positions.md`).

## Values

| Clojure | representation | notes |
|---|---|---|
| identifier `foo` | symbol `c%foo`; a global var of namespace `n` is `c%n/foo` (`user`'s keep `c%foo`) | the prefix keeps every name off `LispNames` case labels, lambda-list keywords and `T`/`NIL`; spelling verbatim (`Foo` and `foo` apart); `:` -> `%c`, `%` -> `%%` keeps the map injective; a local never carries a namespace. Generated names add a lone `%` suffix no identifier spells (`%2`/`%*` arity helpers, `%defN`, `%macro`, `%bound-depth`, `%loaded`, `%init-N`, `%meta`, `%root`, `%local`) |
| `nil` / `true` | `NIL` / `T` | `nil` IS the empty list |
| `false` | the value of `rontolisp::%clojure-false`, a distinct non-`NIL` symbol spelled `false` | the `#f` treatment of `scheme.lisp`; every lowered test is an explicit null-or-false check on a temporary; the symbol `java:` passes as Java's false ("Java interop") |
| `:foo`, `:a/b` | `(:C%KEYWORD "foo")`, spelling verbatim | compared by `equal`; `::kw` / `::alias/kw` resolve at lower time against the current namespace (an unknown alias is the oracle's `Invalid token`) |
| `{k v}` | an `equal` hash table (`rontolisp:plist-hash-table`), never mutated: every verb builds a fresh one | the shared runtime (`.kb/hash-tables.md`), so persistence holds on all four backends with no per-backend code; a persistent-map library would add a representation every backend prints, hashes and compares. Collection keys go through "Structural keys" |
| `#{..}` | `(:C%SET table)`, each member under itself | a repeated literal element is refused when the read forms are `=` (`ClojureReader.equivKey`: `1`/`1N`, `[1]`/`(1)`, maps and sets in any order; `Duplicate key`); members `=` only once evaluated: "Literal keys equal once evaluated" |
| `sorted-map` / `sorted-set` (and `-by`) | `(:C%SORTED setp cmp items)`: a vector of `[k v]` entries or members in comparator order | "Sorted collections" |
| `[..]` | a CL vector (a `vector` call) | a string is a CL vector too, so `vector?`/`coll?` exclude strings |
| list, seq | a CL list | lazy seq: `(:C%LAZY cell)`, memoized through `rplaca`/`rplacd` ("Laziness") |
| atom, volatile, ref, agent | `(:C%ATOM #(value))`; a volatile's cell `#(value :C%VOLATILE)` | one cell shape, so STM verbs accept atoms; the second slot is only what `volatile?` reads |
| record / deftype / reify | `(:C%RECORD tag fields table class)` / `(:C%TYPE tag fields table class slots?)` / a fresh `:C%REIFY` tag | "Dispatch"; the interfaces a body implements are rows under the tag ("Host interfaces") |
| `#"re"` | `(:C%PATTERN stamp source ops ngroups)` | the stamp is a gensym, so `=` is identity like the oracle |
| `reduced`, var, nil dispatch value | `(:C%REDUCED x)`, `(:C%VAR "ns/name" getter)`, `(:C%NIL)` | |
| `ex-info` | a condition with message and data slots | |
| `#inst`, `#uuid` | `(:C%INST ms)` (`clojure.instant` also `:C%TIMESTAMP`, `:C%CALENDAR`), `(:C%UUID msb lsb)` | "Instants and UUIDs" |
| `1M`, `0.1M` / `2N` | exact ratio (`1/10`) / integer | decimals stay exact; neither prints its mark |
| `\a`, `"..."` | `LispChar`, `LispString` | exactly the oracle's spellings and escapes (`\uXXXX`, octal up to `\377`); anything else the oracle's refusal wording (`Unsupported character`, `Unsupported escape character`, `Invalid unicode escape`, `Invalid digit`, `Invalid character length`); a buffer ending mid-`\u` stays `truncated \u escape` so a session waits for more input |
| radix ints (`0x`, `Nr`, leading-`0` octal) | `LispInteger` (bignum past `long`) | a shaped token parsing to nothing is `Invalid number` |

Predicates answer `T`-or-false, as calls and as values, so `(map odd? [1 2])` prints
`(true false)`. Every core verb also names a function value (a lambda over the same
lowering, checking the oracle's arities) unless its row says otherwise.

## Locals named like a special

A local is lexical in the oracle, but a local spelled like a `^:dynamic` var of `user` would
lower to the var's own symbol, a `defparameter`'d special, and so bind it dynamically (a
function called in its scope would read the local). `localSym` gives such a local
`c%name%local` instead; every binding site (parameters, `let`, `loop`, destructuring,
`letfn`, `for`/`doseq`/`dotimes`, `catch`, `with-open`, `if-let`, deftype fields, ...) and
`symOf` go through it, `binding` keeps the var's symbol. The names are `shadowedSpecials`:
`*out*`/`*in*`/`*agent*` (aliases of specials) plus every `^:dynamic` `def`/`defn`/
`defonce` of `user` the pre-scan (`declare`) finds at any depth -- the proclamation is
program-wide, so a var defined below the local counts. Only a colliding local is renamed:
a program without one lowers byte-identically. A namespaced var (`c%ns/name`) never
collides. Measured 2026-10-03: `(defn f [*x*] (show))`, `(let [*x* 5] (show))`,
`((fn [*x*] (show)) 3)` answer the var's `1` on all four backends, like the oracle (they
answered `2 5 3` before).

## The lowering table

| Clojure | lowers to | notes |
|---|---|---|
| `defn` | `defun` of the mangled name, called directly | keeps the direct call and the tree shaker. Several arities: one `c%f%N` `defun` per arity (`%*` variadic) plus a dispatch `defun`; a wrong count signals `wrong number of arguments passed to: f`; at most one variadic clause and one clause per arity. In a body: statement position only, single-arity only |
| redefined `defn` | a fresh internal name per definition: the first keeps the bare name, later ones `%defN` | call sites below each definition call the newest; a value position (`#'f`, `(def g f)`) captures the one current there. Each namespace versions its own names; a session keeps the counts |
| a call to a `VARIABLE`-kind head | `funcall` of the value cell when it holds a real function (a `let` binding of one, a `def`'d one), else `rontolisp::%clojure-call` | the dispatcher applies functions and looks up collections, like `IFn`, so a parameter may hold a set or map; a `declare`d-never-defined name (`Kind.DECLARED`) calls its unbound root through the dispatcher (the oracle's `Attempting to call unbound fn`); a session keeps it a direct call, since a later buffer may define it |
| a call whose head is a compound form (`((fn ...) x)`, `((set v) x)`, `((first ks) m)`) | `funcall` when the head lowers to a `function`/`lambda`, else `rontolisp::%clojure-call` | a call result may be a set, map, vector or keyword, which the dispatcher looks up like `IFn`; a collection literal head and a `#'f` head keep their own rows |
| a call to a core name the program defines or binds | the program's call | `call` checks `known(name)` before the core rows |
| `declare` | `nil` (plus a `^:dynamic` name's hoisted declaim and counter) | a pre-scan forward declaration (`Kind.DECLARED`); a real definition wins. "Vars and metadata", unbound vars |
| `def` | top-level `setq` of the mangled name | inside a body it sets the global when the body runs. The value lowers against the OLD binding, so `(def p (memoize p))` after `(defn p ...)` captures `#'c%p`. A docstring and an attr map are skipped (recorded as var metadata); an attr map needs a value behind it and a lone string is the value |
| `defonce` | `def` unless `boundp` | a reload keeps the root |
| `defn-` | a private `defn` | "Namespaces and project files" |
| `fn` / `#(...)` | `lambda`; several arities one `lambda` over `&rest` dispatching per arity, each arity binding through `let*` | a named `fn` is a `labels` self-binding, an anonymous one only when a `recur` reaches it. `#()` READS as the oracle's `(fn* [p1__N# ...] (body))` (`ClojureReader.readAnonFn`; `fn*` lowers as `fn`): fixed parameters up to the highest `%N`, an unused lower one generated after the body, `& rest__N#` for `%&`, `%` inside a quote replaced too, a nested `#()` refused; its body is ONE call (`#(f a b)` -> `(f a b)`; several forms need `do`). N restarts per top-level form (the oracle's counter is process-wide): a parameter only has to differ from those of forms it nests in, and a case's printed spelling stays put wherever it sits in a file |
| destructuring (`let`/`loop`/`fn`/`defn`/`for`/`doseq`) | `let*` pairs over one temporary per pattern | vector: positional through `%clojure-nth` (nil past the end; a map or set refused, the oracle's `nth`), `&` rest through `%clojure-drop`, `:as`; map: the table-aware read with `:keys`/`:syms`/`:strs`/`:or`/`:as` (a qualified `:keys` entry binds the short name) of the init through `%clojure-destructure-map`, which reads a `seq?` (list or lazy seq, never a vector) as `seq-to-map-for-destructuring` does (one member itself, none `{}`, more pairs through `%clojure-plist-table` with an odd last member's `%clojure-merge-entry-plist`), so `& {:keys ...}` takes keyword arguments and `:as` binds that map; until 2026-10-08 the rest list was read as is and every keyword argument was nil, clojure-spec `a-map-pattern-reads-a-seq-as-keyword-arguments` (oracle-identical); nested; malformed shapes refused by name |
| `let` / `letfn` | `let*` (sequential) / one `labels` over every entry | `letfn` names are pre-scanned, so siblings call each other; each entry is its own `recur` target; a later entry shadows an earlier one |
| `loop` / `recur` | `labels` self call | "recur" |
| `->` `->>` `as->` `doto` `cond->` `cond->>` `some->` `some->>` | datum rewrites around one temporary | `as->` is nested `let`s (shadowing like the oracle); `some->` stops at `nil`, not `false`; a step over a collection literal signals |
| `list*` | a right fold of `cons` over the seq view | of one argument, its seq |
| `doseq` | nested `do` loops stepping the seq view with `%clojure-seq-rest`, answering `nil` (`ClojureLoopLowering.stepOf`) | never `dolist`: a realized lazy cons's tail is a wrapper, not list structure. `:when` skips, `:while` ends its level (an outer one the whole form), `:let` binds; an empty vector runs the body once |
| `for` | `(rontolisp::%clojure-for coll step depth)` | "Laziness". Modifiers as in `doseq` (an inner `:while` ends only its level); unknown keywords the oracle's `Invalid ... keyword`; an empty vector refused |
| `dotimes [i n]` | the core `dotimes` over `(truncate n)` | the oracle's `intCast` (`2.5` counts `0 1`); exactly one plain name |
| `dorun` / `doall` | `%clojure-dorun` / `-doall` (`-n` with a count) | walk to the end, answering `nil` / the collection; with a count `n + 1` members realize, like the oracle; `doall` never coerces |
| `if` `when` `cond` `do` `and` `or` `not` | the core forms over null-or-false tests | `cond`'s odd trailing arm is the default (the oracle refuses); `:else` is true; `and`/`or`/`assert` have no function value |
| `when-let` `if-let` `when-not` `if-not` `when-first` | `let*` over one temporary plus the test | `when-let`/`if-let` destructure, testing the whole init; `when-first` binds the head of the seq view |
| `if-some` `when-some` | `if-let`/`when-let` with `(null tmp)` as the test (`ClojureSeqLowering.testedBinding`, shared by all four) | `false` binds. The pattern destructures inside the taken branch only, so the else sees the outer names and a failing init destructures nothing; until 2026-10-08 `if-let`/`when-let` destructured before the test (`(let [x 1] (if-let [x nil] :t x))` answered nil, `(when-let [[a] false] a)` signalled `nth`), clojure-spec `if-let-and-when-let-destructure-only-in-the-taken-branch`. Refusals in the oracle's words (`if-some requires a vector for its binding`, `... exactly 2 forms in binding vector`, `... 1 or 2 forms after binding vector`) |
| `case` | `(let ((tmp e)) (if TEST1 R1 (if TEST2 R2 ... DEFAULT)))` (`ClojureControlLowering.caseOf`) | one test per constant, picked at lower time by its kind -- the oracle's hash-then-`=` (measured 2026-10-08, clj 1.12.6): `eql` for a number or char (`1.0` never matches `1`, `-0.0` never `0.0`, `##NaN` lowers to no test), `equal` for a string or keyword constant, `eq` for a symbol, `null`/`eq` for nil/true/false, `%clojure-equal` for a vector, map, set or `((1 2))` list constant (a vector constant matches a list or lazy seq). A list test is the alternatives. Duplicates are refused at lower time (`Duplicate case test constant: X`, `caseKey`: `1`=`1N`, `-0.0`=`0.0`, a map or set in any order, `[1]`=`((1))`; NaN never), an empty list test is refused (the oracle's `max` arity error). No default: `%clojure-illegal-argument-exception` over `No matching clause: ` + `str` of the value. Linear, never a jump table: `1M` is the integer `1` here (deviation) |
| `condp` | `(let* ((pred P) (expr E)) clauses)` (`ClojureControlLowering.condpOf`) | the oracle's `emit` recursion: a `:>>` second form takes three forms, else two; `(pred test expr)` through `callFun` (funcall for a real function value); `:>>` binds the answer and calls `f` on it; a lone trailing form the default; else the `case` refusal over `expr` |
| `while` | `(do () (falsey-test nil) body)` | answers nil; the body is non-tail (`recur` refused like the oracle's) |
| `locking` | host targets: `(let ((tmp x)) nil-check (rontolisp:with-mutex ((%clojure-monitor tmp)) body))`; wasm: the same without the mutex (`ClojureLowering.hostTarget`) | `%clojure-monitor` (clojure.lisp "Monitors") keeps one reentrant `make-mutex` per value in an `eql` table (a keyword by its spelling in an `equal` one), made under one guard mutex: a Ring handler runs one thread per request on the interpreter and the JVM. Both defvars are library definitions the pruner drops with the helper, so a program without `locking` and every wasm program carries none. nil is `NullPointerException` before the body (message `... "locklocal" is null`; the oracle's names a gensym). Body behind the `try` barrier |
| `with-redefs` | values bound in order, old roots saved, `(unwind-protect (progn (setq cell v)... body) (setq cell old)...)` (`ClojureVarLowering.withRedefsOf`) | "Vars and metadata", with-redefs |
| `try`/`catch`/`finally`/`throw` | `handler-case` inside `unwind-protect`; `throw` is `%clojure-throw` | one `handler-case` clause per catch, in order, of the type its class takes ("Catching"); `throw` signals an exception as itself (a caught one rethrows unchanged, a host `Throwable` as the `java:java-exception` carrying it, "Host exceptions"), anything else a `ClassCastException` (nil a `NullPointerException`) whose message is its rendering, so strings keep their message |
| `ex-info` `ex-data` `ex-message` `ex-cause`, `.getMessage` `.getLocalizedMessage` `.getCause` | one call to the `clojure.lisp` "Exceptions" function | see "Exceptions" |
| `assert` | `if` around the `AssertionError` carrier ("Refusals") | the `Assert failed:` message evaluates only on failure; it names the failed form, built only in the failure branch: rendered at lower time (`ClojureStringLowering.prSource`: symbols, keywords, integers, strings, chars, lists, vectors, maps) so no printer is linked, else `quote` through the readable `%clojure-str-of` (double, ratio, set, regex...) which links the collection printer (measured 2026-10-03: `(assert (nil? x))` wasm 3171 -> 3282 bytes; with a double in the form 3171 -> 54237 versus 21992 before) |
| `atom` `deref`/`@` `swap!` `reset!` `compare-and-set!` `volatile!` `vswap!` `vreset!` | reads/writes of the cell | answer the new value; `compare-and-set!` compares with `eql`; `seq`/`first`/`count`/`empty?`/`cons`/`conj` onto a cell signal like the oracle; `deref` also reads a host `Future`, with or without a timeout ("A host `Future` under `deref`"), and the rontolisp future the HTTP client answers ("HTTP client") |
| `ref` `dosync` `alter` `commute` `ref-set` `ensure` | the cell under the spliced STM runtime | "State" |
| `agent` `send` `send-off` `await` `shutdown-agents` | the cell as a synchronous agent | "State" |
| `binding` / `set!` | `let*` of specials plus a depth counter | "State" |
| `with-open` | `let*` plus `unwind-protect` closing in reverse | a stream closes through `close` on every backend, anything else through the `close` interop call; an empty vector is the bare body |
| `with-out-str` | `let*` rebinding `*standard-output*` to `make-string-output-stream`, read back | never a literal `with-output-to-string`: it flips a WASM module into EH mode |
| `with-in-str` / `read-line` | `let*` rebinding `*standard-input*` to `make-string-input-stream` (body behind the `try` barrier) / `(read-line *standard-input* nil nil)` | `read-line` as a value is `%clojure-read-line-v`; clojure-spec `with-in-str-binds-in-to-the-string-and-read-line-reads-it` |
| `time` | the value timed with `get-internal-real-time`, printing `Elapsed time: N.0 msecs` | a double like the oracle's `nanoTime` quotient (whole milliseconds here), so the book's `\d+\.\d+` match holds; only the shape pins |
| `*out*` `*in*` `*err*` `*agent*`, the flags, `*ns*` `*file*` `*source-path*` `*repl*` `*1`..`*e` | one special each (`ClojureCoreSpecials`): `*standard-output*`, `*standard-input*`, `*error-output*`, `C%AGENT`, `rontolisp::%clojure-<name>` (`%clojure-history-1`..`-e`); a stream READ as a value calls its `Special.reader` (`%clojure-out`/`-in`/`-err`, "Streams as values") | "State"; `binding`/`set!` take either spelling, bare or `clojure.core/`-qualified; a program var of the name wins |
| `defstruct` `struct` `struct-map` | a key vector behind the name plus fresh-table builders | missing keys `nil`, too many values signal |
| `with-meta` `meta` `vary-meta`, reader `^` | "Vars and metadata" | |
| `var` / `#'` | `(rontolisp::%clojure-var "ns/x" (lambda () ROOT) META)` | "Vars and metadata" |
| `defmacro` `macroexpand(-1)` `gensym`, `` ` `` `~` `~@` | "Macros" | |
| `defmulti` `defmethod` hierarchies `defprotocol` `defrecord` `deftype` `reify` `extend*` `satisfies?` `instance?` `class` | "Dispatch" | a body's `clojure.lang` interfaces and `Object` overrides: "Host interfaces" |
| `ns` `require` `use` `import` `in-ns` | alias and refer wiring; a project namespace's file loaded at the `require` | "Namespaces and project files" |
| `clojure.string` (`join` `split` `split-lines` `upper-case` `lower-case` `capitalize` `trim` `triml` `trimr` `trim-newline` `blank?` `starts-with?` `ends-with?` `includes?` `index-of` `last-index-of` `replace` `replace-first` `escape` `re-quote-replacement` `reverse`) | core string operations | reached as `alias/var`, `clojure.string/var` or a referred var. `split`/`replace` take a pattern (through the regex runtime) or a literal string/char (a plain string never compiles to a pattern). Empty literal-`split` input is `nil` (a pattern answers one empty part); a positive `split` limit caps, a negative keeps every part, else trailing empties drop. `index-of`'s start (and `.indexOf`'s) is clamped into `[0, length]` before CL's `search`, which refuses a start outside the string, so it reads like Java's: past the end nothing is found (an empty match is the length), a negative one is 0 (`ClojureStringLowering.searchFrom`); `trim` `triml` `trimr` `blank?` (and `.strip*`) trim the bag `ClojureStringLowering.trimBag` builds from `Character.isWhitespace` (9-13, 28-32, the Unicode space separators but U+00A0/U+2007/U+202F, U+2028/U+2029), the oracle's test -- verified on clj 1.12.6 over the whole BMP 2026-10-09; Java `.trim` is the `<= 32` bag (`javaTrimBag`); `trim-newline` stays `\n`/`\r` only. Pinned by clojure-spec `blank-and-the-trims-take-java-whitespace` |
| `clojure.set` (`union` `intersection` `difference` `select` `project` `rename-keys` `rename` `index` `map-invert` `join` `subset?` `superset?`: every public var) | `ClojureSetLowering`: one call to the spliced `rontolisp::%clojure-set-NAME` worker (`?` spelled `-p`, the variadic three over one list of their sets, `join` with a key map `-join-km`) after a lower-time arity check in the oracle's wording (`... passed to: clojure.set/NAME`); as a value `#'...-v` | the oracle's own algorithms, so an answer's kind follows the same input: `union` grows its largest input (bubble order and all; a vector or list there answers one, a map signals), `intersection` shrinks its smallest, `difference`/`select` the first; nil stays nil, an unchanged input is answered itself, a set changes in a fresh copy. Membership goes through the structural-key runtime; `contains?` on a vector is by index, like the oracle's. Relation members may be records: `join`'s merge keeps the first's record, `rename-keys` keeps it unless a declared field is renamed away. Answers carry no metadata. Corpus witness: shcloj4 `examples.test.sequences` `test-sets`/`test-joins` (`ClojureProjectNamespacesTest`); the whole namespace stays red on `examples.utils` (the `?.` macro), `clojure.xml` and `file-seq` (measured 2026-10-03: the load stops at `utils.clj:37:1`) |
| `ring.adapter.rontolisp` (`run-server`) | `(rontolisp::%http-serve (%clojure-ring-app f opts) (%clojure-ring-port opts) (%clojure-ring-host opts) (%clojure-ring-join opts))` (`ClojureRingLowering`) | "Ring adapter" |
| `rontolisp.wasm` (`defimport` `export`, a `defn`'s `:wasm/export`) | `rontolisp:wasm-import` hoisted ahead of the datum / `rontolisp:wasm-export` after the whole program, each passing the name as written as `:as`; a converting crossing behind a wrapper `defun` (`ClojureWasmLowering`) | "Host boundary" |
| `rontolisp.wit` (`import` `export` `provide`) | `rontolisp:wit-import` (hoisted) / `rontolisp:wit-export` (after the whole program), each with a `:names` table of the vars' symbols; `rontolisp:wit-provide` (`ClojureWitLowering`) | "Host boundary" |
| `ring.util.response` `ring.util.request` `ring.util.codec` `ring.util.mime-type` `ring.middleware.params` `ring.middleware.keyword-params` `ring.middleware.content-type` | Clojure source in the jar, loaded like a project file; `rontolisp.internal.ring/NAME` (their kernels) is one call to `rontolisp::%clojure-ring-NAME` (`ClojureKernelLowering`, which holds every `rontolisp.internal.*` kernel namespace with its arities) | "Ring util namespaces" |
| `rontolisp.http-client` (`request` `get` `post` `put` `delete` `head` `patch`) | Clojure source in the jar, loaded like a project file; `rontolisp.internal.http/request` is `rontolisp::%clojure-http-request`, `rontolisp.internal.http/fetch` is `rontolisp:fetch` itself (`ClojureKernelLowering`'s worker override) | "HTTP client" |
| `clojure.edn` (`read-string` `read`) | `ClojureEdnLowering`: one call to `rontolisp::%clojure-edn-read-string-1` / `-read-string` / `-read` after a lower-time arity check in the oracle's wording; as a value `#'...-v` | "Reading", clojure.edn |
| `clojure.walk` | Clojure source in the jar written for this front end, loaded like a project file (a startup namespace: on its first qualified name too) | "clojure.jar namespaces" |
| `clojure.template` | the same, loaded at its `require` | "clojure.jar namespaces" |
| `clojure.data` `clojure.zip` `clojure.datafy` `clojure.stacktrace` | the same, loaded at its `require` | "clojure.jar namespaces" |
| `clojure.core.protocols` | the same, a startup namespace like `clojure.walk` | "clojure.jar namespaces" |
| `clojure.core.reducers` | the same, loaded at its `require`; cat's accumulator is `rontolisp.internal.reducers/NAME`, one call to `rontolisp::%clojure-reducers-NAME` (`ClojureKernelLowering`) | "clojure.jar namespaces" |
| `clojure.pprint` | the same; its layout engine is `rontolisp.internal.pprint/NAME`, one call to `rontolisp::%clojure-pp-NAME` (`ClojureKernelLowering`) | "clojure.jar namespaces" |
| `clojure.math` | the same, loaded at its `require`; a double function's body is `rontolisp.internal.math/NAME`, lowered in place to `(%strict-math :name (rontolisp::%clojure-double a) ...)`, `round` and the long arithmetic one call to `rontolisp::%clojure-math-NAME` (`ClojureKernelLowering`) | "clojure.jar namespaces" |
| `subs`, `.substring` | `%clojure-subs`, the refusal family's alias of `subseq` ("Refusals") | a bound outside a string is the oracle's `StringIndexOutOfBoundsException` where a class is read; a double or ratio bound is truncated (`%clojure-string-bound`), a non-number one is refused as the oracle does ("Refusals") |
| `format` | the Java directives translated to `format` over Clojure-rendered arguments | literal format string only; `%s` like `str` (nil spells `null`), `%b`; `%e`/`%g`, flags and the rest refused |
| `spit` `slurp` `line-seq` `file-seq` | `with-open-file` of the `str` spelling / `rontolisp::%clojure-slurp` / a `read-line` loop / `%clojure-io-file-seq`, each with the io family's arm ("clojure.java.io"); with `:encoding` `%clojure-io-spit`/`-slurp` | every backend; wasm needs a `--dir` preopen (without it the open signals). `spit` supersedes unless `:append` is truthy, `nil` writes nothing. `slurp`, `line-seq` and `reader` take a path or an open stream (`streamp`, so a Gray instance -- the Ring `:body` -- too): `slurp` reads a stream to its end and closes it (the oracle's `with-open`; until 2026-10-08 it left it open), `reader` answers it, `line-seq` reads it strictly and never closes it. A read of a CLOSED stream -- `slurp`, `line-seq`, `.read`, `.readLine`, `read-line` -- goes through `%clojure-open-reader` (`open-stream-p`, else the `%clojure-io-exception` carrier: the oracle's `IOException: Stream closed`); before it the interpreter said `READ-CHAR expects an input stream`, the JVM an NPE, and both wasm backends READ a closed string input stream (its record is never marked closed, `.kb/read-load-streams.md`). `read` is not guarded: the oracle wraps the failure in a `LispReader$ReaderException` over a `LineNumberingPushbackReader` and not over a `PushbackReader`, which share one stream kind here. A second close is harmless on every backend (`.kb/read-load-streams.md`, "close on an already-closed stream"). Pins: clojure-spec `slurp-closes-the-stream-it-reads`, `ClojureInteropTest#filesRoundTripThroughReaderAndLineSeq`, `ClojureWasmFileIoTest`. `slurp` was an inline `read-char` loop per site until 2026-10-08. `clojure.java.io/reader` is the namespace's var since 2026-10-08 (before: `%clojure-reader`, a lowering), `file-seq` a lazy walk of Files ("clojure.java.io") |
| `read-string` `read` | `rontolisp::%clojure-read-string`/`-read` (`-opts` for an options map, `-v` as values) over the call site's namespace context | "Reading"; every backend |
| regex `#"..."`, `re-pattern` `re-matcher` `re-find` `re-seq` `re-matches` `re-groups` | `RONTOLISP::%CLOJURE-RE-COMPILE` and the spliced matcher | "Regex" |
| `map` `filter` `concat` | `rontolisp::%clojure-map`/`-filter`/`-concat` | any number of collections (`map` stops at the shortest); lazy when an input is lazy, strict otherwise ("Laziness"); a false object drops like nil |
| `reduce` / `apply` | `%clojure-reduce`/`-reduce-init` / CL `apply` over the whole-collection view of the last argument | `reduce` walks the seq view and stops at `reduced`, a record/deftype/reify with its own `CollReduce` row reducing through it ("clojure.jar namespaces", the reducible arms); its function is a real one ("The IFn dispatcher stays at the call site") |
| `first` `rest` `next` `seq` `cons` | `car`/`cdr` over `%clojure-seq`, `%clojure-cons` | the seq view: lists pass through, vectors/strings coerce, a map gives one two-vector per entry and a set its members (table walk order), nil is empty, anything else signals ("Seq verbs over a wrapper"); `cons` onto a lazy collection answers a wrapper |
| `nth` / `second` | `%clojure-nth` / `%clojure-seq-nth` | a vector or string indexed directly, a list, lazy seq or host object stepped, a matcher answering its group (`%clojure-matcher-nth`, "Regex"; `nth` refuses anything else, a map or set too, as the oracle's `UnsupportedOperationException`; `second` steps any seq view, refusing as `seq`); past either end the default (`nil` without one) |
| `take` `drop` | `%clojure-take`/`-drop`, stepping | `(take n infinite)` terminates, realizing exactly what it answers |
| `last` `butlast` `count` `empty?` `vec` `set` `sort` `sort-by` `reverse` `frequencies` `group-by` `select-keys` | strict, over the whole-collection view | `count` of a map/set/record is `hash-table-count`, of any other wrapper or a non-collection signals; `empty?` realizes one level and refuses a non-collection as `seq` does; `vec` refuses a non-collection as the oracle's `RuntimeException` ("Refusals"); `sort` without a comparator orders by `compare` (see "Sorted collections"), a comparator's answer is read like the oracle's `AFunction.compare` (`ClojureFilterLowering.comparatorBefore`: a number puts the first argument first when `(<= got -1)`, i.e. its integer part is negative -- `compare`, `(- a b)`; anything else when truthy -- `<`, `>`) |
| `keep` `keep-indexed` `map-indexed` `remove` `distinct` `interpose` `partition` `interleave` | one call to the spliced `rontolisp::%clojure-NAME` (`-indexed` for the indexed pair, `partition-v` as a value) | lazy-or-strict ("Laziness"); `keep` keeps `false`; `partition` drops an incomplete tail, refuses a pad; `interleave` stops at the shortest |
| `some` `every?` `take-while` `drop-while` `zipmap` | stepping, so an infinite input answers | `some` answers the predicate's value |
| `mapv` `filterv` `mapcat` | the realized result as a vector / appended seqs | `mapcat` is nil-safe like `concat` |
| `ffirst` `nfirst` | `car`/`cdr` of the seq of the head | each level seqs |
| `range` | a strict list (1/2/3-arity) | a zero step signals; an end-less `(range)` is refused (no chunking; spell it with `iterate`) |
| `lazy-seq` `lazy-cat` `repeat` `cycle` `iterate` `repeatedly` | "Laziness" | finite `repeat`/`repeatedly` arities answer strict lists |
| `drop-last` `split-at` `split-with` `take-last` `nthnext` `nthrest` `peek` `pop` `not-empty` `dedupe` `replace` `find` `subvec` `key` `val` `map-entry?` `rseq` `find-keyword` `partition-all` `partition-by` `min-key` `max-key` `juxt` `fnil` `every-pred` `some-fn` `update-keys` `update-vals` `reduce-kv` `test` `empty` `comparator` `hash-set` | `ClojureCoreLowering`: one call to the fixed-parameter `rontolisp::%clojure-NAME` worker after a lower-time arity check in the oracle's wording (`Wrong number of args (N) passed to: clojure.core/NAME`); as a value `#'rontolisp::%clojure-NAME-v`, checking at run time | `dedupe`/`partition-*`/`drop-last` are lazy-or-strict; `peek`/`pop` take vectors and lists (a strict seq peeks where the oracle's LazySeq throws); `some-fn` answers the oracle's exact failing value; `reduce-kv` walks maps/records/vectors and stops at `reduced`; a non-positive `partition-all` size signals (the oracle loops forever); `find` answers a map/record's stored key (`%clojure-table-key`, so a structural key answers its held representative), a vector's `[i x]` for an integer index in range, nil of nil, and signals on a set, string or list; `subvec` copies (a fresh vector, never a view), truncates a float bound, signals on a nil bound, a non-vector or a range out of bounds; `key`/`val` read a two-member non-string vector and signal otherwise, `map-entry?` is true of exactly those (a map entry IS a plain 2-vector here, so `(map-entry? [1 2])` is true where the oracle's is false; giving entries their own representation would touch `first`/`seq`/`find`/`reduce-kv`/destructuring/`=`/printing for a distinction only `clojure.walk`-style code reads), `rseq` answers a strict list of a vector (nil when empty) and signals on nil, a list, a seq, a string and a hash map; a sorted collection goes through its items vector (`%clojure-sorted-items`, "Sorted collections"), `find-keyword` is `keyword`'s one-argument arm (a two-argument call needs a nil-or-string namespace and a string name) and answers a never-used spelling's keyword where the oracle's is nil -- keywords are `(:C%KEYWORD spelling)` lists with no intern table, and one would cost every `keyword` call and every compiled output a global table; `empty` answers a fresh empty vector, map, set or sorted collection (comparator kept: `%clojure-sorted-with` over an empty items vector, a `cond` clause on `%clojure-sorted-p` the strip folds like any arm) carrying the metadata through `%clojure-put-meta`, nil for a string, nil and any non-collection, and signals `Can't create empty: <record class>` on a record; a list, lazy seq and seq answer nil (the empty-as-nil position, so no metadata; the oracle's `()`) and a map entry `[]` (the oracle nil); `comparator` is a closure over the predicate answering -1 / 1 / 0 from two `%clojure-truthy` tests (a false object counts as false); `hash-set` is `%clojure-set-of` over the argument list (`-v` takes the rest list); type errors use CL wording |
| `pmap` | `map` | no thread pool; the printed seq is the oracle's |
| `update` `update-in` `assoc-in` `get-in` `merge` `merge-with` `into` | fresh tables over the old pairs | the first three associate through `ClojureCollectionLowering.assocAnswer` like `assoc` (a vector level by index); `update-in` with no keys refused; `assoc-in` builds missing levels; `(merge)`/`merge-with` of no maps is nil; `merge` is `conj` folded over the maps: a later item that is a table is read inline, any other (nil, a record, a sorted map, a `[k v]` vector, a set or seq of entries) goes through `%clojure-merge-entry-plist` (one shared worker over `%clojure-seq-entry-plist`, named without `sorted` so a program building no sorted collection still carries none of it), so `(merge {} [1 2])` and `(merge {} (seq {1 2}))` answer `{1 2}` and a list of non-entries signals, like the oracle (measured 2026-10-03, clj 1.12.6; a seq of plain vectors stays accepted, as for `conj`) -- size of a program merging three maps: wasm 63,978 -> 64,535 B, JVM class 83,577 -> 87,317 B (inlining `conj`'s `entryPlist` arms at each later item instead was wasm 66,719, class 98,160); `into` targets lists/vectors/maps/sets through the reduce runtime, `(into to xform from)` is `%clojure-into-xf` |
| `assoc` `dissoc` `get` `contains?` `keys` `vals` `conj` `disj` `hash-map` `array-map` | table operations | `assoc` onto nil builds; onto a vector (`assocAnswer`'s run-time `vectorp` arm, `%clojure-vector-assoc`) a fresh whole copy, index = count appending, a non-integer key `Key must be integer`, out of range signalling, like the oracle -- measured 2026-10-03 on a map-only program: wasm 58,004 -> 60,479 B (dispatch alone +298 B, the `(setf aref)` write ~1 KB; `make-array` plus an `aref` loop instead of `copy-seq`/`coerce` saved 0.8 KB), 2M two-pair `assoc` calls 4.0 s wasm / 2.05 s JVM before and after; pinned by clojure-spec `assoc-on-a-vector-replaces-or-appends-by-index`, `update-and-the-nested-verbs-reach-into-vectors` (and `replace-maps-through-a-map-or-a-vector` for `replace`); odd pairs refused (at run time for values); `get` reads maps, records, sets (the member), vectors, strings, nil (a list or deftype answers the default); `conj` onto a map takes nil, a map, a sorted map, a `[k v]` vector, a set whose members are `[k v]` vectors, or a seq (strict or lazy) of them (`%clojure-seq-entry-plist`; a seq of plain vectors stays accepted for the same reason as a set of vectors -- `(conj {} (seq [[1 2]]))` is a `ClassCastException` in the oracle, measured 2026-10-03); a list of non-entries (`(k v)` included) and a set member that is a map, list, nil or string signal, like the oracle (measured 2026-10-03; the oracle also refuses a set of plain vectors, `(conj {} #{[1 2]})`, but a map entry is a plain 2-vector here, so that one stays accepted -- `(into {} #{[1 2]})` is the oracle's answer too); anything else onto a map signals; `(conj)` is `[]` |
| `sorted-map` `sorted-map-by` `sorted-set` `sorted-set-by` `subseq` `rsubseq` `compare` `vector-of` | `ClojureSortedLowering`: one call to `rontolisp::%clojure-sorted-make` / `-subseq` / `-subseq-5` / `-compare` / `-vector-of` after a lower-time arity check in the oracle's wording; as a value `#'rontolisp::%clojure-NAME-v` | a literal core test of `subseq`/`rsubseq` lowers to its keyword (`:>` ...); "Sorted collections" |
| a keyword, set, map or vector in call position or as a function value | the table-aware read / member / `nth` with an optional default | `({:a 1} :b :d)` is `:d`; a keyword or symbol reaching `%clojure-call`, a keyword function value (`ClojureFnLowering.keywordFn`, also a `defmulti` dispatch fn) or a quoted-symbol `defmulti` dispatch fn (`realFun` wraps it in `%clojure-as-fn`) takes one or two arguments and signals the oracle's `Wrong number of args (N) passed to: :kw` / `clojure.lang.Symbol` otherwise (a literal keyword head refuses at lower time) |
| `comp` `partial` `complement` `constantly` `identity` `memoize` `trampoline` | closures | `(comp)` is `identity`; `memoize` keys the argument list by `=` (`%clojure-memo-key`) |
| `=` / `not=` | the spliced `%clojure-equal` per neighbouring pair | maps structurally (nested), records by tag plus entries, deftype/reify by identity, sequentials (lists, vectors, lazy seqs, nil) element by element across kinds, two floats by CL `=` (-0.0 = 0.0, NaN not = NaN), else `equal` (a host object on the left asks its `equals`, handed a number, string, character, `true`, nil or host object -- never `false`, a keyword, a symbol or a collection: [eq-numbers.md](eq-numbers.md) "Host objects", `ClojureInteropTest#equalsOfAHostObjectAndAValueAsksTheLeftOperand`); a host `List`/`Map`/`Set` and a Clojure collection of its kind through the host-object family's arm `%clojure-host-equal-p`, either side first ([eq-numbers.md](eq-numbers.md) "Clojure `=` of a host collection"). One shared callee, not a `labels` per site: ten sites measured 87,050 -> 34,004 B of wasm |
| `<` `>` `<=` `>=` `==` `nil?` `false?` `true?` `boolean?` `boolean` `string?` `symbol?` `vector?` `fn?` | the CL test answering `T`-or-false | `==` is CL `=` (numeric across categories: `(== 1 1.0)`, `(== 0.0 -0.0)`; a non-number signals, `(==)` is refused at lower time); `<` `>` `<=` `>=` `==` as values are `&rest` lambdas over the CL function answering `T`-or-false (`(map < [1 2] [2 1])` is `(true false)`, not `(true nil)`). `fn?` is false for keywords, sets and maps |
| the type predicates (`coll?` `seq?` `sequential?` `map?` `set?` `list?` `record?` `seqable?` `associative?` `counted?` `indexed?` `reversible?` `ifn?`, `number?` `integer?` `int?` `double?` `float?` `ratio?` `rational?` `nat-int?` `pos-int?` `neg-int?` `infinite?` `NaN?`, `keyword?` `ident?` and the `simple-`/`qualified-` six, `char?` `var?` `volatile?` `realized?` `special-symbol?`, `inst?` `uuid?` `uri?` `class?`) | `ClojurePredicateLowering`: `(if TEST T false)` over one CL type predicate or one spliced `%clojure-is-NAME` helper (CL boolean); as a value a one-argument lambda over the same test | a wrapper is a cons whose car is a CL keyword, so every list test excludes keywords/atoms/vars/records/patterns (`coll?` answered true for them before). The host four go through `%clojure-host-instance-p` (a host arm, `isInstance` of the named class; NIL stand-in without `java:`); `inst?` and `uuid?` also take the program's own instants and UUIDs ("Instants and UUIDs"). `sorted?` is `%clojure-is-sorted`; `set?` and `reversible?` name the sorted-aware `%clojure-is-set`/`%clojure-is-reversible`, which a program building no sorted collection calls as `%clojure-set-p`/`%clojure-is-vector` ("Sorted collections"). `chunked-seq?` `decimal?` `bytes?` `delay?` are `(progn x false)`, and so are `reader-conditional?` `tagged-literal?` where nothing makes one ("Reader conditionals"): no value of that kind exists, which is the oracle's answer for every value a program here builds (not a refusal: `(if (future? x) @x x)` runs); `any?` is `(progn x T)`. `not-any?`/`not-every?` negate the inline `some`/`every?` loops; `identical?` is `eql` plus keyword spelling (keywords are fresh lists), so a host object is identical only to itself (`ClojureInteropTest.identicalOnHostObjectsIsIdentity`, [eq-numbers.md](eq-numbers.md) "Host objects"); `distinct?` goes through `%clojure-distinct-new-p`; `bound?` is false at the first var whose root is the unbound root ("Vars and metadata"; a var whose metadata says `:macro` is bound without taking its root, the oracle's own mark); `extends?` reads the protocol's tables at the type's `extendKeyForm` tag (literal names, no value form). `future?`, `future-done?`, `future-cancelled?` and `future-cancel` read a host `Future` ("A host `Future` under `deref`"). Arity refusals in the oracle's words. Pinned oracle-identical (clj 1.12.6, 2026-10-03) by clojure-spec `collection-predicates-*`, `number-predicates-*`, `name-predicates-*`, `any-not-any-*`, `var-volatile-*`, `predicates-of-kinds-*`, `special-symbol-*`, `extends-*`, `coll-is-false-for-the-tagged-wrappers`; the representation deviations by `the-seq-predicates-follow-the-list-representation`, `decimal-and-bigint-literals-are-plain-rationals`, `identical-compares-numbers-and-symbols-by-value`; the host four by `ClojureInteropTest.hostKindPredicatesTestTheHostClass` |
| `int` `long` | `rontolisp::%clojure-int-cast` / `%clojure-long-cast` (`ClojureCoreLowering.castOf`; `-int-v` / `-long-v` as a value, arity checked at run time), a literal argument folded at lower time through the cast of its own type (`foldedCast`: a double literal's `intCast(double)`, a long's, a bigint's or ratio's object cast), an out-of-range literal folding to its refusal carrier with the oracle's message | the oracle's `RT.intCast` / `RT.longCast` of an object: range checks and messages included; a non-literal double takes the object cast (`(int x)` of a var holding `1e10` "integer overflow", where the oracle's primitive-typed double -- a `let` local, `(* 2.0 x)` -- says "Value out of range for int: 2.0E10"); pinned oracle-identical (clj 1.12.6, 2026-10-08) by clojure-spec `int-and-long-are-the-oracles-range-checked-casts` |
| `char` `quot` | `code-char` / `truncate` | a non-number signals |
| `double` `float` `byte` `short` `num` | one call to `rontolisp::%clojure-NAME` (`-v` as a value, arity checked at run time); `byte`/`short` through `%clojure-cast-bounded` (a character's code, a ratio or double truncated, a double compared BEFORE truncating so `(byte 127.9)` refuses, NaN refuses; the refusal spells the value with `princ-to-string`), `double`/`float` widen to a double, `float` refusing past `3.4028234663852886e38`, `num` is the number itself or nil | a non-number signals; `float` holds a double (`(float 1/3)` prints `0.3333333333333333`, the oracle `0.33333334`); `vector-of :double`/`:float` call the same workers, `:byte`/`:short` keep the longCast path (the oracle's `(vector-of :byte 127.9)` is `[127]`); pinned by clojure-spec `primitive-casts-double-float-byte-short-and-num` |
| `bigint` `biginteger` `bigdec` `rationalize` `numerator` `denominator` `unchecked-int` `-long` `-short` `-byte` `-char` `-double` `-float` | one call to `rontolisp::%clojure-NAME` (`-v` as a value, arity checked at run time) | integers and ratios are plain Lisp rationals (the `N`/`M` literal rule), so `bigint`/`biginteger` truncate to an integer (a decimal string through `parse-integer` after a first/last character check, since `parse-integer` skips surrounding whitespace and `BigInteger` refuses it; `bigdec` of a string was already strict, both pinned by clojure-spec `bigint-and-bigdec-strings-refuse-surrounding-whitespace`) and `bigdec` answers a rational: an integer, a ratio only when its denominator is `2^a 5^b` (the oracle's `Non-terminating decimal expansion`), a string through `%clojure-parse-decimal`. `rationalize` and `bigdec` of a double read the SHORTEST decimal it prints as (`princ-to-string` parsed back: `(rationalize 0.1)` is `1/10`, the oracle's `BigDecimal.valueOf`; the Lisp `rationalize` answers the simplest rational within half an ulp, `0.3333333333333333` is `1/3` there, not `3333333333333333/10000000000000000`). `numerator`/`denominator` signal on an integer (the oracle's `ClassCastException` on a `Long`). The `unchecked-` casts follow the oracle's Java casts: an integer or ratio wraps two's-complement to the width (`%clojure-wrap-bits`), a double saturates first (int range for `int`/`short`/`byte`, long range for `long`/`char`; NaN 0) so `(unchecked-byte 1e20)` is `-1`, a character is accepted by `unchecked-int`/`-char` only; `unchecked-float` answers a double, the infinity past the float range; `-double` is `double`. Pinned oracle-identical (clj 1.12.6, 2026-10-03) by clojure-spec `number-conversions-bigint-bigdec-rationalize-and-the-unchecked-casts` (a bigint printed through `str`, a bigdec through `double`: the representation deviates) |
| `unchecked-add` `-subtract` `-multiply` `-inc` `-dec` `-negate` and the `-int` verbs (`unchecked-add-int` `-subtract-int` `-multiply-int` `-inc-int` `-dec-int` `-negate-int` `-divide-int` `-remainder-int`) | one call to `rontolisp::%clojure-NAME` (`-v` as a value, arity checked at run time) | the long verbs wrap an INTEGER result at 64 bits (`%mask-signed-field` over two integer operands, `%clojure-wrap-long` over the plain result otherwise; a double or ratio result passes, like the oracle's `Ops`), so `(unchecked-inc 9223372036854775807)` is `-9223372036854775808`. An integer past 64 bits is a plain integer here, so the oracle's bigint operand (`9223372036854775807N`), which its unchecked verbs add unwrapped, wraps too (`deviations.md`). The `-int` verbs cast each argument like `RT.intCast` (`%clojure-int-arg`: a ratio or double truncates, an integer outside the int range signals `integer overflow`, a double outside it signals, a non-number and a character signal) and wrap at 32 (`%mask-signed-field`); `-divide-int` truncates (only `-2147483648 / -1` wraps), both division verbs signal on a zero divisor. Pinned oracle-identical (clj 1.12.6, 2026-10-03) by clojure-spec `unchecked-arithmetic-verbs-wrap-at-long-and-int-width` |
| `name` `namespace` `keyword` `symbol` | spliced string workers over the demangled spelling | split at the first `/`, except the lone `/` (a name, no namespace, like the oracle) |
| `str` / `pr-str` | `concatenate` over `%clojure-str-of` parts | `nil` -> `""` (`pr-str`: `"nil"`), keywords with their colon, collections in Clojure notation -- readable inside under `str` too (strings quoted, nil spelled: the oracle's `toString`), so `spit` writes what `read` reads back |
| `print-str` / `prn-str` / `println-str` | `rontolisp::%clojure-print-str` over `(list parts...)` (a `&rest` lambda as a value) | prints to a private string stream, never a `*standard-output*` rebinding: the parts evaluate in the caller, so what one prints reaches the real output; nested strings follow `print`/`pr` (bare/quoted), unlike `str`/`pr-str` whose nested strings are always quoted |
| `println` `print` `pr` `prn` | one `%clojure-write-datum` per part straight to `*standard-output*`, `write-char` spaces, `terpri` | answers nil; with several parts and any computed one, every part binds to a temporary first (the oracle evaluates all arguments before printing) |
| `rand` `rand-int` `rand-nth` `shuffle` | draws from the program-owned generator (`.kb/random.md`) | no domain check; `rand-nth` of nil is nil, of an empty vector signals; `shuffle` pins membership, never order |
| `make-array` `aget` `aset` `alength` | general arrays (`aref`, `array-dimension`) | the element class is ignored; every backend |
| Java interop | "Java interop" | interpreter and JVM only |
| `quote` | `quote` with symbols mangled | vectors, maps and sets inside are rebuilt (a quoted list holding one becomes a `list` construction) |
| `comment` | `nil` | |
| refused by name | | `future` `delay` `force` `promise` `deliver` (no thread pool, memo cell or rendezvous); transients (`transient` ... `disj!`); `definterface` `gen-class` `gen-interface`; `use-fixtures`; `add-watch`/`remove-watch`; `load-string` `eval` (no compiler at run time) |

## Deviations

Each is a real work item unless the reason says otherwise.

- Printing (`clojure.lisp`, `%clojure-write`): `nil` prints `nil`, never `()` (an empty lazy
  seq prints `()`); map/set walk order is unspecified (the spec pins only single-entry maps
  and single-member sets); unreadable values print `#<..>` -- functions `#<procedure>`, atoms
  and agents `#<Atom v>`, an `ex-info` its condition, a hierarchy its hash table, a
  stream the oracle's `#object` without the hash ("Streams as values"), any
  other host object `#<java C>` (the oracle's `#object[C 0x.. "..."]` carries a hash; a
  host `List`/`Map`/`Set` prints readably as its Clojure kind, "Java interop") --
  while a deftype, reify and `reduced` print their
  wrapper lists (a type overriding `toString` the oracle's `#object` without the hash, "Host
  interfaces"); `str` of a lazy seq or record spells the contents where the oracle answers
  `Class@hash`; `*print-meta*` prints no reader `:line`/`:column` (no value carries
  them), `*print-dup*` is a plain value, `print-method` and `pprint` are absent;
  `~S`/`~A` on Clojure values stay CL notation (`format` is a CL surface). Cycles print with
  datum labels, copied from `%scheme-print` (sharing would splice `scheme.lisp` into every
  Clojure program).
- Type predicates follow the representation: `()` is nil (`seq?`/`list?`/`coll?`/`counted?` false); a strict seq is a list (`list?`/`counted?`/`realized?` true where the oracle's LazySeq is false); nothing is chunked; an `iterate`/`cycle` head is unrealized until forced; `M`/`N` literals are plain rationals (`decimal?` never true); `identical?` is `eql` (numbers, chars and symbols by value); `class?` of `(class 1)` is false (`class` answers a kind keyword).
- Map entries: an entry is a plain two-member vector, so `map-entry?` is true of every `[k v]` (the oracle: false for one the program built). `find-keyword` answers a never-used spelling's keyword (keywords are not interned; the oracle: nil).
- Sorted collections copy on every verb (O(n) per association, a hash map's cost), `class` answers `:map`/`:set`, an empty `subseq` walked from the start is `nil` (the oracle `()`), a `subseq` test passed as a value is recognized by its answers, `compare` orders strings by code point, and `vector-of` answers a plain vector ("Sorted collections").
- Structural keys: a stored collection key is the first `=` key of its kind the program
  stored, so its metadata and a nested member's spelling follow that object; the
  representatives live for the whole run, one per distinct value and kind.
- `(= [] nil)` is true (the oracle: false).
- Seqs: `rest`/`next` of empty is `nil` (the oracle prints `()`); `nth` past the end
  answers `nil` where the oracle throws; a strict input to `for`
  and to the lazy-or-strict verbs realizes at once; no chunking.
- Verbs assume the right collection kind; misuse may signal the CL type error instead of
  the oracle's.
- The whole-file pre-scan makes a definition shadow calls ABOVE it (the oracle's reach the
  core verb); a body defined above a `defn` redefinition still calls the older one (only
  `^:dynamic` names see the newest).
- In a REPL, a local named like a `^:dynamic` var a LATER input defines binds that var once
  it is defined (a function called in its scope reads the local's value; the oracle: the
  var). A file is pre-scanned whole, so there it is lexical ("Locals named like a special").
- An error naming no class (a refusal of a construct the oracle accepts) is
  taken by any catch but `ExceptionInfo`'s ("Catching"); a misuse a lower verb refuses first
  carries that verb's class ("Refusals"). Exceptions: see "Exceptions" and "Host exceptions".
- Dispatch: numeric host classes merge into `:number` for multimethods and protocols;
  class chains carry no interface or `Object` ("Class chains");
  protocol dispatch reads no hierarchy; two namespaces' records of one simple name share a
  tag; `class` answers a kind keyword (host classes exist on no wasm backend), an exception
  its class name as a keyword, a host object its host class (interpreter and JVM), which a
  hierarchy also reads as its simple name's kind (`ArrayList` `isa?` `IPersistentList`).
- Metadata: a derived value (`assoc`, `conj`, ...) starts without metadata; a symbol takes
  none; the side table keeps every object for the program's lifetime.
- The oracle-refused leniencies kept: an unquoted vector libspec in a bare `require`; an
  odd trailing `cond` arm.

## Exceptions

An exception is a condition on every backend (oracle-checked clj 1.12.6, 2026-10-03).

- One class per program, `C%E-EXCEPTION` (`ClojureStateLowering.exInfoRuntime`, spliced behind
  `usedExInfo`): class chain ("Catching"; `(car chain)` the class name), message, data, cause.
  Its report IS the oracle's `toString`
  (`%clojure-exception-string`: `clojure.lang.ExceptionInfo: m {data}`, `C: m`, `C`), so `str`,
  `.toString` (`valueToString`'s `(typep x 'condition)` disjunct), printing, the uncaught report
  and clojure.test's error line need no reader of their own. The runtime defines only
  `C%E-NEW` (class message data cause) and `C%E-PARTS` (the four, or NIL); every verb is a
  `clojure.lisp` function calling them by name, so only a program whose lowering set
  `usedExInfo` may reach one (a kept library function reaching them otherwise compiles as an
  undefined call). A `define-condition` stays in the program because the library pruner keeps
  every non-defun definition.
- `%clojure-exception-of`: a condition is itself (one standing for a host exception alone that
  exception's, "Host exceptions"), a host `Throwable` (`%clojure-host-throwable`,
  a host arm: NIL stand-in without `java:`) a new exception of its class chain (read at run time,
  `%clojure-host-chain`), message and cause, anything else NIL. `throw`, `ex-message`, `ex-cause` go through it; `ex-data` reads
  `C%E-PARTS` (a host throwable has no data). `ex-message` of a non-exception is `nil` (was the
  rendering before 2026-10-03); of a CL condition its report (`Division by zero`, the oracle's
  `Divide by zero`; the spec's `ex-message` case uses `(assoc [0 1] :a :x)`, from when wasm-GC
  trapped on division by zero -- it signals since 2026-10-04, and clojure-spec
  `catch-takes-a-division-by-zero-as-an-arithmetic-exception` pins the catch, oracle-identical).
- `ex-info` takes an optional cause; nil data is `{}` (the oracle's); a non-exception cause is
  refused.
- `Throwable->map` (2026-10-08) is `%clojure-throwable-to-map`, a reader like `ex-data`
  (`exReaderOf`, so the exception runtime travels): the oracle's map built in its key order --
  `:via` one `%clojure-throwable-via` map per throwable down `%clojure-ex-cause` (`:type` the
  class symbol of `%clojure-condition-chain`'s head, `:message`/`:data` when non-nil),
  `:trace`, the root's `:cause`/`:data`, `:phase` from the outer data (a record's fields
  too). A throwable has no frames, so `:trace` is `[]` and no `:at` (deviation; the oracle's
  answer for an empty stack trace). nil is the oracle's NPE, a non-throwable its
  `ClassCastException` (the message without the oracle's module tail). Pin: clojure-spec
  `throwable-to-map-answers-the-oracles-map-without-frames`.
- A construction is an exception (`ClojureInteropLowering.throwableConstruction`, untagged
  `(C. ...)`/`new`/`C/new`, value too) when `plainThrowable`: public concrete `Throwable`, no
  public field, every public method `Throwable`'s/`Object`'s or an override of `Throwable`'s --
  so the condition answers every member a program can call. Per arity, only where the class has
  the constructor: 0 `()`; 1 exactly `(String)`+`(Throwable)` -> `%clojure-exception-new-1`
  (an exception argument is the cause, its toString the message), `(String)` alone or a literal
  string to any string-accepting one-argument constructor (`AssertionError(Object)`) -> the
  message; 2 exactly `(String, Throwable)`. Anything else keeps `java:new`. A class with
  members (`java.net.URISyntaxException`) stays a host object until thrown.
- `.getMessage`/`.getLocalizedMessage`/`.getCause` with no argument on a receiver of unknown
  class or a plain throwable class is `%clojure-exception-method`: a condition answers from its
  exception, anything else `%clojure-host-method` (host arm; the stand-in refuses with
  `No matching field found: m`), so the lowering carries no `java:` operator and wasm compiles
  it without the `JAVA:CALL` warning.
- Deviations: `class` answers a keyword and `.printStackTrace`/`.getStackTrace` no frames
  ("Catching"); `.getClass` answers what `class` does ("Dispatch", "Class chains"); any other
  method but the three readers and `.toString` is refused; a runtime error's `str` has no class
  prefix; `throw` of a non-exception is a `ClassCastException` whose message is its rendering
  (the oracle's names the two classes; until 2026-10-03 it signalled the rendering as a plain
  error, which an `IllegalArgumentException` catch took).
- Size, wasm P1, measured 2026-10-04: `(println (ex-message (ex-info "m" {})))` 51,358 B;
  `(try (throw (ex-info ..)) (catch Exception e (ex-message e)))` 147,548 B, the caught
  condition reaching a printer (the report renderer); `(.toString 5)` 51,477 B; `(throw
  (Exception. "boom"))` + `.getMessage` 148,010 B. An `ex-info` program carried ~75 KB more
  until then: `%clojure-cause-of`'s refusal was `(error <computed string>)`, the
  object-designator expansion; a refusal carrier now ("Refusals").
- Pins: clojure-spec `get-message-reads-a-caught-runtime-error`,
  `throwable-constructions-are-exceptions-on-every-backend`, `ex-info-carries-data-through-throw`;
  `ClojureInteropTest.aThrownHostThrowableKeepsItsClassAndMessage` (interpreter and JVM).

## Catching

A catch takes an exception whose class is the class it names or a subclass of it, in clause
order, and anything else passes on (oracle-checked clj 1.12.6, 2026-10-03).

- Class chains (`ClojureThrowables`): the class's name, then each superclass's up to
  `java.lang.Throwable`, resolved at LOWERING time, so every backend tests the same list and none
  needs a hierarchy at run time. `PARENTS` is a table for every throwable a program names
  without an import (`JAVA_LANG`'s, with the classes between them and `Throwable`) and for the
  public `clojure.lang` throwables (not on this class path: `ExceptionInfo`, `ArityException`,
  `Compiler$CompilerException`, the two `ReaderException`s); anything else is host reflection.
  The table exists because a native image reflects only what its image holds (measured: a
  `Class.forName` test image found `IllegalArgumentException` and `FileNotFoundException`,
  not `StackOverflowError` or `java.sql.SQLException`); `ClojureThrowablesTest` pins its `java`
  rows to reflection and its completeness against `JAVA_LANG`. A construction bakes its chain
  (`throwableConstruction`); a catch class resolves like any class name -- an unknown one is the
  oracle's `Unable to resolve classname: Foo`, a non-throwable (`Object`, `String`)
  `Catch type is not a subclass of Throwable: C` (the oracle's `VerifyError`).
- Lowering: `(catch C e body)` is the clause `((and error (satisfies |C%E-CATCHES-<C>|)) (e)
  body)`, `Throwable` plain `error` (byte-identical to before). A condition no clause type takes
  is never caught, so it passes on with nothing signalled again. The first version caught
  everything and re-signalled with `(error c)`, whose object-designator expansion cost +100 KB
  of wasm on a one-`try` program (46,651 -> 147,149 B, the `%princ-piece` printer; `(error c)`
  alone in a CL program 46,175 -> 391,337 B). One predicate per caught class
  (`ClojureThrowables.catchRuntime`, emitted with the runtimes; a session emits a class's
  predicate ahead of the buffer that first catches it) calls `%clojure-catches` over the
  quoted chain: `satisfies` takes one symbol, so the chain cannot ride the clause.
  `thrown?`/`thrown-with-msg?` lower to the same clause around the body
  (`ClojureTestLowering.caughtOf`), so another class's exception reaches the `is` as an ERROR.
- `%clojure-catches` (`clojure.lisp`, "Catching"): an exception (`C%E-PARTS`) or a refusal
  ("Refusals") when the catch's class is in its own chain (`%clojure-exact-chain`); a runtime
  error by `%clojure-error-chain`, the class the oracle throws
  where the runtime signals that condition type: `arithmetic-error` `ArithmeticException`;
  `type-error` `ClassCastException`, `NullPointerException` for a nil datum,
  `IndexOutOfBoundsException` for an `(INTEGER 0 (D))` expected type,
  `UnsupportedOperationException` for `SEQUENCE` (`count` of a number), none for a nil expected
  type (the JVM pad's type-error synthesized from a raw host failure: a compiled `char` past
  the end); `program-error` `ArityException`; `file-error` `FileNotFoundException`. It matches
  when the catch's class is that class, a superclass or a subclass (the operation may throw a
  subclass: `aget`'s `ArrayIndexOutOfBoundsException`). A condition naming no class (a refusal
  of a construct the oracle accepts) matches every catch but
  `ExceptionInfo`'s, so a program that caught one keeps working. A host exception goes by the
  host's class ("Host exceptions").
- A program that catches by class but builds no exception carries `C%E-PARTS` answering NIL
  (`catchRuntime`'s reader) instead of the exception runtime (measured 2026-10-04: a one-`try`
  program 48,229 B of wasm, 64,068 B with an `ex-info` built beside it). A session emits the reader while no buffer built an
  exception; the exception runtime's own `C%E-PARTS` replaces it once one does.
- The type-error slots are read in place (`%clojure-type-error-class`): `(%obj-ref c 0)` /
  `(%obj-ref c 1)` behind `(%obj-is c '|%class-TYPE-ERROR| '|%class-SIMPLE-TYPE-ERROR|)`, the
  seeded classes whose layout the seed fixes (any other type-error subclass is a plain
  `ClassCastException`: a mixin first parent moves the slots, `closer-mop.lisp`'s note). By name
  (`type-error-datum`, i.e. `slot-value`) splices the run-time slot dispatch: measured
  2026-10-03, a typed catch 49,160 / 75,858 B (wasm / class) by name against 47,910 / 74,318 in
  place, and `class` of a caught condition 66,277 / 108,075 against 60,942 / 85,338 -- by name
  turned every stream write of the printer into an instance-unwrapping one.
- Size, measured 2026-10-03, wasm P1 / `--optimize=size` / component / JVM class, before ->
  after: `(println (try (inc 1) (catch Exception e :x)))` 46,651 / 39,096 / 47,992 / 70,405 ->
  47,910 / 40,235 / 49,425 / 74,318; `(try (throw (ex-info ..)) (catch Exception e (ex-message
  e)))` 146,474 / 119,741 / 150,207 / 141,233 -> 148,110 / 121,193 / 151,872 / 145,427;
  `(println (try (+ 1 "a") (catch Throwable e (class e))))` (a refusal before) 59,197 / 48,286 /
  60,809 / 81,081 -> 60,942 / 49,979 / 62,577 / 85,338; a `class` multimethod catching with a
  `.printStackTrace` 184,272 / 149,696 / 188,102 / 186,565 -> 185,590 / 150,861 / 189,426 /
  169,400 (the class no longer carries the `java:call` the method went through); a `Throwable`
  catch, a program with no catch, a `class` multimethod with none and `examples/clojure/demo.clj`
  byte-identical.
- `class`, `instance?` and the stack-trace methods read the class a program can name
  (`%clojure-condition-chain`): an exception's or a refusal's own, a runtime error's from
  `%clojure-error-chain`, `RuntimeException`'s for a condition naming none. `class` answers `(:C%KEYWORD "<class>")`, the
  keyword shape of every kind, through an arm of `classForm` ahead of the host arm
  (`%clojure-exception-p`, family `ClojureArms.Family.EXCEPTION`, whose producer is a
  definition of `C%E-PARTS`): a program that can hold no condition sheds it and compiles as
  before. The catch runtime's reader therefore also travels where `class` meets a catch of
  any class, and wherever `instance?` names a throwable class (`needsExceptionReader`).
  `instance?` of a throwable class is `%clojure-instance-of` over the chain (exact, so an
  error naming no class is an instance of RuntimeException and its superclasses only, where
  a catch takes it whatever it names); a host `Throwable` asks the host. `.printStackTrace` /
  `.getStackTrace` of a receiver of unknown or plain-throwable class are
  `%clojure-print-stack-trace` (the report line to `*error-output*`) /
  `%clojure-stack-trace` (`[]`), the host method for anything but a condition; they read no
  slot, so they need no reader. A `defmethod` over a throwable class dispatches by
  inheritance ("Dispatch", "Class chains").
- Measured against the oracle: 52 runtime-error kinds under a catch of the oracle's class, all
  four backends alike, differ only where documented (`nth` past the end answers nil; the
  division message); the shcloj4 drivers with `catch`/`thrown?` sites (interop, exploring,
  life-without-multi, macros, chain-1/3) report what they did before on the interpreter and the
  JVM.
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

**A refusal of the run-time library or of the lowering carries the class the oracle throws
for the same call**, decided where it is detected (oracle-checked clj 1.12.6, 2026-10-04).

- Carriers (`clojure.lisp` "Refusals", `ClojureRefusals`): one function per class over the
  message (`%clojure-illegal-argument-exception`, `-illegal-state-`, `-class-cast-`,
  `-null-pointer-`, `-index-out-of-bounds-`, `-string-index-out-of-bounds-`,
  `-unsupported-operation-`, `-number-format-`, `-arithmetic-`, `-arity-`, `-runtime-`,
  `-class-not-found-`, `-pattern-syntax-`, `-illegal-format-conversion-`, `-io-exception`, `-sax-parse-exception` (clojure.xml),
  `%clojure-exception` for `java.lang.Exception`, `%clojure-assertion-error`), each signalling
  through `%clojure-refuse` -- the one typed signal -- a `%clojure-refusal`, a `simple-error`
  whose third slot holds the chain (read in place by `%clojure-exact-chain`) and whose report
  is the message. Where the oracle casts a value it was handed, the `-of` carriers
  (`-class-cast-exception-of`, `-illegal-argument-exception-of`) take that value too: nil is
  its NullPointerException, the method call on the nil it cast. `%clojure-map-entry-refusal`
  is the seq-then-cast of a map's `conj`/`merge`/`reduce-kv`: IAE for a value that cannot be
  seqed (or a vector, no pair), else CCE. A message is a literal, `(format nil control
  args...)` or a form answering the text. The lowering builds the same calls
  (`ClojureRefusals.refusal`/`formatted`).
- **Free where nothing reads a class** (`ClojureArms.Family.REFUSAL`, after `EXCEPTION`): only
  a catch by class (`%clojure-catches`), `class` or `instance?` of a condition can tell a
  refusal from the plain error with its message, so those are the producers. Anywhere else the
  strip folds each carrier back to that error -- a literal with each `~` doubled (its text
  control; a literal without one is itself), `(format nil c a...)` to `(error c a...)`,
  anything else `(error "~A" x)` -- drops the value (a variable or a read of one), folds
  `%clojure-refusal-p`, drops the `define-condition`, and turns `%clojure-subs`, `%clojure-char-at` and their `-by-reflection`
  aliases back into `subseq` / `char`. Such a program compiles to the bytes it did before, except where a site was
  `(error <computed string>)`: that object-designator expansion pulled the run-time dispatch
  and the printer, `(error "~A" x)` does not (measured 2026-10-04: an `ex-info` doc example
  134,530 -> 60,598 B of wasm, `assert` with a message 161,811 -> 94,454, the hierarchy and
  proxy runtimes 137-1,642 B less JVM class). The uncaught report is the message either way,
  so a catch elsewhere changes nothing a program prints.
- **The condition gate** (`.kb/error-handling.md`, "The routing gate"): `%clojure-refuse`'s
  signal over the text control of a variable reports that variable, so it routes no report,
  and the `define-condition`'s unreferenced keyword constructor builds nothing. Without either,
  every class-reading program carried the renderer and the printer hook (+10.5 KB wasm,
  +9.5 KB JVM class on a one-`try` program).
- `vec` is `(coerce (%clojure-realize-all (%clojure-vec-arg x)) 'vector)`: the oracle's `vec` casts a
  non-collection to an array before it seqs it, so a number, keyword, symbol, boolean, character,
  function, atom, var, reduced value or matcher is `RuntimeException` ("Unable to convert: class ...
  to Object[]"), which an `IllegalArgumentException` catch does not take, where `seq` and the other
  verbs refuse it as IAE. `%clojure-vec-arg` is the family's one VIEW (`ClojureArms.Family.REFUSAL`,
  `ClojureRefusals.VEC_ARG`): its test list is `%clojure-strict-seq`'s, in its order, and a program
  reading no class folds it to its argument, so such a program compiles the bare coercion
  (`--dump-ir` of 458 doc examples + `demo.clj` + probes, 465 programs: 463 identical, the two that
  differ are a `vec` under a catch by class and a matcher read by `nth`). A lazy seq whose thunk
  answers a non-collection stays `seq`'s IAE, as in the oracle (only the argument is cast). Cost in a
  class-reading program, wasm P1 / component / JVM class: `(try (vec 5) (catch ...))` 51,620 / 53,270 /
  73,075 -> 51,892 / 53,532 / 73,685 (2026-10-05). Pin: clojure-spec
  `vec-of-a-non-collection-refuses-with-the-runtime-exception-the-oracle-throws`,
  `ClojureArmsTest#theRefusalFamilyFoldsVecsArgumentCheckToTheArgument`,
  `ClojureLibraryTest#vecRefusesANonCollectionAsRuntimeExceptionOnlyWhereAClassIsRead`.
- `subs` and `.substring` are `%clojure-subs`, the family's alias of `subseq`: a bound outside
  a string is the oracle's `StringIndexOutOfBoundsException`, in `subseq`'s words. `.charAt` is
  `%clojure-char-at`, the alias of `char`: an index outside a string is the same class, in
  `char`'s words (`CHAR: The value 5 is not of type (INTEGER 0 (3))`), so a stripped program
  reports what the interpreter's `char` reports (the compiled `char` of such a program stays
  unchecked). Where a class is read both check before the verb runs, so a built string's
  out-of-range bound is refused alike on every backend, where the unchecked compiled
  `subseq` / `char` read garbage or trap.
- **A bound of `subs` / `.substring` / `.charAt` is truncated at run time, on every program**
  (`ClojureStringLowering.bound`, `%clojure-string-bound`): the oracle takes a double or a ratio
  as its truncation toward zero (`(subs "abc" 1.0)` and `(.charAt "abc" 1.5)` answer `"bc"` and
  `\b`), NaN as 0. The wrapper is part of the lowering, not of the refusal family, so a
  program reading no class gets it too. A literal bound inside the int range is truncated at
  lower time (an integer literal site compiles to the bytes it did before), and so is a
  `length` form; any other bound, a `count` or `.length` included, is one call. A double past the int range is clamped to its edge, so the verb refuses it as
  outside the string (`StringIndexOutOfBoundsException`): the oracle's `subs` makes it an
  `ArithmeticException` (past the int range) or an `IllegalArgumentException` (past the long
  range), while its `.substring` / `.charAt` of a non-literal receiver saturate like this and
  of a literal one differ again. The helper costs 1.9 KB of JVM class on a program with no
  other numeric code (`truncate` brings `_div` / `_fdiv` / `_rtrunc`), 0.2 KB of it the
  clamps; the exception carriers and `princ-to-string` the exact classes need were 2.3 KB
  more and were left out. Pins: clojure-spec `subs-and-substring-truncate-a-double-or-ratio-bound`,
  `subs-and-substring-refuse-a-truncated-bound-outside-the-string` (the class-reading program),
  `ClojureStringBoundE2eTest` (a program reading no class, on all four backends),
  `ClojureLoweringTest#aStringBoundIsTruncatedAtRunTimeUnlessItIsALiteralInteger`. Measured: `(defn f [s i j] (subs s i j))` wasm P1 / component / JVM
  class 31,958 / 33,171 / 59,227 -> 31,990 / 33,203 / 61,168; a 5M-iteration loop of one
  `subs`, `.charAt` and `.substring` each over variable bounds is within noise (wasm 4.8-5.0
  s both, JVM 2.1-2.2 s both).
- **A bound that is no number is refused by the refusal family's alias, not by
  `%clojure-string-bound`** (which passes it on): `%clojure-subs` / `%clojure-char-at` check it
  before the verb runs (`%clojure-string-bound-number`), so a program reading no class compiles
  the bytes it did before (the strip turns each alias back into `subseq` / `char`; 539 compiled
  doc examples, `examples/clojure` and the probe corpus byte-identical, wasm P1). nil, an `end`
  of nil included (the alias reads `&optional (end nil end-p)`), is `NullPointerException`; any
  other value is the `ClassCastException` of `subs`'s cast, and of a `.substring` / `.charAt`
  whose receiver the lowering types (a string literal, a `cls` it knows). The oracle's
  reflective call (an untyped receiver) is the `IllegalArgumentException` of "no matching
  method", so `ClojureInteropLowering.stringMethod` picks the `-by-reflection` alias there
  (`ClojureRefusals.SUBS_BY_REFLECTION`, `CHAR_AT_BY_REFLECTION`, folded by the strip like the
  others). The oracle also types a `^String` hinted receiver or a `str` result, so there it is
  a `ClassCastException` where this is the reflective class: a hint is dropped at lower time and
  tracking it would change the bytes of every program that hints. Cost in a program reading a
  class, wasm P1 / component / JVM class: `(subs s 1)` 94,001 / 95,680 / 102,305 -> 94,232 /
  95,851 / 103,342; the same with `.substring`, `.charAt` and `subs` 100,912 / 102,595 /
  144,891 -> 101,257 / 102,977 / 146,112; a function over `(subs s i j)` 48,593 / 50,208 /
  72,949 -> 48,901 / 50,422 / 73,982. Pin: clojure-spec
  `subs-and-substring-refuse-a-non-number-bound-with-the-oracles-class`,
  `ClojureLoweringTest#aStringMethodOfAReceiverNotKnownToBeAStringIsTheReflectiveAlias`,
  `ClojureArmsTest#theRefusalFamilyFoldsEachRefusalToThePlainErrorOfItsMessage`.
- Names no class: a refusal of a construct the oracle accepts (regex lookaround, named groups,
  `\G`, POSIX classes, `(partition 0 ...)`, a deftype literal), a value macro's
  `Can't take value of a macro` (a compile error in the oracle), internal invariants. A
  misuse a lower verb refuses first carries that verb's
  class: `(shuffle 5)` is `seq`'s IAE, the oracle's CCE casting to `Collection`.
- Size, measured 2026-10-04 (wasm P1 / component / JVM class, before -> after): `(println (try
  (first 5) (catch Exception e :caught)))` 47,858 / 49,368 / 68,410 -> 48,222 / 49,773 / 69,269;
  the IAE-past-an-`ArithmeticException` plus `AssertionError` pair 48,783 / 50,360 / 70,648 ->
  49,254 / 50,903 / 71,695; a `Throwable` catch, an uncaught refusal, `examples/clojure/demo.clj`,
  every example / size-report / bench program and 413 of the 458 Clojure doc examples
  byte-identical (the 45 others are class readers, +67..+610 B wasm, or the shrinks above).
  The fixed-arity carriers matter: an `&optional` value cost the JVM class 828 B more.
- Run time: a caught refusal builds its instance (200k `(try (first 5) (catch
  IllegalArgumentException e 1))` 260 -> 305 ms wasm, 335 -> 381 ms JVM); `subs` in a
  class-reading program checks its bounds before `subseq` does (a 2M-call loop 1.30 ->
  1.52 s wasm, the JVM unchanged).
- `.charAt` / `.substring` in a class-reading program (`(try (.charAt (str "ab" "c") 1) (catch
  Exception e :x))`): wasm P1 55,286 -> 55,663 B, component 56,897 -> 57,282, JVM class
  118,359 -> 119,356; a program reading no class, or calling neither verb
  (`examples/clojure/demo.clj` among them), is byte-identical on all three.
- Pins: clojure-spec `a-refusal-of-the-runtime-is-caught-by-the-class-the-oracle-throws`,
  `a-string-substring-and-charat-refuse-with-the-oracles-class`, `refusals-of-the-runtime-carry-the-oracles-classes` (each probe first passes a catch of a
  class no refusal is, so a refusal naming no class fails it), `class-instance-and-stack-traces-of-an-exception`;
  `ClojureArmsTest#theRefusalFamily*`,
  `ClojureLibraryTest#aProgramReadingNoConditionsClassSplicesEveryRefusalAsThePlainError`,
  `ClojureRefusalsTest` (each chain is `ClojureThrowables`'),
  `RontoLispCliStreamsTest#aClojureRefusalReportsTheSameLineWhetherOrNotTheProgramReadsAClass`,
  `LispMacroExpanderTest#anUnreferencedConditionConstructorBuildsNothingToRender` /
  `#aSignalOfItsOwnTextControlNeedsNoRenderer`.

## Seq verbs over a wrapper

**A cons headed by a CL keyword is a tagged wrapper -- no user list holds one -- so one
`keywordp` of the car parts a list from every wrapper, and a wrapper no verb takes is
refused with the oracle's class** (oracle-checked clj 1.12.6, 2026-10-05). Before, a
keyword, var, reduced value or namespace read as its list (`(first :a)` was `:C%KEYWORD`,
`(count :a)` 2) and `empty?`/`contains?` of a non-collection answered.

- `%clojure-strict-seq`: a plain list passes after `consp` + `keywordp`; a set, record or
  sorted collection seqs; anything else (a wrapper, `false`, a number, a symbol, a
  function) is `seq`'s IAE. `false` was empty until then (the oracle refuses).
- `count` / `empty?` (inline per site, `ClojureCollectionLowering`): the list arm first,
  the set/record/sorted/lazy arms behind the `keywordp`; any other wrapper is `count`'s
  UOE, and `empty?` falls to `(null (%clojure-seq c))`, `seq`'s refusal (the oracle's
  `(not (seq coll))`). A non-collection's `count` reaches `length`, whose `SEQUENCE`
  type error is the UOE ("Catching"). The per-kind refusal arms (deftype, reify, regex,
  atom) went: each was a test every list and vector paid.
- `contains?`: what no arm holds goes to `%clojure-contains-past` (false of nil, a vector,
  a string; else IAE). A string under a key that is no integer answers false (deviation:
  the oracle truncates a number, refuses anything else); the exact arm cost +340 B wasm.
- `nth` (`%clojure-nth`) steps a list, lazy seq or host object, reads a matcher's group
  ("Regex") and refuses a map, set, record, sorted collection or non-collection (UOE), so a
  vector pattern over a map is refused like the oracle's; `second` is `%clojure-seq-nth` (any seq view, a vector
  indexed, `seq`'s refusal). Each carries its own loop: a shared one was +280 B of class
  on an `nth` program.
- Size, wasm P1 / component / JVM class, 458 doc examples + `demo.clj` + two probes: 446 /
  451 / 450 smaller (`count` example 41,108 / 42,345 / 69,789 -> 38,513 / 39,748 /
  66,742; `demo.clj` 91,184 / 92,514 / 119,828 -> 90,944 / 92,276 / 118,498); the
  `contains?` example +321 / +321 / +209 (the helper), eight tiny printers +1..+5 B of
  wasm (smaller class); byte-identical only where no seq view is spliced.
- Run time, 5M-iteration loops, wasm P1 / JVM: `count` of a list 4.0 -> 1.3 s / 0.70 ->
  0.33 s, `empty?` of a list 2.0 -> 0.35 / 0.33 -> 0.18, `first` of a list 2.1 -> 0.6, `nth`
  of a list 2.4 -> 1.1, `count (seq m)` 6.6 -> 2.4; the vector verbs, `contains?` and
  `second` of a vector within noise; the interpreter alike.
- Pins: clojure-spec `seq-verbs-refuse-a-wrapper-or-a-scalar-with-the-oracles-class`,
  `count-and-empty-reach-maps-and-sets`, `sequential-destructuring-binds-positionally`,
  `reify-values-dispatch-and-deftypes-stay-opaque`;
  `ClojureLoweringTest#countAndEmptyPartAListFromEveryWrapperByOneTestOfItsHead`.

## Host exceptions

**On a host target (the interpreter, the JVM), an exception crossing the `java:` boundary is
the host's own in both directions** (oracle-checked clj 1.12.6, 2026-10-05). The CL half is
`.kb/java-interop.md`, "What a member throws": a failed call signals `java:java-exception`, a
`simple-error` whose slot 2 holds the throwable; one passed to a member is that throwable.

- Host to program: a catch takes a `java:java-exception` by its throwable's class
  (`%clojure-catches`' arm, `%clojure-host-is-a`: the class and superclasses by name, through
  typed resolved calls -- `Class.forName` + `isInstance` per test was 3x slower) and BINDS THE
  THROWABLE (`%clojure-caught`), so `.getMessage`, `class`, `instance?`, host members
  (`.getIndex`), `identical?` and a rethrow are the host's. `throw` of a host `Throwable`
  signals a `java:java-exception` carrying it (not a converted exception), `ex-cause` of one is
  `.getCause`, and a cause argument keeps one as itself (`%clojure-cause-of`,
  `%clojure-exception-new-1`). The binding is a lowering post-pass (`ClojureLowering.recordCatch`
  / `bindCaught`: `(let ((e (%clojure-caught e))) body)` once the whole file is known to name a
  `java:` operator; a session at once) -- an arm cannot fold back to the old clause.
- Program to host: in a program naming a `java:` operator that builds an exception
  (`hostExceptionClasses`: a plain throwable construction's class, ex-info's), `C%E-EXCEPTION`
  is a `java:java-exception` whose slot 2 holds `#'C%E-HOST`, which the boundary calls; it
  builds the host exception once (kept in the fifth slot `C%E-HOST`) through the generated
  `C%E-HOST-OF`, one literal `(java:new "C" message cause)` per class of the chain the program
  builds (`(C. message)` + `initCause` without a `(String, Throwable)` constructor; else
  `RuntimeException`, spelled without asking reflection, which a native image may not answer),
  so every site resolves and nothing reflects (a reflective builder in
  `clojure.lisp` was +9 KB of class on a two-line program). The twin is a copy:
  `(.getCause (UncheckedIOException. "u" e))` is not `identical?` to `e` (user doc
  deviation). A runtime error or refusal (a CL condition) has no twin. A CL file of the same
  program catches one as `java:java-exception`; `java:java-exception-cause` calls the builder.
- `%clojure-lisp-value-p` counts an instance (`%clojure-lisp-instance-p`): the host arms'
  own `java:call`s (`isInstance`) would otherwise hand a condition over as its twin.
- Byte-identity: the arms (`%clojure-host-throwable-p`, `%clojure-host-failure-p`,
  `%clojure-lisp-instance-p`) are `ClojureArms.Family.HOST_EXCEPTION`, folded where the
  program names no `java:` operator AND where the target has no host
  (`ClojureArms.needsHost`, `ClojureLibrary.process(program, hostTarget)` from
  `CompileFrontend`'s `!wasm` and the playground's `rontolisp-wasm` feature); the lowering
  takes the target from `SourceLanguage`'s features
  (`Clojure.read(..., hostTarget)`), and the JVM backend alone registers the class for a
  `java:` program (`JvmLispCompiler.compile`). Measured 2026-10-05 over `examples/`,
  `size-report/`, `bench-report/` and the `clojure-spec.yaml` program: every compilable
  program byte-identical on wasm P1, `--optimize=size` and component; on the JVM all but the
  `java:` programs (CL `cffi-sqlite` +50 B, `java-store` +348 B; Clojure: catch +
  `.getMessage` + one interop call +2.9 KB -- `_jexc`, `_jexMap`, the class layout and
  `%clojure-host-is-a`'s three resolved sites --, the same building and passing an exception
  +6.4 KB, the spec program +28 KB of 6.7 MB).
- Run time (JVM, 200k iterations): a caught host failure 0.80 -> 1.09 s (the `_jexMap`
  record and the condition: a CL `handler-case` pays the same +1.3 us), an ex-info throw/catch
  in a `java:` program within noise; interpreter: host failures within noise, an ex-info
  loop +12-17% in a `java:` file (the C%E builder slot, the binding) and within noise elsewhere.
- Pins: `ClojureInteropTest#aFailedHostCallIsCaughtAsTheExceptionTheHostThrew`,
  `#aCaughtHostExceptionRethrowsAndWrapsAsItself`,
  `#anExceptionPassedToAHostMemberIsAHostThrowableOfItsClass` (oracle-identical);
  `ClojureLibraryTest#aHostExceptionIsMadeOnlyByAJavaOperatorWhereTheHostIs`,
  `ClojureProjectNamespacesTest#aClojureExceptionCaughtInCommonLispCarriesItsHostException`.

## Sorted collections

**A program that builds no sorted collection compiles to the bytes it did before sorted
collections existed.** A sorted map or set is a value every map and set verb must read, so
the verbs carry an ARM for it, and a program naming no producer has every arm stripped
before the library splice.

- Value: `(:C%SORTED setp cmp items)` (`clojure.lisp`, "Sorted collections"): SETP true
  for a set; CMP the `-by` comparator function, or NIL for `compare` (which then also
  refuses a key that is not nil, a number or Comparable, the oracle's `Default comparator
  requires nil, Number, or Comparable: <key>`); ITEMS a simple vector in comparator order,
  members or `[k v]` entries (`seq` answers the entries themselves). Copy-on-write like the
  hash maps, so an association is O(n); a lookup is a binary search asking `(cmp key
  stored)`, so `(get (sorted-map 1 :a) 1.0)` is `:a` and a store keeps the stored key. A
  constructor is one `stable-sort` plus a dedupe (first key kept, last value wins: the
  oracle's assoc after assoc). A persistent tree would make an association O(log n), but
  every hash map verb here already copies; the vector keeps the runtime small.
- `compare` is `%clojure-compare`, the oracle's `Util.compare` over the values here
  (strings by code point: the oracle's UTF-16 units differ past U+FFFF). A comparator
  answer is read by `%clojure-cmp-call`, the oracle's `AFunction.compare`: `true` -1,
  `false` asks the reversed call, a number its integer part (0.5 is equal, NaN 0), nil
  signals. `sort`/`sort-by` with a comparator read the same rule (before 2026-10-03 a
  number was truthy, so `(sort (fn [a b] (- a b)) xs)` answered garbage; pinned by
  `sort-reads-a-comparator-answering-a-number`).
- The default order of `sort`/`sort-by` is `(neg? (compare a b))` (`defaultCmpBody`), with
  `<` kept inline for two numbers (`sort-without-a-comparator-orders-by-compare`). Measured
  2026-10-03 on `(sort xs)` over 100,000 pseudo-random integers, twice per run: the
  previous inline `<`/`string<`/`char<`/keyword `cond` 2.76 s interpreter, 0.36 s JVM; compare
  alone 6.10 s and 0.45 s (the interpreter pays a runtime call per comparison, 2.2x); compare
  with the `<` number arm 2.82 s and 0.39-0.44 s, so the arm stays. Strings need no arm: 50,000
  strings 21.0 s before, 17.9 s compare alone (interpreter), JVM 0.40 -> 0.25 s. Size of
  a program sorting numbers, strings, a `sort-by` and keywords: JVM class 81,011 -> 88,225 B,
  wasm 46,499 -> 51,458 B (the compare runtime is spliced once a program sorts; compare alone
  87,630 / 51,248, number arm plus string arm 90,695 / 53,181).
- `subseq`/`rsubseq` (`%clojure-subseq`, `-5`): the oracle's two paths -- a test leading
  away from the start (`>`/`>=`, `<`/`<=` for rsubseq) starts at the key through the
  oracle's `seqFrom`, any other walks from the start while it holds. The oracle picks the
  path by IDENTITY with the core functions: a literal `<`, `<=`, `>`, `>=` lowers to the
  keyword `:<` ...; a test passed as a value is classified by its answers on `(1 0)`,
  `(0 0)`, `(-1 0)` (`%clojure-sorted-test`; the core functions as values are fresh lambdas,
  so identity cannot work).
- `int`/`long` call `%clojure-int-cast`/`%clojure-long-cast` since 2026-10-08 (inline
  `truncate` before, refusing nothing). The refusal spells the value through `princ` (a
  double upcased, `1.0E19`), not `%clojure-str-of` (the `str` runtime, ~+34 KB wasm). Raw wasm
  / `--optimize=size` / JVM class, before -> after: `(println (int 2.7))` 18,042 / 16,688 /
  63,088 -> 15,071 / 14,048 / 61,621 (a literal folds); `(defn f [x] (int x))` printing
  `(f (/ (rand-int 10) 2))` 21,629 / 20,108 / 64,272 -> 31,049 / 28,246 / 68,957 (4.8 KB of it
  the float printer of the double spelling); the clojure-spec program -1.1 KB on all three;
  `demo.clj` unchanged (no cast). Time, 20M iterations: `(+ s (int i) (long i))` wasm
  1.30-1.53 s -> 1.48-1.62 s, JVM 0.11-0.12 s both; `(+ s (long (quot i 3)) (int (* i 0.5)))`
  wasm 2.96-3.44 s -> 4.17-4.33 s (a double cast pays one generic float compare,
  `_rat_cmp_bits`, ~20 ns there; the first worker's four compares cost twice that), JVM
  2.5-2.8 s both. Since `_rat_cmp_bits`'s f64 arm and the literal-site float test
  (`.kb/wasm-bignum.md`, 2026-10-08) the second loop is 4.43 -> 4.17 s (best of 5): the
  `(< (abs x) 9.2e18)` test is now a raw f64 compare, and what is left is the generic `abs`,
  the two `_as_f64` calls of `truncate` and the integer range tests. Interpreter, 1M iterations: 3.0 s -> 5.0 s and 3.8 s -> 8.0 s (the worker is
  interpreted Lisp). An inline arm answering an in-range integer before the call took the
  interpreter's integer loop to 3.9-4.0 s, left the compiled ones level, and cost ~250 B wasm
  / ~650 B class per call site; not made. A `count` argument is not cast (an int already).
  `double` 14,365 and `num` 9,619 raw wasm (2026-10-03).
  The conversions of 2026-10-03, raw wasm / JVM class of `(println (f x))`: `unchecked-int`
  18,215 / 70,003 (the same for `-long`, `-byte`, `-char`: bignum `mod`/`expt` in the wrap),
  `unchecked-float` 25,192 / 64,642, `numerator` 9,658 / 64,805, `bigint` 29,555 / 72,352,
  `bigdec`/`rationalize` of a double 39,301-39,331 / 74,314-75,980 (the float printer), `(bigint 3)`
  12,825 (a literal integer folds). Each links only when called.
  Wrapping through `(mod n (expt 2 bits))` linked a bignum division: `(println (unchecked-int x))`
  with `x` from `rand-int` was 32,753 raw wasm; the mask form (`logand` over `(ash 1 bits)`,
  `%clojure-wrap-bits`) is 17,710 (JVM class 70,902 -> 70,404). The unchecked arithmetic verbs
  of the same date, `(println (f x 2))` raw wasm / JVM class against the plain `+` 12,547 / 66,210:
  `unchecked-add`/`-subtract` 13,782 / 67,548, `-inc` 13,866, `-negate` 13,769, `-multiply` 28,546
  (the plain `*` is 27,296 / 66,518), `-add-int` 13,989 / 68,445, `-divide-int` 14,054 / 69,012,
  `-remainder-int` 12,862 / 68,095. The wrap's mask and bounds are LITERALS: 20M iterations of
  `h = 31*h + i` that overflow the long on every step took 22.5 s on the JVM through a
  computed-mask wrap against 3.5 s through a literal one (wasm 15.2 s -> 10.4 s).
- **The long verbs wrap through `%mask-signed-field`** (SBCL's `sb-c::mask-signed-field`, a `cl`
  internal: `LispNames.MASK_SIGNED_FIELD`). Two integer operands take
  `(%mask-signed-field 64 (op a b))`, which both compilers fuse into one wrapping `long`/`i64`
  operation (`.kb/jvm-int-fusion.md`); anything else keeps `%clojure-wrap-long` over the plain
  result, and the `-int` verbs wrap their int-cast operands at 32 the same way. Until 2026-10-05
  the wrap was a defun over the plain `*`, so the overflowing hash built a bignum product every
  step and masked it back. 20M iterations raw JVM / wasm, measured 2026-10-05 (load 5-8,
  alternating runs, before -> after): the overflowing 64-bit hash
  `(unchecked-add (unchecked-multiply h 31) i)` 3.3-3.6 s -> 0.19-0.21 s / 11.9-12.3 s -> 1.6 s;
  the `-int` hash `(unchecked-add-int (unchecked-multiply-int h 31) i)` 0.58-0.64 s -> 0.44 s /
  3.6-3.8 s -> 2.2-2.3 s; `(unchecked-add s i)` without overflow 0.25-0.27 s -> 0.19 s /
  1.9 s -> 1.25 s, level with `(+ s i)` (0.19 s / 1.3 s). The interpreter runs the builtin in
  Java and gains little (2M iterations: 29.7 s -> 27.7 s, 15.9 s -> 13.0 s; the time is the
  interpreted worker calls). Sizes of `(println (f x 2))`, raw wasm / `--optimize=size` / JVM
  class, before -> after: `unchecked-add` 12,882 / 11,369 / 59,154 -> 13,320 / 11,672 / 59,752
  (`-subtract` the same +450 / +300 / +600), `-multiply` 27,849 / 11,411 / 59,465 -> 28,277 /
  11,714 / 60,063, `-inc` 12,969 / 11,357 / 59,147 -> 12,898 / 11,508 / 59,017, `-negate` 12,870
  -> 13,172 raw, `-add-int` 13,473 -> 13,230, `-multiply-int` 28,357 -> 13,665 (the bignum
  multiply is no longer linked), `-divide-int` 14,184 -> 14,140; a program without an unchecked
  verb is byte-identical. The size level grows ~300 B: both the integer arm and the fallback
  carry the lowering, which only the default level fuses.
- `vector-of` is a plain vector of cast members (`%clojure-vector-of-1`, the oracle's casts
  and messages); later `conj`/`assoc` do not cast and `:float` holds doubles -- a typed
  vector would need an arm in every vector verb for a difference only those two show.
- **The arms.** A TEST (`%clojure-sorted-p`, `-map-p`, `-set-p`) over a variable or a
  `car`/`cdr` read of one, as a `cond` clause's test, an `if`'s test or an `or`'s disjunct;
  a VIEW (`%clojure-sorted-key`, `-items`, `-hashed`, `-shrunk`, `-rewrap`) answering its
  first argument for anything unsorted, its other arguments variables; an ALIAS
  (`%clojure-is-set` -> `%clojure-set-p`, `%clojure-is-reversible` -> `%clojure-is-vector`).
  `clojure/ClojureArms` (family `SORTED`; `MATCHER` is the regex matcher's, `REDUCIBLE` a typed value's own `CollReduce`/`IKVReduce` row's, "clojure.jar namespaces", `UNBOUND` is the unbound root's,
  `STREAM_DEPTH` the core specials' counters, "Vars and metadata", `READER_VALUE` "Reader conditionals", `PRINT_FLAGS`,
  `PRINT_META` and `NAMESPACE_MAP` the printer's, "State", and `STREAM` the stream printer's, "Streams as values") scans for a PRODUCER (`%clojure-sorted-make` and the four
  constructor `-v` values: no literal makes one) and, without one, strips: a test folds to
  false (its clause, its `if` branch or its disjunct goes; one disjunct left stands alone),
  a view to its first argument, an alias to its plain helper. An arm anywhere else, or over
  an argument with an effect, is an `IllegalStateException` at the strip.
  `eval/ClojureLibrary.process` strips the program family by family and splices a library
  stripped of every family it makes no value of (one cached variant per combination, beside
  the host-arm ones); the interpreter, a session and the macro-time
  evaluator keep `forms()` whole, since what a later input builds is unknown.
- Writing an arm: it allocates no `freshTemp` and lowers no datum again (a shifted temp
  number would rename locals of the stripped program); it sits behind an existing wrapper
  test where it can (`%clojure-strict-seq`, `count`, `empty?`, `%clojure-key-kind`), so the
  interpreter's other kinds pass no extra test; and **a pre-existing defun keeps its docstring byte for
  byte -- the JVM backend emits every docstring as an `ldc`/`pop` statement**, so a changed
  docstring changes the class of every program splicing the defun (found 2026-10-03:
  `demo.clj`'s class grew 209 bytes from two docstrings; comments go in `;;` lines).
- Where the arms are: `getBranches` (`get`, keyword and IFn reads, `get-in`, `update`,
  destructuring), `containsForm`, `countForm`, `emptyForm`, `tableKeysForm`, `conjTwoForm`,
  `dissoc` (source table, `storedKey` inside `lookupKey`, `sorted-shrunk` answer), `disj`
  (`isAnySetForm`, `hashedSet`, `shrunkSet`), `merge` (`mergedEntriesPlist`, a sorted first map
  the `merge` value's base), `merge-with` (a sorted first map delegates to
  `%clojure-sorted-merge-with`, later ones through `sorted-table`), `select-keys`,
  `rand-nth`, `shuffle`, `class`, the protocol tag, `map->R`, `rseq`, `empty`; in `clojure.lisp` the
  printer, `=`, `%clojure-hash`, the structural-key kind (a sorted key is kind 4, so a hash
  set stored first does not replace its spelling), seq, IFn, `%clojure-plist-table` (so
  `assoc` and `merge` onto a sorted map need no lowering arm), `find`, `reduce-kv`,
  `replace`, the predicates, `with-meta`'s map check and `clojure.set`.
- A macro answering a sorted collection decodes to a map or set literal of its entries in
  order (`ClojureMacroLowering.decodeSorted`), an unsorted one, like the oracle's compiler.
- Measured 2026-10-03 against the parent build: `--dump-ir` of the clojure-spec program
  as it stood (231 cases, `sorted?` lines dropped) differs only at its three
  sort-with-a-comparator sites (the `sort` rule above); the 27 shcloj4 drivers' IR is
  identical; `examples/clojure/demo.clj` is byte-identical as wasm, `--optimize=size`,
  component and JVM class. Interpreter, a map/set-verb loop (`get`, `contains?`, `count`,
  `seq`, `empty?`): 10.24 s before and after (medians of four, ±0.4 s noise). A sorted
  program carries the runtime: `(println (sorted-set 3 1 2))` 55,294 B of wasm (41,817 at
  `--optimize=size`) against 33,229 (26,601) for `#{3 1 2}` -- `compare` with its name
  and vector arms, the sort runtime and the `str` path the refusals print through.
  `(apply sorted-map ...)` of 20,000 shuffled pairs plus 20,000 lookups: wasm 1.9 s, JVM
  2.3 s, interpreter about 10 s.
- Pinned by clojure-spec `sorted-map-and-sorted-set-print-in-comparator-order`,
  `sorted-collections-look-up-through-their-comparator`, `sorted-collections-grow-and-shrink-in-order`,
  `sorted-collections-seq-in-order`, `sorted-collections-equal-and-key-like-their-unsorted-kind`,
  `sorted-map-by-and-sorted-set-by-order-through-a-comparator`,
  `subseq-and-rsubseq-walk-a-bounded-range`, `sorted-collections-and-the-type-predicates`,
  `compare-orders-like-the-oracle`, `sort-reads-a-comparator-answering-a-number`,
  `sort-without-a-comparator-orders-by-compare`,
  `vector-of-stores-each-member-as-its-primitive`, `sorted-collections-refuse-like-the-oracle`,
  `clojure-set-grows-and-shrinks-a-sorted-set`,
  `empty-answers-the-empty-collection-of-its-kind`,
  `sorted-collections-carry-metadata-and-travel-through-macros` (oracle-identical but for
  the commented lines); the strip by `ClojureArmsTest`,
  `ClojureLoweringTest#aProgramBuildingNoSortedCollectionCarriesNoneOfItsArms`,
  `ClojureLibraryTest#aProgramBuildingNoSortedCollectionSplicesTheLibraryWithoutItsSortedArms`;
  a session keeping its arms by
  `PlaygroundReplTest#aClojureSessionKeepsTheSortedArmsOfWhatAnEarlierBufferDefined`.

## Structural keys

**A map, set, memo or method-table key finds an `=` key, though the tables are `equal`
tables whose `equal` is identity on a vector or table.** No backend has a custom-test
table, so `clojure.lisp` ("Structural keys") stores every structural key (non-string
vector, list, lazy seq, map, set, record, sorted collection) under a REPRESENTATIVE: the
first `=` key of its kind (vector / lazy seq / list / sorted / other) the program stored.
`%clojure-key-classes` (equal table, `%clojure-hash` -> classes) groups the representatives
`=` to each other;
`%clojure-key-reps` (eq table) maps a representative to its class without hashing.

- `%clojure-table-key k table` (lookups, `remhash`) answers the representative TABLE holds,
  else K; `%clojure-store-key k table` (stores) answers the one TABLE holds (its key kept,
  the value replaced, like the oracle's `(assoc {[1 2] :a} '(1 2) :b)` -> `{[1 2] :b}`),
  else K's own kind's, else makes K one. A class keeps one representative per kind so a
  list key stays a list in a fresh table after an `=` vector keyed another.
- Lowering helpers (`ClojureCollectionLowering`): `lookupKey`/`storeKey`/`tablePut`/
  `setPut`/`grownTable` (`%clojure-plist-table base plist`: BASE copied as is -- its keys are
  representatives -- plus re-keyed pairs). A literal scalar key (`isScalarKeyForm`: keyword
  construction, string/number/char literal, nil, true, quoted symbol) skips the runtime, so
  `(:a m)`, `{:a 1}` and `:keys` destructuring keep plain `gethash`/`plist-hash-table`.
- `%clojure-hash` agrees with `%clojure-equal` (a sequential folds its members whatever its
  kind, a map or set sums its entries); a fold step is `h * 1021 + e` under a 2^20 mask, so
  every intermediate stays a wasm fixnum (< 2^30) and `[x y]` pairs below 1021 never
  collide (the first cut, `* 31` under 2^24, put ~4.6 grid keys per bucket).
- The eq table also makes the module slotted (`programMakesIdentityHashTable`,
  `.kb/hash-tables.md`), so a vector or table key of an `equal` table is placed by identity
  instead of bucket 0. Measured 2026-10-03 (wasmtime, one run): a 22,500-vector set plus
  22,500 lookups and a `frequencies` took 114.6 s on wasm before (bucket 0, wrong answers)
  and 0.73 s after; JVM 0.33 -> 0.50 s, interpreter 3.6 -> 6.8 s.
- Cost on keyword-only work (wasm, medians of 3, 2026-10-03): 4M `(get m k)` with a
  variable keyword 2.10 -> 2.37 s (the call to `%clojure-table-key`), 2M two-pair `assoc`
  calls 4.68 -> 4.93 s; JVM unchanged within noise.
- The float zeros are one key though `equal` (= `eql`) tells them apart: `%clojure-table-key` /
  `%clojure-store-key` answer the zero the table holds (`%clojure-zero-key`), and
  `isScalarKeyForm` does not treat a zero double literal as scalar, so the lowered sites go
  through them. `%clojure-hash` already gives both zeros 0. Measured 2026-10-03 against the oracle:
  `(= -0.0 0.0)` true, `(contains? #{0.0} -0.0)` true. A NaN key is untouched (the oracle itself
  finds the same boxed NaN but not another).
- Two vectors compare in place in `%clojure-equal` (no seq-view copies) -- the bucket scan's
  hot path.
- Untouched on purpose: hierarchy tables (tags), protocol tables (tags), `prefer-method`'s
  `(x . y)` keys, record field reads (keywords).
- Pinned by `clojure-spec.yaml` `structural-keys-find-equal-collections` (oracle-identical)
  and `ClojureLoweringTest.aLiteralScalarKeySkipsTheStructuralKeyRuntime`.

## recur

`recur` targets the innermost `loop`, `fn` (named or anonymous), `defn` clause, `letfn`
entry, `lazy-seq` body (arity 0), stored method lambda (`defmethod`, extension methods) or
inline method arity (below): a target stack, through which a plain lambda passes. Each multi-arity clause is
its own target, so the count must match the clause; a wrong count and a `recur` outside
any target are named refusals; `#()` recurs unchecked (its arity is known only after the
body lowers). `loop` inits are sequential and destructure.

- Tail position is enforced like the oracle: only a `recur` in its target's tail (`if`
  arms, `let*`/`progn` tails, `labels` bodies, `cond` arms, `do`, `when`, the dispatch
  lets) lowers; elsewhere `Can only recur from tail position`.
- A `try` between the `recur` and its target is `Cannot recur across try` (a barrier
  beside the stack, so a target opened inside the `try` still recurs). `binding`, a
  non-empty `with-open` and `is` bodies lower behind the same barrier (the oracle wraps
  them in `try`); their inits do not.
- A `recur` reaching a variadic target splits it into a worker taking the rest as an
  ordinary parameter plus an `&rest` head, so the `recur` assigns exactly while normal
  calls wrap through the head; an unused variadic keeps its single shape.
- An inline method's arity (`deftype`/`defrecord`/`reify`, protocol or interface) counts
  every parameter but the first, the target the method supplies itself (oracle clj 1.12.6;
  `(recur this acc)` there is its `Mismatched argument count to recur`):
  `ClojureProtocolLowering.inlineArityLambda` pushes `RecurTarget.inlineMethod`, and a used
  target wraps the body, inside the field bindings, in one `labels` entry over the other
  parameters (the rest an ordinary one) called once (`ClojureBindingLowering.inlineRecurBody`):
  no worker split, and a `let` rebinding the target cannot substitute it. A `[& r]` vector
  counts every parameter. An extension body is a `fn` (`extensionLambda`), whose `recur`
  passes the target. Pinned by `recur-in-an-inline-method-passes-every-parameter-but-the-target`
  and `ClojureLoweringTest.anInlineMethodRecurPassesEveryParameterButTheTarget`.
- Constant stack comes from the backends' tail calls: wasm `return_call`, the
  interpreter's `eval` loop, the JVM's self tail call as a jump back to the method's start
  and, for `letfn` entries or `defn`s calling each other, its tail groups
  ([jvm-self-tail-calls.md](jvm-self-tail-calls.md); before them, a JVM `loop` overflowed
  near 150,000 rounds, a `defn` near 200,000, a `letfn` pair near 150,000). A call through
  a value in tail position -- `%clojure-call`'s `apply`, a call site's `funcall` of a real
  function -- is a JVM value tail, a real call while shallow and a bounce through the
  trampoline past 64 ([jvm-tail-bounce.md](jvm-tail-bounce.md)): a `fn`
  in an atom calling itself runs 1,000,000 deep on every backend (wasm and the component
  since 2026-10-03: `%clojure-call`'s `apply` sits in a `cond` clause, which wasm compiled as
  no tail before, `.kb/wasm-tail-calls.md`). The spec's
  `deep-recur-answers-on-every-backend` runs a `loop` 1,000,000 deep on all four,
  `letfn-mutual-tail-calls-run-in-constant-stack` a `letfn` pair.
- A multi-arity `defn`'s fixed clause recurs to its own helper (`c%f%<n>`), never through
  the dispatch defun -- that round trip was a mutual recursion of two functions, a tail group
  on the JVM now but a self jump is cheaper. A multi-arity `fn`'s clauses are arms of one
  lambda, so its `recur` re-enters the dispatch, a self call of that lambda; the dispatch on
  the argument count is a `cond`, so the `recur` is a tail of the lambda only where `cond`'s
  clause bodies are (wasm since 2026-10-03; `deep-recur-answers-on-every-backend` runs one
  300,000 deep).

## Namespaces and project files

**Every namespace has its own vars.** A var is keyed `ns/name` in every program-wide
table (`ClojureLowering.varKey`) and lowers to `c%ns/name` -- except `user`'s, `c%name`, so
a program without `ns` lowers unqualified. A quoted `'n/x` is the symbol of var `n/x`.

- **Resolution** (`lookupVar`; `resolveVar` adds the privacy refusal; locals first):
  unqualified, the current namespace's intern, then a refer; qualified, an alias of the
  current namespace, the current namespace, or any namespace an `ns`/`in-ns` created.
  Another namespace's private var (`defn-`, `^:private`) is `var: #'n/x is not public`; a
  project namespace lacking the var is `No such var: l/nope`, never a class.
- **Wiring** (`ClojureNsState`): aliases, refers, imports, the `:refer-clojure`
  `:only`/`:exclude` filter, interns with privacy. A definition replaces a refer of its
  name. Names are referred only by `use` or `:refer` (`:only`/`:exclude` narrow); a
  `require` with a bare `:only` refers nothing, like the oracle's `load-lib`. `:reload`,
  `:reload-all`, `:verbose` flags; quoted libspecs and prefix lists `(prefix [sub ...])`
  go through one spec parser. `clojure.string`, `clojure.set`, `clojure.edn`,
  `clojure.test` and `ring.adapter.rontolisp` resolve as lowerings (`clojure.java.io` did,
  `reader` only, until it shipped as a file on 2026-10-08); any other
  namespace clojure.jar defines (`ClojureBuiltinNamespaces.LANGUAGE`) is a built-in file
  ("clojure.jar namespaces") or `unknown namespace: x`. A `clojure.*` namespace OUTSIDE
  that list (a contrib library, `clojure.data.json`) is an ordinary library on the source
  path, a `:local/root` dependency's or a project's (until 2026-10-08 every `clojure.*` was
  refused; "deps.edn"). The built-in Ring namespaces and `rontolisp.http-client` load as
  project files from the jar when no root holds them ("Ring util namespaces", "HTTP
  client").
- **ns clauses and libspec options** (measured on `clj` 1.12.6, 2026-10-08):
  `(:gen-class ...)` is a no-op outside an AOT compile, options included, so the clause
  declares nothing (a top-level `gen-class` stays refused). `:as-alias` is a real alias
  (`::a/k`, `` `a/x ``, and a loaded library's vars through it) that loads and creates
  nothing, so a file behind it is never read and `a/f` of a namespace without that var is
  `No such var`; here it is `aliases.put` and no `requireCall`. `:rename {old new}` applies
  only to what `:refer`/`use` bring in (the old name is then not referred, a key outside
  the referred set is ignored, no `:refer` ignores it). `(:refer-clojure :rename {old
  new})` excludes `old` and spells the core var as `new` (`ClojureNsState.coreRenames`,
  read by `ClojureLowering.renamedCore` at a call head and in value position, and by the
  syntax-quote qualifier, which spells `clojure.core/old`); a program definition or local
  of `new` wins like any refer.
- **`load` and `(:load ...)`**: a path is relative to the directory of the current
  namespace's resource (`app.ld` -> `app/`; `user` -> the root), a leading `/` is
  root-relative, `.clj` is appended (else `.cljc`, `RT.load`'s order), the file evaluates in the current `*ns*` at EVERY call
  (a `def` resets, a `defonce` keeps) and `*ns*` is restored after it. It lowers like a
  required file under a unit key `load:<ns>:<path>` (`ClojureNamespaceLowering.loadOne`,
  `ClojureLowering.unitFiles` for its `*file*`, a required namespace's file too), once per lowering, and each call site runs
  its init unconditionally (`LoadMode.RELOAD`). The path must be a string literal because
  the file is read while lowering; a computed path is refused by name.
- **Loading** (`ClojureNamespaceLowering.loadNamespace`, `ClojureLowering.loadFile`): an
  `ns` form marks its namespace loaded AFTER its clauses (marking first hid the cycle),
  so a single-file program's later `(:require [a])` reads nothing. Any other project
  namespace reads `my_app/core.clj` and lowers both passes from a clean cursor (no local,
  recur target, `try` depth or gensym of the requiring form leaks in), starting in the
  requiring namespace (a file without `ns` defines there). Definitions are hoisted to top
  level ahead of the datum that loaded them; every other datum becomes a statement of the
  namespace's init.
- **Init at the `require` site**: the statements run from init chunks the `require` calls
  behind a `(defvar |c%n%loaded| nil)` flag -- guarded, unconditionally under `:reload`,
  dependencies first under `:reload-all`. The flag is a `defvar`, so a namespace two
  separately lowered files require runs once per process; a `require` in a body loads when
  the body runs. `defonce` keeps its root across reloads through a run-time `boundp` of the
  var (`.kb/compile-time-boundp.md`). Chunks split past 16 KiB of printed statements, each
  `(setq |c%n%init-N| (lambda () ...))` under a `|c%n%init|` driver so
  `GlobalVarCollector` sees the stores; a `^:dynamic` `def`'s `declaim` and counter stay
  top-level for `SpecialVarCollector`.
- **Refusals in the oracle's words**: `Could not locate a/b.clj or a/b.cljc on the source
  path: <roots>`, `Cyclic load dependency: [ /a ]->/b->[ /a ]`, `namespace 'x' not found after
  loading '/x'`, `x does not exist`, `x is not public`.
- **Source path** (`ClojureSourcePath`, computed when a lowering starts): the root the entry file's
  namespace names (`src` for `src/demo/main.clj` declaring `demo.main`; else the file's
  directory; a session's working directory), then the project's `:paths` and its
  dependencies' roots ("deps.edn"). `deps.edn` over a new flag: it is the oracle's own
  declaration. The file is the
  oracle's `RT.load` order: `ns.clj` under every root, then `ns.cljc` under every root, so a
  `.clj` under a later root beats a `.cljc` under an earlier one (measured on `clj` 1.12.6,
  2026-10-08; instaparse 1.5.0 ships both for 14 namespaces). `Found.resource` is the file
  below its root, the `*file*` and `:file` of the load (`app/portable.cljc`). Files come
  through `ClojureFiles` (`SourceLanguage.clojureFiles` adapts the site's loader; none is
  refused by name), and so does the program's Java class loader: every reflective question
  the lowering asks of a class name goes through `ClojureHostClasses.load`, bound for the
  lowering from `ClojureFiles.javaClassLoader()` (`.kb/java-interop.md`, "The program's
  Java class path").
- **Records** keep the simple-name tag; `typeKeyOf` resolves own, then an imported or
  dotted name matching the class, else the only one of that simple name.

## deps.edn

**Invariant: the source path is the oracle's classpath, minus what this build does not
read, named when a lookup misses; its jars holding classes are the program's Java class
path too.** `ClojureSourcePath.computeRoots`: the entry's own root,
the merged map's `:paths`, every library `ClojureDepsGraph.resolve` selects in the oracle's
order, then `Found.builtin`. Every rule below was measured 2026-10-08 on `clj` 1.12.6.1673
(`clj -Srepro -Spath` over fixture trees -- a `file:` Maven repository with
`:mvn/local-repo` seeded from `~/.m2` minus its `_remote.repositories`, `file://` git
repositories, `GITLIBS` set; tools.deps read from the CLI jar).
- **Maps** (`ClojureDepsEdn`): the oracle's root `deps.edn` (`ROOT_TEXT`, verbatim), the
  user-level one, the project's (nearest at or above the entry file; the oracle reads the
  working directory's), merged like `merge-edns` (a map value merges, anything else
  replaces). A `:local/root` library's map merges over the root map alone (`deps-map`), so
  its `:paths` default to `["src"]`. Read by `ClojureReader.forEdn` (any tagged literal is
  data); one value; no repeated key (checked here: the code reader checks set elements
  only).
- **Validation** in the oracle's words, `Error validating deps in F. Found: v, expected: p,
  in: [path]`, walked in map order (the oracle sorts its problems; one problem, the common
  case, reads the same). **An unknown key passes**: measured (`:foo`, `:foo/bar`,
  `:deps/whatever`), the spec's `s/keys` is open. The plan said refuse it; that would block a
  valid `deps.edn` of a dependency its user cannot edit. Registered qualified keys
  (`:mvn/version`, `:local/root`, `:git/*`, `:deps/root`, `:deps/manifest`) are checked
  wherever a map holds one, like `s/keys`.
- **Selection** (`ClojureDepsGraph`, a port of `expand-deps` and `flatten-libs`): breadth
  first, `include-coord?`'s order (top, excluded, use-top, parent-missing, new, same,
  newer, older), `update-excl` (a same-version revisit queues only what an earlier visit's
  exclusions cut and this one does not exclude: `a4 (excl z4)` + `b4` includes `z4` in
  either order), `deselect-orphans`, then every tree path sorted by length and lib by lib.
  `Lib.compareTo` is `Symbol.compareTo` (namespace, then name), not the `ns/name` string.
  Maven versions by `ClojureMavenVersions`, a port of `GenericVersionScheme`
  (maven-resolver-util 1.9.27) from its class files, pinned by the oracle's 121x121 sign
  matrix; local roots equal or `No known ancestor relationship`; two types `Unable to compare
  versions`; git commits by descent (`Procurer.compareGit`, the first stays while unfetched).
  The Maven graphs the oracle resolved from a `file:` repository fixture (newest wins,
  orphans, exclusion narrowing, order, cycles) are pinned through
  `ClojureDepsGraphTest.FakeRepository`; over fetched descriptors by `ClojureDepsFetchTest`.
  `:override-deps`/`:default-deps` (alias arguments) are `choose-coord`
  (`ClojureDepsGraph.chooseCoord`), canonicalized against the project when first used.
- **Procurer** (`ClojureDepsProcurer`, the oracle's extensions): `:local/root` canonical
  (`SourceLoader.canonicalPath`, `toRealPath`) and checked (`Local lib X not found: R`); a
  manifest `:deps`, `:jar`, `:pom` (below), none (`Manifest file not found ...`) or
  another (`Manifest type :lein not loaded ...`; tools.deps has no lein reader);
  `:deps/prep-lib` checked after every contribution, local or git (`The following libs must be
  prepared before use: [..]`), never run. It fetches through `ClojureFiles.repositories()`
  (`ClojureRepositories`), the host's; null = fetch nothing (below).
- **Maven** (`coord-deps :mvn`, `coord-paths :mvn`): the descriptor's dependencies
  (`ClojureRepositories.mavenDependencies`, `MavenResolver.descriptor`: classifier and
  extension from the type) kept when compile/runtime and not optional, each lib
  `group/artifact$classifier`, its coord `:mvn/version`, `:extension` when not jar, `:exclusions`
  as a set of `group/artifact` (a POM's `*:*` is no lib's name: measured, excludes nothing).
  The jar (`mavenArtifact`) only for extension jar (`:extension "pom"`: children, no root).
  `canonicalize`: `[1.0]` is 1.0; a range goes to `mavenVersion` -> `MavenResolver.versions`, its
  highest (`Unable to resolve LIB version: RANGE` when none, the oracle's words), `RELEASE`/`LATEST`
  -> `MavenResolver.version`; a SNAPSHOT resolves at the fetch (`ClojureDepsFetchCliTest`,
  measured against clj 1.12.6 2026-10-08). A `:classifier` key is the oracle's `Invalid
  library spec` refusal. Repositories (`ClojureBasis.mavenSource`, `remote-repos`): central,
  clojars, then the merged maps' others in order, `nil` removes one, each carrying its
  `:releases` / `:snapshots` policy (`:enabled`, `:update`; tools.deps drops nothing: a
  repository with releases disabled still serves snapshots, `.kb/maven-resolver.md`
  "Per-repository policies"; until 2026-10-08 it was dropped whole), `http:` refused (`Invalid repo url (http not supported)`) unless
  `CLOJURE_CLI_ALLOW_HTTP_REPO`. Local repository: `:mvn/local-repo` against the project
  directory, else `~/.m2/repository`; measured, `clj` reads neither `settings.xml`'s
  `localRepository`, its `offline` nor its profiles' repositories (measured 2026-10-08; `eval/ClojureDepsRepositories` passes mirrors, proxies
  and servers on, global `$MAVEN_HOME` file merged, as `--java-dep`; `.kb/maven-resolver.md`). Only the top-level maps name repositories: a
  dependency's own `:mvn/repos` is never read. A built-in coordinate fetches nothing, children
  included (the oracle's classpath has clojure's spec jars; here they contribute no root).
- **A jar's own `pom.xml`** (`coord-deps :jar`): the first `META-INF/**/pom.xml` entry read as a
  project model (`ClojureRepositories.pomDependencies`, `MavenResolver.projectDependencies`:
  parents and imports from the repositories, classifier AS WRITTEN, type ignored), compile
  and runtime kept, OPTIONAL KEPT, coord `:mvn/version :scope [:optional]`. Measured: a
  test-jar typed dependency is the plain jar, a pom typed one the oracle tries as a jar and
  fails on.
- **A `pom.xml` project** (`coord-deps`/`coord-paths :pom`, `ClojureRepositories.pomProject`
  -> `MavenResolver.project(file, {project.basedir=.})`, read once per resolution): children
  as a jar's pom (`modelChildren`); roots, `pomRoots`: the effective `sourceDirectory`,
  `src/main/clojure`, each resource directory, then the build-helper directories, each
  `canonical(resolve(root, p))` (absent ones included), each once. Build-helper
  (`get-build-helper-paths`): when ANY plugin is `org.codehaus.mojo:build-helper-maven-plugin`,
  the FIRST plugin's executions with goal `add-source` give `<sources>`' children's values,
  then `add-resource`'s `<resources>` -- a value-less child (`<source/>`, a nested
  `<resource><directory>`) dropped, an empty one the root. Measured 2026-10-08 on `clj`
  1.12.6 (`MavenProjectTest`'s cases): no build -> `src/main/java`, `src/main/clojure`,
  `src/main/resources`; `${project.basedir}/x` -> `x`, `${basedir}/x` -> the literal
  `${basedir}/x` under the root, `${project.build.directory}/gen` -> `target/gen`; a parent's
  inherited and managed build-helper executions, a profile's plugin and resources, duplicate
  plugins all reach the first plugin as Maven merges them; a child plugin listed before the
  build-helper it shares with its parent makes that child plugin first (no helper dirs). Both
  POMs are validated at the oracle's level, STRICT (`.kb/maven-resolver.md`, "Validation
  levels").
- **git** (`canonicalize`/`manifest-type`/`compare-versions :git`): both spellings refused,
  URL given or inferred (the oracle's regex table, here only -- `GitFetcher` has none), then
  against the repository: a tag must exist (`Library L has invalid tag: t`), sha and tag must
  name one commit (`... point to different commits`), an abbreviated sha needs a tag and is
  replaced by the full one, no sha `has coord with missing sha`. The manifest checks the
  commit out (`gitCheckout`; absent: `Commit not found for L in repo U at S`), roots
  `:deps/root` below it (an absolute one as written, like the oracle) and detects
  `deps.edn`/`pom.xml`. Of two commits the descendant is newer (`gitDescendant`, asked of
  both repositories when the URLs differ); none: `No known ancestor relationship between git
  versions for L\n  U at X\n  U at Y` -- measured, the oracle throws an EMPTY message for
  two unrelated commits of one repository (`commit-comparator`'s `(ex-info "" {})`); here its
  own wording for the unknown case. Tags and commits are asked once per resolution.
- **A jar is read in place** (`SourceLoader.listArchive`/`loadArchiveEntry`: the central
  directory, an entry opened only when listed). Extracting it (`Archives.extractZip`, the
  plan) would need a cache keyed by content and invalidated when the jar changes, for
  nothing a read in place lacks; fetched Maven jars take the same path. `Found.path` is
  `jar!/entry`, `Found.resource` the entry (`*file*`, measured `lib/core.clj`). A namespace a
  jar holds only as `__init.class` is refused by name.
- **Who fetches**: the command line alone -- `RontoLispCli` puts
  `eval/ClojureDepsRepositories.createDefault()` (am.ik.maven + `GitFetcher` over
  `ArtifactCache`'s `gitlibs`) on `SourceStandards.clojureRepositories`, the seam hands it to
  `ClojureFiles.repositories()`. An embedder (`JvmSourceCompiler`), the tests and the browser
  (`SourceStandards.DEFAULT`) fetch nothing, the env-read rule of the user-level map. A fetch
  failure is a refusal (`FetchFailure` -> `LispReadException`) in the resolver's words.
- **Unfetched where nothing fetches, refused when missed**: a non-built-in Maven coordinate, a
  git coordinate, a jar's `pom.xml` dependencies and a `:pom` project add no root and a note
  (`Contribution.unread`); a lookup that finds nothing appends them
  (`notSearched`) to `Could not locate` and to `unknown namespace`. A lookup steps past an
  unfetched library's place in the order (an earlier unfetched jar holding the same namespace
  is not detected). The browser's refusal by name is this.
- **Resolved when a lowering starts** (`ClojureLowering.resolveProject`, a file's and each
  session buffer's): the roots compute before `declare`, a refusal positioned at the first
  datum -- so a class a dependency's jar holds is the lowering's (an `(:import ...)` before any
  `require` asks for it) and an unresolvable dependency stops the program before it runs, as
  the oracle's classpath does. Until 2026-10-08 the first lookup computed them (a program
  requiring nothing never read its `deps.edn`).
- **The Java class path** (gap 3 of `e39`): after the roots, each selected archive root holding
  a `.class` entry goes to `ClojureFiles.addJavaClassPath(jar, coordinate)` ->
  `SourceLoader.addJavaClassPath` -> the CLI's `JavaClassPath.add` (`.kb/java-interop.md`): the
  interpreter's loader grows, a JVM compile resolves against it, a program jar copies it, a
  generated pom lists the Maven coordinate. Directories never join (a `src` tree would be
  copied beside every jar, and two `src` names collide); a prepped library's
  `target/classes` is `--java-classpath`'s. A source-only jar (most Clojars libraries) does not
  join. Wasm: the compile's lowering and macro time see the classes too (a macro body's helper
  calling a dependency's class expands there, `ClojureDepsFetchCliTest`), a run-time call
  stays refused.
- **`data_readers.clj`** (gap 4): every root's `data_readers.clj`, then every root's
  `.cljc`, read when the project resolves (`ClojureSourcePath.dataReaders`); a tag reads
  through its var while the source is read again a datum at a time ("Data readers").
- **No resolved-graph cache** (gap 5, measured 2026-10-08): the `.cpcache` idea was the plan;
  the caches under the resolution already make a second run network-free -- a local
  repository file is used as is, an installed checkout needs no git, a tag is checked against
  the clone. cheshire 5.13.0 + clj-http 3.13.0 + core.async 1.6.681 + malli 0.16.4 (33 jars,
  54 POMs): first run 8.67 s; warm 1.79-1.89 s against 1.66-1.78 s for an empty `deps.edn`
  (`java -jar`, this machine), so re-resolving costs ~0.1 s, and the warm run passes with both
  repositories pointed at an unreachable host. A cache keyed by `deps.edn` content would save
  that 0.1 s for an invalidation scheme (local roots' manifests, jar times) whose failure is a
  stale classpath. Not built. A POM no repository has is recorded as Maven records it
  (`FILE.lastUpdated`) and not asked again within the update policy (daily), nor is cached
  `maven-metadata.xml` (`.kb/maven-resolver.md`, "Repositories"), so that case is network-free too.
- **Built-in coordinates** (`ClojureBuiltinLibs`): `org.clojure/clojure`, `spec.alpha` and
  `core.specs.alpha` at any Maven version are the front end; `ring/ring-core` up to 1.15.5 and
  `ring/ring-codec` up to 1.3.0 are the shipped Ring files, standing in for an older version
  as newest-wins assumes. A newer one, or the library as a local or git coordinate that lacks
  the namespace, is refused when the built-in file would load (`refuseBuiltinStandIn`); with
  no coordinate at all the files load as before (the oracle would fail).
- **`clojure.*`**: a contrib namespace (outside `ClojureBuiltinNamespaces.LANGUAGE`) loads
  from a dependency's roots like any library, and a miss is `Could not locate` with the
  not-searched note; clojure.jar's own never comes from a root ("Namespaces and project
  files"). Measured: `clojure.data.simple` under a `:local/root` loads on the oracle; a
  project `clojure/walk.clj` breaks the oracle's own startup (cyclic load through spec).
- **User-level map**: `SourceStandards.clojureConfigDir` (`CLJ_CONFIG`, `XDG_CONFIG_HOME/clojure`,
  `~/.clojure`), read from the environment by `RontoLispCli` alone and carried to every read
  (interpreter, compile path, REPL); `SourceStandards.DEFAULT` (tests, `JvmSourceCompiler`, the
  playground) reads none. Merged, measured: its `:paths` apply where the project has none, its
  `:deps` join, a relative path resolves against the project's directory.
- More than eight top deps iterate in the oracle's hash order, here in file order.
- **Aliases** (`ClojureBasis`, the oracle's `create-basis`; measured 2026-10-08): alias data
  is `merge-with merge` over root/user/project (a project `:test` keeps the root's
  `:extra-paths ["test"]`); the selection merges by `merge-alias-maps`' per-key rules
  (`ClojureDepsEdn.argMap`); `tool` replaces the PROJECT map's `:deps`/`:paths` only;
  `flattenPaths` chases `:extra-paths` then `:paths`; `:classpath-overrides` re-roots or
  (blank) drops a selected lib, its children stay. The selection reaches every read through
  `SourceStandards.clojureAliases` -> `ClojureFiles.aliases`. Undeclared aliases and
  `:main-opts` under `-A` (any mode) are the oracle's two warnings. A selected non-map alias
  adds nothing (the oracle: chars destructured, or an error). `:jvm-opts` ignored.
- **Command line** (`ClojureMain`, through `eval/ClojureCommandLine`; `CliOptions.ClojureRun`):
  `-A:x` is an option, `-M`/`-X` end the options (clj's order: M/X aliases, then every
  `-A`'s). `-M` = `:main-opts` (last wins) ++ args into `clojure.main`: `-m ns` is a
  generated program `(require 'ns)` + `(apply ns/-main *command-line-args*)`, a path a
  script, nothing the REPL; `-e`/`-i`/`--report` refused. `-X` is `clojure.run.exec` ported:
  `arg-spec` (a function reading wins when both fit), `qualify-fn`, `apply-overrides`, the
  map printed by `ClojureEdn.print` under `quote`. The command line's arguments are baked as
  `(set! *command-line-args* (seq (concat [..] *command-line-args*)))`, so `-o` keeps them
  ahead of the artifact's own. The project is the working directory's (`ClojureBasis.create`
  without walking up). `rontolisp test` with no target in a `deps.edn` directory (or with
  `-A`) runs `-test` namespaces below the selection's `:extra-paths` (default `:test`),
  exit 0/1 by `successful?` and a nonzero `:test`. `(System/exit n)` lowers to
  `(%host-exit (logand n 255))` (`ClojureInteropLowering.staticCall`), every backend.
  A file-less run keeps `.` as its first root (an existing deviation the oracle lacks).
- Pins: `ClojureDepsAliasesTest` (the measured alias classpaths), `ClojureMainTest`,
  `ClojureDepsCommandLineTest` (`-M`/`-X`/`test` as processes in the project, four backends,
  `System/exit`), `CliOptionsTest#theAliasFlags...`, `ClojureDepsEdnTest`, `ClojureDepsGraphTest`, `ClojureMavenVersionsTest`,
  `ClojureDepsProjectTest` (the four backends over a `:local/root` directory with its own
  dependency, a jar, a `clojure.*` contrib namespace; the refusals, Ring versions, the user
  map, a session), `PlaygroundReplTest#aClojureRequireReadsTheUploadedDepsEdnLikeEveryOtherRoute`,
  `ClojureDepsFetchTest` (Maven and git through in-memory repositories: the measured
  classpaths -- scopes, optional, exclusions, `*/*`, pom and classified types, `[1.0]`, a jar's
  pom, a `pom.xml` project's roots, `:deps/root`, tags, abbreviated shas, the descendant -- and every refusal; the class path
  reported before the lowering), `ClojureDepsFetchCliTest` (the CLI over a `file:` Maven
  repository and on-disk git repositories: four backends, newest-wins across Maven and git,
  what is fetched and what not, a Java jar on the interpreter's class path and beside a jar
  and in its pom, a `pom.xml` monorepo module (local parent, managed build-helper) on four
  backends, a second run with the remote gone and no git, refusals; the output the
  oracle's over the same repositories), `GitFetcherTest`, `MavenRepositoryTest#aPomGivenAsBytes...`.

## Ring adapter

`ring.adapter.rontolisp/run-server` `(handler opts)` serves a Ring handler on every transport
the Clack `:server :rontolisp` shim serves, by lowering to the SAME transport function the
shim's `run` calls, `rontolisp::%http-serve` (`.kb/clack.md`, "Transport selection"). Built
in, not a library: Clojure has no documented way to call a Common Lisp function. The call
site names `%http-serve` itself, so `HttpServeLibrary` (first pass of `expand`) splices it
before the passes reading its legs; `ClojureLibrary` (much later) splices the
`%clojure-ring-*` conversions. Verified 2026-10-08 on the interpreter and the JVM (socket), a
`--no-wasi` module driven by node (P1's serving transport), `--component` under `wasmtime
serve`, and a war on embedded Tomcat; plain P1 compiles and signals the directive's
"requires --component" at call time, like Clack.
- Request map (`%clojure-ring-request` over the Clack env): `:request-method` lower-cased
  keyword, `:uri` = `:request-uri` up to `?` (RAW, not the decoded `:path-info`),
  `:query-string`, `:headers` = the env's equal table as is (already a Clojure map),
  `:server-name`/`-port`, `:remote-addr`, `:scheme` keyword, `:protocol` string,
  `:content-type`/`:content-length`, `:body` = the `:buffered` stream (nil without a body).
  `slurp` closes it, which leaves it readable at its cursor on every backend: the compiled
  Gray body's close is the default no-op, and the interpreter's `close` keeps an
  `HttpRequestBodyStream` entry for the transport to remove. A second `slurp` answers `""`
  and `.read` `-1`, as under the oracle's Jetty adapter (ring-jetty-adapter 1.15.3, measured
  2026-10-08); `ClojureRingAdapterTest`/`ServeRingComponentE2eTest` `/echo` pin it.
  A computed method keyword is a fresh `string-downcase` charvec; `(get {:get ..} m)` and
  `=` were measured to match on all four. The keyword builders are listed slashless in
  `ClojureLibraryTest`'s namespace-map census (HTTP tokens admit no `/`).
- Response map (`%clojure-ring-response`): `:status` (nil -> 200, the servlet default),
  `:headers` to a dotted alist (a keyword name is its name; a seq value is one line per
  member; values through `str`), `:body` string (wrapped in a list), seq (members `str`'d),
  a CL stream (read to the end, closed: Ring closes an `InputStream` body), nil; anything
  else (a `java.io.File` host object) and a non-map response signal -> 500.
- Options: `:port` (default 80, `ring.adapter.jetty`'s), `:host`/`:address` (nil = every
  interface), `:join?` (default true; false answers the socket leg's handle), `:async?`
  truthy refused by name. Arity 2 exactly, the oracle's wording; as a value a 2-arg lambda.
- `(java.io.InputStreamReader. body [charset])` is the stream itself (`READER_WRAPPERS`,
  beside `PushbackReader`/`BufferedReader`) -- the measured `no matching constructor` on the
  JVM is gone on every backend. Over a `StringReader` it also answers the string stream,
  where the oracle has no such constructor (a leniency, not pinned).
- Cloudflare Workers: `examples/cloudflare-workers/ring-hello-one-source/` compiles
  `examples/clojure/ring-hello.clj` unedited (`--no-wasi --optimize=size --emit-js-glue`,
  419,012 B, 0 imports). Its glue is the hello-* glue less the entropy seed: the reactor
  transport reads the clock and the program draws nothing, so the shake drops
  `__ronto_seed_random` (`.kb/wasm-export-no-wasi.md`); `HostGlueEmitterTest` pins it
  against the answering envelope reactor built at `--optimize=size`. Verified 2026-10-08
  under `wrangler dev` 4.148.0: `/`, `/greet?name=`, urlencoded POST `/greet`, `text/plain`
  POST `/echo` (UTF-8 round trip), `/home` 302, 404. A urlencoded POST `/echo` answers ""
  because `wrap-params` consumed the body, as under the oracle's Jetty adapter.
- Not done: `ring.adapter.jetty` as an alias (would claim Jetty options; refused by name,
  pointing here), a `stop-server` (Jetty's is `(.stop server)`, interop on the handle). The
  util namespaces are "Ring util namespaces".
- Pins: `ClojureRingAdapterTest` (interpreter, JVM through a var, the `--no-wasi` export via
  node, the war's registration, the refusals, `run-server` as a value),
  `ServeRingComponentE2eTest` (opt-in), `WarE2eTest#aRingHandlerServesFromTheWarOnTomcat`
  (opt-in), clojure-spec `slurp-and-the-reader-take-an-open-stream`,
  `examples/clojure/ring-hello.clj` (the four compile legs), `HostGlueEmitterTest` (the
  Worker's checked-in glue).

## Ring util namespaces

**The pure-function subset of ring-core 1.15.5 / ring-codec 1.3.0 ships as Clojure source**
(`src/main/resources/am/ik/rontolisp/clojure/lib/ring/**`, `ClojureBuiltinNamespaces`):
`ring.util.response` `request` `codec` `mime-type`, `ring.middleware.params`
`keyword-params` `content-type`. `ClojureSourcePath.find` reads one AFTER every source root
(`Found.builtin`), so a project file of the name wins, as `src` precedes a jar on the
oracle's classpath; a `deps.edn` ring-core newer than the shipped one refuses them
("deps.edn"). Loaded through `loadNamespace` like any project namespace: vars, privacy,
`:refer :all`, `#'`, init statements, all unchanged.
- **Shipping mechanism, measured 2026-10-08 (wasm-GC P1, raw bytes)**: the plan preferred the
  resource. Pure-Clojure ports were oracle-identical on the interpreter but heavy wherever
  they process bytes or strings: `(form-decode-str "a+%41")` 340,532 B, `(url-decode
  "a%41")` 357,787 B, `wrap-params` over a query 500,960 B -- generic `conj`/`apply
  str`/`throw` and the regex engine per program -- against 26,940 B for the Common Lisp
  `rontolisp:url-decode`. And no core verb tests a Unicode letter (`\p{L}` of
  `wrap-keyword-params`; `alpha-char-p` is ASCII on wasm, the regex engine has no `\p`).
  Decided: the namespaces stay Clojure source (the oracle's own code where it is
  map-shaped), and the byte/string work is Common Lisp kernels (`clojure.lisp`, "The
  ring.util kernels"), reached through `rontolisp.internal.ring`, a known namespace only a
  built-in file may require (`ctx.builtinNamespaces`; anything else is refused by name).
  After: 61,083 B / 63,478 B / 167,936 B; `(r/response "hi")` 34,768 B against 30,077 B for
  an inline `defn`. Rejected: lowering rows for every var (the `clojure.set` shape) -- a
  Java arity/value row per var for the map-shaped vars that cost nothing as Clojure.
- **Init statements carry code**: a `def` of a regex in a required namespace compiles the
  regex engine into every program requiring it (the first draft's `ring.util.parsing`
  regexes made `ring.util.response` +30 KB). So `ring.util.parsing` is not shipped; the
  charset match is the kernel `content-type-charset`, the regex's search order spelled out
  (leftmost `;`, greedy `.*\s` from the last space, then none; token before quoted string,
  with the quoted-string backtracking; `$` before one final line terminator).
- **Oracle behaviour, pinned against the JDK code the oracle runs** (`ClojureRingUtilTest`,
  seeded random inputs): percent-decode is `new String(bytes, charset)` per `%XX` run,
  ported from `String.decodeUTF8_UTF16` (how many bytes one U+FFFD takes, a truncated tail
  ending the run); form-decode-str is `URLDecoder.decode` (strict hex, nil where it throws);
  the encoders are `getBytes` (`?` for the unmappable and a lone surrogate) and
  `URLEncoder.encode`; the charset match is the oracle's `re-charset`; the keyword test the
  oracle's two regexes, `\p{L}` from a generated table (`%clojure-ring-letter-table`, 675
  ranges past ASCII, decoded on first use; the test regenerates it from
  `Character.isLetter`). Differential runs against clj 1.12.6 + ring-core 1.15.5 the same
  day: 460 codec inputs (eight calls each), 3,000 content types and 3,000 parameter names,
  identical but the surrogate-pair spelling of `(mapv int s)` (code points here).
- Charsets: UTF-8, ISO-8859-1, US-ASCII and the JDK's aliases, case-insensitively; any other
  is an `IllegalArgumentException` whose message is the name (the oracle's
  `UnsupportedCharsetException`), where the oracle would accept the JDK's other charsets.
  Checked where the oracle checks it (`form-decode-str` only when the string holds `+` or
  `%`; `form-encode` of nil or a map of no strings never).
- Refusals: a var the oracle's namespace has and the built-in one leaves out
  (`file-response`, `url-response`, `resource-response`, `resource-data`, `base64-*`,
  `form-encode*`, `FormEncodeable`) is `ns/var is not built in: <why>` qualified and
  referred (`refuseLeftOut`, only when the namespace came from the built-in file); a
  ring-core namespace not shipped (cookies, session, flash, multipart, nested-params,
  not-modified -- HTTP dates over `java.util.Date` -- file, resource, head, content-length,
  `ring.util.io`/`time`/`parsing`/`test`/`async`, `ring.websocket`) is `x is not built in:
  the built-in Ring namespaces are ...`, and `ring.adapter.jetty` points at `run-server`.
- Deviations: `body-string` is a `cond`, not a multimethod (`class` of a string is no
  class object on wasm); `content-length` reads ASCII digits (`Long/valueOf` takes any `Nd`);
  `wrap-params` slurps the body (already characters; the encoding governs the
  percent-decoding as in Ring); `form-encode` of a map is a function, not the protocol.
- Native image and the web image: `resource-config.json` registers `clojure/lib/ring/...`
  (`NativeImageResourceConfigTest` lists both directories).
- Pins: `ClojureRingUtilTest` (the four JDK differentials, the letter table, the refusals,
  the shadowing project file, a real POST through `wrap-params` on the interpreter),
  clojure-spec `ring-util-*` and `ring-middleware-*` (all four backends, oracle-identical),
  `examples/clojure/ring-hello.clj` (verified by hand 2026-10-08 under curl on the
  interpreter, the JVM class and `wasmtime serve`).

## HTTP client

**`rontolisp.http-client` is babashka.http-client's API (0.4.23) as a built-in Clojure
namespace whose every request is the program's own `rontolisp:fetch` call: no transport is
new, and the five fetch has (the JDK's on the interpreter and the JVM, `wasi:http`, the
`--host-fetch` reactor's `env.fetch`, the `--native` runner) all carry it.**
- Shape: `clojure/lib/rontolisp/http_client.clj` (`request` and six verbs, each `(request
  (assoc opts :uri uri :method m))`) over the kernel namespace `rontolisp.internal.http`
  (`ClojureKernelLowering`): `request` is `rontolisp::%clojure-http-request opts transport`,
  `fetch` is `RONTOLISP:FETCH` itself (`Kernels.workers`). The namespace's transport `(fn [url
  options] (kernel/fetch url options))` makes the PROGRAM name fetch, which is what the
  transport splices read (`HostFetchLibrary`, `processForRunner`, `HttpLibrary` all run
  before `ClojureLibrary`, the library's own references are invisible to them) and what makes
  plain P1 and `--no-wasi` without `--host-fetch` refuse at compile time
  (`WasmFetchCompiler.reject`, whose words name the client too). A kernel call sets
  `usedExInfo` (`Kernels.exceptions`).
- Decided 2026-10-08: babashka.http-client over hato and clj-http, being the smallest API
  over the same `java.net.http` client fetch's JDK leg is. A built-in file rather than a
  lowering slice like `ring.adapter.rontolisp`: the verbs are real vars (values, `#'`,
  `:refer`), the `assoc` onto the options is the core's own; the cost is the front end's
  defn arity words (`wrong number of arguments passed to: get`, an `ArityException`; the
  oracle's `Wrong number of args (0) passed to: babashka.http-client/get`). The name
  `babashka.http-client` (and its sub-namespaces) is refused when no root holds it, pointing
  here (`ClojureBuiltinNamespaces.notShipped`: a claimed name promises its options, and
  `:client`, `:interceptors`, `:version` and its `java.net.URI` `:uri` have no value kind
  on wasm); its vars that build a Java client (`client`, `default-client-opts`, the `->X`
  builders) are refused by name (`leftOut`).
- The request (`clojure.lisp` "rontolisp.http-client"), at the call: the oracle's request
  interceptors in their order -- headers merged under `{:accept "*/*"}` with
  `prefer-string-keys` (a keyword name dropped beside a string one, written or
  capitalized), `:request-method`, `:url`, `:accept :json`, `:basic-auth` (base64 of the
  UTF-8 octets), `:oauth-token`, `:query-params` (`URLEncoder` through the Ring kernel
  `%clojure-ring-form-encode`, a collection value repeating its key at any depth),
  `:form-params` (content type unless the KEYWORD key names one, like the oracle's
  `get-in`) -- then `java.net.URI/create`'s and the JDK client's refusals of the URL
  (illegal character per component, malformed escape, `URI with undefined scheme`,
  `invalid URI scheme`, `unsupported URI`), the client's restricted header names and
  non-string values, and by name what no transport here honours (`:client`,
  `:interceptors`, `:timeout` until todo 148, `:version`, `:multipart`, `:raw`,
  `:expect-continue`, `:as :bytes`). A method fetch does not send is refused by name.
- The exchange: plain defuns each answering an `async-lambda`'s future, never
  `async-defun`s -- `LibraryDefunPruner` drops an unreached DEFUN and keeps every other
  top-level form, so an async-defun in `clojure.lisp` rode every Clojure program (caught by
  `ClojureLoweringTest#aProgramPassingRealFunctionsSplicesNoDispatcher`). `send` is fetch
  with a transport failure as the `%clojure-io-exception` carrier over the transport's own
  text; `follow` is the JDK's `RedirectFilter` under the oracle's default NORMAL policy
  (301/302/303/307/308, `++hops < 5`, never https to http, 303 and a POST's 301/302 to GET,
  the body kept only for an unchanged method off a 303, `ALLOWED_REDIRECT_HEADERS` across
  origins, `URI.resolve` ported with its RFC 2396 empty-path rule; a hop's body
  stream-closed); `respond` refuses a `gzip`/`deflate` body (nothing decompresses, so no
  `accept-encoding` is sent) and an `:as` with no clause, drains through `read-all` unless
  `:as :stream`, builds `{:status :headers :body :uri :request}` (headers a string-keyed
  map, a repeated field a vector in wire order) and throws `ex-info` `Exceptional status
  code: N` over it outside the oracle's unexceptional set unless `:throw false`; `exchange`
  applies `:async-then`/`:async-catch` (the latter handed `{:ex CompletionException :ex-cause
  :ex-data :ex-message :request}`) in async mode only. A plain call `%future-force`s it;
  `:async true` answers it.
- The rontolisp future and stream under the core verbs are arms of
  `ClojureArms.Family.FETCH` (tests `%clojure-future-p`, `%clojure-async-stream-p`; the
  alias `%clojure-future-or-host-p` -> `%clojure-host-future-p`, which the HOST family folds
  in turn, so FETCH precedes HOST in the enum; producer the kernel's `%clojure-http-request`
  alone). `deref`: a clause of `%clojure-deref-other` ahead of the host one (on the JVM the
  future IS a `CompletableFuture`, whose settled EMARKER/VMARKER payload only `_await`
  reads), a failure re-signalled as `java.util.concurrent.ExecutionException` over it (a
  C%E with the cause), like `get`. Timed `deref`: `%clojure-future-get-within` polls the
  new internal `rontolisp::%future-settled-p` between `(sleep 0.001)`s; on a component that
  sleep is wait.lisp's `%future-force` of a timer, which drives the scheduler, so
  `WaitForLibrary` splices it where `ClojureArms.sleepsOnAFuture` (a producer and the
  timed arm). `future?` T; `future-done?` is `%future-settled-p`; `future-cancelled?` and
  `future-cancel` false (cancel's "not possible"); `realized?` stays the
  `ClassCastException` the oracle throws for a `CompletableFuture`. `:as :stream` is
  fetch's body stream itself: `slurp` and `clojure.java.io/reader` drain it through
  `read-all` (the reader into a string stream), `.close` is `stream-close`, and a Ring
  response body passes it to the transport as it is, so a relay is byte-exact.
- Per transport: on P1 (`--native`, `--host-fetch`) an async body runs to its end at the
  call, so an `:async` request has completed when `get` returns and a timed deref never
  times out; the `--host-fetch` host's JS `fetch` follows redirects itself (20 hops, `:uri`
  the requested URL); a transport failure's text is the transport's (`java.net.ConnectException`,
  `the WIT call answered its error arm: :CONNECTION-REFUSED`, `fetch: cannot connect to ...`).
- Cloudflare Workers, verified 2026-10-08 under `wrangler dev` 4.148.0: a Ring handler
  proxying a local upstream through the client (`--no-wasi --host-fetch
  --host-boundary=streaming --emit-js-glue`, the three-line `index.js` of the other Workers)
  relays a 70,000 B binary reply byte for byte under `:as :stream`, answers `:async true` +
  `deref`, and catches the 404 `ex-info`.
- Oracle (clj 1.12.6 + babashka.http-client 0.4.23 against the corpus origin, 2026-10-08):
  identical but the response's missing `:version` and the `java.net.URI` `:uri` (a string
  here), `accept-encoding`, the User-Agent (fetch's), a transport failure's class (an
  `IOException`; the oracle's `ConnectException` is one), map key order, `:as :bytes`.
  What waits on a value kind or a transport feature (`:as :bytes`, compression,
  `:multipart`, a lazy reader over the stream body, `:timeout`, the arity words): todo `e63`.
- Pins: `FetchSpecE2eTest#clojureHttpClient` (`clojure-http-spec.yaml`: interpreter, JVM,
  `--native`, component), `ClojureHttpClientTest` (the lowering, the refusals, the FETCH
  strip, the P1 and `--no-wasi` refusals, a Ring proxy relaying a binary reply on the
  interpreter and the JVM, a redirect to a second origin dropping the credential headers --
  the corpus has one origin), `ClojureHttpClientHostFetchE2eTest` (node `--experimental-wasm-jspi`
  over the generated glue's `defaultHost()`: the client, and a Ring proxy through
  `worker(module)`), `ServeRingComponentE2eTest#wasmtimeServeRelaysAFetchedReplyByteForByte`
  (opt-in), the `http-client.md` doc examples (`DocExamplesTest` points their URLs at its
  local origin).

## Host boundary

**`rontolisp.wasm` and `rontolisp.wit` LOWER to the Common Lisp directives
(`rontolisp:wasm-import`/`wasm-export`, `rontolisp:wit-import`/`wit-export`/`wit-provide`);
no backend gained a path.** Built in (`ClojureWasmLowering`, `ClojureWitLowering`, dispatched
like `ring.adapter.rontolisp`) rather than a library, because Clojure cannot name a CL
function and a directive must be a top-level form.

- **The hook**: the WIT parser and `BoundaryType` live in `compiler`, which this package may
  not import. `ClojureBoundary` (the designators; a WIT interface's members and a world's
  exports, each type as representation + option/result element + WIT spelling, with its line)
  is implemented by `eval/ClojureHostBoundary` over `WitImportDirective.describe` /
  `WitExportDirective.describe` (`.kb/wit.md`, "The naming hook") and injected through
  `Clojure.read(..., boundary)` and `ClojureSession.setBoundary`. Without it
  (`ClojureBoundary.NONE`) both namespaces refuse by name.
- **Names**: the host sees the name as written (`:as`, the WIT label), never `c%ns/name`:
  every lowered `wasm-import`/`wasm-export` passes `:as`; the WIT directives take a `:names`
  table of `("label" "c%ns/name")` pairs.
- **Crossings** (`ClojureWasmLowering.Crossing`): `:bool` maps `false` to `nil` going out and
  a host's `nil` to `false` coming in (`false` is a non-NIL symbol here); `:s-expr` crosses as
  the Clojure printer's text read back by the Clojure reader (the directive declares
  `:string`), so vectors, maps, keywords and `false` round-trip. A declaration with no such
  crossing lowers to exactly the hand-written directive. A converting import binds the
  directive to `<var>%import` behind a `defun` of the var (`importWrapper`); an export that
  converts, or names no single-arity top-level `defn` taking exactly its parameters (several
  arities, a rest parameter, a `def`'d fn, a multimethod), goes through `<var>%export[N]`
  (`exportWrapper`) calling the var. Refused by name: `:bytes` (an `(unsigned-byte 8)`
  vector, which no Clojure value is), `:async` (its future is no Clojure future).
- **Order**: `defimport` registers its name in pass one by spelling (like `deftest`), so a
  call above it is a direct call; on the interpreter and the JVM it binds a stub of the
  declared arity throwing `UnsupportedOperationException` in Clojure's words (the
  directive's own stub would name the mangled symbol). A `wit/import` reads the WIT in pass
  two, so its vars exist below it, like a `require`'s alias. Every export (`wasm/export`,
  `:wasm/export`, `wit/export`) is recorded and resolved by `ClojureWasmLowering.flush` once
  the whole file has lowered (a session: at the end of the buffer), so its var may be defined
  below it and a redefined `defn` exports its newest definition; the interpreter's
  `wit-export` special form then sees every function, wherever the declaration sits.
- **`:wasm/export` metadata is QUOTED** into the var's metadata (`defnExport`): a literal
  map is built at run time, so an exporting program would differ from the non-exporting one
  by that store.
- **Byte identity, measured 2026-10-08**: a `:wasm/export` program equals the Common Lisp
  program loading the same `.clj` under the hand-written directive (419 bytes by default,
  263,545 at `--optimize=off`, and under `--component`); `wit/import` on P1 equals
  `defimport` of the members it calls (it binds no other since 2026-10-08, "`wit/import`");
  a `wit/export` world equals the hand-written `wit-export` block
  (P1 and `--component`). The world case needed `ClojureArms.scan` to skip the three
  function-naming directives: their quoted `|c%ns/f|` is a compile-time name, yet as a
  qualified symbol literal it made the scan keep the `#:ns{...}` printer arm
  (`Family.NAMESPACE_MAP`).
- **`wit/import`**: the interface's members become vars of a namespace named after its id
  (`namespaceOf`: `/` becomes `.`, an id without a package gains `wit:`), reached through
  `:as`/`:refer`; a second import of the id wires names only. The directive is hoisted with a
  `:names` table listing every member with a Clojure value: all but a stream, a future
  (anywhere inside the type, `unsupported`), an `async func` and the async built-ins, which
  are not bound and whose reference is refused at lower time naming the WIT line
  (`refusalOf`, from `ClojureNamespaceLowering.refuseLeftOut`). Each wrapper is emitted only
  when the program names its member (`referencedWrappers`), so `--component` imports only
  the called members, and so does a core module: `WitImportInliner` filters a `:names`
  directive by reference on P1 too (`.kb/wit.md`, "The naming hook"), so a member the core
  import cannot carry (a record, an option, a result, `char`, `s64`) fails the build with the
  Common Lisp P1 refusal only where the program calls it. Until 2026-10-08 P1 bound the whole
  table, and an interface with one `option` member failed every Clojure P1 build.
- **Rich values** (the Clojure spelling of the settled CL tier, `.kb/wit.md`): a record is a
  map of its fields' keywords, an enum or a payload-less case its keyword, a case with a
  payload `[:case payload]`, flags a set of keywords, a tuple or a `list<T>` a vector, a
  `result` value (an argument, or nested) `[:ok v]` / `[:error e]` (`:ok` / `:error` without
  payload); a label keeps its WIT spelling (`:DNS-error`; the boundary's is upcased,
  `:DNS-ERROR`). No oracle: the reference page is the contract; `[:tag value]` is
  `clojure.spec`'s conform shape of an `s/or`. The lowering turns each member's types into
  descriptors (`ClojureWitLowering.descriptor`: `NIL` alike, `:BOOL`, `(:OPTION . d)`,
  `(:LIST . d)`, `(:TUPLE "wit" d ...)`, `(:RECORD "wit" (kw :KW d) ...)`,
  `(:VARIANT "wit" (kw :KW [d]) ...)` for a variant or an enum, `(:RESULT ...)` a variant
  whose payload-less arm the boundary still conses, `(:FLAGS "wit" (kw :KW) ...)`) which one
  `clojure.lisp` walker reads both ways (`%clojure-wit-out` / `-in`), so a deep type costs a
  constant, not code. A wrapper reads each descriptor from a global holding it
  (`typeArg`: `c%wit%type%N`, one per distinct type -- wasi's error variants recur across
  members -- a top-level `setq` emitted ahead of the first wrapper reading it). `bool` and
  `option<bool>` keep the inline crossing, so a member with none of the rich types lowers
  byte-identically to before. To the host a list/tuple/flags takes any collection
  (`%clojure-seq-all`) and a record any map (`%clojure-call-keyword`, records and sorted maps
  too); a value of no shape of its type is an `IllegalArgumentException` naming the type.
- **The error arm** is an `ExceptionInfo` ("member of iface answered its error arm") whose
  data holds the converted value under `:rontolisp.wit/error` (`ERROR_KEY`, `::wit/error`
  with the alias); its cause, on the interpreter and the JVM, the condition the provider
  signalled. Rejected: leaving the CL `wit-error` condition (no Clojure reader reaches its
  payload, and a label like `DNS-error` cannot be recovered from `:DNS-ERROR` without the
  shape); `{:payload ...}` (an unqualified key a provider's own exceptions may hold). How the
  wrapper gets the arm differs by target, because the CL tier signals it on the host and
  answers an envelope on wasm: a WASM wrapper calls the RAW binding (`<bound>%raw`, the
  `(:OK . v)` / `(:ERROR . e)` envelope) and throws itself, so nothing catches -- a catch
  would put every result-calling module in EH mode (`.kb/error-handling.md`) -- and
  `WitImportDirective` binds a member named only through its raw binding without the
  `%wit-result` wrapper, which would have spliced the whole of wit.lisp; a host wrapper
  catches `rontolisp:wit-error` around the provider call and re-raises it as the arm, unless
  `rontolisp::*wit-providers*` holds no provider for the interface (then the
  "No provider is bound" refusal goes on unchanged). The key is spelled in the program
  (the descriptor global `(key . d)`), so `ClojureArms`' scan makes `NAMESPACE_MAP` and
  `(ex-data e)` prints `#:rontolisp.wit{:error ...}` like any map of one namespace.
- **Trap: `clojure.lisp` must name nothing of wit.lisp** (`rontolisp:wit-error`,
  `wit-error-payload`, `%wit-call`, `%wit-result`, `wit-provide`): `WitLibrary.process` runs
  after the Clojure splice, sees the whole library before the pruner, and splices wit.lisp
  (not prunable, its `define-condition` kept) into every Clojure program. What signals or
  catches the condition is lowered into the program instead (the host wrapper, the provide
  adapter).
- **`wit/export`**: each world label names a var of the declaring namespace; `flushWorlds`
  resolves it as `wasm/export` does (wrapper, `bool` crossing) and emits the `:names` table. The
  CL directive keeps the contract check, the type check (primitives only,
  `WitExportDirective.designator`) and `--emit-wit`. Refused: an `async func` export; a
  `wasm/export` beside a world (a file's rule only: a session's world is checked buffer by
  buffer against what is defined so far).
- **`wit/provide`**: `(rontolisp:wit-provide iface fn)` on the interpreter and the JVM; on
  wasm it answers the interface and binds nothing, the host providing every import. The
  interface is a string an import above names (its id or the spelling it wrote,
  canonicalized), refused otherwise, and `provide` has no value: the import's WIT is what the
  provider's values convert by. When a member converts, the provider is wrapped in an adapter
  (`ClojureWitLowering.adapter`: a lambda over `%clojure-wit-serve` and the interface's table,
  `("member" (d ...) result [error])` rows behind the error key), so it sees and answers
  Clojure values; an `ExceptionInfo` holding the error key it throws is signalled as
  `rontolisp:wit-error` with the converted payload and its message (`%clojure-wit-arm-p`, a
  `satisfies` clause), so a Common Lisp caller of a Clojure provider reads the CL tier and a
  Clojure caller turns it back. The language boundary IS the CL tier: each side converts to
  and from it, and what the WIT does not carry (other `ex-data` keys) does not cross.
- **WIT paths** resolve against the naming file through `ClojureFiles`; the directive keeps
  the path as written in the entry file (so the CL inliner, resolving against the entry's
  directory, reads the same file) and the resolved path in a required namespace's file.
- `--scaffold-wit` generates Common Lisp from a WIT file and reads no source, so it has no
  Clojure form; a `.clj` scaffold would be its own item.
- Pins: `ClojureWasmBoundaryTest`, `ClojureWitBoundaryTest` (the interpreter, the JVM, P1 under
  node through `--emit-js-glue`, `--component` under wasmtime including its real
  `wasi:keyvalue`, `wasi:sockets/types` and `wasi:http/types`;
  `everyRichValueCrossesInClojuresSpellingOnTheInterpreterAndTheJvm`,
  `eachLanguageSeesItsOwnSpellingOfOneInterface`,
  `aPreview1ModuleRefusesARichMemberOnlyWhereTheProgramCallsIt`), `WitNamingHookTest`,
  `ClojureHostBoundaryTest`, `WitImportInlinerTest.aNamesTableBindsOnPreview1TheMembersTheProgramNames`;
  `ExamplesE2eTest` over `examples/clojure/host-boundary/`, `examples/clojure/greeter/`,
  `examples/wit/keyvalue/page-hits.clj` (a record answer and an error arm, wasmtime's store
  and the program's alike).

## clojure.jar namespaces

**The map-shaped namespaces of clojure.jar ship as Clojure source written for this front
end** (`src/main/resources/am/ik/rontolisp/clojure/lib/clojure/**`, the
`ClojureBuiltinNamespaces` mechanism of "Ring util namespaces"): `clojure.walk`,
`clojure.template`, `clojure.pprint`, `clojure.data`, `clojure.zip`, `clojure.core.protocols`,
`clojure.datafy`, `clojure.stacktrace`, `clojure.math`, `clojure.core.reducers`,
`clojure.instant`, `clojure.uuid` ("Instants and UUIDs"), `clojure.java.io`
("clojure.java.io"), `clojure.repl`, `clojure.main`, `clojure.java.shell` (host only),
`clojure.xml` (2026-10-09). None of clojure.jar's own namespaces is left `unknown
namespace` but the ones no measured library names (`inspector`, `java.browse`,
`java.javadoc`, `java.process`, `parallel`, `reflect`, `repl.deps`, `core.server`,
`test.junit`/`tap`, `java.basis`, `tools.deps.interop`) and spec ("clojure.spec").
- **Licensing**: clojure.jar is EPL-1.0, this project Apache-2.0, so nothing of it is
  copied -- no code, no docstring. Each file is written from the documented behaviour and
  diffed against the oracle; a one-line var dictated by its contract
  (`(prewalk-replace smap form)`) reads like the oracle's because the contract allows no
  other shape.
- **Which first** (measured 2026-10-08 over 72 jars: the eight `e43` probe libraries plus
  64 widely used Clojars/contrib ones, a library counted once per namespace it names in a
  `.clj`/`.cljc`): `clojure.string` 43, `clojure.java.io` 20, `clojure.set` 19,
  `clojure.walk` 18, `clojure.pprint` 13, `clojure.edn` 13, `clojure.spec.alpha` 10
  (refused, "clojure.spec"), `clojure.core.protocols` 6, `clojure.test` 4,
  `clojure.datafy` 3, `clojure.stacktrace` 2, then one each for `clojure.zip`, `xml`,
  `uuid`, `template`, `repl`, `math`, `instant`, `core.reducers`, and none for
  `clojure.data`, `main`, `java.shell`. Among the probes: malli's `core` and
  camel-snake-kebab require `walk`, data.json and reitit `pprint`, honeysql `template`.
  What the four last ones' users call (the jars in `~/.m2`, 2026-10-09): fipp's `fipp.repl`
  `clojure.repl/root-cause` and `stack-element-str`; clj-commons pretty's `pretty.repl`
  redefines `#'clojure.repl/pst` and `#'clojure.main/repl-caught` and calls
  `clojure.main/main`; core.logic's `bench` requires `clojure.repl` and calls nothing;
  clj-http's `decode-xml-body` calls `(clojure.xml/parse stream startparse)` with its own
  SAXParserFactory startparse (the host route below).
- **Startup namespaces** (`ClojureBuiltinNamespaces.STARTUP`, shipped: `clojure.walk`,
  `clojure.core.protocols`, `clojure.instant`, `clojure.uuid`, `clojure.java.io`,
  `clojure.main`): `clj -M` has loaded `clojure.walk` (with `core.protocols`, `core.server`, `edn`, `instant`, `java.io`,
  `main`, `spec.alpha`, `spec.gen.alpha`, `string`, `uuid`) before the program, so a
  qualified name reaches it with no `require` and a `require` reads no project file
  (`ClojureSourcePath.find` skips the roots). Here `ClojureLowering.projectNamespaceOf`
  loads one on its first qualified name (`ClojureNamespaceLowering.preload`), but only
  inside a top-level datum (`topLevelDepth`): the definitions need `hoisted` to land
  ahead of the datum, and the pre-scan resolves names outside it. `STARTUP_NAMESPACES`
  (what `find-ns` finds) includes them.
- Any other shipped namespace follows the source path like a dependency: a project file of
  the name wins.
- `clojure.walk`: `walk` re-attaches the form's metadata itself (`with-meta-of`), since
  `into` here starts a derived value without metadata ("Deviations") where the oracle's
  keeps `empty`'s; only when there is metadata, so a walk never copies a node to record
  nil. A strict seq is a list, so `walk` keeps it a list (the oracle realizes a
  `LongRange` as a seq: printed alike). Deviation: `macroexpand-all` expands only the
  program's macros -- the core forms are lowering rows, not macros, so `(when x y)` stays
  where the oracle answers `(if x (do y))`.
- `clojure.template`: `do-template` calls `apply-template`, which calls
  `clojure.walk/postwalk-replace`, at expansion time ("Macros": a body sees the
  definitions above it, a required namespace's too).
- `clojure.pprint`: the API (the dynamic vars, `pprint-logical-block`,
  `print-length-loop`, `simple-dispatch`, `write`, `print-table`) is Clojure source; the
  layout is the kernel namespace `rontolisp.internal.pprint` (`clojure.lisp`, "The
  clojure.pprint kernels"). The dispatch runs with `*standard-output*` bound to a capture
  stream, each block start/end, conditional newline, indentation and fresh line an event
  beside the text since the last; the events are then replayed through the oracle's
  pretty writer's decision procedure (what its docstring states: write at once until the
  first conditional newline, then buffer; decide the buffer's first newline only when it
  overflows, `tokens-fit?` strict, the held-back trailing blanks not counted; the final
  flush takes only mandatory newlines and the linear/miser ones of a broken block).
  - Why a replay (measured 2026-10-08): the first draft laid the event tree out with full
    knowledge (a block breaks iff it does not fit, Oppen/XP style). It matched the oracle
    on linear-only data but not on fill, miser and per-line-prefix blocks, nor on the
    final flush: the oracle decides incrementally, so a newline's fate depends on when the
    buffer overflowed, which only the replay reproduces.
  - Fidelity, the same day against clj 1.12.6: 1,500 generated layouts (nested vectors,
    lists, maps up to eight keys, sets, scalars, each at a random margin) identical on the
    interpreter and the JVM class; 500 with one-key maps identical on both wasm legs (a
    hash map's walk order is the backend's, "Deviations", so several keys differ there as
    `pr` does); the feature probes (fill/miser/mandatory, `:current`/`:block` indent,
    per-line prefix, `*print-length*`/`*print-level*`/`*print-meta*`, reader macros,
    namespace maps, radix printing with its negative-number quirk, `print-table`)
    identical on all four.
  - Map and set members come in printer order (`kernel/members`, maphash order): `seq`
    walks a table the other way round, so `seq` would print `{:a 1, :b 2}` as
    `{:b 2, :a 1}` under `pprint` only.
  - `simple-dispatch` has an exact method per common value class (vector, map, set, list,
    number, string, keyword, symbol, boolean, character) beside `:default`: a dispatch
    value with no method of its own tests `isa?` against every method on each call (the
    multimethod keeps no cache, "Dispatch"), once per value printed.
  - Cost (2026-10-08, `pprint` of `(vec (range 20000))` / the same without a margin / 2,000
    four-key maps, 129/109/125 KB of output): JVM class 0.48/0.24/0.54 s, the oracle
    1.34/0.30/1.44 s (cold), wasm P1 and component 1.5/0.5/2.6 s, the interpreter
    13/3.9/22 s.
  - `code-dispatch` (a multimethod on `class`, like the oracle's) emits, per head symbol,
    the event sequence of the oracle's code layout, inferred from its output only
    (2026-10-08): the oracle spells most layouts as format directives, so a run of
    writes stops at the first object `*print-length*` cuts (`write-run`, `code-head`;
    `(condp = ...1 2)` and `(defn f ... ...)` follow), while the plain list, `cond`'s and
    the bindings' pairs use `print-length-loop`. The quirks kept: a blank before a broken
    head's miser newline (`(defn \n  f`), `(let [a 1] )`, `((let x))` for a non-vector
    second, a nested `#(...)` rebinding the parameter spellings, the ns docstring
    unescaped, a 3-member libspec with a keyword second (`[lib :as x]`,
    `[lib :refer [a b]]`) never broken and its list value filled, any other part's
    members filled past its first, a reference's arguments one column past its keyword
    with a linear newline after a list or vector argument and a fill one after any other
    (a `:current` indent set only when the keyword was not cut). `special-symbol?` heads
    (`def` `if` `fn*` `.`) match unqualified only, the rest as `clojure.core/` too.
    Verified the same day: 2,200 generated forms (the table's heads, plain calls,
    bindings, ns forms, at random margins, miser widths, lengths and levels) identical
    to clj 1.12.6 on the interpreter, 1,500 of them on all four backends. Deviations:
    `(ns)` alone prints as a list where the oracle overflows its stack; an empty list in
    a reference (`(:require ())`, `[x :refer ()]`) is nil here ("Deviations"), so it
    prints `nil` where the oracle signals or prints `()` (an empty vector part signals
    the oracle's `Exception` alike).
  - Left out, refused by name (`refuseLeftOut`): `cl-format`, `formatter`,
    `formatter-out` (Common Lisp format directives over Clojure values). Deviation:
    `get-pretty-writer` answers its writer, so each `pprint` lays out from column 0
    within the margin bound when it runs; the oracle's pretty writer keeps its creation
    margin and its column across calls.
- `clojure.data`: `EqualityPartition` and `Diff` are protocols extended to nil,
  `java.util.Set`, `java.util.List`, `IPersistentVector`, `java.util.Map` and `Object`,
  whose arm sends a `map?` value to the map diff: a record reaches neither the `Map` nor a
  vector row here (measured 2026-10-08), where the oracle's record is a `java.util.Map`. The
  map diff walks `(set/union (keys a) (keys b))` over the key SEQS, like the oracle's (a
  shared key comes twice and merges alike), and answers a lazy seq; the sequential diff
  answers vectors. Deviation: a part that is a map of several keys may print its keys in
  another order (a key merged again moves to the end here; "Deviations": walk order).
- `clojure.zip`: a loc is the oracle's own shape (`[node path]`, the path a map of `:l`
  `:pnodes` `:ppath` `:r` and `:changed?`, the zipper functions in the loc's metadata, the
  end loc `[node :end]` without metadata) since programs print and destructure locs; the
  error words (`called children on a leaf node`, `Insert at top`, `Remove at top`, as
  `Exception`) are the oracle's.
- `clojure.core.protocols` / `clojure.datafy`: `Datafiable` and `Navigable` with
  `:extend-via-metadata`, `CollReduce`, `IKVReduce` and `InternalReduce` with nil and
  `Object` rows (the `Object` rows call `reduce`/`reduce-kv`); `iterator-reduce!` is refused
  by name. Loading the namespace costs the `CollReduce` protocol since 2026-10-08:
  `(prn (clojure.core.protocols/datafy 1))` wasm P1 110,671 -> 114,564 B, JVM class 105,351
  -> 108,674 B.
- **`reduce` consults `CollReduce` and `reduce-kv` `IKVReduce` for a record, deftype or
  reify whose type has its own row** (body or extension), like the oracle's protocol
  dispatch, behind a body's `clojure.lang.IReduceInit`/`IReduce`/`IKVReduce`, which the
  oracle asks first ("Host interfaces"); every other value takes the verb's own walk, which answers what the oracle's
  rows for the core collections answer. An extension to nil, `Object` or a core kind is
  reached only through `coll-reduce`/`kv-reduce` (deviation: the oracle's `reduce` also
  takes one for a collection that is no `IReduceInit` -- a string, a map, a lazy seq -- and
  ignores it for one that is, a vector or list; measured 2026-10-08, clj 1.12.6). The
  verbs the oracle builds on them follow (`clojure.core` read 2026-10-08): `into`,
  `transduce`, the `cat` transducer (all through `%clojure-reduce-init`), `run!`, `mapv`
  of one collection, `filterv`, `group-by`, `frequencies`; `update-vals`/`update-keys`
  through `reduce-kv`. `vec`, `set`, `seq`, `count`, `apply`, `sequence` stay refused,
  like the oracle's.
  - The arms: the reducible family (`ClojureArms.Family.REDUCIBLE`, `clojure.lisp`
    "CollReduce and IKVReduce"). Tests `%clojure-coll-reducible-p`/`-kv-reducible-p` in
    the library verbs, the view `%clojure-reducible-items` (the list a reduction steps)
    around `group-by`'s and `frequencies`' collection (`ClojureSeqLowering.reducedAllForm`)
    and an `eduction`'s. Producers `%clojure-coll-reducer-row`/`-kv-reducer-row`: the
    lowering stores a typed row of the two protocols through them
    (`ProtocolDef.reducerRow`, `ClojureProtocolLowering.rowStoreForm`: inline bodies,
    reify, an extension whose target is a record or deftype), and they keep the
    protocol's table in a library global the tests read -- no mangled program name in the
    library. The namespace's own nil/`Object` rows and a core-kind extension are no
    producer, so a datafy program folds the arms. Verified 2026-10-08: a program reducing,
    `reduce-kv`ing, `into`/`transduce`/`mapv`/`filterv`/`group-by`/`frequencies`/
    `update-vals`/`eduction`/`run!` with a protocol record, and `examples/clojure/demo.clj`,
    byte-identical as wasm P1 and JVM class.
  - An `eduction` over such a value reduces it at the `eduction` (the view): `into`,
    `transduce` and a seeded `reduce` answer the oracle's, the init-less `reduce` and the
    `seq` the oracle refuses answer (deviation, user doc).
- `clojure.datafy`: the oracle's `Throwable` (`Throwable->map`) and `clojure.lang.IRef`
  (`[value]` with the ref's metadata) rows, through the walk ("Dispatch", walked classes;
  until 2026-10-08 an `Object` row with an `instance?` test and an exception datafied to
  itself). `:clojure.datafy/class` comes from `rontolisp.internal.datafy/class-name`
  (`%clojure-datafy-class-name`): an exception's class name, else `%clojure-class-name-of`,
  the oracle's class names `%clojure-no-method` already spelled, since `class` answers a
  kind keyword. A namespace or class datafies to itself (deviation). Pin: clojure-spec
  `clojure-datafy-of-an-exception-and-a-ref-like-the-oracle`.
- `clojure.stacktrace`: `print-throwable` spells the class through
  `rontolisp.internal.throwable/class-name` (`%clojure-datafy-class-name`: `class` of a
  throwable is its class-name keyword here, of a caught host exception the host class, on
  which `(name (class tr))` failed until 2026-10-09,
  `ClojureInteropTest#printThrowableSpellsTheClassOfACaughtHostException`; the kernel
  emits the exception runtime it reads) and a nil message `null` like the oracle's
  `printf`. A throwable has no frames (`.getStackTrace` answers `[]`), so
  `print-stack-trace` prints ` at [empty stack trace]`: pinned as a deviation by
  clojure-spec `clojure-stacktrace-prints-no-frames`; `print-trace-element` is the oracle's
  over a host `StackTraceElement` (interpreter and JVM,
  `ClojureInteropTest#printTraceElementSpellsAHostStackTraceElementLikeTheOracle`).
- `clojure.math` (2026-10-08): all 45 vars, each `defn` a one-line body over a kernel of
  `rontolisp.internal.math`. A double function's kernel is lowered IN PLACE
  (`ClojureKernelLowering.Kernels.inline`, `STRICT_MATH`): `(%strict-math :name ...)` over
  `%clojure-double` of each argument -- the oracle's own wrapper is one `Math` call over
  `(double x)` -- so a call costs the `defn` and the primitive (`.kb/transcendentals.md`,
  "%strict-math": `StrictMath`'s bits on all four backends, NaN where the CL function would
  answer a complex). `round` (the floor of a + 1/2 over the exact rational, saturating at the
  long range, NaN 0) and the long arithmetic (`floor-div`/`floor-mod`, the `-exact` six:
  `ArithmeticException` "long overflow" / "/ by zero") are workers `%clojure-math-NAME` over
  `%clojure-long-cast`, the oracle's `RT.longCast` of an object (a double or ratio truncated,
  NaN 0, 2^63 itself the largest long as Java's `(long)` saturates, past it
  `IllegalArgumentException`, a character its code); `scalb`'s exponent goes through
  `%clojure-int-cast` (`RT.intCast`: past the int range "integer overflow"). The two casts are
  shared with `int`/`long` and `vector-of`'s integer kinds, whose copy refused 2^63 until 2026-10-08 (the
  oracle stores Long/MAX_VALUE). `E`, `PI`, `to-radians`, `to-degrees` (one multiply, Java's
  constants) and `random` (`rand`) are plain Clojure. Not a startup namespace: a qualified
  name without a `require` is the oracle's class lookup failure. Deviations, each in the user
  doc: the `StrictMath` bits (`Math` differs in the last place on 1-8% of arguments for nine
  functions, measured in `.kb/transcendentals.md`); `copy-sign` reads a NaN sign as positive;
  a ratio converts to its nearest double (the oracle's `Ratio.doubleValue` rounds to 16 digits
  first: `(double 2/3)` 0.6666666666666666 here, ...667 there -- `double` alike); `scalb` of a
  LITERAL double exponent past the int range is the oracle's other cast
  (`IllegalArgumentException` "Value out of range for int", a non-literal one "integer
  overflow" as here); a character is accepted as a long through a function value too (the
  oracle's value path casts to `Number` first). Size, wasm P1 (2026-10-08): `(m/sqrt 2.0)`
  printed 25,926 B against 25,061 B for printing a double product, `(m/sin 2.0)` 30,554 B (the
  fdlibm sin and its tables), `(m/floor-div 7 2)` 19,502 B -- the shaker drops every other var.
  Pins: clojure-spec `clojure-math-*` (four oracle-identical cases and the `StrictMath`
  deviation, all four backends), `ClojureLanguageNamespacesTest`
  (`clojureMathLowersEachDoubleFunctionToOneStrictMathCallAtItsRequire`,
  `everyStrictMathFunctionIsAClojureMathKernelOfItsArity`: the kernel table against
  `StrictMathFunction`, which this package may not import).
- `clojure.core.reducers` (2026-10-08): every public var but `pool`, `fjtask` and `->Cat`
  (refused by name). The reducers are `reify` objects of `CollReduce` (`folder`s also of
  the namespace's `CollFold`), so they need the multi-arity protocol methods ("Dispatch")
  and the reducible arms above; written over explicit three-arity reducing fns (the
  oracle's `rfn`/`defcurried` are private macros). `fold` keeps the oracle's split -- a
  vector halves while it holds more than `n` (`fold-range` over index bounds, so only a
  leaf is copied by `subvec`), each part reduced from `(combinef)`, the halves combined in
  order -- but runs on the calling thread, the left part first; a map, record or sorted map
  folds as one `kv-reduce` (the `Object` row through the namespace's own `reduce`, which
  takes `java.util.Map` like the oracle's -- the oracle's hash map past eight entries also
  combines its trie's parts), any other collection as one reduction. Measured on clj
  1.12.6 the same day, `(r/fold 2 (fn ([] []) ([a b] (conj a b))) conj [1 2 3 4 5])` is
  `[1 2 [3 [4 5]]]` here as there. cat's accumulator (the oracle's `java.util.ArrayList`)
  is an adjustable vector with a fill pointer (`rontolisp.internal.reducers`): it counts,
  seqs, prints `[...]` and reduces as a vector everywhere, and the `IPersistentVector` row
  of `CollFold` reduces one whole like the oracle's `ArrayList` row. `(cat l r)` of two
  non-empty collections joins them into a fresh accumulator: the oracle's `Cat` is a tree
  that `count`s and `seq`s through interfaces a deftype here cannot implement, so a
  `foldcat` result could not be counted; the cost is one copy per combine level and a
  fold of a joined result reducing it whole (deviation, user doc). Size, wasm P1 / JVM
  class: `(prn (r/fold + (r/map inc [1 2 3])))` 143,501 / 131,041 B.
- `clojure.repl` (2026-10-09, not a startup namespace: `clj -M` has not loaded it, measured
  with `find-ns`): `doc`, `pst`, `root-cause`, `demunge`, `stack-element-str` (the last
  three delegating to `clojure.main`'s). `doc` is a macro expanding to
  `(#'print-doc (meta (var name)))` (the private var reached through `#'`, like the
  oracle's), so it prints the metadata the lowering recorded ("Vars and metadata"):
  oracle-identical for a program definition; a core var has only `:name`/`:ns`, so its
  name alone; a special form (and `&`/`catch`/`finally`, which the oracle maps to
  `fn`/`try`) its name, `Special Form` and the clojure.org link, without the oracle's
  `:forms` and text (clojure.jar's words, not copied); a name that is no var, a namespace's
  included, the lowering's `Unable to resolve var` (no `resolve` or `find-ns` at expansion
  time; the oracle prints a namespace's doc or nothing); a keyword refused (spec). `pst`
  prints `getSimpleName` of `rontolisp.internal.throwable/class-name`, the message and
  `ex-data`, the compile-phase note, no frame lines, `Caused by:` and each cause. Left out
  by name (`ClojureBuiltinNamespaces.replLeftOut`): `dir`, `dir-fn`, `apropos`,
  `find-doc` (`ns-publics`/`all-ns`: a namespace's vars exist only at lower time),
  `source`, `source-fn` (the text is not kept), `set-break-handler!`, `thread-stopper`.
- `clojure.main` (2026-10-09, a startup namespace like the oracle's): `demunge` (the
  munged spellings longest first, `$` as `/`; 30 spellings oracle-checked), `root-cause`,
  `stack-element-str`, `ex-triage`, `ex-str`, `err->msg`, `repl-caught`,
  `repl-exception`, `repl-prompt`, `repl-requires`, `with-read-known`. `ex-triage` reads
  frames given in the data like the oracle's (`:trace` tuples, `core-frame?`,
  `source-symbol`), so triage of `Throwable->map` data is oracle-identical when the data
  is; a throwable here has none, so its report says `(REPL:1)`. Deviations:
  `:clojure.error/path` is the source as given (the oracle's is relative to the working
  directory, which wasm has not); `ex-str` of spec problems is refused. Left out
  (`mainLeftOut`): `repl`, `main`, `load-script` (no `eval`), `repl-read`,
  `renumbering-read`, `skip-whitespace`, `skip-if-eol`, `with-bindings` (the REPL's parts),
  `report-error` (main's uncaught report). Written without `{:clojure.error/keys [...]}`
  destructuring, which the front end does not have (e92); `repl-caught` ends with `(flush)`.
- `clojure.java.shell` (2026-10-09): Clojure source over `ProcessBuilder` interop, so the
  interpreter and the JVM run it; `ClojureBuiltinNamespaces.HOST_ONLY` refuses its load
  while lowering for a target without the host (`ClojureNamespaceLowering.loadNamespace`,
  `ctx.hostTarget`), not at the first call, the uiop `run-program` stance (`.todo/363`)
  being for Common Lisp, where no program reaches the host at all. Standard input from a
  temporary file (a string encoded in `:in-enc` through a host `PrintStream`, a File as it
  is, a reader's text), the error output to another, stdout read by `transferTo` into a
  host `ByteArrayOutputStream` decoded in `:out-enc`: no pipe can fill while another is
  read, without the oracle's futures. A host `byte[]` crosses the boundary as a Lisp list
  (empty: nil), so nothing reads one; `:out-enc :bytes` is refused (e81). Measured
  oracle-identical on the interpreter and the JVM:
  `ClojureInteropTest#clojureJavaShellRunsAHostProcessLikeTheOracle`.
- `clojure.xml` (2026-10-09): `(parse s)` reads the document itself on every backend:
  `slurp` with `:encoding "ISO-8859-1"` (one character per byte), then the kernel
  `rontolisp.internal.xml/events` (`clojure.lisp` "clojure.xml: rontolisp.internal.xml"):
  decoding from the BOM or the declaration (UTF-8 with the oracle's Xerces messages for a
  malformed sequence, UTF-16, ISO-8859-1, US-ASCII, windows-1252; another name
  `UnsupportedEncodingException` naming it, as the oracle does only for a name the JDK
  lacks), line ends, a non-validating XML 1.0 reader with the internal subset's general
  entities (char refs expanded at declaration, entity refs at use, markup inside, the
  oracle's recursion path) and no external DTD/entity (the oracle's `startparse-sax-safe`
  reads none either). It answers events (a vector `[qname n v ...]`, a string, nil), the
  same the host route's `ContentHandler` proxy records, so one `tree` builds both:
  consecutive text merged, a run all Java whitespace dropped (`k/blank?`, Java's
  `isWhitespace`: a no-break space stays), each element made once by `struct` and its
  attributes by `array-map` in reverse document order, so the interpreter and the JVM
  print the oracle's key order (an `assoc`'d struct reordered its keys); `emit-element`
  walks attributes through `rontolisp.internal.pprint/members` (printer order). A
  malformed document is the new refusal carrier `%clojure-sax-parse-exception`
  (`org.xml.sax.SAXParseException`, `ClojureRefusals.SAX_PARSE`) with Xerces' message for
  33 measured mistakes. A host object source (`k/host?`) and `(parse s startparse)` take
  the host route: `startparse` gets an `org.xml.sax.helpers.DefaultHandler` proxy, so
  clj-http's own SAXParserFactory startparse works on the interpreter and the JVM
  (`ClojureInteropTest#clojureXmlHandsAHostSourceAndAStartparseToTheHostsSaxParser`).
  Deviations (user doc): `<!ATTLIST>` defaults not applied; the five encodings; a string
  naming no file `FileNotFoundException` with the path as given; a rarer mistake's message
  rontolisp's own; a wasm program using it compiles with the `java:` warnings of the host
  functions (as `clojure.stacktrace`'s `JAVA:CALL`). Left out (`xmlLeftOut`):
  `content-handler`, `*stack*`, `*current*`, `*state*`, `*sb*`.
- Not shipped (decided 2026-10-08): pprint's `cl-format`/`formatter`/`formatter-out` (e58).
- Pins: clojure-spec `clojure-walk-*` (all four backends, oracle-identical, the first
  case loading `clojure.walk` through a qualified name only),
  `clojure-template-substitutes-per-group-of-values`, `clojure-pprint-*`,
  `clojure-data-diff-compares-like-the-oracle`, `clojure-zip-moves-and-edits-like-the-oracle`,
  `clojure-datafy-and-core-protocols-like-the-oracle`,
  `clojure-stacktrace-prints-throwables-like-the-oracle`, `clojure-repl-*` and
  `clojure-main-*` (`-of-a-special-form-and-a-core-var`, `-pst-prints-no-frames` and
  `-reports-an-error-without-its-location` the deviations), `clojure-xml-*` (files under
  `/tmp`, the wasm legs' preopen),
  `reduce-and-the-verbs-built-on-it-reach-a-coll-reduce-row`,
  `reduce-kv-and-update-vals-reach-an-ikv-reduce-row`, `clojure-core-reducers-*` (the
  last one the deviations), `ClojureLanguageNamespacesTest` (the startup load, a project
  file never shadowing a startup namespace, a contrib `clojure.*` namespace on the source
  path, the refusal of one not built in, the reducers' refusals, the repl/main/xml
  refusals by name, the shell's refusal for a target without the host),
  `ClojureLibraryTest#aProgramStoringNoReducerRowSplicesTheReduceVerbsWithoutTheirProtocolArms`,
  `ClojureArmsTest#theReducibleFamilyIsMadeByATypedRowOfCollReduceOrIKVReduce`,
  `ClojureLoweringTest#aTypedRowOfCollReduceOrIKVReduceIsStoredThroughTheLibrary`.

## clojure.java.io

**`clojure.java.io` is a built-in, startup namespace of Clojure source**
(`clojure/lib/clojure/java/io.clj`, 2026-10-08) over the kernel namespace
`rontolisp.internal.io` (`ClojureIoLowering.kernels()`; `clojure.lisp` "clojure.java.io:
rontolisp.internal.io"). `Coercions` and `IOFactory` are real protocols (`extend-protocol` in
the file), `default-streams-impl` a map. Until then only `reader` resolved, as a lowering.
- **Values of the front end's own** (decided 2026-10-08): `java:` is a call-time error on
  wasm, and `reader`/`slurp`/`spit` already ran there over WASI preopens. Wrappers: `(:C%FILE
  path)` (normalized as `java.io.File` does on Unix), `(:C%URL spec)`, `(:C%URI spec)`,
  `(:C%INPUT-STREAM #(s octets i closed))`, `(:C%OUTPUT-STREAM #(s closed))` over binary file
  streams; a reader decoding / writer encoding a byte stream is a CL string stream registered
  in `%clojure-io-streams` (`eq` table, `#(kind sink charset)`); a file's own reader and
  writer are plain file streams (the STREAM family's `BufferedReader`/`BufferedWriter`). A
  wrapper is a list, so `equal` (and with it `=`, map keys) compares by spelling: no `=` arm
  (one made the interpreter's `=` measurably slower and was dropped). A URL keeps only its
  spelling; a resource's text lives in `%clojure-io-resources` (`equal` table by spelling).
- **Arms** (`ClojureArms.Family.IO`: tests `%clojure-io-p`, `%clojure-io-instance-p`,
  `%clojure-io-openable-p`, view `%clojure-io-host`, producers `ClojureIoLowering.PRODUCERS`,
  the kernels and the lowering's `java.io` constructions): the printer, `str`, the
  structural hash, `class` and its name, `instance?`, a protocol's tag, `slurp`, `spit`,
  `line-seq`, every instance call (`ClojureIoLowering.methodArm`). A program making no io
  value compiles byte-identical (measured 2026-10-08 on `demo.clj`, print, protocol,
  multimethod and spit/slurp/line-seq programs; pinned by
  `ClojureLibraryTest#aProgramMakingNoIoValueSplicesTheLibraryWithoutItsIoArms`). The
  interpreter keeps every arm (`ClojureLibrary.process` is the compile path's): `str` of a
  non-io value pays one `%clojure-io-p` call, ~5% of a `str`-bound loop (bench 2026-10-08:
  300k `(str i :k)` 19.3 -> 20.4 s mean of five on a loaded host; `=` and `pr-str` within
  noise). `%clojure-io-p` is one call however it answers (the registry is read only once a
  stream is in it).
- **Prelude trap** (measured 2026-10-08): a `clojure.lisp` parameter named `write` grew every
  Clojure program (+29 KB `demo.clj`): any symbol of the library, even in a defun nothing
  calls, counts for `LispPreludeLibrary` selection, and `WRITE` pulled the printer renderer.
  Renamed `for-write`; check a new library symbol against the prelude's names.
- **The `java:` boundary**: `ClojureIoLowering.crossing` wraps every computed
  `java:call`/`new`/`static` operand, and a call's receiver, in the view `%clojure-io-host`
  (a File, URL, URI to the host object; the HOST family's arm), so `(.toPath f)` and
  `(Objects/toString f)` see a `java.io.File`. A host File/URL/URI a member answers stays
  host: `slurp`/`spit`/`line-seq` take one through the HOST view `%clojure-host-file-path`
  in path position (an `or` of arms over the io runtime there carried +69 KB of JVM class
  into every `java:`-naming spit program), the kernels through `%clojure-io-from-host`, a
  protocol through `hostTagArms` (`:java.io.File`). `java.net.URL.`/`URI.` constructions stay
  host (a `URISyntaxException` test pins one).
- **Classes**: `class` answers `:java.io.File`, `:java.net.URL`, `:java.net.URI`,
  `:java.io.BufferedInputStream`, `:java.io.BufferedOutputStream` (character streams
  `:java.io.BufferedReader`/`Writer`); `ClojureClassBases.IO_SUPERS` + `INTERFACES` chain
  them, so a dispatch value or hierarchy argument spelling one is its keyword ("Dispatch")
  and `instance?` asks `%clojure-io-instance-p` (`%clojure-io-supers`). A protocol extended
  to File/URL/URI keys the exact keyword (`ClojureIoLowering.EXTENDABLE`, never a walked
  class: `walkTargetOf` answers null for them, merged with e60's walk 2026-10-08); one
  extended to `java.io.InputStream`/`Closeable` walks.
- **Methods**: `ClojureIoLowering.METHODS` (name x arity -> `%clojure-io-m-*`, each refusing
  another kind in the oracle's `No matching method m found taking n args for class C`); any
  other member of an io value is the host object's (interpreter, JVM). Constructions
  (`ClojureIoLowering.construction`): `File.` of 1/2, `FileInputStream.`,
  `FileOutputStream.` (append), `FileReader.`, `FileWriter.` (append), `Buffered*.` (the
  stream itself), `OutputStreamWriter.`, `InputStreamReader.` over a byte stream.
- **Resources**: a literal name is found while lowering (`ClojureSourcePath.findResource`):
  a directory root answers `file:` + its absolute path (`ClojureFiles.absolute`, link not
  resolved), a jar root `jar:file:<abs jar>!/name`; the text is embedded
  (`%clojure-io-url-found spec text`), so a jar's entry reads on wasm too. A computed name
  is `%clojure-io-resource name '(roots)` at run time, below the directory roots only (the
  jar case is the documented deviation). A loader argument still runs and is ignored.
- **Charsets**: UTF-8, ISO-8859-1, US-ASCII and aliases; any other name the oracle's
  `UnsupportedEncodingException`. A non-UTF-8 reader decodes the rest of its byte stream at
  once; a registered writer encodes into its byte stream at flush/close.
- **slurp/spit through `IOFactory`**: io.clj's last form `(k/install reader writer)` sets
  `%clojure-io-factory`; `%clojure-io-openable-p` (an io test) sends `slurp`/`spit` of any
  non-string to `%clojure-io-slurp`/`-spit`, which ask the namespace's `reader`/`writer`
  (with `:encoding`/`:append`) when no kernel opens the value -- a type extended to
  `IOFactory`, and the oracle's `Cannot open <1> as an InputStream.` for `(slurp 1)`. A
  program not loading the namespace keeps the path-only `slurp`.
- **`extend` of a computed map** (2026-10-08, needed for `default-streams-impl`):
  `ClojureProtocolLowering.computedRows` -> `%clojure-extend-rows (lambda (method fn)
  store) map '(methods)`, the store the literal map's row takes, an entry naming no method
  skipped (the oracle stores it but never reads it); `extend` takes several protocol/map
  pairs. Answers nil (a literal map's `extend` still answers the last lambda, e72).
- **Measured cost of a java.io-naming program** (2026-10-08, wasm P1 / size / component /
  class, before -> after): `(spit f x :append true)` 66,852 -> 66,803 / 58,450 -> 58,401 /
  72,788 -> 72,737 / 74,662 -> 74,659; `.write` on a `StringWriter` 52,383 -> 52,741 /
  44,383 -> 44,741 / 55,918 -> 56,289 / 102,833 -> 103,383 (the instance call's io arm).
- **Deviations** (user doc `clojure-java-io.md`): no identity hash in `#object`; a URL's
  `.hashCode`/`=` by spelling; reading a non-`file:` URL refused by name; no byte arrays
  (`read` into a buffer, `readAllBytes`, `write` of one refused); three charsets; a computed
  resource name never inside a jar; `lastModified` in whole seconds (`file-write-date`'s
  resolution, every backend); wasm: `getAbsolutePath` of a relative File refused (no
  cwd); `canRead` is `exists`; `line-seq` takes a File/URL/byte stream like a path.
- Pins: clojure-spec `clojure-java-io-*`, `a-java-io-file-prints-as-the-host-object-*`,
  `java-io-files-are-made-renamed-and-deleted`, `extend-takes-a-map-computed-at-run-time`
  (all four backends, oracle-identical but the hash/`class` case); `ClojureJavaIoTest`
  (directories, an empty directory deleted and a file dated on all four, a deps.edn project's directory and jar
  resources on all four backends, the non-file URL refusals);
  `ClojureInteropTest#aJavaIoFileCrossesTheJavaBoundaryAsTheHostFile`;
  `ClojureArmsTest#theIoFamilyIsMadeByClojureJavaIoAndFoldsTheArmsOfAProgramMakingNone`;
  `ClojureLoweringTest#javaIoIsABuiltInNamespaceLoadedAtItsFirstQualifiedName`;
  `ClojureWasmFileRefusalTest` (no preopen: the oracle's `FileNotFoundException`).

## Macros

- `defmacro` lowers to one expander lambda dispatching on the argument count (each arity's
  parameters with their destructuring prologue) plus a `c%name%macro` table global. The
  same lambda expands call sites datum-to-datum at lower time (through
  `eval/ClojureMacroTime`, one per file or session) and serves `macroexpand-1` at run
  time. Docstring and attr map skipped, `&` rest works, `&form`/`&env` refused. A call
  above the definition names the missing expander; a macro has no function value; a
  later `def`/`defn` wins the call sites back.
- **A body sees the program's top-level definitions above the call site** (the
  oracle's form-by-form load). `ClojureMacroLowering.handOver` gives the macro evaluator
  each top-level datum's lowered forms once the datum lowered (`topLevels`, and
  `loadFile` per datum of a required namespace, the init `def`s included): `defun` and
  `declaim` as they are, a `setq`/`defparameter` store as a LAZY root
  (`LispEvaluator.defineLazyGlobal`: its value runs only when an expansion reads the
  var, reading the root it supersedes for `(def x (inc x))`), a `defonce`'s store the
  same unless bound (`ClojureLowering.defonceStores` maps the guarded form to it),
  `progn` member by member, and every other form only from a datum headed
  `DEFINITION_HEADS` (`defn`, `defmethod`, `extend-protocol`, `defrecord`, ...). The
  evaluator queues them and runs them before its next evaluation, so a macro-free
  program builds nothing; one that fails is dropped (a later expansion reading it names
  what it misses). The caught classes' predicates go over as the lowering meets them
  (`handOverCaughtClasses`, also before a `defmacro` or an expansion evaluates). The
  evaluator holds every per-program runtime from its start
  (`ClojureLowering.macroTimeRuntimeForms`: STM, ex-info, protocol, hierarchy, macro;
  built-in class rows only, no host exceptions), since a helper may use one before the
  lowering met a use. Deviations: no other statement runs at lower time, and what a body
  changes (a `swap!` of a program atom) the program never sees -- the oracle's `@seen`
  after two counting expansions is 2, here 0; a `defmethod` a program macro expands to,
  or a definition inside a top-level `let`, is not replayed.
  `def` lazily, not eagerly (measured 2026-10-08 over the probe jars in `~/.m2`):
  macro bodies calling a same-file `defn` -- camel-snake-kebab `defconversion`, hiccup
  `defelem`/`build-string`/`html5`, malli `assert` and its instrument macros, data.json
  `codepoint-case`; one reading a `def` through its helpers -- hiccup's `html` compiles
  through `container-tag?` (the private `void-tags` set) and `util/*html-mode*`. No probe
  macro builds a value with an effect, but a `(defonce server (run-server ...))` above a
  macro call would start at every compile if a `def` ran eagerly.
  Pins: clojure-spec `a-macro-body-calls-the-programs-definitions` (all four backends,
  oracle-identical), `ClojureLoweringTest#aMacroBodyCallsAHelperDefinedAboveIt`,
  `#aDefValueRunsAtMacroTimeOnlyWhenAnExpansionReadsIt`,
  `#aRedefinitionReadsTheRootItSupersedesAtMacroTime`,
  `ClojureProjectNamespacesTest#aRequiredNamespacesMacrosCallItsFunctions` (all four),
  `ClojureSessionTest#aMacroOfALaterBufferCallsWhatAnEarlierOneDefined`.
- **A program macro wins over every lowering row of its name from its definition on;
  above it the core meaning holds**, like the oracle's form-by-form compile. `lowerInner`
  tries the macro before any row, except for `isReservedHead` (the oracle's special forms
  plus the heads the reader spells: `syntax-quote`/`unquote*`/`deref`, `ns`/`in-ns`; `fn` is not one since `#(...)`
  reads as `fn*`, and a `defn`'s recorded dispatch datum is spelled `fn*` so a `fn` macro
  never captures it),
  whose `defmacro` is refused. Above the definition `lookupVar` hides a `pendingCoreMacro`
  (a name in `ClojureCoreNames`, the oracle's 679 `clojure.core` publics). A definition
  head a macro above shadows pre-declares nothing.
- `clojure.core/name` (`ClojureCoreNames.coreSpelling`) lowers the core row with
  `coreOnly` (no macro, no program var, no filter); outside the list it is `No such var`.
  The lowering's own datum rewrites spell their heads this way, so a program macro never
  captures them.
- Syntax-quote: a symbol naming a var the namespace sees qualifies as `ns/name` (`user/`
  included; locals are no vars); a special form stays bare; every other symbol qualifies
  even unresolved -- a visible core name as `clojure.core/name`, else the defining
  namespace, an alias head with its namespace, a class head with its full name
  (`System/nanoTime` -> `java.lang.System/nanoTime`). `~` lowers as code, `~@` splices
  into lists, vectors, maps and sets; `x#` is one `(gensym "x")` per syntax-quote node per
  expansion (fresher than the oracle's per-compilation suffix). `quote` and syntax-quote
  build data symbols (`dataSym`), so `'*out*` is `*out*`, not the stream alias.
- **An argument datum travels quoted and its answer decodes back (`decodeDatum`)**, so
  every reader form must survive the round trip. A regex literal answers its
  `(:C%PATTERN ...)`, decoded to `(%regex source)` (a fresh pattern; the oracle's
  expansion holds the one object). `#()` needs no decoding since it READS as `fn*` over
  plain symbols; until 2026-10-04 it read as `(fn %anon ...)`, whose marker matched by
  identity and broke in any expansion (`(t (map #(inc %) xs))` and `` `#(inc %) ``).
  Pinned by clojure-spec `a-macro-argument-holding-a-regex-literal-expands`,
  `anon-fn-reads-as-fn-star-over-generated-parameters`,
  `a-macro-argument-holding-an-anonymous-fn-expands` (clj 1.12.6, oracle-identical but
  for the parameter numbers). Measured 2026-10-04 against the parent build: a `#()`
  program shrinks (fixed parameters instead of `&rest` + `nth`): `(map #(* % %) ...)` plus
  `(reduce #(+ %1 %2) ...)` wasm 45,805 -> 45,447 B, class 85,811 -> 85,675;
  `examples/clojure/demo.clj` wasm 91,456 -> 91,190, class 131,678 -> 131,498. A
  `read-string` program carries the run-time `#()` reader: wasm 126,952 -> 129,209,
  class 196,456 -> 201,150.
- `macroexpand-1`/`macroexpand` answer the mangled data itself, so `=` against a quoted form
  holds and the printer (demangling `c%`) spells the oracle's lowercase; a non-macro head
  answers the form. The head resolves through the call site's namespace (the lowering
  passes an alist of the spellings the table cannot spell itself). `gensym` is the
  ordinary uninterned symbol.

## The IFn dispatcher stays at the call site

**A spliced runtime worker funcalls its function argument; the lowering hands it a real
function.** `rontolisp::%clojure-call` (the IFn dispatcher: sets, maps, vectors, keywords, symbols,
vars) drags the structural-key runtime behind it, about 26 KB of raw wasm, so one worker
naming it put it in every program that used the verb. The workers: `map` `filter` `mapv`
`filterv` `mapcat` `iterate` `repeatedly` `keep` `keep-indexed` `map-indexed` `remove`
`reduce` `reduce-kv` `min-key`/`max-key` `juxt` `fnil` `every-pred` `some-fn`
`update-keys` `update-vals` `partition-by` `split-with` `vary-meta`, every transducer
constructor and consumer, and a regex `replace` with a function replacement.

- `ClojureBindingLowering.realFnValue` passes a function argument as itself when
  `holdsRealFun`: a form `ClojureLowerUtil.yieldsFun` (a `function`/`lambda`; a
  `let`/`let*`/`labels`/`flet`/`progn` ending in one -- `comp`, `partial`, `complement`,
  `memoize`, a named `fn`; a call to a worker in `FUNCTION_WORKERS` -- `juxt`, `fnil`,
  `every-pred`, `some-fn`, `completing`, the `%clojure-xf-*` constructors), or a variable
  bound to one (`isDirectVar`). Anything else goes through `ClojureLowering.realFun`:
  `(rontolisp::%clojure-as-fn x)`, which answers a function as itself and wraps any other
  value in a rest lambda over the dispatcher. A symbol reads like a keyword
  (`%clojure-call-keyword`: set member, map/sorted/record entry, else the default); a record
  is no IFn and signals, like the oracle (and `ifn?`). A worker added to `FUNCTION_WORKERS` must
  answer a `lambda` on every path.
- A verb's VALUE (`(apply map ...)`, the `-v` entries) wraps its parameter at run time
  through `%clojure-as-fn`, so using a verb as a value carries the dispatcher.
- The same test makes a call-site invocation direct: `callFun`/`applyFun` (`comp`,
  `partial`, `complement`, `memoize`, `trampoline`, `update`/`update-in`/`assoc-in`, a
  computed head like `((comp f g) x)`), CL `apply`, and a `let`/`def` binding marked
  direct. `update` lowers its function form in place (it has no effect to order against
  the map and key before it); `update-in` binds it and threads `holdsRealFun`.
- A regex `replace` with a replacement that is neither a literal string nor a real
  function form wraps it in `%clojure-re-replacement` (a string stays a string).
- The inline loops (`every?` `some` `take-while` `drop-while` `group-by` `sort` `sort-by`
  `merge-with`, and the closures `comp` `partial` `complement` `memoize` `trampoline`)
  take a `ClojureBindingLowering.FnArg` (the `fnValue` plus `real`), decided from the
  DATUM by `fnArg`/`holdsRealFun` and passed to `callFun(real, ...)`/`applyFun(real, ...)`.
  Never ask the scope from a generated symbol: the value forms (`everyValue`, ...) build
  `FnArg.of(parameter)`, which is real only for a `yieldsFun` form, because a value
  lambda's `c%NAME-fn` parameter can spell a user local's name. Raw wasm, before -> after:
  `(let [f odd?] (every? f v))` 61,251 -> 34,113; `(let [f inc] (sort-by f v))` 71,010
  -> 43,451; `(let [f inc] ((comp f dec) 1))` 60,886 -> 38,873. Pins: `ClojureLoweringTest`
  `inlineLoopsFuncallALocalBoundToARealFunction`, clojure-spec
  `locals-bound-to-functions-feed-the-inline-loops`.
- Kept on the dispatcher: `test` (the `:test` metadata value) and a `defn` parameter used
  as a function anywhere (`(defn f [g xs] (map g xs))` carries it).
- Measured 2026-10-03, raw wasm of a one-line program, default / `--optimize=size`, before
  -> after: `(map inc v)` 65,808 -> 43,452 / 51,352 -> 32,699; `(filter odd? v)` 65,593 ->
  38,555 / 51,339 -> 30,515 (`remove` was already 38,987); `mapv` 65,222 -> 42,880;
  `iterate` 64,137 -> 36,868; `reduce-kv` 64,263 -> 37,928; `update-vals` 67,192 ->
  38,412; `(transduce (map inc) + v)` 62,461 -> 34,498; `(map (comp inc dec) v)` 66,128 ->
  43,751; `(map (partial + 1) v)` 66,518 -> 44,189; `(filter (complement odd?) v)` 65,950
  -> 44,093; `((comp inc dec) 1)` 61,024 -> 38,873; a regex `replace` with a string
  117,361 -> 100,157; `(update m :a inc)` 62,265 -> 60,333 (it keeps the structural keys
  `get` needs). Interpreter, 100,000-element strict vector, steady state of three runs on
  a loaded host: `map inc` ~430 -> ~390 ms, `filter odd?` ~530 -> ~480 ms, `map` over a
  `defn` parameter holding `inc` unchanged within the noise (~450 ms: `%clojure-as-fn`
  answers the function itself).
- Pins: `ClojureLoweringTest.aRuntimeWorkerTakesARealFunctionWrappedAtTheCallSite`,
  `aProgramPassingRealFunctionsSplicesNoDispatcher` (the pruned IR); clojure-spec
  `a-collection-as-the-function-of-a-seq-worker-answers-like-ifn` (every backend).

## Dispatch

- `defmulti`: four globals (`equal` method table, default value, prefers table, `Object`
  slot) plus a rest-args dispatcher `defun`. A keyword dispatch function takes a lookup
  plus an optional default, so multi-argument calls dispatch; `:default` and `:hierarchy`
  options. `defmethod` stores a parameter lambda under the value `dispatchKeyForm` lowers:
  a class spelling to the keyword `class` answers for it, `nil` to the `(:C%NIL)` marker
  (the dispatcher maps a true nil there, so no table keys on nil and a literal `:nil` keeps
  its own row), `Object` to `:object` plus the slot, literal vectors element by element.
- `(methods mt)` (a `builtin` row, so a program var or local of that name shadows it) lowers to
  `%clojure-methods` over the `%methods` global: a copy (`%clojure-plist-table`, keys are
  representatives already) with the `(:C%NIL)` marker row re-keyed by nil, which is how a map
  keys nil. The `:object` and default rows stay. Takes a `defmulti` NAME like `get-method`
  (a local alias of a multimethod is refused: the table is reached through the var key, and
  nothing ties a function value to it); a name no `defmulti` made is `No such multimethod`.
  A host class row keeps the keyword `class` answers (the oracle: the `Class`), so
  `(get (methods f) (class x))` works while `(contains? (methods f) String)` is false.
  Programs that do not call it compile byte-identically (measured 2026-10-04: wasm P1,
  `--optimize=size`, component and JVM class of a Clojure demo and a multimethod program).
- `:import` / `import` read a `[pkg A B]` vector like the `(pkg A B)` list: the reader's
  `VECTOR` marker leads the items and is skipped (`importSpecs`, as `referNames` does), so a
  vector spelling no longer registers `<marker>.pkg` and `<marker>.A`.
- A `defmulti` of a var that holds a multimethod lowers to `nil`, like the oracle's (the
  corpus's second `(defmulti my-print class :default :everything-else)` keeps the first's
  methods and default): `ClojureLowering.multimethods`, by var key, survives buffers and
  is cleared by every other definition (`intern`), never by the pre-scan (`internName`).
  Static, so a `defmulti` run repeatedly inside a function still redefines.
- An exception or a runtime error answers its class as a keyword, through the arm ahead of the
  host one ("Catching").
- `class`'s last arm (no Clojure kind) is `%clojure-host-class` (`clojure.lisp`): `(java:call
  x "getClass")` under `handler-case` -- `java:call` refuses every non-host value on both
  paths, so its refusal IS the host test -- else `class needs a value of a known kind`.
  `ClojureLibrary.process` splices a refusal-only body when the program names no `java:`
  operator (`LispNames.JAVA_OPERATORS_QUALIFIED`, the list `JvmLispCompiler.
  programUsesAnyJavaOp` reads), since a `java:` reference changes the JVM output; the
  interpreter always has the host body. A host object then misses every class row and
  reaches `Object`, then `:default`. Measured 2026-10-03, shcloj4
  `examples.test.multimethods`: 2 errors before (`.toString 42`, `class` of a `File`),
  byte-identical to the oracle after on the interpreter and the JVM.
- The printer and `str` have host arms of the same kind (`HOST_ARMS_WITHOUT_JAVA` holds
  every stand-in, NIL where the arm answers "not mine"): `%clojure-write`'s fall-through
  asks `%clojure-host-class-name`, so a class object prints `java.lang.String` (`long` for
  `Long/TYPE`) under print and pr alike, like the oracle; `%clojure-str-of`'s non-readable
  fall-through asks `%clojure-host-string`, so `str` of a host object is its `toString`
  (`class java.lang.String`, a `File`'s path, `[1, 2]` for a host list) -- inside a
  collection the printer's spelling stands, as in the oracle. Both test
  `%clojure-lisp-value-p` first, so a number, keyword or vector in a `java:` program never
  pays the `getClass` refusal. The stand-ins' cost without `java:`, measured 2026-10-03 on
  `(println [1 2] (str 3 :k))`: wasm 36332 -> 36352 bytes, JVM `.class` 69529 -> 69757.
- A `class` call in the dispatch function answers nil for nil, so the marker is hit --
  bare, wrapped, through a named `defn`/`def`'d function (the `defmulti` re-lowers its
  recorded definition with the dispatch lowering) or nested inline (inlined at the call
  site; a name already being inlined keeps its direct call, so recursion terminates).
- Lookup: exact hit, else `C%H-DISPATCH` over the hierarchy (strictly most specific wins,
  `prefer-method` breaks ties, an unbroken tie is `Multiple methods ...`), then the
  `Object` slot (ahead of the default, like the oracle), then the default, else
  `No method in ...`.
- A hierarchy is a map of `:parents`/`:ancestors`/`:descendants` tables to wrapped sets;
  the global one is `C%H-GLOBAL`, rebound by two-argument `derive`/`underive`.
- `defprotocol`: an `equal`-table global plus one dispatcher `defun` per method over
  `C%PROTOCOL-TAG`; exact tag, then (a walking protocol, below) the walked classes, then the
  method's `Object` row, else a signal. The
  multimethod shape over runtimes all four backends already run, not a per-backend value
  model (`.todo/artefacts/b13-protocols/spike.md`).
- **A method declares one parameter vector per arity** (`(m [x] [x y] "doc")`;
  `ProtocolDef.arities` holds each method's counts, the target included, which an
  instance call `(.m r ...)` matches). The dispatcher stays `&rest` + `apply`, so the row
  holds ONE lambda: a single arity stores its own lambda exactly as before (a protocol
  program of single arities lowers byte-identically), several store `arityDispatch`'s
  `(let ((a1 L1) (a2 L2)) (lambda (&rest args) (cond ((= n 1) (apply a1 args)) ...)))`,
  each `Li` the lambda that arity alone would store (its own recur target, an inline body's
  field bindings from its own target), fixed counts first, a variadic one last, any other
  count the `ArityException` carrier. An inline body (`defrecord`/`deftype`/`reify`) names
  the method again per arity, each a count the protocol declares ("Can't define method
  not in interfaces", the oracle's compile error, where an undeclared count was accepted
  before 2026-10-08) and each once; an extension (`extend-protocol`/`extend-type`) spells
  `fn` clauses (`extensionMethod`: the `fn` refusals "Can't have 2 overloads with same
  arity", "Can't have more than 1 variadic overload") and a repeated method there keeps
  the later one, like the oracle's map of fns; `extend` takes a multi-arity `fn` as it
  is. The defprotocol refusals take the oracle's words ("Function m in protocol P was
  redefined...", "Definition of function m in protocol P must take at least one arg.").
  Deviation: a count an inline body leaves out signals `ArityException` where the oracle
  throws `AbstractMethodError`. Pin: clojure-spec
  `protocol-methods-of-several-arities-dispatch-by-count` (all four, oracle-identical),
  `ClojureLoweringTest#aProtocolMethodOfSeveralAritiesStoresOneLambdaApplyingTheArityOfTheCall`.
  `:extend-via-metadata true` adds an `%inline` table; the order is body implementation,
  then `(%clojure-meta-method target 'ns/method)`, then extension rows, then `Object`
  (measured on the oracle); the key is the namespace-qualified method symbol.
  `satisfies?` reads both tables, never metadata.
- `extend-protocol`/`extend-type`/`extend` add rows under the target's tag: the
  class-keyword kinds, `nil`, `Object`, known records and deftypes, the classes of the
  instants and the UUID ("Instants and UUIDs"), and any other class `instance?` resolves
  (a name no class has is its `unknown name: X`). The three answer nil like the oracle
  (2026-10-09, clj 1.12.6; `extensionRows` ends the rows in `nil`; they used to answer the
  last `setf`'s lambda, echoed `#<procedure>`); pinned by clojure-spec `extend-forms-answer-nil`
  and `ClojureSessionTest.theExtendFormsEchoNil`.
- **Walked classes** (oracle-checked clj 1.12.6, 2026-10-08). A target no value's tag names
  exactly -- a throwable (a condition's tag is the fresh miss list), an interface or
  abstract class over core kinds (`clojure.lang.IRef`/`IDeref`/`ARef`/`IExceptionInfo`), a
  stream or host class, and `java.util.Date` (a Timestamp's tag is its own;
  `ClojureProtocolLowering.walkTargetOf`) -- stores its row under its binary name's keyword
  (`extendKeyForm`) and joins `ctx.walkTests`: its `instance?` test
  (`ClojureDispatchLowering.instanceTest`, `instance?`'s arms as a raw test over `x`). A
  protocol extended to one WALKS: its dispatchers, past the exact row (and an
  `:extend-via-metadata` protocol's inline and metadata lookups), ask `C%PROTOCOL-SUPER x
  table method miss` before the `Object` row, and `satisfies?` asks it with a nil method.
  The protocol runtime's walk (`walkRuntime`, emitted with the tag reader where any protocol
  walks) tests the walked classes in the oracle's `find-protocol-impl` order (`walkOrder`):
  the classes first, then the interfaces, each ahead of its supertypes
  (`ClojureValueClasses.isSubtype`: the class rows or reflection; two `clojure.lang` names,
  not on this class path, by kinds -- `IRef`'s values a proper subset of `IDeref`'s;
  `isInterface` tells a `clojure.lang` interface by its name), the first with a row of the
  method winning. So an `ex-info` takes `Exception` over `IExceptionInfo` (the superclass
  chain first), a volatile `IDeref`, a Timestamp `Date`'s row. `extends?` reads the exact
  key only, like the oracle. Which protocols walk is decided per protocol before its
  dispatchers lower: an extension to a walked class of a protocol whose dispatchers lowered
  plain records it in `walkMisses`, and `ClojureLowering.lower` starts over with it in
  `walkingProtocols` (the `with-redefs` restart), so a program extending no protocol to one
  lowers byte-identically; a session's dispatchers all walk and a buffer adding a walked
  class re-emits the runtime (`walkTestsEmitted`). Pins: clojure-spec
  `a-protocol-extended-to-throwables-and-refs-walks-the-oracles-classes` (all four,
  oracle-identical), `ClojureLoweringTest#onlyAProtocolExtendedToAWalkedClassWalksPastAnExactMiss`,
  `#theWalkTriesSuperclassesThenInterfacesEachAheadOfItsSupertypes`,
  `ClojureSessionTest#aLaterBufferExtendingAProtocolToAWalkedClassReachesItsValues`.
- `defrecord`/`deftype`: positional (and, for records, map) constructors as `defun`s plus
  one row per inline method. `class` in the record is the printed host class name
  (`#my_app.core.R{...}`: `-` munged to `_`); every rewrap carries it. `(T. ...)`/`(new T
  ...)` call `->T`. Inline bodies see fields as locals; a parameter shadows its field.
  `assoc` keeps the type; `dissoc` keeps it while every declared field remains;
  `merge`/`merge-with`/`conj`/`into` keep it when the first argument is a record;
  `select-keys`/`into {}` answer plain maps.
- Record literals `#ns.Name{...}`/`#ns.Name[...]` build the record over the QUOTED body
  (the oracle never evaluates it); the class must match a defined record; a deftype
  literal is refused; an undotted `#P{}` is `No reader function for tag P` (`#inst`/`#uuid`
  read their values, "Instants and UUIDs").
- `reify`: a fresh tag per evaluation with a row per method; `=` is identity (unless the body
  overrides `equals`, "Host interfaces").
- `instance?` (oracle-checked clj 1.12.6, 2026-10-04; it has no value): a record/deftype name
  tests the tag, `Object` non-nil, a throwable class its chain ("Catching"). Any other class
  is a disjunction over the bound value (`ClojureDispatchLowering.instanceOf`): one test per
  kind whose oracle class is or implements it (`ClojureValueClasses.Kind`: per kind the
  class and its `supers`, tables read off the oracle -- `clojure.lang` is not on this class
  path -- one row per representation, so a strict seq is a `PersistentList` and a `[k v]`
  a `MapEntry`; all three number kinds fold to `numberp`), a stream arm over
  `%clojure-stream-class` (`ClojureValueClasses.STREAM_CLASSES` mirrors it), a
  `%clojure-instance-of` per highest tabled throwable implementing an interface
  (`Serializable` -> `Throwable`), and `%clojure-host-object-p` when the host loads the class
  and no host value of it is converted at the `java:` boundary (`String`, the boxes,
  `BigInteger`). The host arm is the test of `ClojureArms.Family.HOST` (producers: the
  `java:` operators; `=`'s `%clojure-host-equal-p` is its other test), so a program naming none sheds it; `Number`/`CharSequence` lower to
  `%clojure-host-number-p`/`-char-sequence-p`, the family's aliases to `NUMBERP`/`STRINGP`,
  so such a program compiles them as before. The value is bound to a temp unless it is a
  variable, or every arm reads it once or it is a constant no family arm reads (a family
  test needs a variable or literal argument; `(instance? Boolean (f))` evaluated `(f)`
  twice before). A bare `clojure.lang` simple name (`Keyword`, `IPersistentMap`) resolves
  like the dispatch keywords; a loadable class no kind or host object can be (`Integer`) is
  `(progn x false)`; anything else is `unknown name: X` (`clojure.lang.PersistentQueue`
  too, where the oracle answers false). Measured against the oracle over 59 classes x 29
  values on all four backends and 59 x 5 host objects on the interpreter and the JVM:
  identical but the strict `(map inc [1])` (`IPersistentList`, `Counted`, not `LazySeq`)
  and `[1 2]` (`Map$Entry`). Size (wasm P1 / `--optimize=size` / component / JVM class,
  before -> after): `(instance? Number x)` in a `defn`, an exception, a record and
  `examples/clojure/demo.clj` byte-identical; the core classes over impure arguments
  65,667 / 55,403 / 66,924 / 76,656 -> 65,593 / 55,355 / 66,848 / 76,522 (`Boolean`'s
  argument bound once); `(instance? Number (java.math.BigDecimal. "1"))` JVM 90,005 ->
  94,328 (the host arm; wasm refuses `java:` either way); `(instance? java.util.List x)` in
  a `defn`, no `java:`, 17,742 / 14,184 / 18,934 / 60,317 (before: refused). Pins:
  clojure-spec `instance-of-an-interface-or-a-host-class-tests-the-classes-of-each-kind`,
  `ClojureInteropTest#instanceOfAHostClassTestsTheValuesKindAndTheHostObjectsClass`,
  `ClojureArmsTest#theHostFamilyFoldsInstanceOfAHostClassInAProgramNamingNoJavaOperator`.
  A protocol's interface (oracle-checked clj 1.12.6, 2026-10-04) is the class
  `ClojureProtocolLowering.interfaceName` spells (namespace and name through the oracle's
  `munge`/`CHAR_MAP`: `auto.ipr_dash_QMARK_`; an import resolves to it), matched before the
  core-class tables: `%clojure-implements-p` (`clojure.lisp`) answers a record/deftype whose
  class is among those whose body names the protocol (`TypeDef.protocols`, the pre-scan's
  `BodyProtocols`, methods or none; quoted at lowering, so a type a later REPL input defines
  is not seen) and a reify with a row under its fresh tag in the protocol's body table
  (`inlineTable`). An `extend-type`/`extend-protocol` target is not an instance (its rows
  share the tag with a record's body rows, hence the class list). A body naming a protocol
  with no method stores an empty row (`emptyRowForm`), so `(deftype T [] P)`/`(reify P)` also
  satisfy and extend it like the oracle (before: false). The bare protocol name (`P`, a var)
  stays `unknown name` (oracle: `ClassCastException`). Size (wasm P1 / `--optimize=size` /
  component / JVM class): a protocol program without it and `examples/clojure/demo.clj`
  byte-identical; two `(instance? user.P x)` sites +694 / +622 / +701 / +1015 against one
  `(instance? R x)`; an empty body group +498 / +498 / +502 / +118. Pin: clojure-spec
  `instance-of-a-protocol-interface-tests-the-body-implementations`.
- **Class chains** (oracle-checked clj 1.12.6, 2026-10-04). A dispatch value
  (`dispatchClassKey`) and an argument of `isa?`/`derive`/`underive`/`parents`/`ancestors`/
  `descendants` (`hierarchyArg`: a class spelling no local or var shadows) lower a class
  spelling through `classKey` to the keyword `class` answers for its values: a core class its
  kind, a record its tag, a throwable, a stream class, an interface among their supers or
  `Object` (`chainedClassKey`, `ClojureClassBases.isChained`) its own name. The oracle's
  `isa?` follows Java inheritance (`isAssignableFrom`, then each of `supers` through the
  hierarchy), `parents` adds `bases`, `ancestors` adds `supers` and their hierarchy
  ancestors, `descendants` of a class refuses (`UnsupportedOperationException`), and all
  three answer nil for nothing (`not-empty`). Here `C%H-SUPERS` holds class ROWS
  `(name base ...)`, the oracle's bases (superclass, then interfaces) from
  `ClojureClassBases` (tables pinned to reflection by `ClojureClassBasesTest`, reflection for
  any other throwable); `C%H-ISA?`'s last arm and the readers ask `clojure.lisp`'s "Class
  chains" (`%clojure-class-isa`/`-parents`/`-ancestors`/`-rows`). Every class row `isa?`
  `Object` (an interface too, like `isAssignableFrom`), while an interface's supers stop
  short of it. A core kind, a record, a deftype or `reify` is a row `(name . t)`: its host
  classes are no one value here (`:number` is `Long`, `Double`, `Ratio`...), so its supers
  are `Object` alone and `parents` adds nothing -- the documented deviation.
  Rows: `ClojureLowering.recordClass` records a class and its supers' rows (constructions,
  catches, `instance?`, spellings); a spelled class also records each class a value may have
  unnamed (`ClojureClassBases.implicitClasses`: the runtime errors', the streams') whose
  supers hold it; `Object` spelled or a reader lowered (`allClassRows`) records all of them
  plus the kinds and the program's types. Everything rides on `usedClassChains`: a hierarchy
  runtime of a program spelling no class in those positions reads only the hierarchy (a
  session redefines `C%H-ISA?` and the readers and sets the rows ahead of the first buffer
  spelling one, then `append`s later buffers' rows, `ClojureSessionTest#aBufferSpelling*`,
  `#aSessionReadsAClassKeywordsSupersInALaterBuffer`). A class keyword with no row asks the
  host arm `%clojure-host-class-rows` (stand-in NIL without `java:`): a host Throwable class
  reflects its rows, so the class of a host throwable no construction names (one a host
  method returned, then thrown) walks too on the interpreter and the JVM
  (`ClojureInteropTest#theClassOfAHostThrowableNoConstructionNamesTakesItsSupersFromTheHost`);
  it reflects on every miss, uncached -- only a dotted name of a `java:` program pays it.
  `descendants` of a class keyword is a `C%E-NEW` exception, so `readsDescendants` with
  chains turns on the exception runtime; the walk carries the refusal arm only where
  `descendants` is read (or in a session), since 2026-10-04 -- before, every chain program
  reading none compiled `C%E-NEW` as an undefined call, with a warning.
  Gap: `(ancestors (class x))` in a program spelling no class and naming no `java:`
  operator answers only the hierarchy.
- **Host classes** (oracle-checked clj 1.12.6, 2026-10-04). `class` of a host non-throwable
  (interpreter, JVM) is its host class OBJECT. A program using a hierarchy and naming a
  `java:` operator (`ClojureHierarchyLowering.namesHost`, the library's host-arm criterion;
  a session cumulatively, `ClojureLowering.noteHost`) sets `hostClassWalk`, which implies
  `usedClassChains` and swaps the walk's arms for `clojure.lisp`'s "Host classes"
  (`%clojure-host-class-isa`/`-parents`/`-ancestors`, `%clojure-host-names-class-p`) plus
  `(setq C%H-KINDS '((simple-name . kind) ...))` from `DISPATCH_CLASS_KEYWORDS`. A class
  object stands under three keys: itself, `(:c%keyword name)`, and the kind keyword of its
  simple name (`java.util.List` spells `:list` in a hierarchy position, so `ArrayList`
  reaches it -- and `clojure.lang.IPersistentList`, also `:list`: the documented
  deviation). `isa?` of a class object: `Object` unless primitive, else any key of it or of
  one of its supers (`getSuperclass`/`getInterfaces`, recursively) equal to the parent or
  holding it among its hierarchy ancestors; a class object PARENT of any other child is
  asked through its keywords. `parents`/`ancestors` answer class objects (the oracle's
  printing) plus what any key derives from; `descendants` of one refuses. Non-chained
  spellings (`java.util.AbstractList`) keep lowering to `Class.forName`, a class object --
  a dispatch value too since 2026-10-04 (`dispatchClassKey` answers null for them; before,
  `defmethod needs a core class`): the exact lookup hits the class object (`equal` on two
  class objects is identity on the interpreter and the JVM), the miss search walks its
  supers, so `AbstractList` beats `java.util.List` (`:list`) for an `ArrayList`. The
  `defmethod` names `java:static`, so its program is a `java:` program (wasm: the
  call-time `JAVA:STATIC` error where lowering refused). An unloadable capitalized name is
  `unknown name: X` (the oracle's `Unable to resolve symbol`). Rejected: storing under the
  class keyword (`:java.io.File`, `java:`-free) -- no row relates two such keywords, so
  `AbstractList` and `AbstractCollection` methods tie (`Multiple methods`) where the oracle
  picks the subclass. (`java.io.File` itself is a chained class since 2026-10-08, its
  keyword what `class` answers for a clojure.java.io File: "clojure.java.io".) Size, measured 2026-10-04 (wasm P1 / `--optimize=size` / component
  / JVM class): the `Exception`, `java.io.Writer` and keyword multimethods and
  `examples/clojure/demo.clj` byte-identical (187,603 / 152,656 / 191,427 / 158,997;
  89,846 / 75,050 / 91,238 / 99,415; 94,778 / 77,919 / 96,124 / 103,647; 91,151 / 78,254
  / 92,481 / 119,448); `(defmethod f java.io.File ...)` + `java.util.AbstractList` over
  host objects 13,247 / 13,177 / 14,663 / 137,186 (before: refused). Pin:
  `ClojureInteropTest#aMultimethodDispatchesOnAHostClassOfNoKind`.
  Rejected: lowering every class spelling to a keyword and a class object to its keyword --
  `parents` would print `:java.util.AbstractList` and `(= (first (parents c))
  java.util.AbstractList)` would be false, where class objects match the oracle.
  Size, measured 2026-10-04 (wasm P1 / `--optimize=size` / component / JVM class, before
  -> after): no-`java:` programs -- `(defmethod f Exception ...)` over a caught error
  187,552 / 152,605 / 191,376 / 158,802 -> 187,548 / 152,601 / 191,371 / 158,802;
  `(defmethod f java.io.Writer ...)` 89,146 / 74,378 / 90,534 / 98,795 -> 89,149 / 74,381 /
  90,534 / 98,795 (the dropped refusal leaves a hole in the string pool -- `#C(` -- that
  splits a data segment: +3 B of segment header); `(parents java.io.IOException)` with an
  ex-info `ancestors` 138,568 -> 138,564 (class 150,512 both); a keyword
  `derive`/`isa?`/readers multimethod, `(isa? (class '(1)) java.util.List)`,
  `(descendants Exception)` and `examples/clojure/demo.clj` byte-identical. `java:`
  programs: `(isa? (class (java.util.ArrayList.)) java.util.List)` + its `parents`
  13,064 / 12,994 / 14,478 / 118,831 -> 14,030 / 13,960 / 15,445 / 123,386 (wasm refuses
  `java:` either way); a keyword `derive` + interop 74,762 / 63,371 / 76,351 / 113,480 ->
  77,540 / 65,765 / 79,096 / 126,013.
  Rejected: registering the chain at run time where `class` reads a condition -- every
  program calling `class` on one would carry the table and the write, where the rows are
  known at lowering time for every class a value can have but a host-returned throwable's.
- `.getClass` of a receiver of unknown class (or a plain-throwable / stream class) is
  `getClassForm`: an EXCEPTION arm answering `%clojure-exception-class` and a STREAM arm
  answering the stream's class keyword ahead of the host call, so both shed where no
  condition or stream can reach it (before: a `java:call` refusal on the interpreter and
  the JVM, a call-time error on wasm).
- Size, measured 2026-10-04 (wasm P1 / `--optimize=size` / component / JVM class): a
  `class` multimethod with a `String` method, a `.getClass` of a parameter, a catch reading
  `class`, a `class` multimethod over a caught error, `examples/clojure/demo.clj`:
  byte-identical. `(defmethod f Exception ...)` over a caught error against the same program
  spelling `:java.lang.Exception` (exact hit only) 184,464 / 149,718 / 188,247 / 156,625 ->
  185,329 / 150,413 / 189,138 / 157,603; `(defmethod f java.io.Writer ...)` over `*out*`
  against `:java.io.Writer` 88,031 / 73,414 / 89,378 / 96,502 -> 88,747 / 74,019 / 90,122 /
  97,655; `(.getClass e)` of a caught error 63,530 (a refusal) -> 63,547.
- Size after the class rows, measured 2026-10-04 (same four outputs, against the tree before
  them): the `String` method, `.getClass` of a parameter, a catch reading `class`, a `class`
  multimethod over a caught error, a keyword `derive`/`isa?` multimethod and
  `examples/clojure/demo.clj` byte-identical; `(defmethod f Exception ...)` 186,955 /
  152,087 / 190,768 / 157,618 -> 187,548 / 152,601 / 191,372 / 158,796; `(defmethod f
  java.io.Writer ...)` 88,751 / 74,023 / 90,126 / 97,656 -> 89,142 / 74,374 / 90,530 /
  98,789. A keyword `parents`/`ancestors`/`descendants` program pays the `not-empty` fix:
  49,148 / 39,227 / 50,375 / 84,440 -> 49,274 / 39,320 / 50,501 / 84,712. A first draft
  with `assoc`/`member` `:test #'equal` and `nreverse` in the library cost the stream
  program 5,743 bytes of wasm; the hand-written walks cost 391.
- Pins: clojure-spec `a-multimethod-dispatches-on-an-exception-class-by-inheritance`,
  `a-multimethod-dispatches-on-a-stream-class-by-inheritance`,
  `parents-and-ancestors-of-a-class-add-its-java-supers`;
  `ClojureInteropTest#aHostClassObjectWalksJavaInheritanceInAHierarchy`,
  `ClojureSessionTest#aSessionWalksAHostClassObjectOnceABufferNamesTheHost`,
  `ClojureLoweringTest#theClassWalkTakesTheDescendantsRefusalAndTheHostOnlyWhereTheProgramNeedsThem`.

## Host interfaces

**A `reify`, `deftype` or `defrecord` body implements the `clojure.lang` interfaces whose
methods a core verb reads, each group behind an arm family that only the store of its row
makes, so a program naming none compiles as before** (`ClojureInterfaces`; every verb below
measured on clj 1.12.6, 2026-10-08).
- Which (measured 2026-10-08 over 85 libraries -- the eight `e43` probes, instaparse,
  data.priority-map, next.jdbc and 74 widely used jars -- counting the libraries whose bodies
  name each): `Object` 21, `IFn` 14, `ILookup` 13, `IObj` 12, `IDeref` 11, `Counted` 11,
  `Seqable` 10, `Indexed` 10, then the collection interfaces (`IPersistentCollection`,
  `IHashEq`, `Associative` 9 each ...: "Collection interfaces" below) and `IReduceInit` 5
  (next.jdbc's `plan`, `clojure.core/iteration`, below). Supported: `IReduceInit`, `IReduce`,
  `IKVReduce`, `Seqable`, `Counted`, `Indexed`, `ILookup`, `IFn` with its supers `Callable` and
  `Runnable`, `IDeref`, `IMeta`, `IObj`, `Object`'s `toString`/`equals`/`hashCode`, and the
  collection interfaces. Any other interface of the jar (`CLOJURE_LANG`, its public list:
  `IChunkedSeq`, `IRef` ...) or loadable host interface (`java.util.Deque`) is refused by name
  (`X is not supported yet as an interface of reify`), a class the oracle's `only interfaces
  are supported, had: C`, an undotted unknown name its `Unable to resolve symbol` (`clojure.lang`
  is no default import), a dotted one `Unable to resolve classname`.
- Parse (`ClojureProtocolLowering.typeBody`): the group heads first, then each method matched
  by name and count against its group's protocol, the interfaces with their supers
  (`ClojureInterfaces.closure`: `Indexed` a `Counted`, `IObj` an `IMeta`, `IReduce` an
  `IReduceInit`) and `Object`'s three, then the body's other protocols -- the oracle's class
  matches across every interface, so a `toString` under a protocol group overrides `Object`'s
  (before: `Can't define method not in interfaces`). An interface method takes fixed
  parameters (the oracle reads `&` as a parameter's name; refused here) and each count once.
  An interface the form's class implements itself stores no row (`RECORD_PROVIDED`: a
  record's map interfaces and their supers; `REIFY_PROVIDED`: `IMeta`/`IObj`). Naming one of
  the class's direct ones is the oracle's `Duplicate interface name` (`RECORD_DIRECT`:
  `ILookup`, `IObj`, `IPersistentMap`, `IHashEq`, `java.util.Map`, `java.io.Serializable`;
  a reify's `IObj`). A method the class generates (`RECORD_GENERATED`, the oracle's
  `getDeclaredMethods` of a plain record, 2026-10-09: `count`, `seq`, `valAt`, `assoc`,
  `iterator`, the `java.util.Map` methods, `equals`, `hashCode` ...; a reify's `meta` and
  `withMeta`) is its `Duplicate method name`, under any group, since the oracle matches a
  method against the class's own interfaces too; one of those interfaces the class leaves
  to the interface (`assocEx`, `Iterable.forEach`, a `java.util.Map` default), which the
  oracle's class would override, is refused by name (`I/m is not supported yet as a method of
  defrecord`: the record's own verbs answer those interfaces, so no row would hold it).
  `extend-type` keeps protocols only (`implGroups`).
- Rows (`ClojureInterfaces.rowForms`): one store per family under the type's tag,
  `(%clojure-<family>-row tag '("clojure.lang.X" ...) (list "method" lambda ...))`, into the
  library's `%clojure-interface-rows` (tag -> an `equal` table: interface name -> T, method name
  -> lambda; `%clojure-interface-entry` reads it). A method an interface declares at several
  counts (`valAt`, `nth`, `invoke`, `reduce`) is one lambda dispatching on the call's count, the
  fallback the oracle's `AbstractMethodError` (a refusal carrier added for it); a declared method
  the body leaves out stores a lambda refusing the same way. A deftype's or record's methods see
  its fields like inline protocol methods (`inFieldScope`) and share their `recur` rule. A
  reify's `Object` row names its printed class, `<ns>$reify`.
- Families (`ClojureArms.Family`):
  - REDUCE_INTERFACE (`IReduceInit`, `IReduce`, `IKVReduce`): its store makes REDUCIBLE too,
    whose `%clojure-coll-reducible-p`, `-coll-reduce-2/3`, `-kv-reducible-p` and `-kv-reduce-3`
    hold its arms. `reduce` with an init takes `IReduceInit` ahead of a `CollReduce` row; without
    one `IReduce`, else the `CollReduce` row, else the oracle's `ClassCastException`;
    `kv-reduce` takes a type's `IKVReduce` protocol row ahead of the interface. Its view
    `%clojure-reduce-init-items` is what `vec` and `set` walk.
  - SEQABLE: `%clojure-strict-seq`'s clause (an answer that is no seq is the oracle's
    `ClassCastException`), `seqable?`, and the alias `%clojure-lazy-input-p` -> `%clojure-lazy-p`
    at the lazy-or-strict verbs' choice, so `map`, `filter`, `for`, `concat` ... over an endless
    Seqable stay lazy. Their lazy arms step the input through `%clojure-seq` alone and never
    hold it as a seq's tail, which is why `%clojure-cons` and `%clojure-sequence` keep
    `%clojure-lazy-p`: a typed value as a cdr would be walked as list structure.
  - COUNTED: `countForm`'s and `emptyForm`'s clauses (the oracle's `empty?` asks `counted?`
    first), `counted?`.
  - INDEXED: `%clojure-nth`'s clause at three arguments (destructuring's too, the oracle's
    `(nth v i nil)`), the alias `%clojure-nth-2` -> `%clojure-nth` for `nth` of two, the alias
    `%clojure-is-indexed` -> `%clojure-is-vector` for `indexed?` (an instance call's vector arms
    keep `%clojure-is-vector`).
  - LOOKUP: `getBranches`' clause, whose new `supplied` argument (a constant, or a form over a
    value's arguments) picks `valAt` at two or three like the oracle's `get`; the dispatcher's
    keyword and symbol clauses (the count they have) and `%clojure-call-keyword` (a non-nil
    default as given).
  - INVOKABLE: `%clojure-call`'s clause (so every function argument reaches it through
    `%clojure-as-fn`), `ifn?`, and the view `%clojure-applied-fn` around `apply`'s function,
    which calls `applyTo`. `clojure.lang.AFn/applyToHelper` (the usual `applyTo` body) lowers
    to the dispatcher over the argument seq, and `.applyTo` of any IFn value to `apply`.
  - DEREFABLE: `%clojure-deref-other`. META_INTERFACE: `%clojure-meta`, `%clojure-with-meta`.
  - OBJECT_METHODS: `%clojure-str-of`, `%clojure-write` (`#object[class "text"]`, after the
    record clause, so a record still prints its literal), `%clojure-equal` ahead of the
    identity clause (the value itself is equal, a collection on the right is not -- the
    oracle asks the collection --, else `equals` by truth).
  `instance?` of a supported interface adds its test (never over a literal or quoted value,
  which no typed value is, so no binding changes), and an instance call of a declared method a
  clause calling the row's method (`ClojureInterfaces.instanceTest`; an `if` around the refusal
  where no row maps the method).
- `iteration` (`ClojureCoreLowering`, one call to `%clojure-iteration` over the step as a real
  function and the options as a run-time list; `-v` as a value): the oracle's reify in
  `clojure.lisp`, a fresh `:C%REIFY` tag whose `Seqable` and `IReduceInit` rows the worker
  stores through the families' stores, so `%clojure-iteration`/`-v` are producers of SEQABLE,
  REDUCE_INTERFACE and REDUCIBLE (`ClojureInterfaces.ITERATION`). The options are the map
  pattern's read of the rest (`%clojure-destructure-map`, "The lowering table",
  destructuring), read with `%clojure-call-keyword`; a given
  option, nil too, is called through `%clojure-as-fn`. `seq` steps from `initk` on every call;
  each element's `somef`/`vf`/`kf` run when it is built, the next `step` when the lazy rest is
  realized; `reduce` stops at `reduced` before `kf`. Pin: clojure-spec
  `iteration-seqs-lazily-and-reduces-through-its-step` (the oracle's, clj 1.12.6, 2026-10-08,
  all four backends), `ClojureLibraryTest#aProgramStoringNoInterfaceRowSplicesTheVerbsWithoutTheirInterfaceArms`.
- Deviations (user doc): the `#object` has no identity hash and a reify's class no number;
  `equals`/`hashCode` key no map or set (the tables hold such a value by identity); `sort` and
  `distinct` take a type implementing `Seqable` alone, where the oracle's `to-array` and
  destructuring `nth` refuse it.
- Pins: clojure-spec `a-type-implementing-ireduceinit-ireduce-or-ikvreduce-reduces-through-it`,
  `a-seqable-type-seqs-through-its-seq`,
  `a-counted-or-indexed-type-counts-and-indexes-through-its-methods`,
  `an-ilookup-type-reads-through-the-valat-of-the-calls-count`,
  `an-ifn-type-is-called-through-its-invoke-and-applied-through-its-apply-to`,
  `an-ideref-type-derefs-through-its-deref`,
  `a-type-overriding-object-answers-str-and-equals-through-it`,
  `a-deftype-implementing-iobj-carries-its-own-metadata` (all four backends, the oracle's but
  the `#object` line); `ClojureLoweringTest#aBodyImplementingAnInterfaceStoresItsRowThroughTheFamilyOfEach`
  (the stores and every refusal), `ClojureArmsTest#anInterfaceFamilyIsMadeByTheStoreOfARowOfItsInterfaces`,
  `ClojureLibraryTest#aProgramStoringNoInterfaceRowSplicesTheVerbsWithoutTheirInterfaceArms`.

## Collection interfaces

**A body implementing a collection interface is a collection to the core verbs, each group of
interfaces behind an arm family its row's store makes (`ClojureArms`, `COLLECTION` through
`MARKER`), so a program storing no such row compiles as before** (`ClojureInterfaces`'
`collectionTable`/`javaTable`, methods and supers read off the oracle's jar; `clojure.lisp`
";;;; Collection interfaces"; every verb measured on clj 1.12.6, 2026-10-08).
- What a verb asks, in the oracle's order (RT, core): `conj`/`into`/`merge` an
  `IPersistentCollection`'s `cons`; `assoc` (`update`, `assoc-in`) an `Associative`'s `assoc`;
  `dissoc` an `IPersistentMap`'s `without`; `disj` an `IPersistentSet`'s `disjoin`;
  `contains?` an `Associative`'s `containsKey`, then an `IPersistentSet`'s `contains`, a
  `Map`'s `containsKey`, a `Set`'s `contains`; `get` an `ILookup`'s `valAt`, then a `Map`'s
  `get`, an `IPersistentSet`'s `get`; `find` an `Associative`'s `entryAt`, then a `Map`'s;
  `count` a `Counted`'s `count`, then an `IPersistentCollection`'s seq walked, a
  `Collection`'s or `Map`'s `size`; `seq` a `Seqable`'s `seq`, then an `Iterable`'s
  `iterator`, a `Map`'s `entrySet`; `=` an `IPersistentCollection`'s `equiv` on either side (a
  core collection on the left reads a vector type by `count`/`nth`, a sequential by its seq, a
  map type as a `java.util.Map` only with `MapEquivalence`, a set type as a `java.util.Set`);
  `reduce` an `IReduceInit`, then an `Iterable`'s iterator (`iter-reduce`, stopping at
  `reduced`), else the seq; `peek`/`pop` an `IPersistentStack`; `rseq` a `Reversible`;
  `realized?` an `IPending`; `subseq`/`rsubseq` a `Sorted`'s `seqFrom`/`seq`/`comparator`/
  `entryKey`; `compare`, `sort` and a sorted collection's default order a `Comparable`'s
  `compareTo`; `empty` its `empty`; `keys`/`vals`/`reduce-kv`/`select-keys` a map type's
  entries; the printer by kind (`%clojure-typed-print-kind`: map, set, vector, seq; a
  `java.util` one under `pr`, like the oracle's `print-method`).
- Families, each stored by `%clojure-<family>-row`: `COLLECTION`, `ASSOCIATIVE`,
  `PERSISTENT_MAP` (`MapEquivalence` too), `PERSISTENT_SET`, `STACK`, `PERSISTENT_VECTOR`,
  `ISEQ`, `SEQUENTIAL` (`IPersistentList` too), `REVERSIBLE`, `PENDING`, `SORTED_INTERFACE`,
  `COMPARABLE`, `ITERABLE` (a producer of `REDUCIBLE` and `SEQABLE` too, whose arms it rides),
  `ITERATOR`, `JAVA_COLLECTION` (`Collection`, `SequencedCollection`, `List`, `Set`,
  `RandomAccess`), `JAVA_MAP` (a `SEQABLE` producer too), `MARKER` (`IHashEq`, `Serializable`,
  `IEditableCollection`, the transients: `instance?` and instance calls only). The predicates'
  helpers (`coll?`, `map?`, `set?`, `seq?`, `list?`, `sequential?`, `associative?`,
  `reversible?`) are `%clojure-is-*-type` aliases of the old ones, so the families stand ahead
  of `SORTED`, whose aliases they rename into (the strip goes in enum order). A strip fold
  position is a `cond` clause test, an `if` test or an `or` disjunct, never inside an `and`
  (`%clojure-counts-agree` nests `if`s for it).
- Iterators: a Lisp seq iterator is `(:C%ITERATOR #(seq))` (`%clojure-seq-iterator`), what
  `(.iterator coll)` of a core collection, `clojure.lang.SeqIterator.` and `RT/iter` answer;
  `%clojure-iter-has-next`/`-next` step it, a typed `Iterator` row, or a host iterator
  (`java:call`, a `HOST` arm). `iterator-seq` realizes one member at a time (the oracle 32).
  `clojure.lang.MapEntry.` and `MapEntry/create` build a `[k v]` vector, the map entry here.
- `java.util` default methods (`getOrDefault`, `forEach`, `stream` ...) are declared
  (`HostInterface.defaults`) but stored only when the body defines them; an instance call of a
  method only defaults declare goes through `%clojure-default-method`, refusing by name when
  the row holds none (`the default method m is not supported yet`: its body is Java).
- `extend-protocol`/`extend-type`/`extend` to an interface (`ClojureProtocolLowering`):
  - spelled like the one core kind whose values implement it (`IPersistentVector` :vector,
    `IPersistentMap` :map): the kind key stays, and the same lambda also stands under the
    binary name (`interfaceWalkKey`, `storedTwice`), which a dispatcher that does not walk asks
    `C%PROTOCOL-SUPER` for behind a guard: the interface's family test (folded with no row of
    the family) and, for an interface every record implements, `%clojure-record-p` once the
    program defines a record (`recordGuards`, settled after the last file by
    `noteRecordGuards`); a guard the dispatchers lacked restarts the lowering (`guardMisses`).
  - spelled like a kind whose interface other kinds' values implement too (`Sequential`,
    `java.util.List`, `java.util.Collection`, `IPersistentCollection`, `IFn`;
    `reachesOtherKinds` over `ClojureValueClasses.kindsOf`/`dispatchKeyword`): a walked class
    keyed by its binary name alone, so the walk picks the protocol's most specific interface,
    like the oracle's `pref` (`ISeq` ahead of `IPersistentCollection` for a list), where a kind
    key would answer first. Measured 2026-10-09: before, a vector missed `Sequential`,
    `java.util.List` and `Collection`, a keyword `IFn`, a record `IPersistentMap`,
    `java.util.Map` and `IPersistentCollection` (all fell to `Object`), and a list took
    `IPersistentCollection` ahead of `ISeq`.
  - any other interface: a walked class as before (`Counted`, `IDeref`).
- Deviations (user doc: reify, "Collection interfaces"): `first`/`next`/`rest` of an `ISeq`
  type read its `seq` (an `ISeq` answer the seq view walks through `first`/`next`,
  `%clojure-iseq-lazy`), so `next`/`rest` answer that seq's tail where the oracle calls `next`
  and `more`; a verb may call a method another number of times (no chunked seqs); `str` spells
  the contents where the oracle answers `Class@hash`; a `java.util` type prints its contents
  under `print` too.
- Re-probes (2026-10-08): data.priority-map 1.2.0 stops at `priority_map.clj:216:7: unknown
  name: eval` (its `compile-if` macro evaluates a form while expanding); expanded by hand (the
  `compile-if` taken, `hasheq` as `(count this)`) it runs whole on the interpreter, the JVM and
  wasm. instaparse 1.5.0 now stops at `auto_flatten_seq.clj:13:15: unknown name: eval` (the
  same macro shape) and needs `hash`/`mix-collection-hash` past it.
- Pins: clojure-spec `a-collection-type-conjs-empties-counts-and-compares-through-its-methods`,
  `a-map-type-assocs-dissocs-reads-and-prints-as-a-map`,
  `a-set-type-disjs-contains-and-prints-as-a-set`,
  `a-vector-type-indexes-stacks-reverses-and-prints-as-a-vector`,
  `a-seq-type-walks-through-its-first-and-next`,
  `an-iterable-type-seqs-and-reduces-through-its-iterator`,
  `pending-comparable-and-sorted-types-answer-through-their-methods`,
  `a-protocol-extended-to-a-collection-interface-reaches-a-type-implementing-it`,
  `a-protocol-extended-to-an-interface-reaches-every-value-implementing-it` (all four backends,
  the oracle's lines); `ClojureLoweringTest#aBodyImplementingACollectionInterfaceStoresTheRowsOfItsWholeClosure`
  (the stores and the refusals), `#anExtensionToAnInterfaceKeyedByACoreKindReachesATypedValueBehindItsTest`,
  `#anExtensionToAnInterfaceOtherKindsImplementTooIsAWalkedClass`,
  `ClojureArmsTest#aCollectionInterfaceFamilyIsMadeByTheStoreOfARowOfItsInterfaces`,
  `ClojureLibraryTest#aProgramStoringNoCollectionInterfaceRowSplicesTheVerbsWithoutTheirArms`,
  `ClojureInteropTest#aHostIteratorStepsThroughIteratorSeqAndAnIterableTypesVerbs`.

## Java interop

The `java:` surface (`.kb/java-interop.md`); interpreter and JVM only -- wasm compiles
`java:` to a call-time error (`ClojureWasmInteropRefusalTest`).

- `(. obj m args)`/`(.m obj args)` instance, `(. Class m args)`/`(Class/m args)` static,
  `(Class. args)`/`(new Class args)`, `(Class/FIELD)`, `(.-f obj)`, `..`, `memfn`. Classes
  resolve dotted, imported or `java.lang` (`ClojureNamespaceLowering.JAVA_LANG` is the oracle's
  fixed default-import list read off `(ns-imports 'user)` on clj 1.12.6, 2026-10-03: the common
  throwables included, `AutoCloseable`/`Record`/`Module` not -- the oracle does not resolve them
  either). A construction of a plain throwable (`Exception.` included) is an exception
  condition on every backend ("Exceptions"); any other is `java:new`, refused on wasm. A zero-argument `(Class/m)` is the static method
  when the class has one, else the field. A bare `Class/member` value reads the static
  field, else answers a lambda dispatching per fixed arity (a variadic-only member is
  refused).
- Clojure 1.12 qualified members (`ClojureInteropLowering.memberCall`/`memberValue`, one
  `qualifiedMember` split): `Class/.m` is `instanceCallLoweredWithClass` with the class as
  the known receiver class (so the string/stream/boolean rules apply); `Class/new` is
  `hostConstruction`, the one construction path `(Class. ...)`/`new` share; `R/new` of a
  record or deftype is `->R`. Values are `arityLambda` over the public instance-method
  arities (+1 for the target) or constructor arities; none, or variadic-only, is refused
  at lowering (the oracle's `no matches found`). `^[types]` param tags (`paramTags`, the
  first vector among the `%with-meta` layers) become the `java:` designator `m(T1,T2)` /
  `C(T1)` (`tagTypes`: primitives, `ints`.../`objects`, `T/N` arrays, `_`, else
  `resolveClass`); a tagged value has the one fixed arity, a tagged call with another
  count is refused at lowering. Tags apply only where `ClojureLowering.hostMemberName`
  holds (no local/var/library/project namespace claims the name); elsewhere they stay
  plain metadata. `constructedClass` strips a designator's `(...)` so a tagged
  construction still records the local's class. Measured 2026-10-03 vs `clj` 1.12.6:
  the oracle refuses `^[_] Math/abs` (tags leaving several overloads); ronto leaves `_`
  to the cost rule (user doc deviation).
- A bare class name in value position is `(java:static "java.lang.Class" "forName"
  "<fqn>")`, the oracle's class object.
- A string receiver answers the mapped core operation (`stringMethod`, every backend); any
  other method reaches `java:call`, which calls a string / number / character as its
  `String` / narrowest box / `Character` (`.kb/java-interop.md`, "A Lisp value as a
  `java:call` receiver"; measured 2026-10-03 vs `clj` 1.12.6, `(.codePointAt "abc" 0)` 97,
  `(.compareTo 1 2)` -1; before: `java:call expects a java object ..., got "abc"`). A host
  false is the false object through the call's own `:java-false`, whatever the receiver
  (`(.matches "abc" "x")` false, not nil; "Host booleans" below). Deviation: an int-sized integer is an `Integer` (`(.getClass 1)`; the oracle's `Long`).
  Pins: `ClojureInteropTest#unmappedMethodsCallAStringNumberOrCharacterAsItsHostObject`.
  `.toString` of a number, character, symbol (booleans too), cons, array, table or
  function answers `(%clojure-str-of x "nil" nil)`, the oracle's `toString`, on every
  backend (`ClojureInteropLowering.valueToString`); nil signals (the oracle's NPE); only
  what is left reaches `java:call`.
  Stream receivers run on every backend: `.write` -> `princ` (nil signals), `.flush`,
  `.readLine` -> `read-line` (nil past the end), `.read` -> a character code (`-1` past
  the end), `.toString` -> `valueToString`'s stream clause, `%clojure-stream-string` (a
  string output stream's text so far, any other stream its host class name) behind the
  exact tag test `%clojure-stream-p`, never `streamp`: that answers true for `t`
  (Clojure's `true`) and, on the JVM, for a host object whose `equals` answers true (a
  `proxy` with `(equals [o] true)`: `ClojureInteropTest#proxyOverAClassSeesThisAndSuper`
  went `true` when `.toString` took the printer behind `streamp`). `(new
  java.io.StringWriter)` with no argument is a string output stream on every backend; a
  `java.io.PushbackReader`/`BufferedReader` construction over a stream is that stream and
  over a `(StringReader. s)` argument a string input stream ("Reading").
- A collection, keyword, symbol, ratio or atom has no host object (`java:call` refused every
  one: `(.count [1 2 3])` was `java:call expects a java object ..., got #(1 2 3)`). With the
  receiver class unknown, `ClojureValueMethodLowering.valueArm` puts `(if
  (%clojure-value-receiver-p r) ARM call)` in front of the host call (not for `.toString`,
  which `valueToString` answers). ARM is a row per `method/arity`: arms of one-argument
  predicate kinds (`ClojurePredicateLowering.rawTest`, so the sorted strip still folds them)
  over a `clojure.core/`-spelled verb datum lowered with the receiver and arguments bound to
  `recv%`/`argN%` locals (the core meaning whatever the program defines), e.g. `.get` is
  `get` on a map/set and `%clojure-list-get` (`nth` that signals past the end, the oracle's
  `IndexOutOfBoundsException`) on a sequential. Nil passes the gate and counts as a list
  for the `coll?`/`seq?`/`list?`/`sequential?` arms (it is the empty list: `(.isEmpty
  (filter odd? [2]))` true like the oracle's empty LazySeq), else the oracle's NPE. A kind
  no arm takes, or a row's method at another arity, is refused in the oracle's words (`No
  matching method contains found taking 1 args for class clojure.lang.PersistentArrayMap`,
  `No matching field found: first for class ...`); a method no row names is `Method m
  taking N args is not supported for class C` (the oracle's class may have it). The lowering
  fixes the words; `%clojure-no-method` appends the class. Measured 2026-10-04 vs `clj`
  1.12.6: clojure-spec `instance-calls-on-collections-keywords-symbols-and-ratios`
  oracle-identical but the unsupported-method line.
  A record, deftype or reify passes the same gate, and its class also has the protocol
  methods its body implements and its declared fields (oracle, clj 1.12.6, 2026-10-04:
  `(.m r)` calls the inline `m`, `(.a r)` reads field `a`, an `extend-type` method or an
  undeclared name is `No matching field found: q for class user.R`, a mutable field too).
  `typedMembers` decides at lowering: a site whose name is a method of some protocol (any
  arity) or a field of some known type gets, inside the arm's `cond`, per protocol declaring
  it at the site's arity (`ProtocolDef.arities`) a clause `(%clojure-inline-method-p recv
  table kw '(classes))` -> the protocol's dispatcher; the classes are the types whose body
  implements it (`TypeDef.inlineMethods`, read leniently by the pre-scan in
  `declareRecordType`), a reify is any row under its fresh tag (no extension reaches it).
  Then the mapped rows (a record is `map?`, so `(.count r)` stays the map's), then for zero
  arguments an immutable declared field (`%clojure-declared-field-p`, the `(caddr x)` key
  list), then the refusal in the oracle's words on a typed receiver. A site naming neither
  is lowered as before, so a typed receiver there keeps the unsupported-method words (the
  oracle: `No matching field found`), and a site lowered before a later REPL input defines
  a type does not see it. A deftype carries its class name at index 4 like a record, for
  the refusal; `.-f` refuses in the same words. Size, 2026-10-04 (wasm P1 / `--optimize=size`
  / component / JVM class): a program with no such site is byte-identical but +26 B per
  deftype (the class string); `(.m o)` against `(.foo o)` +1212 / +1084 / +1215 / +1326,
  `(.a o)` against `(.foo o)` +1011 / +829 / +1013 / +914. Pin: clojure-spec
  `instance-calls-reach-a-record-deftype-or-reify-method-or-field`.
  Cost, `(defn f [s] (.toUpperCase s))`:
  wasm 56160 -> 57298 B, JVM `.class` 94998 -> 97917 B (comment-only helpers and lowering-time
  words: docstrings and `format` arms had made it +4.0 KB on the JVM). Pins: that case,
  `ClojureInteropTest#collectionKeywordSymbolAndRatioReceiversAnswerTheirCommonMethods`.
- Host booleans (e73, 2026-10-09): every host call the lowering builds ends in `:java-false`
  (`ClojureInteropLowering.hostCall`, `fieldCall`; a `proxy`'s `java:proxy` / `java:subclass`
  too), so Java's false comes back as the false object -- an answer of any receiver, a
  `Boolean.FALSE` element, a field, a fn's or a proxy body's argument
  (`.kb/java-interop.md`, "Markers and handles"); the shared unmarshal stays nil for a CL
  program. The `T`-or-false wraps over known receiver classes (`booleanAnswer` at static and
  instance sites, `valuePredicate`, `instanceBooleanAtArity`) are gone. The library's host
  DATA reads end in it too (`toArray`, `getKey`/`getValue`, `next`, `get`, a `Future`'s
  `get`); its host PREDICATES (`containsKey`, `contains`, `hasNext`, `isDone`, ...) must not:
  a CL `if` reads `|false|` as true. Before, measured 2026-10-08 (interpreter and JVM): `(vec
  l)` of a list holding false `[nil]`, `(.get m "x")` of a false value nil, `(e? l)` over an
  unknown receiver nil. Pin: `ClojureInteropTest#aHostFalseComesBackAsFalse`.
- `false` crosses to Java as Java's false (e69, 2026-10-08): the false object IS the symbol
  `java:` passes as `false` / `Boolean.FALSE` (`FALSE_VALUE_NAME = LispNames.JAVA_FALSE`,
  `.kb/java-interop.md` "Java's false and hash tables"), an argument and a fn's or proxy
  body's answer alike; a map crosses as a fresh `LinkedHashMap` (the same section), its
  vector/map values converted too, so the copy's `toString` spells them the Java way (user doc
  deviation). A set, keyword or record reaches Java through `%clojure-host-value` (next
  bullet). Before, measured 2026-10-08
  (interpreter and JVM): `(.add l false)`, `(Boolean/toString false)` and `(java.util.HashMap.
  {"a" 1})` were `No matching method/constructor`, `(.removeIf l odd?)` and a proxy `test`
  answering false `cannot return |false| as boolean`.
- Clojure values Java has none of (e73, 2026-10-09): `hostArgument` wraps a host call's
  argument (`java:new`/`java:call`/`java:static`, a class `proxy`'s constructor arguments) in
  `%clojure-host-value` (`HOST_VALUE`, `clojure.lisp`): a keyword or symbol becomes a
  `java:handle` (`%clojure-host-ident`: its spelling, the oracle's `Keyword`/`Symbol` hashCode
  -- `Util.hashCombine` of the name's and namespace's `String.hashCode`, a keyword
  `0x9e3779b9` more -- and an order text sorting no namespace first, then namespace, then
  name, keywords before symbols), so a host `HashMap`/`HashSet` iterates and a `TreeSet`
  sorts in the oracle's order and Java hands back the keyword itself; a set or sorted set a
  fresh `LinkedHashSet`; a map holding such a value, a record or a sorted map an `equal`
  table of converted entries (`java:` makes it a `LinkedHashMap`); a lazy seq the list it
  realizes; a vector or list holding such a value a converted copy; anything else itself (a
  ratio or atom stays refused by `java:`). Not wrapped, so the site keeps resolving on the
  argument's kind: a literal, a fn form, a construction (`isPlainForm`) and a `let` local
  bound to one and not shadowed (`isPlainLocal` over `noteHostClass`); nothing on wasm
  (`!ctx.hostTarget`: a `java:` call is a call-time error there). `get`/`contains?`/`find`
  over a host map convert their key the same way. Measured 2026-10-08 before choosing this
  over a generic `java:` hook (the plan's preference): wrapping every non-literal argument
  of the 784 `java:` sites in 276 lowered programs (`ClojureInteropTest`'s, `examples/
  clojure`, the clojure-spec corpus) moved 2 from resolved to dispatched, both a
  construction-bound local -- the exemption above -- and no other site's status. Before,
  measured 2026-10-08: `(java.util.HashSet. #{1 2})` `No matching constructor for
  java.util.HashSet with 1 argument(s)` (a record, a sorted set alike), `(.put m :k 1)` `No
  matching method java.util.HashMap.put with 2 argument(s)`. Deviations (user doc): a copy's
  `toString` and a collection inside it spell the Java way; keywords and symbols sort together
  where the oracle refuses to compare them. Pins: `ClojureInteropTest#aSetAKeywordAndARecord
  CrossTheJavaBoundaryAsTheOraclesDo`, `#aKeywordOrSymbolHashesAndSortsInJavaAsTheOraclesDo`,
  `ClojureLoweringTest#aHostArgumentThatMayHoldAValueJavaLacksGoesThroughTheHostValue`.
- A fn receiver is the oracle's `AFunction` (`ClojureValueMethodLowering.functionRows`; the
  value gate `%clojure-value-receiver-p` takes `functionp`): `invoke` of 0..20 arguments and
  `applyTo` for any `ifn?` value, `call`, `run` (nil), and `compare` on a fn through
  `%clojure-fn-compare` (`AFunction.compare`: true -1, false 1 when the reversed call is true
  else 0, a number its `intValue` by `%clojure-unchecked-int`, nil the NPE, else a
  ClassCastException). Runs on all four backends (the arm answers before the `java:call`);
  before, `java:call expects a java object as the first argument, got #<lambda>`. A fn passed
  TO Java as a `Comparator` reads the same way since e73 (its call ends in `:functional` and
  `:java-false`: `.kb/java-interop.md`, "Markers and handles"; pin
  `ClojureInteropTest#aFnPassedAsAComparatorComparesLikeTheOraclesAFunction`). Pin: clojure-spec
  `instance-calls-on-a-fn-are-its-ifn-callable-runnable-and-comparator-methods`.
- A fn passed where an interface is expected implements every abstract method by the
  method's arguments, defaults keeping their bodies (`.kb/java-interop.md`, `:functional`;
  `ClojureInteropLowering.hostCall` ends a `java:new`/`java:call`/`java:static` with any
  non-literal argument in the marker). Before (measured 2026-10-08, interpreter and JVM)
  the shared auto-proxy passed the method name first: `(.start (Thread. (fn [] ...)))`
  was `Function expects 0 arguments, got 1`. Oracle (clj 1.12.6, same day): a fn converts
  only to a `@FunctionalInterface` (a `PropertyChangeListener`/`DocumentListener` is a
  `ClassCastException`; here every abstract method of any interface calls it -- user doc
  deviation), `Comparator` takes a boolean answer (`AFunction.compare`; alike here since e73).
  A fn prefers a functional interface's overload (`TreeSet(Comparator)` over
  `TreeSet(Collection)`; the oracle's fn IS a `Comparator`).
  `(proxy [Super] [fn] ...)` constructor arguments convert the same way: `proxyClassOf` ends
  the `java:subclass` in the marker after its callable (until e69 they converted as
  `java:proxy`, the method name first). Pins:
  `ClojureInteropTest#aFnPassedWhereAnInterfaceIsExpectedImplementsItsMethodByItsArguments`
  (`locking`'s threaded pin passes its fn to `Thread.` directly),
  `ClojureInteropTest#falseAMapAndAFnCrossTheJavaBoundaryAsTheOraclesDo`.
- `proxy` of interfaces is `java:proxy` with a name-dispatching lambda over the Java
  arguments (no `this`); a missing method raises `no proxy method: <name>`;
  `toString`/`equals`/`hashCode` are refused there (`java:proxy` keeps `Object`'s, so the
  body would never run). `(proxy [Super I...] [args] ...)` is `java:subclass`: bodies bind
  `this`, `proxy-super` calls the generated `super$` accessor, an unnamed abstract method
  throws `UnsupportedOperationException`. Multi-arity methods, a second class, a final
  superclass are refused.
- No host-field `set!`: the `java:` surface has no write primitive.
- Host collections under the seq verbs (decided 2026-10-04, oracle clj 1.12.6): `RT.seq`
  takes an `Iterable`, a `Map` (its `entrySet`) and a `CharSequence`; `RT.count` a
  `Collection`, `Map` or `CharSequence` (another `Iterable` is `count not supported on this
  type: <simple name>`); `RT.get` a `Map` (anything else nil); `RT.contains` a `Map` or `Set`
  (anything else `contains? not supported on type: <name>`). Before: `seq needs a
  collection` for every seq verb, `count` a `LENGTH` type error, `get` nil, `contains?`
  false. One host-object family test, `%clojure-host-seqable-p`
  (`ClojureCollectionLowering.HOST_SEQABLE_P`), heads a clause ahead of each verb's
  fall-through: `%clojure-strict-seq` (so every verb over the seq view: `first`, `map`,
  `reduce`, `into`, `vec`, `nth`, destructuring), the lowered `count`, `empty?`, `get`
  (and keyword call position, `getBranches`), `contains?`, and an `if` around `keys`/`vals`'
  table walk. The reads (`clojure.lisp`, `%clojure-host-seq` and friends): a `Collection`
  through `toArray`, a `Map` through `entrySet` `toArray` as `[k v]` vectors (the oracle's
  are the host entries: user doc deviation), a `CharSequence` through `toString`, another
  `Iterable` through `iterator` (a JDK non-public iterator class trips the `java:call` gap
  of todo c92). `get`/`contains?` ask the host's own `containsKey`/`contains`/`get`, the
  oracle's `equals` lookup, of the key as `%clojure-host-value` makes it (a keyword its
  handle), after `%clojure-host-key-p` (`Objects.isNull` under `handler-case`): a key
  `java:call` still cannot marshal (a ratio, an atom) is in no host map, since `.put` refused
  it too. Not the c90 walk by `=`: that pulled `%clojure-equal`'s
  whole closure into every `get` (+9.9 KB JVM class) and is O(n).
  Cost, measured 2026-10-04 (load average 25-140, so speeds are medians of 5-7 alternated
  runs): a program naming no `java:` operator is byte-identical (wasm P1, `--optimize=size`,
  component, JVM class with its runtime classes: `demo.clj` and a program over `seq`,
  `count`, `empty?`, `get`, `contains?`, `keys`, `vals`, keyword call). A `java:` program
  carries the arms: JVM class `count` + one `.toUpperCase` 98,490 -> 104,225 B, a program
  also reaching `=` 127,068 -> 130,787, `empty?`/`count`/`keys`/`get` loops 109,655 ->
  117,610. Speed there (JVM): an `empty?` list walk +5%, a `count` loop over a vector and
  nil 0.70 -> 0.93 s per 8M (one more call per count), `keys`/`get` +15%. Interpreter, which
  keeps the arms in every program: the same loops +3% / +24% / +14% (the `empty?` walk was
  +30% before the inline consp/arrayp/symbolp/numberp exit at the head of
  `%clojure-host-seqable-p`, which spares lists and vectors the `%clojure-lisp-value-p`
  call), a mixed program (quicksort over `empty?`, a word count over `get`/`assoc`,
  `keys`/`vals`/`frequencies`) 3,031 -> 2,988 ms, within noise.
  Pins: `ClojureInteropTest#aHostCollectionSeqsCountsAndLooksUpLikeAClojureOne`
  (oracle-identical), `ClojureLibraryTest#aProgramNamingNoJavaOperatorSeqsAndCountsWithoutTheHostCollectionArms`.
- Host maps under the map verbs (decided 2026-10-04, oracle clj 1.12.6): `RT.find` takes a
  `Map` by `containsKey` and answers the LOOKUP key's entry (anything else `find not
  supported on type: <name>`); `select-keys` is `RT.find` per key (so a non-`Map` with no
  keys answers `{}`); `kv-reduce` and a map's `cons` read any seq of `Map.Entry` (a `Map`
  through its entries, `(.entrySet m)` too). Before: `find not supported on this type`,
  `select-keys needs a map`, `reduce-kv needs a map or a vector`, `conj needs a map entry`,
  a `MAPHASH` type error in `merge-with`. Arms, all behind the one `%clojure-host-seqable-p`:
  a clause ahead of the fall-through of `%clojure-find` (`%clojure-host-find`, the host's
  own lookup after `%clojure-host-key-p`, like `get`), `%clojure-kv-pairs` (so `reduce-kv`,
  `update-vals`, `update-keys`, `map-invert`, `rename-keys`' map, a sorted `merge-with`),
  `%clojure-merge-entry-plist`, `%clojure-sorted-entry-plist` and the lowered `entryPlist`
  (`conj` onto a map; `%clojure-host-entry-plist`). Entries: `%clojure-host-entries`, a
  `Map`'s `[k v]` vectors, else each member a host `Map$Entry` or a `[k v]` vector (the
  deviation `seq-entry-plist` keeps), else the verb's refusal. Two views, since a test there
  would not fold to the old form: `select-keys`' key list (`%clojure-host-select-keys keys
  whole out`, which fills `out` by `%clojure-host-find` and answers nil; the walk's
  `hash-table-p` test gains an `or` disjunct) -- an `if` arm would need the key form twice,
  and a snapshot table of the host map would look up by `=`, not the host's `equals` -- and
  the map `merge-with` walks (`%clojure-host-table`, a fresh table of the entries). Both
  views and the library function behind the `if` test answer a `hash-table-p` first: the
  view is asked of EVERY `select-keys`/`merge-with` map. A view body must keep the family
  test as a bare `if`/`cond` test, never inside `and` (the library strip signals `a
  host-object test where it cannot fold`). `format nil` in a host arm pulled the whole
  `format` runtime in (+41 KB JVM class on the test program); the refusal words go through
  `error`'s own arguments instead.
  Cost, measured 2026-10-04 (load average 6-150; speeds are medians of 8-10 alternated
  runs): a program naming no `java:` operator is byte-identical (wasm P1, `--optimize=size`,
  component, JVM class with its runtime classes: `demo.clj` and a program over every verb
  above, call and value forms, sorted and record inputs). A `java:` program: `count` + one
  `.toUpperCase` 104,225 -> 104,251 B (the `hash-table-p` exit), one map verb +1.7-3.5 KB,
  all of them 143,524 -> 147,828, the `clojure-spec.yaml` program 6,486,791 -> 6,504,280
  (wasm P1 +4.1 KB). Speed there (JVM): `select-keys` of two keys +10% (one view call per
  select-keys; +16% before the view's own `hash-table-p` exit), `merge-with` +2%, `conj`,
  `find`, `reduce-kv` within noise. Interpreter (arms in every program): `select-keys` +3%,
  `merge-with` +1% (+12-15% while the arm was an `if` on `%clojure-host-seqable-p`, a call per
  map), `find`/`reduce-kv` within noise.
  Pins: `ClojureInteropTest#aHostMapIsAMapToTheMapVerbs` (oracle-identical),
  `ClojureLibraryTest#aProgramNamingNoJavaOperatorRunsTheMapVerbsWithoutTheHostMapArms`.
- A host `Future` under `deref` (oracle clj 1.12.6): `@f` is its `get` (a failure surfaces as
  `ExecutionException`, a cancelled one as `CancellationException`), and the three-argument
  `deref` is `get(ms, MILLISECONDS)` answering the third argument on `TimeoutException` (the
  timeout truncated to a long, a non-number a `ClassCastException`); every other value, after
  both extra arguments ran, is a `ClassCastException` (nil an NPE), so a program naming no
  `java:` operator compiles the three-argument form to that refusal on all four backends
  (clojure-spec `deref-with-a-timeout-refuses-a-value-that-is-no-future`). The one-argument arm is
  a clause of `%clojure-deref-other` headed by `%clojure-host-object-p` of
  `java.util.concurrent.Future` (the HOST family test, so the strip folds it); the timed form is
  the lowered `if` on the same test around `%clojure-host-future-get-within`, whose handler
  matches the cause's class by `%clojure-host-is-a` and re-signals anything else. `deref` as a
  function value stays one argument: a variadic lambda would change the bytes of every program
  naming it. Cost: a program naming no `java:` operator is byte-identical (wasm P1,
  `--optimize=size`, component, JVM class with its runtime classes: `demo.clj` and an atom,
  var and reduced `deref` program); a `java:` program that reads an atom grows by the arm's
  closure (JVM `.toUpperCase` + `@a`: class 103,872 -> 105,633 B; wasm +66 B). Pin:
  `ClojureInteropTest#derefOfAHostFutureIsItsGetWithAnOptionalTimeout` (oracle-identical,
  interpreter and JVM).
- A host `Future` under the future verbs (oracle clj 1.12.6): `future?` is true of a host
  `java.util.concurrent.Future` (a `CompletableFuture`, a `FutureTask`); `future-done?`,
  `future-cancelled?` and `future-cancel` are its `isDone`, `isCancelled` and `cancel(true)`,
  each cast to `Future` first, so every other value is a `ClassCastException` (nil an NPE) on
  all four backends (clojure-spec `future-verbs-refuse-a-value-that-is-no-future`; wasm has no
  host object, so the host arm folds). `realized?` of a host `Future` is the `ClassCastException`
  it always was (`%clojure-is-realized`). The verbs are `ClojurePredicateLowering` calls: a
  `let` over the value, `%clojure-host-object-p` of `java.util.concurrent.Future` (the HOST
  family test) around the library function and the `ClassCastException` refusal. `future?` is
  `(%clojure-host-future-p x false)`, a HOST alias of `progn`, so the strip leaves
  `(progn x false)` and every program naming no `java:` operator is byte-identical (wasm P1,
  `--optimize=size`, component, JVM class with its runtime classes: `demo.clj` and programs
  calling `future?` as a call and as a value); a `let*` over a temporary instead, the first
  try, changed the bytes of every program calling `future?` and shifted the temp names. A
  `java:` program calling `future?` grows by the arm (JVM class 99,607 -> 99,862 B around one
  `CompletableFuture/completedFuture`); one that does not call it is byte-identical.
  Pin: `ClojureInteropTest#theFuturePredicatesReadAHostFuture` (oracle-identical, interpreter
  and JVM).
- Host collections under the printer (decided 2026-10-04, oracle clj 1.12.6): `print-method`
  under `*print-readably*` writes a `RandomAccess` as a vector, another `List` as a list, a
  `Map` as a map and a `Set` as a set, members readably, under `*print-length*`/`*print-level*`,
  in the host's own order; another `Collection` (`ArrayDeque`, a `Map`'s `values`), a `Map`'s
  entries, and every host object not readably (`println`, `print`, `str` of one) are
  `print-object` -- here `#<java C>`. Before: `#<java C>` for all. One clause
  `(%clojure-host-seqable-p x)` ahead of `%clojure-write`'s fall-through calls
  `%clojure-write-host`, which builds the Clojure value (a plain `equal` table / `:C%SET`
  table / vector / list, an empty `List` a realized empty lazy seq so it is `()`, `#` at level
  0) and writes it through `%clojure-write` at the same depth, so the print-flag arms apply.
  The members go in without `%clojure-store-key`: its key representatives pulled
  `%clojure-equal`'s closure in (`count` + `.toUpperCase` + `println`: JVM class +17,697 B with
  it, +1,574 B without). Cost, measured 2026-10-04: a program naming no `java:` operator is
  byte-identical (wasm P1, `--optimize=size`, component, JVM class and runtime classes:
  `demo.clj`, a program printing vectors, maps, sets, lists, sorted maps under the print
  flags). A `java:` program, JVM class: `count` + `.toUpperCase` + `println` 113,378 ->
  114,952 B, the same with `prn` 114,969 -> 115,881, the pin's program 183,604 -> 184,545 (wasm
  output is the `java:new` refusal either way, +-3 B). Speed: JVM `pr-str` of a 200k-integer
  vector in a `java:` program within noise (3.97 -> 3.95 s / 20, medians of 5); the
  interpreter, which keeps the arm in every program, pays one call per value reaching the
  fall-through (integers): `pr-str` of a 50k-integer vector 6.87 -> 7.40 s / 4 (+8%, medians
  of 5, load 3-13). An integer therefore takes `((integerp x) (princ x stream))` right after
  the `nil` arm (it has no metadata, is no collection, carries no label; a bignum is an
  integer, a ratio still falls through to the same `princ`): the same `pr-str` x 5 on the
  interpreter 12.2 -> 6.0 s (the load, compile and `princ` share is the rest), JVM 200k x 20
  3.24 -> 3.09 s, wasm and component 4.9 -> 4.6 s (3-5%, near noise: the compiled kind tests
  are cheap). Cost, measured 2026-10-04: a program that prints a non-literal value
  grows by the one clause, wasm +46 B (P1, `--optimize=size`, component alike; 36,796 ->
  36,842), and any JVM program that links the printer, `(println "n")` included, grows
  +288-336 B of class (59,347 -> 59,635); a program linking no printer (wasm
  `(println "n")`, a JVM `(def x 1)`) is byte-identical. Output identical on the four backends
  (`an-integer-and-a-ratio-print-as-their-digits-under-every-flag`).
  A keyword, string, character and float (no metadata, no collection, no label) take their
  arms right behind it, the float arms merged into one `(floatp x)` with the
  `symbolic-float-p` split inside; nothing else moved. They sat behind the library tests
  (`print-meta`, `print-deep`, lazy, pattern, matcher for a keyword; twenty more for a
  string; every kind for a float). Measured 2026-10-04 (wall minus a no-print control, a
  50k-element vector x 5 `pr-str`, interpreter): keywords 9.5 -> 8.1 s, strings 10.2 -> 7.9 s,
  doubles 7.8 -> 3.5 s; wasm 200k x 20 within 4% (keywords 8.2 -> 7.9 s, doubles and
  component within noise), JVM within noise. Size: wasm -100 to -140 B (P1,
  `--optimize=size`, component alike: the merged float arm drops a test), JVM class
  +87-92 B for a program that links the printer; a program linking none (`(def x 1)`) is
  byte-identical. Output identical on the four backends
  (`a-keyword-string-character-and-float-print-the-same-under-every-flag`).
  Pins: `ClojureInteropTest#aHostCollectionPrintsReadablyLikeItsClojureKind`
  (oracle-identical but the `#<java C>` lines),
  `ClojureLibraryTest#aProgramNamingNoJavaOperatorPrintsWithoutTheHostCollectionArm`.

## Laziness

A lazy seq is `(:C%LAZY cell)` over a zero-argument thunk, run at most once per object.
A realized lazy seq is a cons whose tail is another wrapper, so a CL list operation over
the one-level view walks wrapper cells as members. Each consumer takes the view it needs:

- **Whole-collection view** `%clojure-seq-all` (`ClojureSeqLowering.seqAllForm`): every
  lazy tail realized, the view itself when its spine holds no wrapper (a strict input is
  never copied). For every consumer using a CL list operation (`count`, `set`, `reverse`,
  `last`, `sort`, `apply`, `~@`, the strict arms of `map`/`filter`/`concat`, ...).
- **Stepping** with `%clojure-seq-rest`, so an infinite input answers: `some`, `every?`,
  `take(-while)`, `drop(-while)`, `nth`/`second`, positional destructuring, `zipmap`,
  `cycle`, `doseq`, `dorun`.
- **One level**: `first`/`rest`/`next`/`seq`/`when-first`/`ffirst`/`nfirst`/`list*`.

A verb answering a seq follows the lazy-or-strict rule (`%clojure-lazy-or-strict`): a lazy
wrapper when an input is lazy, the strict list otherwise. `cons` onto a lazy collection
answers a wrapper, so no strict cons holds a lazy tail (`seq`/`conj` still may, like the
oracle's `Cons`).

- `lazy-seq`: `%clojure-make-lazy` over a lambda of the body, which is its own zero-arity
  `recur` target; `lazy-cat` desugars to `(concat (lazy-seq e) ...)`. Infinite
  `repeat`/`cycle`/`iterate`/`repeatedly` are wrapper chains.
- `for`: `(%clojure-for coll step depth)`, one step closure per level
  (`ClojureLoopLowering.forStep`: binds the pattern, runs the modifiers, answers
  `:C%FOR-SKIP`/`:C%FOR-STOP`, the body's value innermost, else `(next-coll . next-step)`).
  The iteration lives once in `clojure.lisp`: strict while every collection met is strict,
  a lazy seq from the first lazy one on (the oracle's `(fn iter [s] (lazy-seq ...))`), so
  an infinite level ends behind `take`. One shared runtime, not inline structure per site:
  the inline cut made every element pay a thunk and a wrapper (a strict 200,000-element
  `for` on the interpreter 8.95 s vs 4.18 s).
- `%clojure-concat-step` lets its last member answer its own seq: re-wrapping it made a
  lazy multi-level `for` walk each element through one layer per outer element.
- **A skip never nests a realization** (b93). `%clojure-realize` hands a thunk's wrapper
  answer to `%clojure-realize-chain`, which forces the chain in a loop (the oracle's
  `LazySeq.seq`) and memoizes the final seq into every cell it forced; a cell reads
  realized-and-empty from the moment its thunk answers, so a self-answering body and a
  throw further down the chain leave `nil`, both the oracle's answers. The runtime
  producers skip in a loop inside one realization: `filter`, `concat` over empty members,
  `for` (`%clojure-for-next`), `dedupe`, `partition-by`, the transducer puller. Before:
  `(first (filter #(> % 100000) (iterate inc 0)))` overflowed every backend (50,000 the
  interpreter and wasm, 20,000 wasm). Raw wasm (2026-10-03): the chain loop is its own
  defun (256 B) because inlining it into `%clojure-realize` widened the analysis of
  `%clojure-strict-seq`'s argument (+688 B in a vector-free program); `concat-step`
  113 -> 206 B; a lazy program +255..362 B. Pin: clojure-spec
  `long-skips-and-wrapper-chains-run-in-constant-stack`.
- **The dropping and round-robin verbs** (b94): `remove` `keep` `keep-indexed`
  `map-indexed` `distinct` `interpose` `partition` `interleave` are one call each to a
  spliced lazy-or-strict worker (they answered strict lists over the whole-collection
  view, so an infinite input never answered and `interleave` recursed per element). A
  strict input keeps a direct loop: through `%clojure-lazy-or-strict` (a thunk per
  group) a strict `partition` took 10.1 s against 5.1 s (3 x 100,000 members,
  interpreter). The first five share one lazy arm, `%clojure-keep-lazy`, whose adapter
  answers `:C%SKIP` to drop; `interleave`/`interpose` emit one wrapper per element, so
  `rest` never exposes a strict cons holding a wrapper. The function argument is a real
  function ("The IFn dispatcher stays at the call site"). Raw wasm of a one-verb strict
  program, before -> after
  (default / `--optimize=size`): `remove` 38,534 -> 39,262 / 30,965 -> 31,471, `keep`
  38,916 -> 38,989 / 31,323 -> 31,222, `keep-indexed` 38,462 -> 38,910, `map-indexed`
  37,336 -> 37,894, `distinct` 50,767 -> 50,941 / 40,361 -> 40,343, `interpose`
  36,674 -> 37,292, `partition` 37,661 -> 38,298, `interleave` 33,960 -> 37,663 (its
  lazy arm and closures; it had no lazy machinery), all eight 58,869 -> 59,733. Pin:
  clojure-spec `dropping-and-stepping-verbs-answer-over-infinite-inputs`.
- The printer realizes a wrapper where it stands and steps its tail, so an infinite seq
  prints without end like the oracle's; the cycle walk keeps a wrapper a leaf.

## Transducers

**A transducer is the oracle's: a function from a reducing function to a reducing
function**, built by a spliced `%clojure-xf-*` worker, not a tagged closure the consumers
interpret -- so `comp` composes them unaided and a program's own transducer runs beside
the core ones. A built-in reducing function is an `&optional` lambda telling init /
completion / step apart by supplied-p; per-reduction state lives in variables the
`(lambda (rf) ...)` closes over, so completion is not skipped (`partition-all` flushes its
tail after an early `take` stop).

- `ClojureTransducerLowering.xformCall` intercepts the transducer arity of
  `map` `filter` `remove` `keep` `keep-indexed` `map-indexed` `take` `drop` `take-while`
  `drop-while` `take-nth` `mapcat` `partition-all` `partition-by` `interpose` (one argument)
  and `dedupe` `distinct` (none); `xformValue` widens their value lambdas. Also
  `transduce` `eduction` `sequence` `completing` `reduced` `reduced?` `unreduced`
  `ensure-reduced` `cat`.
- `reduce`, `reduce-kv`, `transduce`, `into` and the stepping consumers stop at
  `reduced`; `deref` reads it. `transduce`, `into` and `cat` reduce through
  `%clojure-reduce-init`, so a record/deftype/reify with its own `CollReduce` row reduces
  through it under them too ("clojure.jar namespaces").
- `sequence`/`eduction` step inputs one element at a time behind a lazy wrapper
  (`%clojure-xf-puller`), lazy-or-strict; several collections step in lockstep.
- Every transducer, reducing function and `completing` argument is a real function
  ("The IFn dispatcher stays at the call site"), so the transducer runtime funcalls and
  never names the dispatcher. A transducer a program calls BY HAND with a set, map or
  keyword as the reducing function signals (`funcall` of a non-function) where the oracle
  invokes it; every consumer (`transduce` `into` `sequence` `eduction` `completing`, as
  calls and values) wraps such an argument first.
- Deviations: an `eduction` is a seq computed once (the oracle re-runs it per reduction and
  `println` prints the object); `take-nth` of the seq arity signals on a zero step and
  steps by the magnitude of a negative one; `halt-when`/`random-sample` are absent.

## Regex

`#"..."` reads to `(%regex source)` with every escape verbatim like the oracle (`#"\\d"`
is a backslash plus `d`); unknown alphabetic escapes, a short `\u`/`\x`, a bare `\0` and a
lone `\E` are refused at read time. The engine is a backtracking matcher in `clojure.lisp`
over string primitives every backend compiles: no per-backend code (a host
`java.util.regex` would serve two backends). It costs about 20 KB of wasm when referenced,
nothing otherwise. Greedy, reluctant and possessive quantifiers and backreferences work;
lookarounds, named groups, inline flags, POSIX classes, `&&`, `\G` signal `unsupported
regex` at construction. Patterns print `#"..."`, matchers `#<Matcher source>`; `class`
answers `:pattern`/`:matcher`.

**`nth` of a matcher reads `Matcher.group(n)`** (oracle-checked clj 1.12.6, 2026-10-05): the
last match's group `n`, `nil` for a group that took no part, `IllegalStateException` ("No match
found") before a match or after a failed `find` (even with a default, for an index the pattern
has), so destructuring a matcher binds its groups. `%clojure-matcher-nth` is the arm behind
`%clojure-nth`'s refusal clause, whose test `%clojure-matcher-value-p` (an alias of
`%clojure-re-matcher-p`, which is also used outside arm positions) is the test of
`ClojureArms.Family.MATCHER`, produced by `%clojure-re-matcher` alone: a program naming no
`re-matcher` has the arm folded and compiles `nth` as before (the 465 programs of "Refusals":
`--dump-ir` identical but the matcher probe). Cost of the arm in a program reading a matcher by
`nth`, wasm P1 / component / JVM class: 68,384 / 69,664 / 115,873 -> 68,722 / 70,002 / 116,433.
Deviations: an index below zero or past the groups answers the default (nil without one) where
the oracle's two-argument `nth` throws `IndexOutOfBoundsException` ("No group n"; `nth` past the
end answers the default, "Deviations"); and the oracle's three-argument `nth` reads no group of a
pattern without groups (`groupCount > 0 && n <= groupCount`) while its two-argument one reads
group 0, and the call without a default shares one shape with a nil default, so a groupless
pattern reads group 0 unless a non-nil default is given. `second`/`first`/`seq` of a matcher are
`seq`'s IAE and `count` is the UOE, as in the oracle. Pin: clojure-spec
`nth-of-a-matcher-reads-its-group`, `ClojureArmsTest#theMatcherFamilyIsMadeByReMatcherAndFoldsNthsGroupArm`,
`ClojureLibraryTest#aProgramMakingNoMatcherSplicesNthWithoutItsMatcherArm`.

## Reading

**`##NaN`, `##Inf`, `##-Inf` read as doubles** in both readers (`readSymbolicValue`,
`%clojure-rd-symbolic`): the oracle reads the NEXT FORM after `##` (so `## Inf` and
`##Inf)` read) and refuses a symbol not in the three with `Unknown symbolic value: ##x`, any
other form with `Invalid token: ##<str of the form>`. The printer spells them `##NaN`/`##Inf`/
`##-Inf` under print and pr alike (`%clojure-write`, via `%clojure-symbolic-float-p`); `str`
of the bare value and `format` keep `NaN`/`Infinity` (`%clojure-str-of`), a collection under
`str` is readable, so `##`. Pinned on all four backends (clojure-spec, measured against clj
1.12.6.1673, 2026-10-03). Not reproduced: `(get {##NaN 1} ##NaN)` is nil and
`(contains? #{##NaN} ##NaN)` false there (two reads, two boxed objects); a double here has no identity,
so a NaN key is found by value like a computed one always was.

**A double's exponent marker is uppercase** (`1.0E19`, `1.5E-7`, like `Double.toString`): the
`%clojure-write` float arm upcases the Common Lisp printer's text (the digits and the plain range,
1.0E-3 up to 1.0E7, were already the oracle's); `str`, `format`'s `%s` and every collection go
through it. Pinned on all four backends by clojure-spec `a-double-prints-its-exponent-marker-in-uppercase`
(clj 1.12.6, 2026-10-03). The Common Lisp printer is unchanged.

**`read-string`/`read` run one reader in `clojure.lisp` (`%clojure-read-from`) over the
source reader's language, answering what a quote of the same text answers**, so `(=
(read-string s) 's)` holds: `@x` reads `(deref x)` and `` `x `` `(syntax-quote x)` like a
quote does (the oracle: `clojure.core/deref`, the expansion), `#(...)` the source reader's
`(fn* [p1__N# ...] (body))` (N per datum read, through the specials `%clojure-rd-args`/`%clojure-rd-arg-id`), `#=` is its refusal, a tag reads through `*data-readers*`, then `#inst`/`#uuid` into their values ("Instants and UUIDs"), then `*default-data-reader-fn*` ("Data readers"), and `#?` reads under `{:read-cond :allow}` ("Reader conditionals"). A read map
or set stores its keys through "Structural keys" (`%clojure-rd-map-of`, `%clojure-set-put`),
so it finds `=` keys and refuses an `=` duplicate like the oracle (`Duplicate key: k`, k's
toString: a map names the earlier key, a set the later member). `::kw` resolves against the
context each call site passes, `("ns" ("alias" "full.ns") ...)`, plus the libraries
`isKnownNamespace` names and the startup ones (mirrored in `%clojure-rd-alias`).
The source reader refuses the same duplicates at read time, positioned after the closing
brace (`readBraced`/`readSet`, measured on `clj` 1.12.6, 2026-10-08): the oracle's
`PersistentArrayMap.createWithCheck` compares the READ forms, so `{1 :a 1N :b}`,
`{[1] :a (1) :b}`, `{{:a 1 :b 2} 1 {:b 2 :a 1} 2}` and `{-0.0 1 0.0 2}` are refused and
`{1 :a 1.0 :b}`, `{1 :a 1M :b}`, `{#"a" 1 #"a" 2}` and `{:a 1 ::a 2}` are not; keys equal
only once evaluated are checked in the lowering ("Literal keys equal once evaluated").
`1M` reads as the rational `1` (`doc/en/clojure/deviations.md`), so `{1 :a 1M :b}` is
refused where the oracle reads it. A `deps.edn` read (`forEdn`) leaves maps to
`ClojureDepsEdn.duplicateKey`, whose wording (`Error reading edn. Duplicate key: k (path)`)
the reader's positioned message cannot give.

**Literal keys equal once evaluated** (`ClojureCollectionLowering.mapLiteral`/`setLiteral`,
the oracle's `MapExpr`/`SetExpr`; measured on `clj` 1.12.6, 2026-10-08):
- Every key a constant (`constantKey`: the oracle's `LiteralExpr` -- number, string, char,
  keyword, `nil`/`true`/`false`, a quoted datum, a regex, a NON-empty vector/map/set
  literal of constants without metadata; `[]`, `()` are its `EmptyExpr`, no constant):
  two `=` ones are the compile-time `Duplicate constant keys in map`, a lower-time
  `LispReadException` (`{[1] :a '(1) :b}`, `{1 :a '1 :b}`); the map then builds unchecked.
  A set of constants is not refused: it dedupes (`#{[1] '(1)}` has one member).
- Otherwise a literal of two or more entries builds through `%clojure-map-literal` /
  `%clojure-set-literal` (the oracle's `RT.map`/`RT.set` `createWithCheck`), after every
  key and value ran: a table count short of the pair/member count reruns the reader's
  builders `%clojure-rd-map-of` / `%clojure-rd-set-of` over the evaluated forms, which
  raise `IllegalArgumentException` `Duplicate key: k` -- a map names the EARLIER key, a set
  the LATER member, nil as `null` (`{(+ 1 2) 1 3 2}`, `(let [a 0.0 b -0.0] {a 1 b 2})` ->
  `0.0`, the set -> `-0.0`). One entry is never checked (the oracle's `mapUniqueKeys`).
- Not reproduced: past 8 entries the oracle's `PersistentHashMap.createWithCheck` names
  the later key, in the hash order its reader gave the forms (evaluation order too); here
  always the earlier, in source order. Two computed NaN keys are one key here (a double has
  no identity), so they are a `Duplicate key` where the oracle keeps both.
- `hash-map`/`hash-set`/`array-map`/`sorted-map` calls and quoted literals keep last-wins /
  dedupe (`mapBuild`/`setBuild`), as the oracle's do.
- `()` and `[]` as keys: `()` IS `nil` here ("Values"), so `(get {[] 1} ())` is
  `(get {[] 1} nil)`, `nil` like the oracle's answer to the latter (the oracle answers `1`
  to the former). Making the empty vector and `nil` one key would merge `{nil 1 [] 2}`
  (two entries in the oracle) and make `(let [a nil b []] {a 1 b 2})` a `Duplicate key`,
  so they stay two keys (measured 2026-10-08: `structural-keys-find-equal-collections` pins
  `(get {[] :e} nil)` -> `nil`). The source reader still refuses `{() 1 [] 2}` (both read
  forms are empty sequentials), like the oracle.
- Pinned by clojure-spec `a-literal-refuses-keys-equal-once-evaluated` (oracle-identical)
  and `ClojureLoweringTest.aLiteralOfConstantKeysEqualOnceEvaluatedIsRefusedWhenLowered`.
`eval`/`load-string` stay unknown names: no compiler runs at run time.

**Oracle-checked 2026-10-08 (clj 1.12.6), shared by `read-string`/`read` and clojure.edn:**
- Metadata attaches (`%clojure-rd-meta`, the oracle's MetaReader): a keyword `{k true}`, a
  symbol or string `{:tag x}`, a vector `{:param-tags v}` (EDN refuses it), merged over the
  form's own with the outer winning; a non-IMeta form (number, string, keyword, char,
  boolean, pattern) is `Metadata can only be applied to IMetas`; a symbol form takes none
  (symbols carry no metadata here), nil is left alone (`()` reads as nil). Until then the
  reader dropped it.
- Namespace maps (`%clojure-rd-ns-map`, the oracle's NamespaceMapReader): `#:ns{}`,
  `#::{}`/`#::alias{}` against the call site's context; refusals in the oracle's words.
  The source reader takes them too (`ClojureReader.readNamespaceMap`): `#::` spells a
  keyword key `::k`/`::alias/k` for the lowering to resolve, and refuses a symbol key by
  name (no auto-resolved symbol spelling exists).
- A symbol or keyword token goes through the oracle's `matchSymbol` rules
  (`%clojure-rd-token-valid-p`: the pattern's first match by its backtracking order, then
  no ns ending `:/`, no name ending `:`, no `::` past the first character; `a/9` is a
  1.12 array-class symbol outside EDN). Character literals refuse like the oracle's
  (`Invalid unicode character`, `Invalid digit`, `Invalid character constant`, octal range
  and length). `#!` is a line comment, `#<` `Unreadable form`, and a tagged literal reads
  its value before refusing (`#a` at the end is `EOF while reading`). The source reader's
  `#!` is a comment anywhere too, where it was the first line only.
- Size, measured 2026-10-08 (wasm P1, `(prn (read-string "[1 {:a 2}]"))` plus a `defn`):
  134,269 -> 146,539 B. Of that, reverting one feature at a time: metadata 5.9 KB (the
  `eq` side table and its reads), token validation 2.6 KB, namespace maps 1.6 KB, the
  duplicate-key refusal 1.0 KB; `string-downcase` in the `\ud800` message alone had cost
  8 KB (the Unicode case table), so the hex digits are spelled by hand. A program that
  reads nothing is byte-identical.
- Pinned by clojure-spec `read-string-metadata-namespace-maps-and-token-refusals`,
  `ClojureReaderTest#aNamespaceMapQualifiesItsKeys`.

**clojure.edn** (`ClojureEdnLowering`, a known namespace like `clojure.string`, so its
qualified names need no `require` -- the oracle loads it before the program) reads through
the same reader in EDN mode: the entries `%clojure-edn-read-string-1`/`-read-string`/`-read`
bind `%clojure-rd-edn` to `(readers . default)` from the options, and every reader clause
the EDN grammar decides is behind `(%clojure-rd-edn-p)`, the arm test of
`ClojureArms.Family.EDN` (producers: the entries), so a `read-string` program carries none
of them. EdnReader's grammar, measured on the oracle: the quote is a constituent; a leading
`` ` ``/`~`/`@` is `Invalid leading character`, one inside a token `Invalid constituent
character` (a number's parse refuses it instead); `::kw` and `#::` are `Invalid token`; a
number needs a digit or a sign and a digit first (`.5` is a symbol here only in EDN: the
source reader and `read-string` read it as 0.5, the leniency the source reader keeps);
`#` dispatches to `{` `_` `#` `:` `^` and a letter (a tagged literal; `<` is `Unreadable
form`), anything else `No dispatch macro for: c`; `#:` reads its namespace as a whole form.
A tag calls the `:readers` value, then the built-in `#inst`/`#uuid` ("Instants and UUIDs"),
then `:default` with tag and value, through `%clojure-call` -- a var or
keyword reader works -- outside the read in progress (`%clojure-rd-edn-call` rebinds the
read specials, so a read inside the reader function starts afresh). `(read-string s)` is
`{:eof nil}` (a nil `s` answers nil); `read`'s stream is any character stream.
Measured 2026-10-08, wasm P1: the same program through `clojure.edn/read-string` 158,257 B
against 146,539 B through `read-string`; of the 11.7 KB, about 7 KB is the IFn dispatcher
the tag readers go through (a `read-string` program naming it measured 134,269 -> 141,165 B
before the reader changes above).
Pins: clojure-spec `clojure-edn-reads-data-only`,
`clojure-edn-tagged-literals-options-and-streams` (all four backends, oracle-identical),
`ClojureLibraryTest#aProgramReadingNoEdnSplicesTheReaderWithoutItsEdnClauses`.

- Source: a `(string . index)` cursor (`read-string`) or a CL character input stream
  (`read`) through `peek-char`/`read-char`, so a read leaves the stream right after its
  datum, like the oracle's `PushbackReader`. **No `unread-char`**: `ClojureLibrary` splices
  `clojure.lisp` whole before `UnreadCharLibrary` runs, so naming it would route every
  Clojure program's character reads through the pushback cell. One character of
  lookahead suffices because a token ends where the oracle's does (`#` and `'` are
  constituents, a backslash ends one); the source reader's `a#'x` split looks two ahead.
- Where the source reader deviated from the oracle, both follow the oracle now: `#_`
  before a closing bracket or the end of input discards (`ClojureReader.DISCARD`; a
  session buffer ending in one waits, `endsInDiscard`), and a character literal takes the
  character after the backslash unconditionally (`\(` reads back what `pr` wrote; a
  backslash ends the literal, and `ClojureSession.isComplete` skips it).
- **A double is `float` of the exact rational its digits spell**, through the prelude's
  `%decimal-double` (shared with the Scheme reader's `%scheme-decimal`): every backend rounds
  an exact rational once, ties to even (`.kb/wasm-bignum.md`, "Ratios"), so the run-time
  reader answers the double the source reader's `parseDouble` compiled. A value surely past
  either end of the double range is decided from the mantissa's bit length, so `1e400000000`
  never builds its power of ten. Until 2026-10-03 the reader built the double from its IEEE
  bits in Lisp (`%clojure-rd-double`) because WASM ratios held i32 components; that guard
  estimated log10 too high for a long mantissa, and a 903-digit one worth `1e305` read as
  infinity. Measured 2026-10-03: 1,593 values -- random bit patterns, 1-30-digit decimals with
  exponents -340..320, exact halfway expansions, subnormal and overflow edges -- equal Java's
  `parseDouble` on all four backends, and so do the same strings through Scheme's
  `string->number`. `M` decimals stay exact ratios.
- Records: a program that reads registers every record/deftype class, behind the false
  binding (`ClojureReadLowering.registration`: `(class tag fields record-p)` strings); a
  session registers what each buffer adds or redefines. So a class from a namespace
  required later still reads (the oracle needs it loaded first); an unknown class and a
  deftype literal are the source reader's refusals.
- Streams: `(java.io.PushbackReader. x)`/`BufferedReader.` is `x` itself when it surely is
  a stream (`%clojure-reader`, `%clojure-string-reader`, `%clojure-in`:
  `ClojureInteropLowering.isStreamForm`), else a `streamp` test around the `java:new`; over
  a `(StringReader. s)` argument it is `(%clojure-string-reader s)`. A `StringReader` alone stays the host class: the corpus's SAX `InputSource` takes
  one. `read` of a host reader is refused (`read needs a reader ...`).
- Messages: the oracle's for the end of input (`EOF while reading`, `... string`, `...
  character`), `Unmatched delimiter: )` and the odd map; the source reader's otherwise.
  Built messages go through `(error "~A" ...)`: a `~` in user text is no directive.
- `str` of a collection is readable inside (the oracle's `toString`), so `spit` of a list of
  records writes what `read` reads back -- the book's `concurrency.clj` backup, whose
  test namespace is byte-identical to the oracle on the interpreter and the JVM.
- Cost (2026-10-03, `(prn (read-string "[1 \"a\"]"))` against `(prn [1 "a"])`): wasm 32,204 ->
  103,678 B, class 61,618 -> 176,613 B. Of the wasm, ~20 KB is the regex parser (a `#"..."`
  in the input compiles at read time), ~13 KB the number parser's ratio and bignum
  arithmetic, ~3 KB `intern`. Re-measured the same day after the shared `%decimal-double`
  replaced the Lisp IEEE-bits conversion: wasm 118,226 -> 115,286 B, class 190,861 -> 186,835 B
  (the plain `(prn [1 "a"])` 32,463 -> 32,112 B, class unchanged).

## Reader conditionals

**`#?(...)`/`#?@(...)` read like the oracle's `LispReader.ConditionalReader` where the
oracle reads them -- a `.cljc` file (`Compiler.load` decides by the file name, so
`ClojureReader(source, file)` does), a session (clj's REPL reads `{:read-cond :allow}`), and
`read-string`/`read` under `{:read-cond :allow}` -- and are `Conditional read not allowed`
everywhere else (a `.clj` file, text without a file, `deps.edn`).** Both readers implement
one algorithm (`ClojureReader.readConditional`, `%clojure-rd-conditional`):
- The first feature the reader has takes its form; after a feature not taken, and after the
  taken form, every form up to `)` reads SUPPRESSED and drops, unpaired (so `#?(:clj 1
  :cljs)` reads, `#?(:clj)` is `read-cond requires an even number of forms.`). Suppressed,
  a tagged literal (`#js`, `#inst`, a record literal) and `#=` read their form and build
  nothing; every other syntax error still signals, as the oracle's (`#{1 1}`, `##Foo`,
  nested `#()`). A taken-nothing conditional is a discard (`DISCARD`/`:C%READ-SKIP`), so a
  session buffer ending in one waits for the next datum, like clj's REPL.
- A splice pushes the members onto the PENDING forms every read takes first (the oracle's
  `pendingForms`): a list read shares its enclosing one, a quote/deref/var/meta/discard
  makes one at the top level and drops what is left there. So `(a '#?@(:clj [x y]) b)` is
  `(a (quote x) y b)`, `'#?@(:clj [x y])` is `(quote x)`, a splice inside a taken branch
  keeps its first member; a splice of a non-list (map, set, regex, scalar) is `Spliced form
  list ... java.util.List`, then one at the top level `... not allowed at the top level.`
  (in that order). Refusals `Feature name :else is reserved.`, `Feature should be a keyword:
  <str of it>` (`null`, `clj`, `[:clj]`), `read-cond body must be a list`. About 80 probes
  diffed against `clj` 1.12.6 (2026-10-08) are pinned by `ClojureReaderTest`
  (`aReaderConditional*`, `aSplicing*`) and clojure-spec
  `read-string-takes-reader-conditionals-under-read-cond-allow`.
- **Features: `:rontolisp`, `:clj`, `:default`** (`ClojureReader.FEATURES`), plus a
  `:features` hash set at run time. Decided 2026-10-08 on seven Clojars libraries' 339
  conditionals (camel-snake-kebab 0.4.3, cuerdas 2023.11.09, stuartsierra dependency 1.0.0,
  instaparse 1.5.0, malli 0.16.4, medley 1.8.1, test.check 1.1.1, read with `:preserve`):
  `:clj` 288 keys, `:cljs` 242, `:bb` 11, `:default` 2. A `:cljs` front end is structurally
  wrong, not just interop-heavy: the `:clj`-only branches (96) hold the `defmacro`s a cljc
  library defines for itself, and the `:cljs`-only ones (50) `(:require-macros ...)`,
  `goog` requires and `js/` calls this front end has no counterpart for. Of the branches,
  `:clj` ones were Java interop by a symbol heuristic 165 times against 123 portable,
  `:cljs` ones 74 `js`/`goog`/`.-` against 168 -- but a "portable" cljs branch still names
  cljs protocols (`ILookup`, `-invoke`). `:clj` also keeps the oracle's answer for every
  branch. The own key is babashka's shape (`:bb` before `:clj`; malli writes
  `#?(:bb ... :clj ...)`): free for a library naming no `:rontolisp`, and the one way a
  library can say "not the JVM branch here". The order of the form decides, not the set:
  `#?(:clj a :rontolisp b)` is `a`.
- ns forms: every `:require-macros`/`:include-macros` in the seven libraries (18 lines)
  sits inside a `:cljs` branch, so none reaches `ClojureNamespaceLowering` (whose
  `:include-macros` refusal stays; the oracle ignores the option).
- **`{:read-cond :preserve}`** (oracle-checked clj 1.12.6, 2026-10-08, about 170 probes): after
  the `#?`/`#?@` and `(` checks, `%clojure-rd-preserved` reads the whole list (features never
  asked, `:else` too) into `(:C%READER-COND form splicing)`, `splicing` the Clojure boolean,
  with `%clojure-rd-cond` rebound to `:C%PRESERVING`, so a nested `#?@` is one more member
  (no pending forms) and a top-level splice reads. Only there (the oracle's `READ_COND_ENV`)
  `%clojure-rd-record` makes `(:C%TAGGED form tag)` of ANY tag -- `#js`, `#inst`, `#uuid`,
  a record literal's `#my.R`; outside one a tag keeps `No reader function`. `reader-conditional`
  and `tagged-literal` build the same (the oracle's Boolean / Symbol casts: nil is an NPE,
  else a CCE). Both are an ILookup (`:form`, then `:splicing?` / `:tag`; another key the
  default; no IFn, no meta, `^m` on one `Metadata can only be applied to IMetas`), `=` by
  kind and parts through `%clojure-equal` (a list form `=` a vector one, like Java
  `equals`), a structural key, printed `#?(...)`/`#?@(...)`/`#tag form` (print-method's
  shape; strings bare under `print`), and `instance?` of their class or `ILookup`
  (`ClojureValueClasses.Kind`). Deviations: `class` is the keyword, `str` is
  `Class@<hex of %clojure-hash>` (the oracle's `hashCode`, so its set order and `Duplicate
  key` text differ too), `#?()`'s form is nil here so a nil form prints `()`. Arms: family
  `ClojureArms.Family.READER_VALUE`, tests `%clojure-reader-value-p`/`-reader-cond-p`/
  `-tagged-literal-p` (printer, `str`, `=`, hash, structural key, `%clojure-call-keyword`,
  `getBranches`, `classForm`, `instance?`) and the reader's `%clojure-rd-preserve-p`/
  `-preserving-p`; the predicates lower to `(%clojure-is-reader-conditional x false)`, an
  alias of `progn`; producers the opts entries (`read-string`/`read` with a map, or as a
  value) and the constructors. So `(read-string s)` drops the preserve clauses (the old
  refusal with them) and a program reading nothing never had them; the
  docstrings of the touched pre-existing defuns are unchanged. Pins: clojure-spec
  `read-cond-preserve-reads-reader-conditionals-and-tagged-literals`,
  `reader-conditional-and-tagged-literal-values-look-up-compare-and-print`,
  `ClojureArmsTest#theReaderValueFamilyFoldsThePredicatesAndTheReaderArmsOfAProgramReadingWithoutOptions`,
  `ClojureLibraryTest#aProgramReadingWithoutOptionsSplicesTheReaderWithoutItsPreserveClauses`.
  Deviations kept (error cases only):
  `::alias/kw` of an unknown alias in a branch not taken reads (the lowering never sees it;
  the oracle refuses at read), the runtime reader splices `#?@(:clj nil)` as nothing (`()`
  and `nil` read alike there) and takes `:features` as a hash set only.
- A symbol's `'` is a constituent (`coll'`, `a'b`) and a number stops at it, as in the
  oracle's token and number readers (`ClojureReaderTest#aQuoteInsideASymbol*`); medley
  and dependency spell `coll'`/`g'` and were unreadable before. First failure per library
  after this (interpreter, 2026-10-08): medley `transients are not supported yet: assoc!`,
  dependency `infinite range is not supported`, camel-snake-kebab `extend needs a core
  type, not Pattern`, cuerdas `unknown namespace: clojure.core` -- lowering gaps for `e43`,
  none in the reader.

## Instants and UUIDs

**`#inst` and `#uuid` read through the oracle's default data readers in source, under
`read-string`/`read` and in `clojure.edn`, into values of this front end's own on all four
backends** (oracle-checked clj 1.12.6 on JDK 25, default time zone UTC, 2026-10-08):
`(:C%INST ms)` the oracle's `java.util.Date`, `(:C%TIMESTAMP ms nanos)` a `java.sql.Timestamp`
(`ms` its `getTime`), `(:C%CALENDAR ms offset zone)` a `GregorianCalendar` (offset in
minutes, `zone` the oracle's id `GMT-00:00`, which `=` compares: `-00:00` is not `Z`),
`(:C%UUID msb lsb)` a `java.util.UUID` (signed halves). Tagged wrappers, so the seq verbs
refuse them and `equal` decides `=` and keys them, but for `(= date timestamp)` (true by
milliseconds; the reverse false, the oracle's one-sided `equals`).
- Timestamps (`clojure.lisp` "Instants and UUIDs", Java twin `ClojureDefaultReaders`): the
  oracle's pattern matched by hand, deepest component level first and backing off while what
  follows is no `Z`/`+hh:mm` offset reaching the end (`2020-05:30` is 2020 at -05:30; `\d` is
  ASCII); `validated`'s nine checks in its order and words; the instant computed as the
  oracle's lenient `GregorianCalendar` computes it: time of day first (`23:59:60` carries),
  the day Julian before 1582 and Gregorian after, in 1582 Gregorian from the cutover day
  -141427 on and Julian before it (`1582-10-10` is the Julian date of `10-20`), the zone
  `GMT+` unless the sign is negative. Fields back out the same way (year of the era: year 0
  prints `0001`). Printing is `print-date`'s UTC `yyyy-MM-dd'T'HH:mm:ss.SSS-00:00`, nine
  nanosecond digits for a Timestamp, a Calendar at its own offset; `str` is `Date.toString`
  in UTC (the oracle's JVM zone; deviation), `Timestamp.toString`, and a Calendar's printed
  form (deviation: the oracle dumps its fields).
- A source literal is read at read time, positioned after its string like the oracle's
  error, into the marker `(%inst ms)`/`(%uuid msb lsb)`; the lowering
  (`ClojureDefaultReaders.construction`, also under quote, syntax-quote and a macro's
  decoded answer) builds `(%clojure-make-inst ms')` where `ms'` is read(print(ms)): the
  oracle's compiler embeds a `Date` constant by `RT.printString` and `readString` at class
  init, so a BC date comes back AD (`#inst "0000-01-01"` is year 1) and one past 9999 fails
  when the code runs (lowered to the run-time read of the printed text). A macro answering a
  Timestamp or Calendar decodes to a Date the same way, and so does one answering a HOST
  `java.util.Date`/`Timestamp`/`Calendar` or `UUID` (`ClojureMacroLowering.decodeHostValue`:
  the oracle's `print-dup` prints `#inst`/`#uuid`, read back here as the own values). A form
  the default reader refuses stays pending on the first read, since a data reader of the tag
  may take it ("Data readers"); the second read refuses it, at the same position.
- UUIDs: `UUID.fromString`'s lenient read (at most 36 chars, exactly four dashes, groups
  parsed like `Long.parseLong(s, b, e, 16)` -- optional `+`, overflow past 2^63-1 -- and
  masked), its `IllegalArgumentException`/`NumberFormatException` texts for `#uuid` and nil
  for `parse-uuid`. Deviation: ASCII hex only (`Character.digit` takes any `Nd` and fullwidth
  letters). `random-uuid` is 16 `rontolisp:random-bytes` with the version/variant bits.
- Families `INSTANT` and `UUID` (`ClojureArms`): arms in the printer, `str`, `=` (the
  Date/Timestamp clause), `compare` and `%clojure-comparable-p`, `%clojure-hash`,
  `%clojure-class-name-of`, `inst?` (`%clojure-is-inst`'s disjunct), the `class` and
  protocol-tag branches (`ClojureDispatchLowering.timeValueClassBranches`), `instance?`'s
  kinds (`ClojureValueClasses` DATE/TIMESTAMP/CALENDAR/UUID) and the instance-call rows
  (`getTime`, `before`/`after` by milliseconds, `compareTo`, the UUID halves, `version`,
  `variant`). `uuid?` lowers to `(%clojure-is-uuid x "java.util.UUID")`, an alias of the
  host test. Producers: the two constructors, every read entry (`Reads.ENTRIES`), the
  `clojure.instant` kernels, `random-uuid`/`parse-uuid`. Inside the runtime a type test is
  inline (`(eq (car b) :C%UUID)`), never a family test in a position the strip cannot fold.
  Measured 2026-10-08 against the parent build, wasm P1 / `--optimize=size` / component / JVM
  class: 15 programs naming none (printing, `compare`, `sort`, `class`, `instance? Comparable`
  of a literal, `defmulti` on `String` and `Object`, `extend-protocol`, `ancestors`, `inst?`,
  `uuid?`, `format`, structural keys, `clojure.walk`) plus `demo.clj` and `ring-hello.clj`
  byte-identical; `(.getTime x)` -3 B wasm (the row makes the method known, so the refusal is
  the oracle's `No matching field found`).
- Cost: a program that reads at run time carries both default readers. `(prn (read-string
  "[1 {:a 2}]"))` plus a `defn`: 203,543 / 158,350 / 205,980 / 209,346 -> 240,221 / 178,890 /
  242,622 / 250,512 B (`clojure.edn` likewise, 227,283 -> 263,608 wasm). Stubbing one piece at
  a time (wasm P1): both readers 21 KB (the timestamp match 5.8, `validated` 2.9, the
  calendar arithmetic 3.8, the UUID read 4.8), the printer's field split 5.8, `str` 3.1, the
  UUID spelling 1.5; Timestamp and Calendar together ~2 KB (no family of their own). `(prn
  #inst "2020-01-01")` 49,040 B, `(prn #uuid "...")` 45,146 B, `(prn (random-uuid))` 46,969 B.
- Classes: `class` answers `:java.util.Date` / `:java.sql.Timestamp` /
  `:java.util.GregorianCalendar` / `:java.util.UUID`. `ClojureClassBases` tables the four
  classes and `Calendar` (`TIME_VALUE_SUPERS`, roots, interfaces), so a `defmethod` on
  `java.util.Date` dispatches a Timestamp through `isa?` (spelling Date records the Timestamp
  row: `timeValueSubclassesOf`; `Object` and the interfaces record none, so `ancestors` of a
  class the program never spells answers nil, a deviation). `extend-protocol` takes the four
  classes (`TIME_VALUE_DISPATCH`, `Calendar` to `GregorianCalendar`), by exact tag; a protocol
  extended to `java.util.Date` reaches a Timestamp through the walk ("Dispatch", walked
  classes; a deviation until 2026-10-08).
- `inst-ms`/`inst-ms*` read a Date or Timestamp, a host `Date`/`Instant` through the host
  arms, else the oracle's `No implementation of method: :inst-ms* of protocol:
  #'clojure.core/Inst found for class: C`. A host Date or UUID stays a host object: never `=`
  to a read one (deviation), and `(java.util.Date.)`, `UUID/randomUUID` and their kin stay
  `java:` calls, refused on wasm (`e76`).
- `clojure.instant` (parse-timestamp, validated, read-instant-date/-timestamp/-calendar)
  and `clojure.uuid` ship as startup namespaces ("clojure.jar namespaces") over the
  kernels `rontolisp.internal.instant` (`parse`, `validate`, `read-date`, `read-timestamp`,
  `read-calendar`); the runtime reader calls `%clojure-instant-read-date` directly.
  `clojure.uuid`'s one var is the private `default-uuid-reader` over
  `rontolisp.internal.uuid/read-uuid` (`%clojure-read-uuid`), which `default-data-readers`
  names. A data reader of `inst` or `uuid` reads it first ("Data readers").
- Pins: clojure-spec `inst-literals-*`, `uuid-literals-*`, `inst-and-uuid-*`,
  `read-string-and-clojure-edn-read-inst-and-uuid-like-the-oracle`,
  `clojure-instant-parses-validates-and-reads-three-instants` (all four backends,
  oracle-identical); `ClojureDefaultReadersTest` (the pattern, the calendar, the printed and
  `toString` forms and `UUID.fromString` against the JDK over seeded random inputs, the Java
  half 20,000 each and the Lisp half 1,500 each on the interpreter);
  `ClojureReaderTest#instAndUuid*`, `ClojureArmsTest#theInstantAndUuidFamilies*`,
  `ClojureLibraryTest#aProgramMakingNoInstantOrUuid*`,
  `ClojureInteropTest#instMsReadsAHostDateOrInstant*`.

## Data readers

**A tag a `data_readers.clj`/`.cljc` maps calls its var while the source is READ, after the
datums above it lowered, like the oracle's form-by-form `load`; its answer stands in the
literal's place, decoded like a macro's.** Measured on `clj` 1.12.6.1673, 2026-10-08
(fixtures over a `:local/root` directory, a `:local/root` jar and the project's own `src`):
- Startup (`load-data-readers`): every `data_readers.clj` on the classpath in order, then
  every `.cljc` (`:read-cond :allow`), ONE form read each (a second is ignored, none is `Not
  a valid data-reader map`); keys symbols (`Invalid form in data-reader file`; an
  unqualified tag is accepted), values qualified symbols (`no conversion to symbol`, a
  number a `Named` cast), a repeated key the reader's `Duplicate key`; a tag two files give
  different vars `Conflicting data-reader mapping` (the same var twice is fine). The var is
  INTERNED in a created namespace, not loaded: `find-ns` finds the namespace, a call before
  something loads it is `Attempting to call unbound fn: #'ns/name` -- a tag above the
  `require`, in a `#_` discard (the oracle reads the discarded form, reader call included),
  of a var the namespace lacks. Errors read `Syntax error reading source at (f:l:c)`,
  positioned after the literal's form.
- Answers: data (record, symbol, false, pattern, `#inst`, `#uuid`, set, char, ratio,
  keyword, metadata kept) is the value; a list or symbol is code (`(list 'inc x)` runs, a
  lazy seq of numbers is a call of a number); `nil` is the dispatch reader's `No dispatch
  macro for: c`; an atom, a host object without `print-dup` (a `LocalDate`), a closure
  `Can't embed object in code ...` (a closure's `No matching ctor`).
- `*data-readers*` prints `{tag #'ns/name}` (`#:ns{...}` when every tag shares one); a
  `binding` of it reaches `read-string`/`read`, not a literal inside the `binding` (read
  first); a top-level `set!` reaches the file's later forms; `*default-data-reader-fn*`
  (bound by `clojure.main` only) takes `(tag value)` of a tag nothing else reads, after
  `#inst`/`#uuid`; `clojure.edn` asks neither special; a runtime reader's exception
  propagates unwrapped; `default-data-readers` is `{uuid #'clojure.uuid/default-uuid-reader,
  inst #'clojure.instant/read-instant-date}`; `#my.ns/tag` is a tagged literal (a record
  only when the NAME is dotted).

Mechanics:
- **Discovery** (`ClojureSourcePath.dataReaders`, `ClojureDataReaders.of`): `FILES` over
  `roots()` (directories through `ClojureFiles.read`, jars by entry), computed in
  `resolveProject`, so a refusal is the program's first, positioned in the data readers
  file (`ClojureReader.here` for its read errors, which read with `Tags.NONE`: only the
  defaults). The map is tag -> `ns/name`, insertion-ordered.
- **Two reads** (`ClojureLowering.Datums`): the first read (`ClojureReader` without `Tags`)
  feeds the pre-scan; a tag other than `#inst`/`#uuid`, or one of those whose form the
  default reader refuses, reads as `(%pending-tag tag form)` (counted; unique for the
  duplicate-key check, a one-member splice). When the first read left one pending, or took
  a default tag a data reader maps, pass two reads the text again (`again(Tags)`) a datum at
  a time (`readTopLevel`), each only after the lowering lowered and handed over the one
  above -- the entry file, a required file (`loadFile`) and a session buffer alike. A datum
  whose first read was all pending (or whose defined name was) is pre-scanned again, the
  lowering's earlier kinds kept (the session's rule); `declareOne` skips a pending name. A
  pending tag that reaches the lowering (datums lowered without their reader) is `No reader
  function`. Programs without a pending tag lower as before, read once.
- **The call** (`ClojureDataReaders.read`, the `Tags` of pass two): an unmapped tag is `No
  reader function` plus `ClojureSourcePath.notSearched()` (an unfetched library may map
  it); `#inst`/`#uuid` unmapped fall to the default readers. The var: a startup shipped
  namespace loads first (`preload`, its hoisted forms ahead of the datum); a project
  FUNCTION is `(if (fboundp 'cell) (cell 'form) :C%UNBOUND-READER)` over `currentDefnSym`
  (redefinitions), a value cell `(if (boundp 'cell) (%clojure-call cell (list 'form)) ...)`,
  a `clojure.core`/known library var its value through `%clojure-call`, a macro the oracle's `Wrong number of args (1)`,
  anything else unbound. It runs in the macro-time evaluator under the reading file's
  `*ns*`/`*file*`/`*source-path*`, so it may call Java even for a wasm target, which only
  receives the decoded answer. The sentinel and a namespace never loaded are the oracle's
  unbound words; a thrown exception is `in data reader #'v: <message>`; `nil` `No dispatch
  macro for: c`; an undecodable answer `Can't embed object in code, maybe print-dup not
  defined: <str>`. The reader positions every refusal after the form.
- **Run time**: `%clojure-rd-record-of` asks `(%clojure-rd-data-readers-p)`, the arm test of
  `ClojureArms.Family.DATA_READERS` (producers: the two specials' symbols), before the
  default clauses; `%clojure-rd-data-read` is the oracle's order through `%clojure-call`
  outside the read in progress (`%clojure-rd-edn-call`). Both specials are `defvar`'d in
  `clojure.lisp` (the interpreter keeps every arm); a program with data readers that reads
  at run time or names `*data-readers*` gets the files' map as its root
  (`ClojureDataReaders.noteRuntimeReads` -> `ClojureLowering.dataReadersRoot`, a
  `defparameter`, since a compiled program's library `defvar` runs first): each var a
  program var's root, a core or known library var's value, else `%clojure-unbound` (a
  session probes `fboundp`/`boundp`). A program naming neither special and reading nothing
  through data_readers files splices the reader without the arm (`ClojureLibraryTest`).
- `default-data-readers` is a value row (`ClojureDataReaders.defaults`), loading
  `clojure.uuid`/`clojure.instant`; `find-ns` finds the data readers' namespaces
  (`knownNamespaces`).

Deviations (kept): an answer loses its metadata and needs a datum here -- a function, a
deftype instance, a host object other than a `UUID`/`Date`/`Calendar` is refused where the
oracle compiles what its `print-dup` prints (`decodeDatum` is the macro answer's decoder; a
deftype has no literal, a host value no spelling on wasm); `()` is `nil`, so an empty-list
answer is `No dispatch macro`; a `set!` of `*data-readers*`/`*default-data-reader-fn*`
reaches run-time reads only (the macro-time evaluator runs no statement, and the source
reader's map is the files'); the entry file's own root's `data_readers` files count (it is a
root here); a refusal names `in data reader` where the oracle prints the bare message.
Pins: `ClojureDataReadersTest` (the project on all four backends, the answer kinds with
host-calling readers on wasm too, the unbound/discard/missing/`No reader function` positions,
the inst override, the files' refusals, a session, the unfetched note), clojure-spec
`data-readers-read-a-tag-at-run-time-like-the-oracle` (all four backends, oracle-identical),
`ClojureReaderTest#aFirstReadLeavesATaggedLiteralToTheDataReaders`,
`#aTagIsARecordClassOnlyWhenItsNameIsDotted`, `#theDataReadersReadATagAfterItsFormLikeTheOracle`,
`ClojureLibraryTest#aProgramNamingNoDataReaderSplicesTheReaderWithoutItsDataReaderClause`.

## Vars and metadata

**A var is `(:C%VAR "ns/name" getter)`, interned per name in `%clojure-var-table`.**
`ClojureVarLowering.varOf` lowers `#'x`/`(var x)` to `(%clojure-var "ns/x" (lambda () ROOT)
META)`; each evaluation re-points the interned var, so `=` holds. ROOT is the name's value
as the site sees it (a redefined `defn`'s current version, a value cell, a signal for a
macro); a `user` var shadowed by a local reads through a hoisted `|c%x%root|`. `lookupVar`,
not `resolveVar`: a private var is reachable, like the oracle. A local is `Unable to
resolve var`.

- **Core vars** (`coreVarOf`): a name no program var claims, or a `clojure.core/`
  spelling, is `(%clojure-var "clojure.core/x" (lambda () CORE-VALUE) META)`, the value
  `coreValueOrNull` gives `clojure.core/x`; a macro's (`ClojureCoreNames.MACROS`, the
  oracle's 79) root signals `Can't take value of a macro`. META is `:name`/`:ns` (+
  `:macro`) only -- the oracle's `:arglists`/`:doc`/`:added`/position would be a table
  per core name (deviation). A core name with no value here (`#'all-ns`) keeps the
  refusal `var of a clojure.core var is not supported yet`. The core specials
  (`ClojureCoreSpecials`: the streams, `*agent*`, the flags) are `%clojure-var-dynamic`
  sites. Measured 2026-10-03 (clj 1.12.6, `clj -M file` and the REPL alike):
  `clojure.main` binds `*ns*`, the print/compiler flags, `*command-line-args*`, `*file*`,
  `*1`..`*e`, not the streams, `*print-dup*`, `*flush-on-newline*`, `*compile-files*`
  and the rarer flags -- so `thread-bound?` of `#'*out*`/`#'*in*`/`#'*err*`/`#'*agent*`
  is FALSE at the root and true under `binding`, `with-out-str`, `with-in-str` and an
  agent action, and of a main-bound flag always true. A special `clojure.main` does not
  bind has a counter (`%clojure-out-depth`, `%clojure-print-dup-depth`, ... -- defvars the
  program carries for every special it uses) that `binding`, `with-out-str`,
  `with-in-str` and `sendBuild` rebind one deeper in a `let` pair; a main-bound flag has
  none and its depth reader is `(lambda () 1)`. The pairs are arms of
  `ClojureArms.Family.STREAM_DEPTH` (a fourth arm shape: the pair goes, and a mention of
  the counter anywhere else is the producer), so a program with no such site compiles
  byte-identically. Measured 2026-10-03: a `with-out-str` + `binding [*out*]` + `send`
  program, `(prn [1 "a"])` with an IFn set lookup, a dynamic var's `thread-bound?` and
  `examples/clojure/demo.clj` are byte-identical as wasm, `--optimize=size`, component and
  class; adding `(println (thread-bound? #'*out*))` to the first: wasm 43,601 -> 48,273 B,
  class 81,986 -> 87,664 B. Pinned by clojure-spec
  `core-vars-read-their-core-value-and-the-stream-binding-depth`,
  `core-flag-specials-read-bind-and-assign`, `err-is-the-error-stream-and-rebinds-like-out`,
  `ClojureArmsTest#theStreamDepthFamilyDropsTheRebindingPairsOfAProgramReadingNoCounter`,
  `ClojureLibraryTest#aProgramReadingNoStreamDepthShedsTheRebindingPairs`.

- Metadata is recorded at lower time (`ClojureVarLowering.record`, kept across session
  buffers) in the oracle's order: `:arglists`, the name's reader metadata, `:private`,
  `:doc`, the attr map, `:line`/`:column`/`:file`, `:name`, `:ns` (a symbol), `:macro`.
  Constant values stay a datum at each `#'` site; evaluated ones are stored into
  `|c%x%meta|` ahead of the definition. A site above a redefinition sees the older
  metadata. Anything other than `def`/`defn`/`defn-`/`defmacro` carries only
  `:name`/`:ns`. `test` calls `(:test (meta v))`. `var-get` (measured 2026-10-04: `unknown name: var-get`) is
  `%clojure-var-root` (`ClojureVarLowering.getOf`, value `-v`): the root of a var, and unlike
  `deref` (atoms, reduced values) a signal for anything that is no var, like the oracle.
- **Unbound vars.** A declared-never-defined name and a value-less `def` are the oracle's
  unbound var: the value cell holds `(:C%UNBOUND "ns/name")` (`%clojure-unbound`), truthy,
  `str` `Unbound: #'ns/name`, printing `#<Unbound: #'ns/name>` (the oracle's `#object` has a
  hash), calling it `Attempting to call unbound fn: #'ns/name`; `bound?` reads the root
  through the var, `defonce` of a name in `unboundCapable` treats it as unbound. A file is
  closed, so `ClojureLowering.lower` stores every root the lowering met
  (`unboundRoots`) right after the false binding and the site lowers to nothing -- a
  `declare` or value-less `def` never touches a bound root, a definition below the site
  still finds it. A session stores it at the site under `(unless (boundp ...))`, and reads
  a `Kind.DECLARED` name as `(if (fboundp 's) #'s s)`, since a later buffer may define it
  either way. `declare` records `:declared true` after the name's metadata (the oracle's
  order); a `^:dynamic` declare joins `dynamicVars` with its declaim and counter hoisted.
  A function's or macro's name declared too gets no root (its value is no value cell); a
  `#'f` deref above the `defn` answers the function where the oracle's is unbound (kept).
  **Arms**: every library test of the root (`%clojure-unbound-p` in the printer, `str`,
  IFn, `bound?`) and `defonce`'s is an arm of `ClojureArms.Family.UNBOUND`, whose producer
  is `%clojure-unbound`, so a program making no unbound var compiles byte-identically.
  Measured 2026-10-03 (wasm / class bytes, before -> after): `(prn [1 "a"])` 32,313 /
  64,141 unchanged, `(let [s #{:a}] (println (s :a)))` 63,546 / 85,453 unchanged; the
  printer arm alone, unstripped, cost every printing program 234 / 380 bytes and the IFn
  arm 210 / 419. `(declare y) (def v #'y) (println (bound? v) (str @v))` 45,395 / 79,399
  (did not compile before); reading `:macro` costs `bound?` users ~2.8 KB wasm, against
  59 / 177 bytes in every IFn program for a NIL-getter macro var (`%clojure-var-get`
  testing it).
  Pinned by clojure-spec `unbound-vars-answer-the-unbound-root`,
  `def-without-a-value-is-unbound-and-defn-is-called-directly`,
  `ClojureSessionTest#aDeclaredNameStaysOpenForALaterBuffer`,
  `ClojureLibraryTest#aProgramMakingNoUnboundRootSplicesTheLibraryWithoutItsUnboundArms`,
  `ClojureArmsTest#theUnboundRootFamilyStripsOnlyItsOwnArms`.
- **Streams as values.** At the root `*standard-output*`/`*standard-input*` hold the `t`
  designator, the same object as Clojure's `true`, so `(prn *out*)` printed `true` on all
  four backends. A read of `*out*`/`*in*` as a VALUE (`ClojureLowering.specialRead`: the
  name, `clojure.core/` spelling, a core var's getter; never a `binding`/`set!` target)
  lowers to `(%clojure-out)`/`(%clojure-in)`: the bound stream, or at the root one
  library stream value over `t` itself, `(%obj-new '%stream t :standard-output)` /
  `:standard-input` (`LispLayout.Kinds`). Every operation resolves it back to `t`
  (`%stream-target` answers the handle slot), so no backend learned a handle: measured
  2026-10-04, read/write/`binding`/`.readLine`/`line-seq` over it agree on all four, where
  the plan of a `%STREAM` over handles 1/0 would have needed the JVM's `emitStderrBranch`
  treatment for both and a second stdin buffer on wasm. The kinds keep `=` apart and give
  the direction predicates their answer (`Environment.streamDirection`,
  `expandStreamDirectionP`). `LispMacroExpander.mayCreateStreamValues` counts a literal
  `(%obj-new '%STREAM ...)` as a producer (the library value names no constructor).
  The printer and `str` spell a stream as the oracle's `#object[C "toString"]` WITHOUT the
  identity hash, `str` that toString (`%clojure-stream-class`/`-string`/`write-stream`):
  `:standard-output` `java.io.OutputStreamWriter`, `:standard` (`*err*`)
  `java.io.PrintWriter`, `:string-output` `java.io.StringWriter` (toString the text so
  far, read before anything is written since the target may be the stream itself),
  `:standard-input`/`:string-input` `clojure.lang.LineNumberingPushbackReader` (the
  oracle's `with-in-str`; its PushbackReader over a StringReader deviates), `:file`
  `java.io.BufferedReader` (`clojure.java.io/reader`). **Arms**: the printer's and
  `str`'s `%clojure-stream-p` tests are `ClojureArms.Family.STREAM`, whose producers are the
  Clojure-only wrappers a stream reaches a program through -- `%clojure-out`/`-in`/`-err`,
  `%clojure-string-writer` (`(StringWriter.)`), `%clojure-string-reader` (a reader over a
  StringReader), `%clojure-reader`, and the io kernels opening a file
  (`ClojureIoLowering.STREAM_PRODUCERS`, "clojure.java.io") -- never `open` or
  `make-string-output-stream`, which `ClojureLibrary.references` would read as a library
  reference in a Common Lisp program; `with-out-str`'s own stream reaches a value only
  through a read of `*out*`. Measured 2026-10-04 (wasm / class bytes, before -> after):
  `(prn [1 "a"])` 32,305 / 64,141 and `(println (with-out-str (print 1)))` unchanged,
  `(println [1 2] (str 3 :k))` 36,549 / 70,622 -> 36,490 / 70,060 (`str`'s old
  `string-stream` arm was unconditional), `examples/clojure/demo.clj` 91,190 / 131,494 ->
  91,151 / 130,914; a StringWriter read back with `str` 37,539 / 78,733 -> 37,841 /
  80,743, `(binding [*out* *out*] (println 2))` 9,910 / 72,296 -> 11,528 / 77,211 (the
  stream value turns the instance runtime and the per-operation unwrap on). Pinned by
  clojure-spec `a-stream-prints-as-the-host-object-of-its-kind`,
  `ClojureArmsTest#theStreamFamilyFoldsThePrinterArmOfAProgramMakingNoStream`,
  `ClojureLibraryTest#aProgramMakingNoStreamSplicesTheLibraryWithoutItsStreamArms`, the
  direction lines of `StringStreamPrograms`.
  **`class` of a stream** (measured 2026-10-04: every one signalled `class needs a value of
  a known kind`, `(class *out*)` answered `:boolean`) is an arm of `ClojureDispatchLowering.classForm`
  on the same `%clojure-stream-p` test, so the same family sheds it: the keyword of
  `%clojure-stream-class`, `:java.io.OutputStreamWriter` where the oracle's class prints
  `java.io.OutputStreamWriter`, like an exception's class keyword. A program with no stream
  producer compiles `(prn (class 1))` with no mention of it (wasm bytes: no
  `OutputStreamWriter` string). Pinned by clojure-spec
  `class-of-a-stream-answers-the-host-class-of-its-kind`, `ClojureInteropTest#filesRoundTripThroughReaderAndLineSeq`
  and `ClojureWasmFileIoTest` (the file reader), `ClojureLibraryTest#classOfAStreamIsAnArmAProgramMakingNoStreamSheds`.
  `defmethod` on a stream CLASS SPELLING (`java.io.StringWriter`) is still the "needs a core
  class" refusal (a keyword dispatch value works).
- **with-redefs.** A `defn` is a direct `defun` call, so a replaced root would never
  reach a call site; the design is the oracle's own direct-linking opt-out. A var is
  REDEFINABLE (`ClojureLowering.redefinable(key, nameDatum)`) when its key is in
  `redefinable` (seeded by an earlier pass), its simple name is in `redefNames` (the
  pre-scan of every lowered file, `scanRedefinitions`: every `with-redefs` binding name
  at any depth, namespace dropped, so a test file's `(with-redefs [alias/f ...])` reaches
  the namespace it requires next), or its name carries `^:redef`. A redefinable `defn`
  pre-declares and lowers as `Kind.VARIABLE`, keeps its `defun` (recur and arity helpers
  stay direct) and adds `(setq var #'fn)` (`ClojureBindingLowering.redefCellStore`, also
  allowed in `defnInBody`); it is never in `globalDirectFuns`, so calls go through
  `%clojure-call` (a stub may be a map or keyword). A redefinable `def`/`defonce` drops
  out of `globalDirectFuns` the same way. A program naming no such var lowers
  byte-identically. `withRedefsOf` evaluates the values in order, saves each distinct
  var's value cell, sets, runs the body behind the `try` barrier and restores in an
  `unwind-protect`; a later pair of one var wins, a trailing name is resolved only. A
  target the lowering already lowered as a direct call (a `defn` of a namespace loaded
  before the file naming it was scanned) goes into `redefMisses`; `ClojureLowering.lower`
  then lowers the whole program again with those keys seeded (reusing the resolved
  `ClojureSourcePath`), and stops when a pass adds no new key -- at most one extra pass
  in practice, none without such a target. The macro evaluator sees the first pass's
  definitions again (a macro that prints while expanding prints twice). A session cannot
  re-lower an evaluated input: there such a target is refused, naming `^:redef`. Refused
  too: a local and an unknown name (`Unable to resolve var`, the oracle's), a macro, a
  `clojure.core` var (its verbs lower inline; the oracle's inlined `inc` ignores the
  redef too, its `println` does not), and a key still `FUNCTION` after the reseed (a
  multimethod, protocol method, record constructor, test). Deviation: inside a `binding`
  of a `^:dynamic` var the `setq` changes the binding (the oracle changes the root; CL has
  no portable global-value write under a dynamic binding). Measured 2026-10-08 (clj
  1.12.6) and pinned by clojure-spec `with-redefs-replaces-var-roots-for-the-body`,
  `ClojureProjectNamespacesTest#aWithRedefsReachesTheCallsOfANamespaceLoadedBeforeIt*`
  (the reseed, all four backends), `ClojureControlLoweringTest`.
- `with-meta`/`vary-meta` answer a shallow copy recorded in the eq table
  `%clojure-meta-table`; `meta` reads it. IObj kinds only (a string, number, keyword,
  boolean, atom, deftype or pattern signals; a symbol answers itself).
- Reader `^m x` reads as `(%with-meta x m)` (a head no Clojure call spells; `#^` reads the
  same): dropped everywhere except on a collection literal, where it attaches without a
  copy. A type hint never reaches `with-meta`. Layers merge outer-wins; a keyword is
  `{:k true}`, a symbol or string `{:tag x}`. Quoted data strips it. On a definition's
  name, `^:dynamic` and `^:private` (bare or a non-false map entry) are read.

## State

- `binding` is a `let*` over the bound names, sequential, rebinding only `^:dynamic` vars
  and the core specials (the oracle's non-dynamic error otherwise). A `^:dynamic`
  `def`/`defonce` is a `defparameter` plus a zeroed `%bound-depth` counter special (two
  top-level forms, for `SpecialVarCollector`); a `^:dynamic` `defn` keeps its `defun` and
  adds a `defparameter` of the function, so calls go through the value cell (`recur` and
  the arity helpers stay direct). Each `binding` rebinds the counter one deeper.
- `thread-bound?` reads that counter through the var: the `#'x` site of a `^:dynamic` var
  lowers to `%clojure-var-dynamic`, which adds `(lambda () counter)` as a fourth element of
  `(:C%VAR name getter depth)`; every other var site is the plain three-element `%clojure-var`,
  so a program with no dynamic var is unchanged (wasm, `(var? #'x)` on a plain var: 21473 bytes
  before and after; one dynamic var plus a `#'` of it: 22026 -> 22106, 2026-10-03). A var without the element (non-dynamic) or
  at depth zero is not thread-bound; a non-var signals only when reached. A core
  special's site reads its counter, or is always bound ("Vars and metadata", core vars).
- `set!` of a dynamic var: past depth zero `setq`, at zero the oracle's `Can't
  change/establish root binding of: x with set` -- the counter has dynamic extent, so a
  callee outside the binding's lexical extent still sets it. A core special with a
  counter (`*out*`, `*err*`, `*print-dup*`, ...) assigns the same way; a main-bound flag
  is a plain `setq` anywhere, like the oracle's top-level `(set! *print-length* 2)`
  (`*ns*`, `*file*`, `*1` included); anything else is refused.
- The flags hold the oracle's `clj -M` root (`ClojureCoreSpecials`: `*data-readers*`
  `{}`, `*command-line-args*` `(cdr (%host-argv))`, `*clojure-version*` 1.12.6,
  `*compile-path*` `"classes"`, ...).
- **The load's specials** (measured 2026-10-04, clj 1.12.6: `*ns*` is the namespace `ns` /
  `in-ns` switch to WHERE THEY RUN and a function reads its caller's; a required file runs
  with `*ns*` rebound, `*file*` its root-relative path `app/where.clj`, `*source-path*`
  `where.clj`; the entry's `*file*` is `getAbsolutePath` of the argument; all main-bound but
  `*repl*`). `*ns*` is the special `%clojure-ns` over an interned namespace value
  `(:C%NS-OBJECT "name")` (`clojure.lisp` "Namespaces": printer `#object[clojure.lang.Namespace
  "user"]` without the hash, `str` the name, `class` `:clojure.lang.Namespace`; `the-ns` /
  `find-ns` / `ns-name` take the names created above the call, `ClojureLowering.knownNamespaces`,
  since namespaces exist only at lower time). `ns` and `in-ns` lower to a `(setq %clojure-ns
  (%clojure-ns-object "x"))` statement (`nsSwitch`; `in-ns` `(progn SWITCH nil)`), the require
  site runs an init under `(let ((%clojure-ns %clojure-ns) (%clojure-file "a/b.clj")
  (%clojure-source-path "b.clj")) (funcall init))` (`ClojureLowering.loading`), and a macro
  expands under the same three bound to its site. Roots: `user`, `ClojureSourcePath.entryPath`
  / `entryName` (`NO_SOURCE_PATH` / `NO_SOURCE_FILE` without a file). The switches, the pairs
  and the defvars are arms of `ClojureArms.Family.NS_SWITCH` / `FILE_SWITCH` /
  `SOURCE_PATH_SWITCH` (the fifth arm shape, `Family.switches`: a let left without a pair is
  its body, a progn with one form that form); any other mention of the special keeps them, so
  a program reading none compiles byte-identically -- an init holding only a switch is no init
  (`hasInit`), and init chunks cut by the switch-free print length. Measured 2026-10-04 (wasm,
  `--optimize=size`, component and class bytes all md5-identical before -> after):
  `(prn [1 "a"])`, an `ns` + `in-ns` program, a `try`/`class` program,
  `examples/clojure/demo.clj` and a `deps.edn` project requiring a printing and a
  definitions-only namespace. Reading one: `(println (str *ns*))` wasm 37,458 / class 65,856
  (`(println (str "user"))` 36,296 / 64,327), `(prn *ns*)` 33,157 (`(prn [1 "a"])` 32,305),
  `(println *source-path*)` 16,879 (`(println "u3.clj")` 16,840). Deviations: `set!`/`binding`
  of `*ns*` do not move the lowering's namespace; `in-ns` answers nil; a compiled `*file*` is
  the compile-time path. `*repl*` has a counter (false and unbound in a file; a session defines
  it true with the counter at 1); `*1`/`*2`/`*3`/`*e` are main-bound nil roots
  (`%clojure-history-N`, with library defvars) that a session records into (see "A session").
  Pinned by clojure-spec `ns-is-the-current-namespace-and-the-load-specials-have-their-values`,
  `ClojureProjectNamespacesTest#aLoadingFileReadsItsOwnNamespaceAndPath*` (all four backends),
  `ClojureArmsTest#theSwitchFamiliesDropTheSwitchesOfAProgramReadingNoLoadSpecial`,
  `ClojureLibraryTest#aProgramReadingNoLoadSpecialShedsTheirSwitches`.
- `assert` reads `*assert*` at lower time, like the oracle's macroexpansion:
  `ClojureLowering.assertEnabled`, set by a TOP-LEVEL `(set! *assert* literal)`
  (`topLevelsOf`, so a required namespace's too, and a REPL input's for the next input;
  `ClojureProtocolLowering.assertSetTo`), makes every later `assert` lower to `nil`. A
  `set!` inside a function or to a computed value is not seen (the oracle's takes effect
  when it runs); a `binding` around an expanded `assert` changes nothing on either side.
  Pinned by clojure-spec `assert-reads-the-assert-flag-where-it-expands` and
  `ClojureSessionTest#aSetOfAssertInOneBufferSwitchesOffTheAssertsOfTheNext`.
- The printer honours `*print-length*`, `*print-level*` and `*print-readably*`: specials
  `%clojure-print-length`/`-level`/`-readably` with library defvars too (the interpreter
  loads the library lazily, after the program's own). `%clojure-print` passes `readable`
  through the view `%clojure-print-readable` (false makes pr write like print, pr-str and
  str of a collection included); `%clojure-write` gains the test arms
  `%clojure-print-deep-p` (a collection at `%clojure-print-depth` >= level prints `#`,
  checked before a lazy seq realizes) and `%clojure-print-cut-p` (more members than the
  length: `%clojure-write-cut` writes the first n and `...`, realizing a seq only that
  far), and every member write goes through the alias `%clojure-write-nested` (one level
  deeper). Family `ClojureArms.Family.PRINT_FLAGS`, producers the three specials, so only a
  program naming a flag pays. Measured 2026-10-03 (wasm): `(prn [1 2 3])` 32,338 B,
  `(binding [*assert* false] ...)` around it 32,362 B, `(binding [*print-length* 2] ...)`
  44,066 B (the cut writer, the collection test and their helpers; a dynamic `let` in the
  library costs nothing extra). Pinned by clojure-spec
  `print-length-level-and-readably-shape-the-printer`,
  `ClojureArmsTest#thePrintFlagFamilyFoldsTheCutTheLevelTheDepthAndTheReadableSwitch`,
  `ClojureLibraryTest#aProgramNamingNoPrintFlagSplicesTheLibraryWithoutItsPrintArms`.
- `*print-meta*` (family `PRINT_META`, producer the flag's special): `%clojure-write`'s
  clause `((%clojure-print-meta-p x readable stream labels))` writes `^m ` (a lone truthy
  `:tag` as its value) on the pr side when the metadata is non-empty and answers NIL, so
  the `cond` goes on to write X -- at X's own level, before the `#` of `*print-level*`,
  like the oracle's `[^# #]`. The library's root is the literal false object `'|false|`
  (a compiled program splices the library's defvars ahead of its own, so a `nil` root
  would make `(prn *print-meta*)` print `nil`).
- `*print-namespace-maps*` (family `NAMESPACE_MAP`): `%clojure-print-ns-map-p` ahead of
  the cut clause sends a hash or sorted map whose every key is a keyword or symbol of one
  namespace to `%clojure-write-ns-map` (`#:a{:b 1}`, the cut and the order its own).
  The flag's root is TRUE, so the producer is not the flag but what makes a qualified
  key: a `(:C%KEYWORD "ns/name")` wrapper (built or quoted), a quoted `c%ns/name` symbol
  (`Family.qualifiedIdents`; a qualified name in code is a call, not a value), or a library
  function building a keyword/symbol from a computed spelling or calling one (the
  constructors, `find-keyword`, the reader). The last list is pinned by
  `ClojureLibraryTest#everyLibraryFunctionBuildingAKeywordOrSymbolFromAComputedSpellingMakesANamespaceMap`,
  which walks the library's call graph from every `intern` and computed `:C%KEYWORD`.
- Measured 2026-10-04 against the parent build (raw wasm / JVM class): `(prn {:a 1} [1 2 3])`,
  a `meta`/`with-meta` program, `(binding [*print-length* 2] ...)`, an `assert` plus
  `sorted-map` program and `examples/clojure/demo.clj` are byte-identical. `(prn (:a/b {:a/b 1}))`
  33,484 -> 40,418 / 62,296 -> 70,794 (a qualified literal links the lift; a first version
  through `search`/`subseq`/`equal` and the entry list was 43,754 / 77,136, the char loops of
  `%clojure-slash-at`/`-same-namespace-p`/`-write-chars` took 3.3 KB off; a symbol key's
  demangle through `%clojure-unescape-part` is 1.7 KB of the rest).
  `(binding [*print-meta* true] (prn (with-meta [1] {:a 1})))` 43,523 -> 45,370 / 69,228 ->
  71,582. Pinned by clojure-spec `a-map-whose-keys-share-a-namespace-prints-it-lifted`,
  `print-meta-writes-the-metadata-ahead-of-the-value`, `ClojureArmsTest#thePrintMetaFamily...`,
  `#theNamespaceMapFamilyIsMadeByAQualifiedKeywordOrSymbol` and
  `ClojureLibraryTest#aProgramNamingNoPrintMetaAndMakingNoQualifiedKeySplicesThePrinterWithoutTheirArms`.
- deftype mutable fields (`^:unsynchronized-mutable`/`^:volatile-mutable`; ClojureScript's
  `^:mutable` is no marker): a sixth element `(vector m1 ...)` in the deftype
  (`DEFTYPE_SLOTS`, behind the class name; absent without mutable fields), invisible to
  `.-field` and `.field`; `defrecord` refuses the markers. An
  inline method wraps its body in `(symbol-macrolet ((field (aref slots i))) ...)`
  (`.kb/symbol-macrolet.md`), so every read is live and `set!` is `(setf (aref ...))`;
  `set!` of any other local is `Cannot assign to non-mutable`. The oracle compiles
  `fn`/`#()`/`letfn`/`reify`/`lazy-seq`/`for`/`dosync`/`proxy` to classes that copy the
  fields they read, so `capturingMutableFields` rebinds those as plain locals around them.
- STM (single-threaded): `dosync` binds the depth deeper and never retries; `alter`/
  `commute`/`ref-set` go through the validator (a failed one writes nothing); `commute`
  runs once (the oracle may twice); every verb outside `dosync` signals `No transaction
  running`. Agents are synchronous: `send`/`send-off` apply at once with `*agent*` bound,
  answering the cell; `await` and `shutdown-agents` answer `nil`.

## clojure.test

The shapes are the oracle's macro expansions, lowered; the runtime is `clojure.lisp`
(`%clojure-test-*`), one code path on all four backends.

- `deftest name body` -> `(defun c%name%body () body)` (the `recur` target), `(defun c%name
  () (%clojure-test-var ...))` and a registration per namespace in definition order. The
  pre-scan registers the name as a function, so `(name)` calls it.
- `is` -> `%clojure-test-try` over a lambda, behind the `try` barrier. The assertion kind
  is the oracle's `assert-expr`: `thrown?`/`thrown-with-msg?` (bare names only); a
  PREDICATE when the head resolves to a function var (not a local, keyword, interop
  spelling, macro, `def`'d non-function, or `ClojureTestLowering.CORE_MACROS`), whose
  arguments bind first so a failure shows `(not (f values...))`; else the value kind.
- `are` substitutes its template per group at lower time; a count that does not divide is
  the oracle's message.
- Reports go to `%clojure-test-out` (the oracle's `*test-out*`), so `with-out-str` never
  captures them. `(file:line)` is a lower-time string (the form's position, the file's
  last segment or `NO_SOURCE_FILE`; an unlocated form names its `deftest`).
- `run-tests` takes symbols or strings and bakes in the namespaces seen so far, so an
  unknown one is `No namespace: x found`; `run-all-tests` filters by `re-matches`.
- Deviations: definition order (the oracle's is map order); `thrown?` matches like `catch`
  ("Catching": measured 2026-10-03, shcloj4 `examples.test.interop`: `Ran 6 tests containing
  17 assertions. 0 failures, 0 errors.` on the interpreter and the JVM, as the oracle --
  its `(thrown? IllegalArgumentException ...)`/`(thrown? ClassCastException ...)` take
  `No matching method java.lang.String.getName`, a refusal naming no class, since the
  `#^Class` hint is dropped);
  error reports print the message without a stack trace, at the `is` line; a
  failed `thrown-with-msg?` shows the message; a host `StackOverflowError` is no CL
  condition on the interpreter (`(is (thrown? StackOverflowError ...))` ends the program with
  the CLI's one-line report) and a trap on WASM (`call stack exhausted`, no catch), but the
  JVM landing is catch-any, so compiled JVM output runs the catch like the oracle. Measured
  2026-10-03 over shcloj4 `examples.test.functional`: oracle `Ran 7 tests containing 19
  assertions. 0 failures, 0 errors.`, compiled JVM the same until its self tail call became a
  jump the same day, then `1 failures`: the corpus's `(thrown? StackOverflowError (tail-fibo
  1000000N))` overflows only where a named self call keeps a frame, as the oracle's does, and
  every backend here computes the millionth Fibonacci number instead (19 s;
  [jvm-self-tail-calls.md](jvm-self-tail-calls.md)); interpreter and WASM end after the
  `Testing ...` header (the non-tail `stack-consuming-fibo` comes first). Pinned by `ClojureProjectNamespacesTest` (`aDeepNonTail...`),
  `RontoLispCliStreamsTest` (`aClojure...StackOverflow...`) and the passing shapes in
  `clojure-spec.yaml` (`functional-shapes-match-the-oracle`). Not fixed: a catchable depth
  guard on every call would have to track JIT-varying frame sizes (`interpreter-stack.md`);
  revisit only if depth guards become a product feature;
  `run-all-tests` sees only the program's namespaces.

## A session

`ClojureSession` keeps the lowering across buffers: each buffer declares its top-level
names into the session first, so a later buffer calls what an earlier one defined, and
its inits evaluate against the OLD binding (like one file). `SourceSession` prompts
`clojure> `, echoes through the `clojure.lisp` printer, and decides completeness by
bracket counting over `()[]{}` (outside strings and comments) plus a reader probe for a
trailing dispatch prefix. A buffer's `require` loads from the working directory's source
path; an `ns` buffer echoes nothing; `*ns*` carries across buffers.

The runtimes a buffer first needs (hierarchy, protocols, macros, specials, ...) travel ahead
of it, and the false binding ahead of them all: a special's root may be the false object
(`*print-meta*`), so a first buffer reading a print flag failed on an unbound
`%clojure-false` until 2026-10-08
(`ClojureSessionTest#theFalseBindingGoesAheadOfTheRuntimesOfTheFirstBuffer`).

Each input's forms evaluate as one `(handler-bind ((error #'%clojure-repl-error))
(%clojure-repl-result (progn FORMS...)))` (`ClojureLowering.evaluated`): the value rotates
into `*1`/`*2`/`*3` (an `ns` input records nil, `(progn ... nil)`), a condition is stored as
`*e` and declined, so the REPL's report is unchanged; a lowering refusal records nothing (the
oracle's compiler exception does). `class` of `*e` needs the exception reader, which a session
emits like a catching file's (`needsExceptionReader`). Pinned by
`PlaygroundReplTest#aClojureSessionKeepsItsLastResultsAndItsLastExceptionInTheHistoryVars`,
`ClojureSessionTest#anInputRecordsItsValueAndAnNsInputNil`.

The echo of a top-level `def`/`defn`/`defn-`/`defmacro`/`defmulti`/`defonce`/`defstruct` is the
var it defined (`#'user/f`, `#'foo/x`; `ClojureLowering.echoingTopLevelsOf`, appended as the
datum's last form; a file's definition shows nothing), the oracle's. `defonce` over a bound var
and `defmulti` over a held multimethod answer `nil`, like the oracle. `defprotocol` answers its
name (`P`), `defrecord`/`deftype` the class name (`my_app.R`, namespace munged), `declare` the
last name's var. A `def` nested below the datum (`(do (def v 3))`, `(println (def x 1))`)
answers its var too, through `ClojureLowering.nestedDefAnswersVar`, set for a session datum only.

A file's nested `def` keeps answering the value, not the var (oracle divergence, kept). Measured
2026-10-03, wasm-GC: building the var per site costs ~0.5 KB per site plus ~26 KB once (the
`VAR` runtime a program with no `#'x` otherwise leaves out): a 7-site program grew 10.1 KB ->
39.7 KB, 14 sites 10.2 KB -> 43.5 KB. The value of a nested `def` is read almost never, so a
size cost on every file is not worth the parity.

## `clojure.spec`: refused

`clojure.spec.alpha` stays `unknown namespace`. Its corpus users (`spec.clj`,
`hangman/specs.clj`) are non-goals and spec functions with `s/fdef`, whose value is
`instrument`/`check`; a `valid?`/`conform`/`explain` core would unblock neither, and
`explain-data` carries fn and reify objects no text comparison can pin. Revisit when an
in-scope program needs `valid?`/`conform` (then `explain-data` stays refused).

## Tests

- `clojure-spec.yaml` via `ClojureSpecE2eTest`: one case per table row or builtin group,
  concatenated into one program and sliced back per case, on all four backends.
- `ClojureControlLoweringTest` (`case`/`condp`/`if-some`/`when-some`/`while`/`locking`/
  `with-redefs`: refusals, the per-kind `case` tests, the host-only monitor, the
  redefinable `defn`, the session refusal).
- `ClojureLoweringTest` (lowered shapes and refusals; `aLiteralScalarKeySkipsTheStructuralKeyRuntime`,
  `clojureSetWiresLikeClojureString`), `ClojureThrowablesTest` (class chains), `ClojureRefusalsTest`
  (the refusal carriers' chains), `ClojureClassBasesTest` (class rows), `ClojureReaderTest`,
  `ClojureSessionTest`, `ClojureProjectNamespacesTest` (a `deps.edn` project, all four
  backends, `aCljcFile*` a `.cljc` entry and namespace; `MemoryClojureFiles` for the unit
  tests, `ClojureLoweringTest#aNamespaceLoadsItsCljFromAnyRootAheadOfItsCljc`).
- Reading: the `read-string-*`/`read-takes-*`/`str-spells-*` spec cases,
  `ClojureLoweringTest#readingVerbs*`/`#aReaderWrapper*`, `ClojureReaderTest#aDiscard*`/
  `#aCharacterLiteral*`, `ClojureSessionTest#aBufferRegisters*`,
  `ClojureInteropTest#readTakesBackWhatSpitWrote`, `ClojureWasmFileIoTest`;
  `ClojureDefaultReadersTest` (`#inst`/`#uuid` against the JDK, both halves);
  `ClojureDataReadersTest` (`data_readers` files, four backends).
- Interop and host IO: `ClojureInteropTest` (interpreter and JVM),
  `ClojureWasmInteropRefusalTest`, `ClojureWasmFileIoTest`, `ClojureWasmFileRefusalTest`,
  `ClojureJavaIoTest` (clojure.java.io's directories and resources, four backends).
- `ClojureArmsTest` (the sorted-collection, unbound-root, matcher, reducible, interface and
  refusal strips).
- `ClojureRingAdapterTest`, `ClojureRingUtilTest` (the Ring namespaces),
  `ClojureHttpClientTest`, `ClojureHttpClientHostFetchE2eTest` and
  `FetchSpecE2eTest#clojureHttpClient` (the HTTP client),
  `ClojureWasmBoundaryTest`, `ClojureWitBoundaryTest` (the host boundary),
  `ClojureLanguageNamespacesTest` (where the clojure.jar namespaces come from).
- `ClojureLibraryTest` (the splice; `everyLibraryNameIsDefinedOnce`: a second `defun` of a
  `clojure.lisp` name replaces the first for every caller, which a wrong arity then shows only
  as a JVM compile warning), `SourceLanguageTest`, `RontoLispCliTest` and
  `PlaygroundReplTest` (the `clojure>` transcript, `--no-gc`), `examples/clojure/demo.clj`
  through `ExamplesE2eTest`.

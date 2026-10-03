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
  only the AST types and `reader`.
- The run-time helpers are Lisp in `src/main/resources/am/ik/rontolisp/eval/clojure.lisp`,
  spliced by `eval/ClojureLibrary` (the `SchemeLibrary` shape). Each runtime (printer,
  STM, hierarchy, regex, ex-info, `clojure.test`, transducers) is referenced only when
  used and the pruner drops it otherwise, so a program without it pays nothing. Macro
  bodies run at lower time through `eval/ClojureMacroTime`.
- Reached ONLY through the seam: `eval/SourceLanguage.CLOJURE`, picked per FILE for `.clj`
  or by `--source-language clojure` (`clj`) (`.kb/source-language.md`). A Common Lisp
  file may `(load "lib.clj")` and call `(c%name ...)` (`(|c%my.ns/name| ...)` for a
  namespaced one).
- The browser needs no Java-side change: `RontoPlayground` splices `ClojureLibrary`,
  `PlaygroundRepl` is language-agnostic, doc ` ```clojure ` fences are Run cells
  (`data-lang="clojure"`, `.kb/documentation-site.md`) checked by `DocExamplesTest`.
- A whole FILE is lowered at once. Pass one pre-scans every top-level definition name
  (`def`/`defn`/`declare`/`defmacro`/`defrecord`/`deftype`/`deftest`, following `ns`), so
  a form may use a definition below it. A REPL lowers through a session ("A session").
- Errors name the innermost form's source position (`.kb/source-positions.md`).

## Values

| Clojure | representation | notes |
|---|---|---|
| identifier `foo` | symbol `c%foo`; a global var of namespace `n` is `c%n/foo` (`user`'s keep `c%foo`) | the prefix keeps every name off `LispNames` case labels, lambda-list keywords and `T`/`NIL`; spelling verbatim (`Foo` and `foo` apart); `:` -> `%c`, `%` -> `%%` keeps the map injective; a local never carries a namespace. Generated names add a lone `%` suffix no identifier spells (`%2`/`%*` arity helpers, `%defN`, `%macro`, `%bound-depth`, `%loaded`, `%init-N`, `%meta`, `%root`, `%local`) |
| `nil` / `true` | `NIL` / `T` | `nil` IS the empty list |
| `false` | the value of `rontolisp::%clojure-false`, a distinct non-`NIL` symbol spelled `false` | the `#f` treatment of `scheme.lisp`; every lowered test is an explicit null-or-false check on a temporary |
| `:foo`, `:a/b` | `(:C%KEYWORD "foo")`, spelling verbatim | compared by `equal`; `::kw` / `::alias/kw` resolve at lower time against the current namespace (an unknown alias is the oracle's `Invalid token`) |
| `{k v}` | an `equal` hash table (`rontolisp:plist-hash-table`), never mutated: every verb builds a fresh one | the shared runtime (`.kb/hash-tables.md`), so persistence holds on all four backends with no per-backend code; a persistent-map library would add a representation every backend prints, hashes and compares. Collection keys go through "Structural keys" |
| `#{..}` | `(:C%SET table)`, each member under itself | a repeated literal element is refused by spelling (`Duplicate key`) |
| `[..]` | a CL vector (a `vector` call) | a string is a CL vector too, so `vector?`/`coll?` exclude strings |
| list, seq | a CL list | lazy seq: `(:C%LAZY cell)`, memoized through `rplaca`/`rplacd` ("Laziness") |
| atom, volatile, ref, agent | `(:C%ATOM #(value))` | one cell shape, so STM verbs accept atoms |
| record / deftype / reify | `(:C%RECORD tag fields table class)` / `(:C%TYPE ...)` / a fresh `:C%REIFY` tag | "Dispatch" |
| `#"re"` | `(:C%PATTERN stamp source ops ngroups)` | the stamp is a gensym, so `=` is identity like the oracle |
| `reduced`, var, nil dispatch value | `(:C%REDUCED x)`, `(:C%VAR "ns/name" getter)`, `(:C%NIL)` | |
| `ex-info` | a condition with message and data slots | |
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
| a call to a `VARIABLE`-kind head | `funcall` of the value cell when it holds a real function (a `let` binding of one, a `def`'d one), else `rontolisp::%clojure-call` | the dispatcher applies functions and looks up collections, like `IFn`, so a parameter may hold a set or map; a `declare`d-never-defined name keeps its direct-call error |
| a call whose head is a compound form (`((fn ...) x)`, `((set v) x)`, `((first ks) m)`) | `funcall` when the head lowers to a `function`/`lambda`, else `rontolisp::%clojure-call` | a call result may be a set, map, vector or keyword, which the dispatcher looks up like `IFn`; a collection literal head and a `#'f` head keep their own rows |
| a call to a core name the program defines or binds | the program's call | `call` checks `known(name)` before the core rows |
| `declare` | `nil` | a pre-scan forward declaration; a real definition wins |
| `def` | top-level `setq` of the mangled name | inside a body it sets the global when the body runs. The value lowers against the OLD binding, so `(def p (memoize p))` after `(defn p ...)` captures `#'c%p`. A docstring and an attr map are skipped (recorded as var metadata); an attr map needs a value behind it and a lone string is the value |
| `defonce` | `def` unless `boundp` | a reload keeps the root |
| `defn-` | a private `defn` | "Namespaces and project files" |
| `fn` / `#(...)` | `lambda`; several arities one `lambda` over `&rest` dispatching per arity, each arity binding through `let*` | a named `fn` is a `labels` self-binding, an anonymous one only when a `recur` reaches it. `#()` takes one `&rest` list, `%`..`%9` as `(nth n args)`; its body is ONE call (`#(f a b)` -> `(f a b)`; several forms need `do`) |
| destructuring (`let`/`loop`/`fn`/`defn`/`for`/`doseq`) | `let*` pairs over one temporary per pattern | vector: positional through `%clojure-nth` (nil past the end), `&` rest through `%clojure-drop`, `:as`; map: the table-aware read with `:keys`/`:syms`/`:strs`/`:or`/`:as` (a qualified `:keys` entry binds the short name); nested; malformed shapes refused by name |
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
| `try`/`catch`/`finally`/`throw` | `handler-case` inside `unwind-protect`; `throw` over `error` | every catch clause is catch-all, first wins, binding the CL condition; `throw` signals an `ex-info` as itself, anything else through its Clojure rendering, so strings keep their message |
| `ex-info` `ex-data` `ex-message` | `make-condition` of the spliced class | `ex-data` of any other condition is `nil`; `ex-message` renders anything else |
| `assert` | `if` around `error` | the `Assert failed:` message evaluates only on failure; it names the failed form, built only in the failure branch: rendered at lower time (`ClojureStringLowering.prSource`: symbols, keywords, integers, strings, chars, lists, vectors, maps) so no printer is linked, else `quote` through the readable `%clojure-str-of` (double, ratio, set, regex...) which links the collection printer (measured 2026-10-03: `(assert (nil? x))` wasm 3171 -> 3282 bytes; with a double in the form 3171 -> 54237 versus 21992 before) |
| `atom` `deref`/`@` `swap!` `reset!` `compare-and-set!` `volatile!` `vswap!` `vreset!` | reads/writes of the cell | answer the new value; `compare-and-set!` compares with `eql`; `seq`/`first`/`count`/`empty?`/`cons`/`conj` onto a cell signal like the oracle |
| `ref` `dosync` `alter` `commute` `ref-set` `ensure` | the cell under the spliced STM runtime | "State" |
| `agent` `send` `send-off` `await` `shutdown-agents` | the cell as a synchronous agent | "State" |
| `binding` / `set!` | `let*` of specials plus a depth counter | "State" |
| `with-open` | `let*` plus `unwind-protect` closing in reverse | a stream closes through `close` on every backend, anything else through the `close` interop call; an empty vector is the bare body |
| `with-out-str` | `let*` rebinding `*standard-output*` to `make-string-output-stream`, read back | never a literal `with-output-to-string`: it flips a WASM module into EH mode |
| `time` | the value timed with `get-internal-real-time`, printing `Elapsed time: N.0 msecs` | a double like the oracle's `nanoTime` quotient (whole milliseconds here), so the book's `\d+\.\d+` match holds; only the shape pins |
| `*out*` / `*in*` | `*standard-output*` / `*standard-input*` | `binding` rebinds either spelling, bare or `clojure.core/`-qualified |
| `defstruct` `struct` `struct-map` | a key vector behind the name plus fresh-table builders | missing keys `nil`, too many values signal |
| `with-meta` `meta` `vary-meta`, reader `^` | "Vars and metadata" | |
| `var` / `#'` | `(rontolisp::%clojure-var "ns/x" (lambda () ROOT) META)` | "Vars and metadata" |
| `defmacro` `macroexpand(-1)` `gensym`, `` ` `` `~` `~@` | "Macros" | |
| `defmulti` `defmethod` hierarchies `defprotocol` `defrecord` `deftype` `reify` `extend*` `satisfies?` `instance?` `class` | "Dispatch" | |
| `ns` `require` `use` `import` `in-ns` | alias and refer wiring; a project namespace's file loaded at the `require` | "Namespaces and project files" |
| `clojure.string` (`join` `split` `split-lines` `upper-case` `lower-case` `capitalize` `trim` `triml` `trimr` `trim-newline` `blank?` `starts-with?` `ends-with?` `includes?` `index-of` `last-index-of` `replace` `replace-first` `escape` `re-quote-replacement` `reverse`) | core string operations | reached as `alias/var`, `clojure.string/var` or a referred var. `split`/`replace` take a pattern (through the regex runtime) or a literal string/char (a plain string never compiles to a pattern). Empty literal-`split` input is `nil` (a pattern answers one empty part); a positive `split` limit caps, a negative keeps every part, else trailing empties drop |
| `clojure.set` (`union` `intersection` `difference` `select` `project` `rename-keys` `rename` `index` `map-invert` `join` `subset?` `superset?`: every public var) | `ClojureSetLowering`: one call to the spliced `rontolisp::%clojure-set-NAME` worker (`?` spelled `-p`, the variadic three over one list of their sets, `join` with a key map `-join-km`) after a lower-time arity check in the oracle's wording (`... passed to: clojure.set/NAME`); as a value `#'...-v` | the oracle's own algorithms, so an answer's kind follows the same input: `union` grows its largest input (bubble order and all; a vector or list there answers one, a map signals), `intersection` shrinks its smallest, `difference`/`select` the first; nil stays nil, an unchanged input is answered itself, a set changes in a fresh copy. Membership goes through the structural-key runtime; `contains?` on a vector is by index, like the oracle's. Relation members may be records: `join`'s merge keeps the first's record, `rename-keys` keeps it unless a declared field is renamed away. Answers carry no metadata. Corpus witness: shcloj4 `examples.test.sequences` `test-sets`/`test-joins` (`ClojureProjectNamespacesTest`); the whole namespace stays red on `examples.utils` (the `?.` macro), `clojure.xml` and `file-seq` (measured 2026-10-03: the load stops at `utils.clj:37:1`) |
| `subs` | `subseq` | |
| `format` | the Java directives translated to `format` over Clojure-rendered arguments | literal format string only; `%s` like `str` (nil spells `null`), `%b`; `%e`/`%g`, flags and the rest refused |
| `spit` `slurp` `line-seq` `clojure.java.io/reader` | `with-open-file` of the `str` spelling / a `read-char` loop / a `read-line` loop / `open` | every backend; wasm needs a `--dir` preopen (without it the open signals). `spit` supersedes unless `:append` is truthy, `nil` writes nothing. `line-seq` takes a path or an open reader, strictly, and never closes the reader. `file-seq` and every other `clojure.java.io` fn are refused |
| `read-string` `read` | `rontolisp::%clojure-read-string`/`-read` (`-opts` for an options map, `-v` as values) over the call site's namespace context | "Reading"; every backend |
| regex `#"..."`, `re-pattern` `re-matcher` `re-find` `re-seq` `re-matches` `re-groups` | `RONTOLISP::%CLOJURE-RE-COMPILE` and the spliced matcher | "Regex" |
| `map` `filter` `concat` | `rontolisp::%clojure-map`/`-filter`/`-concat` | any number of collections (`map` stops at the shortest); lazy when an input is lazy, strict otherwise ("Laziness"); a false object drops like nil |
| `reduce` / `apply` | `%clojure-reduce`/`-reduce-init` / CL `apply` over the whole-collection view of the last argument | `reduce` walks the seq view and stops at `reduced`; its function is a real one ("The IFn dispatcher stays at the call site") |
| `first` `rest` `next` `seq` `cons` | `car`/`cdr` over `%clojure-seq`, `%clojure-cons` | the seq view: lists pass through, vectors/strings coerce, a map gives one two-vector per entry and a set its members (table walk order), nil and false are empty, anything else signals; `cons` onto a lazy collection answers a wrapper |
| `nth` / `second` | `%clojure-nth` | a vector or string indexed directly, anything else stepped; past either end the default (`nil` without one) |
| `take` `drop` | `%clojure-take`/`-drop`, stepping | `(take n infinite)` terminates, realizing exactly what it answers |
| `last` `butlast` `count` `empty?` `vec` `set` `sort` `sort-by` `reverse` `frequencies` `group-by` `select-keys` | strict, over the whole-collection view | `count` of a map/set/record is `hash-table-count`, of a deftype/reify signals; `empty?` realizes one level; `sort` orders numbers, strings, chars and keywords (else signals), a comparator runs on truthiness |
| `keep` `keep-indexed` `map-indexed` `remove` `distinct` `interpose` `partition` `interleave` | one call to the spliced `rontolisp::%clojure-NAME` (`-indexed` for the indexed pair, `partition-v` as a value) | lazy-or-strict ("Laziness"); `keep` keeps `false`; `partition` drops an incomplete tail, refuses a pad; `interleave` stops at the shortest |
| `some` `every?` `take-while` `drop-while` `zipmap` | stepping, so an infinite input answers | `some` answers the predicate's value |
| `mapv` `filterv` `mapcat` | the realized result as a vector / appended seqs | `mapcat` is nil-safe like `concat` |
| `ffirst` `nfirst` | `car`/`cdr` of the seq of the head | each level seqs |
| `range` | a strict list (1/2/3-arity) | a zero step signals; an end-less `(range)` is refused (no chunking; spell it with `iterate`) |
| `lazy-seq` `lazy-cat` `repeat` `cycle` `iterate` `repeatedly` | "Laziness" | finite `repeat`/`repeatedly` arities answer strict lists |
| `drop-last` `split-at` `split-with` `take-last` `nthnext` `nthrest` `peek` `pop` `not-empty` `dedupe` `replace` `find` `subvec` `key` `val` `map-entry?` `rseq` `find-keyword` `partition-all` `partition-by` `min-key` `max-key` `juxt` `fnil` `every-pred` `some-fn` `update-keys` `update-vals` `reduce-kv` `test` | `ClojureCoreLowering`: one call to the fixed-parameter `rontolisp::%clojure-NAME` worker after a lower-time arity check in the oracle's wording (`Wrong number of args (N) passed to: clojure.core/NAME`); as a value `#'rontolisp::%clojure-NAME-v`, checking at run time | `dedupe`/`partition-*`/`drop-last` are lazy-or-strict; `peek`/`pop` take vectors and lists (a strict seq peeks where the oracle's LazySeq throws); `some-fn` answers the oracle's exact failing value; `reduce-kv` walks maps/records/vectors and stops at `reduced`; a non-positive `partition-all` size signals (the oracle loops forever); `find` answers a map/record's stored key (`%clojure-table-key`, so a structural key answers its held representative), a vector's `[i x]` for an integer index in range, nil of nil, and signals on a set, string or list; `subvec` copies (a fresh vector, never a view), truncates a float bound, signals on a nil bound, a non-vector or a range out of bounds; `key`/`val` read a two-member non-string vector and signal otherwise, `map-entry?` is true of exactly those (a map entry IS a plain 2-vector here, so `(map-entry? [1 2])` is true where the oracle's is false; giving entries their own representation would touch `first`/`seq`/`find`/`reduce-kv`/destructuring/`=`/printing for a distinction only `clojure.walk`-style code reads), `rseq` answers a strict list of a vector (nil when empty) and signals on nil, a list, a seq, a string and a map (a sorted collection's rseq comes with sorted collections), `find-keyword` is `keyword`'s one-argument arm (a two-argument call needs a nil-or-string namespace and a string name) and answers a never-used spelling's keyword where the oracle's is nil -- keywords are `(:C%KEYWORD spelling)` lists with no intern table, and one would cost every `keyword` call and every compiled output a global table; type errors use CL wording |
| `pmap` | `map` | no thread pool; the printed seq is the oracle's |
| `update` `update-in` `assoc-in` `get-in` `merge` `merge-with` `into` | fresh tables over the old pairs | the first three associate through `ClojureCollectionLowering.assocAnswer` like `assoc` (a vector level by index); `update-in` with no keys refused; `assoc-in` builds missing levels; `(merge)`/`merge-with` of no maps is nil; `into` targets lists/vectors/maps/sets through the reduce runtime, `(into to xform from)` is `%clojure-into-xf` |
| `assoc` `dissoc` `get` `contains?` `keys` `vals` `conj` `disj` `hash-map` `array-map` | table operations | `assoc` onto nil builds; onto a vector (`assocAnswer`'s run-time `vectorp` arm, `%clojure-vector-assoc`) a fresh whole copy, index = count appending, a non-integer key `Key must be integer`, out of range signalling, like the oracle -- measured 2026-10-03 on a map-only program: wasm 58,004 -> 60,479 B (dispatch alone +298 B, the `(setf aref)` write ~1 KB; `make-array` plus an `aref` loop instead of `copy-seq`/`coerce` saved 0.8 KB), 2M two-pair `assoc` calls 4.0 s wasm / 2.05 s JVM before and after; pinned by clojure-spec `assoc-on-a-vector-replaces-or-appends-by-index`, `update-and-the-nested-verbs-reach-into-vectors` (and `replace-maps-through-a-map-or-a-vector` for `replace`); odd pairs refused (at run time for values); `get` reads maps, records, sets (the member), vectors, strings, nil (a list or deftype answers the default); `conj` of a set onto a map adds its members one level deep, anything else onto a map signals; `(conj)` is `[]` |
| a keyword, set, map or vector in call position or as a function value | the table-aware read / member / `nth` with an optional default | `({:a 1} :b :d)` is `:d`; a keyword value takes extra arguments (a keyword-dispatched multimethod passes several) |
| `comp` `partial` `complement` `constantly` `identity` `memoize` `trampoline` | closures | `(comp)` is `identity`; `memoize` keys the argument list by `=` (`%clojure-memo-key`) |
| `=` / `not=` | the spliced `%clojure-equal` per neighbouring pair | maps structurally (nested), records by tag plus entries, deftype/reify by identity, sequentials (lists, vectors, lazy seqs, nil) element by element across kinds, two floats by CL `=` (-0.0 = 0.0, NaN not = NaN), else `equal`. One shared callee, not a `labels` per site: ten sites measured 87,050 -> 34,004 B of wasm |
| `<` `>` `<=` `>=` `==` `nil?` `false?` `true?` `boolean?` `boolean` `coll?` `string?` `symbol?` `vector?` `fn?` | the CL test answering `T`-or-false | `==` is CL `=` (numeric across categories: `(== 1 1.0)`, `(== 0.0 -0.0)`; a non-number signals, `(==)` is refused at lower time); `<` `>` `<=` `>=` `==` as values are `&rest` lambdas over the CL function answering `T`-or-false (`(map < [1 2] [2 1])` is `(true false)`, not `(true nil)`). `fn?` is false for keywords, sets and maps |
| `int` `long` `char` `quot` `unchecked-add` | `truncate` (`char-code` for a char) / `code-char` / `truncate` / `+` | a non-number signals; `unchecked-add` never wraps |
| `name` `namespace` `keyword` `symbol` | spliced string workers over the demangled spelling | split at the first `/` |
| `str` / `pr-str` | `concatenate` over `%clojure-str-of` parts | `nil` -> `""` (`pr-str`: `"nil"`), keywords with their colon, collections in Clojure notation -- readable inside under `str` too (strings quoted, nil spelled: the oracle's `toString`), so `spit` writes what `read` reads back |
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
  and agents `#<Atom v>`, an `ex-info` its condition, a hierarchy its hash table, any
  other host object `#<java C>` (the oracle's `#object[C 0x.. "..."]` carries a hash) --
  while a deftype, reify and `reduced` print their
  wrapper lists; `str` of a lazy seq or record spells the contents where the oracle answers
  `Class@hash`; `*print-length*`/`*print-level*`, `print-method` and `pprint` are absent;
  `~S`/`~A` on Clojure values stay CL notation (`format` is a CL surface). Cycles print with
  datum labels, copied from `%scheme-print` (sharing would splice `scheme.lisp` into every
  Clojure program).
- Map entries: an entry is a plain two-member vector, so `map-entry?` is true of every `[k v]` (the oracle: false for one the program built). `find-keyword` answers a never-used spelling's keyword (keywords are not interned; the oracle: nil).
- Structural keys: a stored collection key is the first `=` key of its kind the program
  stored, so its metadata and a nested member's spelling follow that object; the
  representatives live for the whole run, one per distinct value and kind.
- `(= [] nil)` is true (the oracle: false). `(empty? false)` is false (the oracle signals).
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
- `catch` is catch-all; a thrown host `Throwable` reaches handlers as an opaque host object,
  so its message is lost (b66).
- Dispatch: numeric host classes merge into `:number` for multimethods and protocols;
  protocol dispatch reads no hierarchy; two namespaces' records of one simple name share a
  tag; `class` answers a kind keyword (host classes exist on no wasm backend), a host
  object its host class (interpreter and JVM).
- Metadata: a derived value (`assoc`, `conj`, ...) starts without metadata; a symbol takes
  none; the side table keeps every object for the program's lifetime.
- The oracle-refused leniencies kept: an unquoted vector libspec in a bare `require`; an
  odd trailing `cond` arm.

## Structural keys

**A map, set, memo or method-table key finds an `=` key, though the tables are `equal`
tables whose `equal` is identity on a vector or table.** No backend has a custom-test
table, so `clojure.lisp` ("Structural keys") stores every structural key (non-string
vector, list, lazy seq, map, set, record) under a REPRESENTATIVE: the first `=` key of its
kind (vector / lazy seq / list / other) the program stored. `%clojure-key-classes` (equal
table, `%clojure-hash` -> classes) groups the representatives `=` to each other;
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
entry, `lazy-seq` body (arity 0) or stored method lambda (`defmethod`, protocol methods,
`reify`): a target stack, through which a plain lambda passes. Each multi-arity clause is
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
- Constant stack comes from the backends' tail calls: wasm `return_call`, the
  interpreter's `eval` loop, the JVM's self tail call as a jump back to the method's start
  and, for `letfn` entries or `defn`s calling each other, its tail groups
  ([jvm-self-tail-calls.md](jvm-self-tail-calls.md); before them, a JVM `loop` overflowed
  near 150,000 rounds, a `defn` near 200,000, a `letfn` pair near 150,000). A call through
  a value in tail position -- `%clojure-call`'s `apply`, a call site's `funcall` of a real
  function -- bounces through the JVM's trampoline ([jvm-tail-bounce.md](jvm-tail-bounce.md)): a `fn`
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
  go through one spec parser. `clojure.string`, `clojure.set`, `clojure.java.io` (`reader`
  only) and `clojure.test` resolve; any other `clojure.*` is `unknown namespace: x`.
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
- **Refusals in the oracle's words**: `Could not locate a/b.clj on the source path:
  <roots>`, `Cyclic load dependency: [ /a ]->/b->[ /a ]`, `namespace 'x' not found after
  loading '/x'`, `x does not exist`, `x is not public`.
- **Source path** (`ClojureSourcePath`, on the first project require): the root the entry
  file's namespace names (`src` for `src/demo/main.clj` declaring `demo.main`; else the
  file's directory; a session's working directory), then the `:paths` of the nearest
  `deps.edn` walking up (read as EDN; `["src"]` when absent), else `src` -- the `clj`
  default. `deps.edn` over a new flag: it is the oracle's own declaration. Files come
  through `ClojureFiles` (`SourceLanguage.clojureFiles` adapts the site's loader; none is
  refused by name).
- **Records** keep the simple-name tag; `typeKeyOf` resolves own, then an imported or
  dotted name matching the class, else the only one of that simple name.

## Macros

- `defmacro` lowers to one expander lambda dispatching on the argument count (each arity's
  parameters with their destructuring prologue) plus a `c%name%macro` table global. The
  same lambda expands call sites datum-to-datum at lower time (through
  `eval/ClojureMacroTime`, one per file or session) and serves `macroexpand-1` at run
  time. Docstring and attr map skipped, `&` rest works, `&form`/`&env` refused. A body
  sees the core builtins and `clojure.lisp`, not the program's definitions. A call above
  the definition names the missing expander; a macro has no function value; a later
  `def`/`defn` wins the call sites back.
- **A program macro wins over every lowering row of its name from its definition on;
  above it the core meaning holds**, like the oracle's form-by-form compile. `lowerInner`
  tries the macro before any row, except for `isReservedHead` (the oracle's special forms
  plus the heads the reader spells: `syntax-quote`/`unquote*`/`deref`/`fn`, `ns`/`in-ns`),
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
- `macroexpand-1`/`macroexpand` answer the mangled data itself, so `=` against a quoted form
  holds and the printer (demangling `c%`) spells the oracle's lowercase; a non-macro head
  answers the form. The head resolves through the call site's namespace (the lowering
  passes an alist of the spellings the table cannot spell itself). `gensym` is the
  ordinary uninterned symbol.

## The IFn dispatcher stays at the call site

**A spliced runtime worker funcalls its function argument; the lowering hands it a real
function.** `rontolisp::%clojure-call` (the IFn dispatcher: sets, maps, vectors, keywords,
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
  value in a rest lambda over the dispatcher. A worker added to `FUNCTION_WORKERS` must
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
- A `defmulti` of a var that holds a multimethod lowers to `nil`, like the oracle's (the
  corpus's second `(defmulti my-print class :default :everything-else)` keeps the first's
  methods and default): `ClojureLowering.multimethods`, by var key, survives buffers and
  is cleared by every other definition (`intern`), never by the pre-scan (`internName`).
  Static, so a `defmulti` run repeatedly inside a function still redefines.
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
  `C%PROTOCOL-TAG`; exact tag, then the method's `Object` row, else a signal; one signature
  per method. The multimethod shape over runtimes all four backends already run, not a
  per-backend value model (`.todo/artefacts/b13-protocols/spike.md`).
  `:extend-via-metadata true` adds an `%inline` table; the order is body implementation,
  then `(%clojure-meta-method target 'ns/method)`, then extension rows, then `Object`
  (measured on the oracle); the key is the namespace-qualified method symbol.
  `satisfies?` reads both tables, never metadata.
- `extend-protocol`/`extend-type`/`extend` add rows under the target's tag: the
  class-keyword kinds, `nil`, `Object`, known records and deftypes; anything else
  (`Instant`, `Date`) is refused.
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
  stay `unsupported reader form`).
- `reify`: a fresh tag per evaluation with a row per method; `=` is identity.
- `instance?` takes core classes and record/deftype names, else refuses; it has no value.

## Java interop

The `java:` surface (`.kb/java-interop.md`); interpreter and JVM only -- wasm compiles
`java:` to a call-time error (`ClojureWasmInteropRefusalTest`).

- `(. obj m args)`/`(.m obj args)` instance, `(. Class m args)`/`(Class/m args)` static,
  `(Class. args)`/`(new Class args)`, `(Class/FIELD)`, `(.-f obj)`, `..`, `memfn`. Classes
  resolve dotted, imported or `java.lang`. A zero-argument `(Class/m)` is the static method
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
  `(.compareTo 1 2)` -1; before: `java:call expects a java object ..., got "abc"`). With the
  receiver class unknown at lowering, a method whose overloads at that arity all answer a
  primitive boolean on `String` (`(if (stringp r) ...)` arm) or on `Integer`+`Long` / `Double` /
  `Character` (`valuePredicate`) answers T-or-false (`(.matches "abc" "x")` false, not nil).
  Deviation: an int-sized integer is an `Integer` (`(.getClass 1)`; the oracle's `Long`).
  Pins: `ClojureInteropTest#unmappedMethodsCallAStringNumberOrCharacterAsItsHostObject`.
  `.toString` of a number, character, symbol (booleans too), cons, array, table or
  function answers `(%clojure-str-of x "nil" nil)`, the oracle's `toString`, on every
  backend (`ClojureInteropLowering.valueToString`); nil signals (the oracle's NPE); only
  what is left reaches `java:call`. The stream arm's non-string-stream branch reaches the
  same test, since `streamp` answers true for `t` (the terminal's designator, and
  Clojure's `true`).
  Stream receivers run on every backend: `.write` -> `princ` (nil signals), `.flush`,
  `.readLine` -> `read-line` (nil past the end), `.read` -> a character code (`-1` past
  the end), `.toString` of a string output stream -> the text so far. `(new
  java.io.StringWriter)` with no argument is a string output stream on every backend; a
  `java.io.PushbackReader`/`BufferedReader` construction over a stream is that stream and
  over a `(StringReader. s)` argument a string input stream ("Reading").
- Host booleans: the shared unmarshal maps host false to nil (CL's only false; changing
  it would make host false truthy in `java:` programs and move all three paths plus the
  bridge parity). The lowering wraps to `T`-or-false instead where every overload at that
  arity returns a primitive boolean and the receiver class is known: a static call or
  member value, or an instance call on a construction literal, a `let`/`if-let`/`when-let`
  local bound to one (single-shot, so the inference is sound), or a `..` step's declared
  return. Anything else prints `nil` for false.
- `proxy` of interfaces is `java:proxy` with a name-dispatching lambda over the Java
  arguments (no `this`); a missing method raises `no proxy method: <name>`;
  `toString`/`equals`/`hashCode` are refused there (`java:proxy` keeps `Object`'s, so the
  body would never run). `(proxy [Super I...] [args] ...)` is `java:subclass`: bodies bind
  `this`, `proxy-super` calls the generated `super$` accessor, an unnamed abstract method
  throws `UnsupportedOperationException`. Multi-arity methods, a second class, a final
  superclass are refused.
- No host-field `set!`: the `java:` surface has no write primitive.

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
  `reduced`; `deref` reads it.
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

**`read-string`/`read` run one reader in `clojure.lisp` (`%clojure-read-from`) over the
source reader's language, answering what a quote of the same text answers**, so `(=
(read-string s) 's)` holds: `@x` reads `(deref x)` and `` `x `` `(syntax-quote x)` like a
quote does (the oracle: `clojure.core/deref`, the expansion), `#(...)` the source reader's
`(fn %anon ...)`, metadata drops, `#=`/`#?`/`#inst` are its refusals. A read map
or set stores its keys through "Structural keys" (`%clojure-plist-table`,
`%clojure-set-put`), so it finds `=` keys and refuses an `=` duplicate member like a
literal. `::kw` resolves against the context each call site passes, `("ns" ("alias"
"full.ns") ...)`, plus the libraries `isKnownNamespace` names (mirrored in
`%clojure-rd-alias`).
`eval`/`load-string` stay unknown names: no compiler runs at run time.

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
  a stream (`open`, a string input stream, `*standard-input*`), else a `streamp` test
  around the `java:new`; over a `(StringReader. s)` argument it is `(make-string-input-stream
  s)`. A `StringReader` alone stays the host class: the corpus's SAX `InputSource` takes
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

## Vars and metadata

**A var is `(:C%VAR "ns/name" getter)`, interned per name in `%clojure-var-table`.**
`ClojureVarLowering.varOf` lowers `#'x`/`(var x)` to `(%clojure-var "ns/x" (lambda () ROOT)
META)`; each evaluation re-points the interned var, so `=` holds. ROOT is the name's value
as the site sees it (a redefined `defn`'s current version, a value cell, a signal for a
macro); a `user` var shadowed by a local reads through a hoisted `|c%x%root|`. `lookupVar`,
not `resolveVar`: a private var is reachable, like the oracle. A local is `Unable to
resolve var`; a `clojure.core` var is refused.

- Metadata is recorded at lower time (`ClojureVarLowering.record`, kept across session
  buffers) in the oracle's order: `:arglists`, the name's reader metadata, `:private`,
  `:doc`, the attr map, `:line`/`:column`/`:file`, `:name`, `:ns` (a symbol), `:macro`.
  Constant values stay a datum at each `#'` site; evaluated ones are stored into
  `|c%x%meta|` ahead of the definition. A site above a redefinition sees the older
  metadata. Anything other than `def`/`defn`/`defn-`/`defmacro` carries only
  `:name`/`:ns`. `test` calls `(:test (meta v))`.
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
  and the stream specials (the oracle's non-dynamic error otherwise). A `^:dynamic`
  `def`/`defonce` is a `defparameter` plus a zeroed `%bound-depth` counter special (two
  top-level forms, for `SpecialVarCollector`); a `^:dynamic` `defn` keeps its `defun` and
  adds a `defparameter` of the function, so calls go through the value cell (`recur` and
  the arity helpers stay direct). Each `binding` rebinds the counter one deeper.
- `set!` of a dynamic var: past depth zero `setq`, at zero the oracle's `Can't
  change/establish root binding of: x with set` -- the counter has dynamic extent, so a
  callee outside the binding's lexical extent still sets it. The `clojure.main`-bound
  flags (`*warn-on-reflection*`, `*unchecked-math*`, `*print-meta*`, `*print-length*`,
  `*print-level*`, `*ns*`) answer the value with no effect. `set!` of `*out*`/`*in*`/
  `*agent*` or anything else is refused.
- deftype mutable fields (`^:unsynchronized-mutable`/`^:volatile-mutable`; ClojureScript's
  `^:mutable` is no marker): a fifth element `(vector m1 ...)` in the deftype (absent
  without mutable fields), invisible to `.-field`; `defrecord` refuses the markers. An
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
- Deviations: definition order (the oracle's is map order); `thrown?` matches any
  condition (measured 2026-10-03, shcloj4 `examples.test.interop`: `Ran 6 tests containing
  17 assertions. 0 failures, 0 errors.` on the interpreter and the JVM, as the oracle --
  its `(thrown? IllegalArgumentException ...)`/`(thrown? ClassCastException ...)` catch
  `No matching method java.lang.String.getName` (before 2026-10-03: `java:call`'s refusal of
  a string receiver), since the `#^Class` hint is dropped; a
  class-typed match needs the host exception as a condition, the `catch` deviation above);
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
- `ClojureLoweringTest` (lowered shapes and refusals; `aLiteralScalarKeySkipsTheStructuralKeyRuntime`,
  `clojureSetWiresLikeClojureString`), `ClojureReaderTest`,
  `ClojureSessionTest`, `ClojureProjectNamespacesTest` (a `deps.edn` project, all four
  backends; `MemoryClojureFiles` for the unit tests).
- Reading: the `read-string-*`/`read-takes-*`/`str-spells-*` spec cases,
  `ClojureLoweringTest#readingVerbs*`/`#aReaderWrapper*`, `ClojureReaderTest#aDiscard*`/
  `#aCharacterLiteral*`, `ClojureSessionTest#aBufferRegisters*`,
  `ClojureInteropTest#readTakesBackWhatSpitWrote`, `ClojureWasmFileIoTest`.
- Interop and host IO: `ClojureInteropTest` (interpreter and JVM),
  `ClojureWasmInteropRefusalTest`, `ClojureWasmFileIoTest`, `ClojureWasmFileRefusalTest`.
- `ClojureLibraryTest` (the splice), `SourceLanguageTest`, `RontoLispCliTest` and
  `PlaygroundReplTest` (the `clojure>` transcript, `--no-gc`), `examples/clojure/demo.clj`
  through `ExamplesE2eTest`.

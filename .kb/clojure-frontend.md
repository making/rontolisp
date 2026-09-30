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
| `<`/`>`/`<=`/`>=` | the Common Lisp operation, answering `T`-or-false | so `(= false nil)` is false and printing spells it `false` |
| `nil?` | `null`, answering `T`-or-false | `(nil? false)` is false |
| `false?`/`true?`/`boolean?` | their predicates (`eq` against the false object / `T`), answering `T`-or-false; as values, lambdas answering a Common Lisp boolean | |
| `map`/`filter`/`reduce`/`apply`/`concat` | `mapcar`/`remove-if-not`/`reduce`/`apply`/`append` over the seq view | lists pass through untouched (no copy); every other collection coerces first, so vectors, strings, maps and sets all work; `reduce` is 2/3-arity with the Clojure argument order (`(reduce f val coll)`) mapped onto CL `reduce` `:initial-value`; `apply` spreads any leading arguments over the seq-coerced last one (`(apply f x args)`), like CL `apply`; `(concat)` is nil |
| `first`/`rest`/`next`/`seq`/`cons` | `car`/`cdr` over the seq view, the view itself, `cons` onto the view | a seq IS a strict list view (decided 2026-09-30, b03): lists pass through, vectors/strings coerce, maps contribute one two-vector per entry and sets one member per element (both in the table's walk order, unspecified), nil and the false object are empty, anything else signals like the oracle; no laziness, chunking or memoisation -- the only sequence all four backends already share is the cons list, so a lazy struct would add a representation every backend prints, hashes and compares (the b02 argument against a persistent-map library) |
| `nth` (2/3-arity) | the seq view indexed, past the end the default | the 2-arity answers nil past the end where the oracle throws; as a VALUE a lambda with the Clojure order (`(lambda (c i) ...)`), since a bare `#'NTH` takes the index first |
| `quot` | `truncate` | as a VALUE a two-argument lambda over `truncate` |
| `take`/`drop` | a labels self call over the seq view / `nthcdr` over the view | strict; an over-long take/drop answers the whole/empty seq (nil, where the oracle prints `()`) |
| `range` (with an end) | a labels self call building the strict list | 1/2/3-arity (`end` / `start end` / `start end step`); a zero step signals; an end-less `(range)` is refused by name -- an infinite seq cannot be spelled strictly |
| `lazy-seq`/`cycle`/`repeat`/`repeatedly`/`iterate` | refused by name (`lazy sequences are not supported: lazy-seq`) | OUT: there are no lazy seqs -- strict-only by decision, named refusals instead of `unknown name` |
| a vector literal | a `vector` call | |
| `first`/`rest` | `car`/`cdr` over the seq view | see the seq-view row above; `count` stays the table-aware length (the fast path, no seq built) |
| `count` | a table-aware length | maps and sets answer `hash-table-count`, everything else `length` |
| `empty?` | a table/vector/string-aware null test, answering `T`-or-false | `nil`, an empty map/set/vector/string are empty |
| `=`/`not=` | a labels self call comparing maps entry by entry and sets member by member, deep, answering `T`-or-false | two maps compare structurally (nested included); a map and a set never compare equal; anything else is `equal` |
| `{k v ..}` | `rontolisp:plist-hash-table` over the lowered pairs | an `equal` table, never mutated in place: every verb builds a fresh one |
| `#{..}` | an `equal` table holding each member under itself, wrapped as `(:C%SET table)` | the wrapper tells verbs a set from a map; a repeated literal element is refused by spelling (`Duplicate key`) |
| `assoc`/`dissoc` | a fresh table over the old pairs plus/minus the keys | `assoc` onto nil builds from empty; `dissoc` of nil is nil; odd `assoc` pairs are refused |
| `get` | `gethash` with the default, or a bounds-checked `elt`/`char` | takes maps, sets (answering the member), vectors, strings and nil; a list answers the default |
| `contains?` | a sentinel-`gethash` presence test, or a bounds check | takes maps, sets, vectors and strings; anything else answers false |
| `keys`/`vals` | a `maphash` accumulation into a list | the order is the table's walk order, unspecified; of nil, nil |
| `merge` | one fresh table over every argument's pairs | later maps win; `(merge)` is nil; of all nil, nil |
| `conj` | a member onto a set, entries onto a map, at the end of a vector, at the front of a list | a set conjoined onto a map contributes its members one level deep; anything else conjoined onto a map is refused |
| `disj` | a fresh set minus the members | of nil, nil; of a map, refused |
| `set`/`hash-map`/`array-map` | a set from a collection, a map from key/value pairs | `set` takes lists, vectors, maps (entry vectors) and sets; odd constructor pairs are refused |
| `str` | `concatenate 'string` over mapped parts | `(str)` is `""`; `nil` maps to `""`, `true`/`false` to `"true"`/`"false"`, anything else through `princ-to-string` |
| `println`/`print`/`pr`/`prn` | one `concatenate` + `princ`, the newline folded into the last part | a string prints unquoted (`pr`/`prn` convert readably, so strings print quoted); collections print in CL notation; parts are joined with a single space, like Clojure; each part maps like `str` except `nil` prints as `nil` |
| `true` | `LispTrue` (`T`) | a raw symbol spelled `T` is unbound -- `evalSymbolRef` looks the name up |
| `nil` | `NIL` | falsey |
| `false` | the value of `rontolisp::%clojure-false`, bound before anything else runs | a DISTINCT non-`NIL` symbol spelled `false` (the distinct-object treatment `scheme.lisp`'s `#f` uses); falsey in every conditional through the lowered tests; `eq`-comparable by name on every backend |
| a keyword `:foo` | the list `(:C%KEYWORD "foo")` holding its spelling verbatim (case-preserved) | data, compared by `equal` through the cons shape; `:a` and `:A` stay apart; `println`/`print`/`str` spell it with its colon; a keyword nested in a printed collection shows the wrapper |
| a keyword in call position `(:k m)` / `(:k m dflt)` | the same table-aware read `get` lowers to | the idiomatic map lookup, over b02's map runtime (sets answer their member, vectors/strings their element) |
| a keyword as a function value (`map`/`filter`/`reduce`/`apply` over `:k`) | a one-argument lambda over the same read | `(map :k coll)` reads the key out of each member |
| a namespaced keyword `:a/b` | the same wrapper over the whole spelling | opaque data: prints and compares whole; `::`-auto-resolve is refused by name (there is no namespace to resolve against) |
| `ns` | nothing | a namespace declaration defines nothing |
| `quote` | `quote`, with symbols mangled and vectors re-emitted as `vector` calls | a quoted map or set is the construction over the quoted elements |
| `get` with a default | `gethash`'s own default argument | IN: `(get m k dflt)` answers `dflt` past the end, like the oracle |
| transients | refused by name (`transients are not supported yet: assoc!`) | OUT: `transient`, `persistent!`, `assoc!`, `dissoc!`, `conj!`, `disj!` -- there is no transient runtime behind the tables |

## Deviations (each a real work item)

- A keyword nested in a printed collection shows its `(:C%KEYWORD name)` wrapper
  (e.g. `#((C%KEYWORD a) (C%KEYWORD b))` for `[:a :b]`), like a set shows its
  wrapper; only `println`/`print`/`str` of the keyword itself spell the colon. The
  REPL echo goes through the Common Lisp printer, so it shows the wrapper too.
- Maps and sets print in the runtime's notation, like vectors print in CL notation: a
  map prints `#<HASH-TABLE :TEST EQUAL :COUNT n>`, a set `(C%SET #<HASH-TABLE ...>)`
  (the wrapper reads `C%SET` because `princ` strips a keyword's colon). The runtime is
  the shared hash-table runtime (`.kb/hash-tables.md`), decided 2026-09-30 (b02): an
  `equal` table per map/set, copy-on-write for every verb, so the persistent semantics
  holds observably on all four backends with no new runtime and no per-backend code. A
  persistent-map library spliced like `scheme.lisp` was the alternative; it would have
  added a representation every backend prints, hashes and compares, for no measured
  user beyond what the table already does.
- Vector and table keys compare by identity, not structurally: the runtime's `equal`
  on an array or a table IS identity (`.kb/hash-tables.md`), so
  `(get {[:a] 1} [:a])` misses here and answers `1` there, and a vector member never
  finds its set. Lists, strings, numbers and keywords key structurally.
- A set literal refuses a repeated element BY SPELLING (`Duplicate key: 1`): two
  differently-spelled elements that are equal at run time still dedupe silently, and
  the spelling names the datum as the reader prints it.
- Verbs assume the right collection kind; misuse is unspecified and may signal the
  CL-level type error instead of the oracle's (e.g. `dissoc` of a set, `keys` of a
  vector). `conj` of a set onto a map goes one level deep; anything else conjoined
  onto a map signals. `(empty? false)` answers false where the oracle signals.
- The seq family runs over strict list views of every collection (decided 2026-09-30,
  b03). What still differs from the oracle: `rest`/`next` of empty is `nil`, where it
  prints `()`; `nth` past the end answers the default (nil without one) instead of
  throwing; a map/set seq's order is the table's walk order, unspecified; strings seq
  to characters, which print in Common Lisp notation; there is no laziness, chunking
  or memoisation, so `lazy-seq` and an end-less `range` are refused by name.
- Destructuring, `->`/`->>`, `defmulti`/`defmethod`, protocols, `atom`/`swap!`/`deref`,
  metadata `^`, `var`/`#'`: all absent. The reader parses `@x`, `^meta`,
  backquote, `~` into marked lists the lowering refuses.
- `defn` inside a body works only in statement position; `declare` is absent. `def`
  inside a body mutates the global at run time, unreviewed.
- Errors name the innermost form's source position: the reader's `LispReadException`s
  are prefixed (`file:line:column` when the file is known), and the lowering
  re-reports its own errors (`unknown name`, arity refusals, ...) against the
  reader's per-datum offsets, innermost first (`.kb/source-positions.md`).
- Printing: `print-method`/`pprint` are absent; `pr`/`prn` are the readable arms of
  `print`/`println`, and a boolean nested in a collection prints in Common Lisp notation
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
`nth` takes the collection first (2/3-arity with an optional default over the seq
view; as a value a correctly-ordered lambda), `quot` is `truncate` (as a value a
two-argument lambda), an `(ns ...)` form defines nothing (the file-level skip used to
match only a bare `ns` symbol), and identifiers keep their case behind the
prefix (`Foo` and `foo` no longer fold into one symbol). Maps and sets lower to the
shared hash-table runtime (b02): literals, `assoc`/`dissoc`/`get` (with default)/
`contains?`/`keys`/`vals`/`merge`/`conj`/`disj`/`set`/`hash-map`/`array-map`,
map/set-aware `count`/`empty?`/`=`, quoted maps/sets, and the transient refusals --
each pinned in `clojure-spec.yaml` (run on all four backends) or, for the refusals,
in `ClojureLoweringTest`. Keywords lower to the case-preserving `(:C%KEYWORD
spelling)` wrapper (b01): printing with the colon through `println`/`print`/`str`,
keyword-as-function call and function value over the table-aware read, `:a/b` as
opaque data and the `::` refusal -- each pinned in `clojure-spec.yaml` (run on all
four backends) or, for the refusals, in `ClojureLoweringTest`. Seqs over every
collection lower to strict list views (b03): `first`/`rest`/`next`/`seq`/`cons`/
`concat`/`map`/`filter`/`reduce`/`apply`/`take`/`drop`/finite `range`, each pinned
in `clojure-spec.yaml` (run on all four backends) or, for the lazy refusals, in
`ClojureLoweringTest`. Printing joins `println`/`print` parts with a space and
`pr`/`prn` convert readably (b06); `inc`/`dec`/`str` and the seq verbs name lambdas
as values and `apply` spreads leading arguments, each pinned in `clojure-spec.yaml`
(run on all four backends); lowering errors name the innermost form's position,
pinned in `ClojureLoweringTest`.

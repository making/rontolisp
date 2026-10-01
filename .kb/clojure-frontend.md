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
  The browser runs it with no Java-side change: `RontoPlayground` already splices
  `ClojureLibrary`, `PlaygroundRepl` is language-agnostic, and the doc site's ` ```clojure `
  fences are Run cells on the scheme shape (`data-lang="clojure"`, `.kb/documentation-site.md`);
  the playground's language pick gained `Clojure (experimental)` with its own samples
  (2026-10-01). `DocExamplesTest` checks every ` ```clojure ` block on both doc trees the
  way it checks ` ```scheme `.
- A whole FILE is lowered at once: defn-or-variable is decided by a pre-scan. A REPL
  has no whole program to scan and lowers through a session instead ("A session"
  below).

## The lowering table

| Clojure | lowers to | why |
|---|---|---|
| identifier `foo` | symbol `c%foo`, always prefixed | the prefix holds a lowercase letter and `%`, so no name can reach a `LispNames` case label, a lambda-list keyword or `T`/`NIL`; the spelling is otherwise verbatim, so `Foo` and `foo` stay apart; `:` -> `%c`, `%` -> `%%` keeps the map injective |
| `defn` | `defun` of the mangled name, called directly; a head-position call to a `VARIABLE`-kind name holding a real function (a `let` binding of one, a `def`'d one) is a `funcall` of the value cell instead, while any other variable goes through the prelude dispatcher (`rontolisp::%clojure-call`: functions through `apply`, collections through their lookup, like `IFn`), so higher-order `defn` parameters run on collections too; a `declare`d-but-never-defined name keeps its direct-call error; several arities one `defun` per arity plus a dispatch `defun` | keeps the direct call and the tree shaker. Pass one collects every top-level `def`/`defn` name (and every `declare` name), so a definition may use one below it; a real definition still wins over a declaration. Helpers are named `c%<name>%<arity>` (`%*` for the variadic clause) -- a lone `%` no mangled identifier spells, so they stay apart from user definitions. A wrong count signals (`wrong number of arguments passed to: f`); at most one variadic clause and one clause per arity, else a named refusal. A multi-arity `defn` in a body is refused by name (several `defun`s cannot splice into expression position) |
| `declare` | nothing (`nil`) | a forward declaration in the pre-scan, so a session buffer may call what a later buffer defines |
| `defmacro` | one expander lambda over the call's argument list plus a runtime table entry, call sites expanded datum-to-datum at lower time | the expander is one lambda dispatching on the argument count (like the multi-arity `fn`), applying each arity's parameters with their destructuring prologue; the same lambda runs at lower time (through the macro evaluator) and at run time (through the `c%name%macro` table global, for `macroexpand-1`); a docstring and an attr map are skipped, `&` rest works, `&form`/`&env` are refused; the pre-scan registers the name, a call above its definition names the missing expander, a macro has no function value, a later `def`/`defn` wins the call sites back; a body sees the core builtins and the `clojure.lisp` library, not the program's definitions; four-backend parity by construction (expansion before backends), the interpreter's `eval` of a macro call expanding the same way |
| syntax-quote (`` ` ``) / `~` / `~@` | `quote` with unquote splicing over the mangled namespace | every symbol qualifies behind `c%` (the documented deviation: no namespaces); `~` lowers as code, `~@` splices a sequence into the enclosing list, vector (`apply vector`), map (plist `append`) or set (`dolist` accumulation); each `x#` binds one `(gensym "x")` per syntax-quote node (one symbol per expansion, the same at every occurrence, fresh across expansions -- fresher than the oracle's per-compilation suffixes); an unquote outside any syntax-quote and a splice outside a sequence are refusals; nested levels evaluate in the one expansion |
| `macroexpand-1` / `macroexpand` | the spliced `C%MACROEXPAND-1` / `C%MACROEXPAND` runtime over the table globals | once / to the fixpoint, each answering the expansion demangled and uppercased for printing (case folds, print-only); a non-macro head answers the form itself, demangled the same way; each names a function value; their data takes bare operator names |
| `gensym` | the ordinary `gensym` (uninterned `#:`-spelled symbol) | fresh per evaluation (per expansion in a macro, per call at run time); a string names the prefix, an integer suffix spells itself; names a function value |
| `def` | top-level `setq` of the mangled name | inside a body it still sets the global when the body runs (decided 2026-09-30, b04: keep the `setq`, document it) |
| `fn` / `#(...)` | `lambda`; several arities one `lambda` over `&rest` dispatching per arity; a named one a `labels` self-binding | `#(...)` arguments travel as one `&rest` list, `%`..`%9` as `(nth n args)`; at most 9 args; the body forms are wrapped as ONE call (`#(f a b)` -> `(f a b)`, matching the dominant spelling; multi-form bodies need an explicit `do`). The `fn` dispatch binds each arity's arguments through `let*` (no local functions, so clauses close over the outer scope); a name lowers to direct self-calls the `labels` expansion rewrites |
| destructuring (`let`/`loop`/`fn`/`defn` patterns) | `let*` pairs over one temporary per pattern | a vector pattern binds positionally through the seq view (`nth`, past the end nil; `&` the rest as a seq, itself a pattern; `:as` the whole); a map pattern through the table-aware read (`:keys` binding the short name when qualified, `:syms` from quoted symbols, `:strs` from strings, explicit locals from key expressions, `:as`, `:or` defaults); nested patterns recurse. Malformed shapes are named refusals |
| `let` | `let*` | Clojure's `let` is sequential |
| `loop`/`recur` | `labels` self call | the interpreter's tail calls make it constant-stack; inits are sequential and parameters destructure, like `let` (decided 2026-09-30, b04) |
| `->`/`->>`/`as->` | the threaded call, rewritten as datums | `->` inserts second, `->>` last; a bare name or keyword calls/reads with the value; `as->` is nested `let`s, so shadowing matches the oracle. A step over a collection literal signals (collections are not functions here) |
| `doto`/`cond->`/`cond->>`/`some->`/`some->>` | the threaded calls around one temporary | `doto` answers its (unchanged) target; `cond->` threads only on truthy tests; `some->` stops at `nil` but not at `false`, like the oracle |
| `list*` | a right fold of `cons` over the seq view | of one argument, just its seq (signalling for a non-collection, like the oracle) |
| `doseq` | nested `dolist` loops over the seq view around an implicit `do`, answering `nil` | `:when` skips, `:while` ends its level through a block (an outer level's ends the whole form), `:let` binds sequentially; patterns destructure like `let`; an empty vector runs the body once, `nil` never |
| `dotimes [i n]` | the core `dotimes` over `(truncate n)`, answering `nil` | the count runs through `truncate` first (the oracle's `intCast`: `2.5` counts `0 1`, a non-number signals there); exactly one plain name and count, else a named refusal |
| `for` | nested `dolist` loops accumulating in reverse into a strict list | `:when`/`:while`/`:let` per level like `doseq` (an inner `:while` ends only its level, measured on the oracle); empty is `nil` (the `rest`/`take` divergence, not `()`); unknown keywords the oracle's `Invalid ... keyword` refusal; an empty vector refused, like the oracle |
| `dorun`/`doall` | the strict companions: the collection (and the optional count) evaluated, answering `nil`/the collection itself, each a function value too | seqs are already strict, so realizing is evaluating; `doall` never coerces (a vector stays a vector) |
| `defmulti`/`defmethod`/`remove-method`/`get-method` | a method table plus a dispatcher `defun` | `defmulti` builds an `equal` table, a default value and a per-multimethod prefers table in three globals no identifier can spell (the suffix follows the mangled name, like the multi-arity helpers) plus a rest-args `defun` applying each call's dispatch value to the table; `defmethod` stores a parameter lambda (destructuring included); an exact hit applies, else the `C%H-DISPATCH` helper searches every method the dispatch value descends from through the multimethod's hierarchy (the global value without `:hierarchy`, a per-call expression with it), the strictly most specific wins, `prefer-method` breaks ties, and an unbroken tie signals `Multiple methods ...`; a miss with no candidate falls back to the default dispatch value (`:default` without an option) or signals `No method in ...` |
| `derive`/`underive`/`isa?`/`parents`/`ancestors`/`descendants`/`make-hierarchy`/`prefer-method` | the hierarchy runtime over the shared table runtime | a hierarchy value is a map of `:parents`/`:ancestors`/`:descendants` tables (children to wrapped sets); the global value lives in `C%H-GLOBAL`, rebound by two-argument `derive`/`underive` (answering `nil`), while three-argument forms answer an updated value (`make-hierarchy` an empty one); `isa?` is `equal`, element-wise vector derivation, or ancestor membership (two- or three-argument), answering `T`-or-false; the three reads answer (possibly empty) sets; `prefer-method` records into the multimethod's prefers table and answers the multimethod; the runtime (set helpers, transitive rebuild, dispatch search) is spliced once behind the false binding when used |
| `try`/`catch`/`finally`/`throw` | `handler-case` inside `unwind-protect`; `throw` over `error` | every catch class answers the catch-all `error` clause (first clause wins; the catch variable binds the CL condition); `throw` signals an `ex-info` value as its own condition and anything else through its Clojure-notation rendering (`rontolisp::%clojure-str-of`), so strings keep their message |
| `ex-info`/`ex-data`/`ex-message` | a condition with message and data slots | `ex-info` builds it through `make-condition` (its report prints the message); `ex-data` answers the map (`nil` for any other condition), `ex-message` the message (anything else through its Clojure-notation rendering); each works as a function value; the class plus the throw/data/message helpers are spliced once behind the false binding when used |
| `atom`/`deref`/`@`/`swap!`/`reset!`/`compare-and-set!` (and `volatile!`/`vswap!`/`vreset!`) | a tagged one-vector cell `(:C%ATOM #(value))`, like the set wrapper | every verb reads/writes the cell and answers the new value (`compare-and-set!` compares with `eql` and answers `T`-or-false); misuse signals; each works as a function value, so `(map deref atoms)` runs |
| `ns`/`require`/`use`/`import`/`in-ns` | alias wiring, defining nothing | `:as` registers an alias, `:refer`/`:use` unqualified names, `:import` simple class names, `(:refer-clojure :only/:exclude ...)` narrows the visible core; only `clojure.string` resolves (see below); an unknown namespace is an error; `in-ns` answers `nil` (the namespace is flat) |
| `clojure.string` (`join`/`split`/`split-lines`/`upper-case`/`lower-case`/`capitalize`/`trim`/`triml`/`trimr`/`trim-newline`/`blank?`/`starts-with?`/`ends-with?`/`includes?`/`index-of`/`last-index-of`/`replace`/`replace-first`/`escape`/`re-quote-replacement`/`reverse`) | core string operations over lowered arguments | reached as `alias/var`, `clojure.string/var`, or a referred bare var; each works as a function value (a rest lambda dispatching on the count); `split`/`replace` match literal strings only (regex literals are refused at the reader); an empty `split` input is nil; a positive `split` limit caps (the last part holding the rest), a negative one keeps every part, otherwise trailing empties drop |
| `subs` | `subseq` (2/3-arity) | as a value a two-or-three-argument lambda |
| Java interop (`.`, `..`, `.method`, `.-field`, `Class/member`, `Class.`, `new`, `memfn`, `proxy`) | the `java:` surface (`.kb/java-interop.md`) | `(. obj m args)` / `(.m obj args)` an instance call, `(. Class m args)` / `(Class/m args)` a static, `(Class. args)` / `(new Class args)` construction, `(Class/FIELD)` a static field (a zero-argument static method spells `(. Class m)` instead -- kept, b08), `(.-f obj)` an instance field, `(.. obj (step args) name)` nested `.` datums, `(memfn m args...)` a lambda over the instance call, `(proxy [I] [] ...)` a `java:proxy` (kept gaps, b08: no `set!` field write -- the `java:` surface has no write primitive; non-string receivers go to `java:call` and fail there); classes resolve dotted, imported, or `java.lang`; a string receiver answers the mapped core operation (a Lisp string is no host object), anything else goes to `java:call`; interpreter and JVM only (wasm rejects `java:`) | `(. obj m args)` / `(.m obj args)` an instance call, `(. Class m args)` / `(Class/m args)` a static, `(Class. args)` / `(new Class args)` construction, `(Class/FIELD)` a static field (a zero-argument static method spells `(. Class m)`), `(.-f obj)` an instance field, `(.. obj (step args) name)` nested `.` datums; classes resolve dotted, imported, or `java.lang`; a string receiver answers the mapped core operation (a Lisp string is no host object), anything else goes to `java:call`; interpreter and JVM only (wasm rejects `java:`) |
| `defprotocol` / `defrecord` / `deftype` / `definterface` / `reify` / `extend-protocol` / `extend-type` / `extend` / `satisfies?` / `gen-class` / `gen-interface` | refused by name (`protocols are not supported yet: <name>`) | REJECTED by design (decided 2026-09-30, b08): a protocol needs type-based dispatch and `defrecord`/`deftype` a value representation every backend prints, hashes and compares -- a new runtime on all four backends for no measured user (the b02 argument against a persistent-map library). The multimethod table plus the hierarchy search stays the dispatch story; `proxy` moved out (see the `proxy` row) |
| `comment` | nothing (`nil`) | |
| characters (`\a`, lowercase names, `\uXXXX`, `\oNNN`) | `LispChar`, self-evaluating | exactly the oracle's spellings, case-sensitively; anything else is the oracle's `Unsupported character` refusal |
| radix integers (`0x`, `Nr`, leading-`0` octal) | `LispInteger` (a `LispBigInteger` past the `long` range) | the sign applies outside; `2r101N` keeps the suffix rule; a shaped token that parses to nothing is the oracle's `Invalid number` refusal |
| `1M` | an exact ratio | `0.1M` is `1/10`: decimal arithmetic stays exact instead of the double's precision loss, printing as the ratio without its mark; `2N` narrows like any integer (a bignum past the `long` range) and prints without its mark |
| regex literals (`#"..."`) | refused by name (`regex literals are not supported yet`) | there is no regex runtime to lower to |
| syntax-quote/unquote (`` ` ``, `~`, `~@`) | lowered, not refused (b12) | the reader parses them into marked lists (and `x#` into one identifier); the lowering qualifies, unquotes, splices and gensyms per the rows above |
| `var`/`#'`, metadata (`^`) | refused by name | `var` stays refused everywhere (macro bodies quote symbols instead); the lowering names what is missing instead of `unknown name` |
| `set!`, `gen-class`/`gen-interface`, `var` / `#'/` and `^` metadata (`with-meta`) | refused by name | each names the missing design (`set!` needs a field-write primitive and a type to mutate -- `defrecord`/`deftype` stay refused with protocols; `var`/metadata need their designs) |
| `memfn` | a lambda over the instance-call path | `(memfn name args...)` is `(lambda (target args...) (. target (name args...)))`, so string receivers take the mapped core operation like any other instance call |
| `proxy` | `java:proxy` with a name-dispatching lambda | a single interface and no constructor arguments; each `(method [params...] body...)` becomes an `equal` arm applying a lambda to the Java arguments (which are the params -- no `this`); a superclass, constructor arguments, several interfaces and multi-arity methods are refused by name; interpreter and JVM only, like all interop |
| `if`/`when`/`cond`/`do`/`and`/`or` | the core forms | `cond` with an odd trailing arm treats it as the default; `:else` is true; every test treats `nil` and the false object as falsey (an explicit null-or-false check, the test bound once to a temporary) |
| `not` | an explicit null-or-false check answering `T`-or-false | |
| `<`/`>`/`<=`/`>=` | the Common Lisp operation, answering `T`-or-false | so `(= false nil)` is false and printing spells it `false` |
| `nil?` | `null`, answering `T`-or-false | `(nil? false)` is false |
| `false?`/`true?`/`boolean?` | their predicates (`eq` against the false object / `T`), answering `T`-or-false | as values, lambdas answering `T`-or-false too (every predicate value does, so `(map odd? [1 2])` prints `(true false)` like the oracle) |
| `map`/`filter`/`reduce`/`apply`/`concat` | `mapcar`/`remove-if-not`/`reduce`/`apply`/`append` over the seq view | lists pass through untouched (no copy); every other collection coerces first, so vectors, strings, maps and sets all work; `reduce` is 2/3-arity with the Clojure argument order (`(reduce f val coll)`) mapped onto CL `reduce` `:initial-value`; `apply` spreads any leading arguments over the seq-coerced last one (`(apply f x args)`), like CL `apply`; `(concat)` is nil |
| `first`/`rest`/`next`/`seq`/`cons` | `car`/`cdr` over the seq view, the view itself, `cons` onto the view | a seq IS a strict list view (decided 2026-09-30, b03): lists pass through, vectors/strings coerce, maps contribute one two-vector per entry and sets one member per element (both in the table's walk order, unspecified), nil and the false object are empty, anything else signals like the oracle; no laziness, chunking or memoisation -- the only sequence all four backends already share is the cons list, so a lazy struct would add a representation every backend prints, hashes and compares (the b02 argument against a persistent-map library) |
| `nth` (2/3-arity) | the seq view indexed, past the end the default | the 2-arity answers nil past the end where the oracle throws; as a VALUE a lambda with the Clojure order (`(lambda (c i) ...)`), since a bare `#'NTH` takes the index first |
| `quot` | `truncate` | as a VALUE a two-argument lambda over `truncate` |
| `take`/`drop` | a labels self call over the seq view / `nthcdr` over the view | strict; an over-long take/drop answers the whole/empty seq (nil, where the oracle prints `()`) |
| `keep`/`keep-indexed`/`map-indexed` | `remove-if` of nils over `mapcar` / labels self calls with an index | strict; `keep` keeps `false` (only nil drops) and a signalling function signals (`(keep inc [1 nil 2])` throws, like the oracle); as values two-argument lambdas |
| `every?`/`some` | labels self calls testing null-or-false | `every?` answers `T`-or-false directly (empty is true); `some` answers the predicate's own value (not the member); as values two-argument lambdas |
| `remove` | `remove-if` over the seq view | the complement of `filter`; as a value a two-argument lambda |
| `distinct` | a labels self call with a seen table | first occurrences kept in order, `equal` membership; as a value a one-argument lambda |
| `partition` | a labels self call over `take`/`nthcdr` | 2/3-arity (size, optional step defaulting to size); an incomplete tail drops, like the oracle; a non-positive size signals; a pad argument is refused by arity; as a value a one- or two-argument lambda |
| `take-while`/`drop-while` | labels self calls stopping past the truthy prefix | `false` stops like nil; as values two-argument lambdas |
| `interleave`/`interpose` | a labels self call over the seq-view list / the head plus a `mapcan` | `interleave` stops at the shortest, like the oracle; `(interleave)` is nil; as values rest lambdas |
| `zipmap` | a labels self call filling a fresh table | stops at the shorter side, like the oracle; as a value a two-argument lambda |
| `group-by` | one `dolist` pass plus a vector-freezing `maphash` | values are vectors in encounter order; of empty, the empty map; as a value a two-argument lambda |
| `sort`/`sort-by` | `sort` over a copy with the default or wrapped comparator | the default orders numbers, strings, characters and keywords (anything else signals); a comparator runs on truthiness through the null-or-false test; as values rest lambdas |
| `last`/`butlast`/`second` | `car` of `last` / `butlast` / `cadr` over the seq view | of empty, nil; as values one-argument lambdas |
| a set/map/vector literal in call position, or as a function value | the member / table-aware read / `nth` with an optional default | `(#{:h} :h)` is `:h`, `({:a 1} :b :d)` is `:dflt`, `([10 20] 5 :d)` is `:d` (the `nth` past-the-end-is-default position); `(filter #{:h} ...)` runs through the same lambda |
| `update`/`update-in`/`assoc-in`/`get-in` | a fresh table over the old pairs with the rewritten pair / the nested walk | `update` applies `(apply f (get m k) args...)`; `update-in` recurses (an empty key vector is refused, like the oracle's throw); `assoc-in` builds missing levels (no keys associates under nil, like the oracle); `get-in` threads the default through every level; as values lambdas walking the key sequence at run time |
| `select-keys`/`merge-with`/`into`/`frequencies` | a fresh table over the present keys / grown map by map through `f` / a `conj` fold / one `dolist` pass | `select-keys` of nil is the empty map; `merge-with` of no maps is nil (a transducer argument is refused); `into` targets lists/vectors/maps/sets; as values lambdas |
| `comp`/`partial`/`complement`/`constantly`/`identity`/`memoize`/`trampoline` | right-nested closures / fixed-plus-rest closures / the negated predicate / the kept value / the value itself / an `equal`-tabled closure / a labels self call over thunks | no functions is `identity` for `comp`; `complement` answers `T`-or-false; `memoize` keys the argument list structurally; `trampoline` invokes zero-argument results until a non-function answers; each a function value too |
| `when-let`/`if-let`/`when-not`/`if-not`/`when-first` | `let*` pairs over one temporary plus `if` on null-or-false | `when-let`/`if-let` destructure like `let` (testing the whole init); `when-not`/`if-not` swap the branches; `when-first` binds the head of the seq view |
| `coll?`/`string?`/`symbol?` | `or` over the shapes / `stringp` / `symbolp` minus the booleans and nil | `coll?` excludes strings (which the runtime stores as vectors) and nil, like the oracle; as values lambdas answering `T`-or-false |
| `instance?`/`class` | the class name mapped onto the shared predicates / a `cond` answering a kind keyword | only the core classes lower (`String`, `Long`, ...), anything else a named refusal; `class` answers `:map`/`:vector`/`:set`/`:list`/`:string`/`:number`/`:keyword`/`:symbol`/`:char`/`:boolean`/`:nil`/`:function`/`:atom` (host classes exist on no wasm backend); as values lambdas (`instance?` has none -- an arity error stays one) |
| `int`/`long`/`unchecked-add` | `truncate` / `+` | a non-number signals, like the oracle; `unchecked-add` never wraps (bignums); as values lambdas |
| `spit`/`slurp`/`line-seq` | `with-open-file` writes / a `read-char` loop into a string stream / a `read-line` loop | interpreter and JVM only (no filesystem on wasm); `spit` supersedes unless `:append` is truthy; `line-seq` takes a path and answers strictly; each a function value too; `file-seq`/`reader` stay refused by name |
| `format` | the Java directives translated to Common Lisp over Clojure-notation arguments | the format string must be literal; `%s` converts like `str` (nil spells `"null"`), `%b` the boolean spelling, numbers the matching checked directive; `%e`/`%g`, flags and anything else are named refusals |
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
| `str` | `concatenate 'string` over mapped parts | `(str)` is `""`; `nil` maps to `""`, `true`/`false` to `"true"`/`"false"`, a keyword to its colon spelling, collections in Clojure notation through `rontolisp::%clojure-str-of` |
| `pr-str` | `concatenate 'string` over mapped parts joined with a space | the readable arm of `str` (like `pr`): `(pr-str)` is `""`, `nil` maps to `"nil"` |
| `println`/`print`/`pr`/`prn` | one `rontolisp::%clojure-write-datum` call per part straight to `*standard-output*`, spaces as `write-char`, the newline as `terpri`, answering nil | parts joined with a single space, like Clojure; `pr`/`prn` convert readably, so strings print quoted; collections print in Clojure notation; no `with-output-to-string` ever reaches a compiled program (a literal one flips a WASM module into EH mode -- measured gate, `.todo/artefacts/b07-clojure-print/NOTES.md` finding 6); the print family answers nil, like the oracle |
| `true` | `LispTrue` (`T`) | a raw symbol spelled `T` is unbound -- `evalSymbolRef` looks the name up |
| `nil` | `NIL` | falsey |
| `false` | the value of `rontolisp::%clojure-false`, bound before anything else runs | a DISTINCT non-`NIL` symbol spelled `false` (the distinct-object treatment `scheme.lisp`'s `#f` uses); falsey in every conditional through the lowered tests; `eq`-comparable by name on every backend |
| a keyword `:foo` | the list `(:C%KEYWORD "foo")` holding its spelling verbatim (case-preserved) | data, compared by `equal` through the cons shape; `:a` and `:A` stay apart; every print arm spells it with its colon, nested or not |
| a keyword in call position `(:k m)` / `(:k m dflt)` | the same table-aware read `get` lowers to | the idiomatic map lookup, over b02's map runtime (sets answer their member, vectors/strings their element) |
| a keyword as a function value (`map`/`filter`/`reduce`/`apply` over `:k`) | a one-argument lambda over the same read | `(map :k coll)` reads the key out of each member |
| a namespaced keyword `:a/b` | the same wrapper over the whole spelling | opaque data: prints and compares whole; `::`-auto-resolve is refused by name (there is no namespace to resolve against) |
| a bare `(ns name)` | nothing | a namespace declaration defines nothing; clauses wire aliases (see the `ns` row above) |
| `quote` | `quote`, with symbols mangled and vectors re-emitted as `vector` calls | a quoted map or set is the construction over the quoted elements |
| `get` with a default | `gethash`'s own default argument | IN: `(get m k dflt)` answers `dflt` past the end, like the oracle |
| transients | refused by name (`transients are not supported yet: assoc!`) | OUT: `transient`, `persistent!`, `assoc!`, `dissoc!`, `conj!`, `disj!` -- there is no transient runtime behind the tables |

## Deviations (each a real work item)

- Printing runs through the spliced `clojure.lisp` library (`eval/ClojureLibrary`,
  the `SchemeLibrary` shape: `forms`, `isClojureFunction`, `process`), decided
  2026-09-30 (b07): `println`/`print`/`pr`/`prn` write each part straight to the
  stream behind `rontolisp::%clojure-write-datum` (spaces and the newline as their
  own writes, answering nil like the oracle), `str`/`pr-str` build from
  `rontolisp::%clojure-str-of` parts, and the `clojure> ` echo renders through it
  (the `ECHO` shape). The renderer writes straight to the stream it is given
  (`write-string`/`write-char`/`princ`, `princ` doubling for the unreadable
  fallback), so no `with-output-to-string` ever reaches a compiled program. Vectors print
  `[1 :a s]`, maps `{:a 1}`, sets `#{1}`, lists `(true false nil :k)`, quoted
  symbols demangled (`e2e-foo`), chars `\a` (readable) / `a` (plain), strings
  readable when asked. `nil` stays `nil` (it IS the empty list; never `()`),
  map/set walk order stays unspecified (same as `keys`/`vals`, so only
  single-entry maps and single-member sets print deterministically), unreadable
  values (functions as `#<procedure>`, conditions, host objects) stay `#<..>`.
  Cycles print with Scheme-scale datum labels (`#0=(1 . #0#)`), copied from
  `%scheme-print`'s design and extended to hash-table nodes (a map can close a
  cycle through an atom) -- copied, not shared, because the node shapes differ
  and sharing would splice `scheme.lisp` into every Clojure program. The
  `*print-length*`/`*print-level*` loss is documented, not implemented (a routed
  `println` never passed through `%print-cased` either); `~S`/`~A` on Clojure
  values stay Common Lisp notation (`format` is a CL surface);
  `print-method`/`pprint` stay absent.
- Cost (2026-09-30, x86-64 Linux, Java 25): the write path (`println` of a number,
  a string, a vector, a map) compiles to 9,546 / 16,776 / 25,778 / 27,514 B of
  wasm (51,329 B of class for the string case, 1,759 B without printing); adding
  `str` (the string-stream half) reaches 29,778 B. The analogous Scheme shapes
  measure 500 B (`(display "hi")`, which lowers behind no helper at all),
  8,285 B (`(display (list 1 2))`, the full `%scheme-print` machinery) and
  30,009 B (a string-port program) -- the same order, so the design (one
  stream-direct writer, `str` over `make-string-output-stream` /
  `get-output-stream-string`, never `with-output-to-string`, which measured
  11,948 B vs 6,429 B for the same writes) stands as drawn.
- Maps and sets keep the shared hash-table runtime (`.kb/hash-tables.md`),
  decided 2026-09-30 (b02): an `equal` table per map/set, copy-on-write for
  every verb, so the persistent semantics holds observably on all four backends
  with no new runtime and no per-backend code. A persistent-map library spliced
  like `scheme.lisp` was the alternative; it would have added a representation
  every backend prints, hashes and compares, for no measured user beyond what
  the table already does.
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
- protocols, `set!`, regex
  literals, `var`/`#'`, metadata `^`: all absent, each refused by name (backquote
  lowered in b12, below).
  Hierarchies and `ex-info` lowered in b08 (below); protocols were rejected by
  design there instead (a per-backend value model for `defrecord`/`deftype`, the
  b02 argument). Catch clauses are catch-all in order (the first handles any condition, where the
  oracle dispatches by class); multimethod dispatch values compare like `equal`
  table keys (vectors by identity), widened by the hierarchy search (most specific
  wins, then `prefer-method`, like the oracle); a hierarchy value prints as its
  `#<HASH-TABLE ...>` map and its reads answer wrapped sets; an `ex-info` value
  prints as its `#<C%E-EX-INFO ...>` condition; atoms print unreadably (`#<Atom value>`),
  functions as `#<procedure>`; `split`/`replace` match literal strings, never patterns (the documented
  literal-only position, decided 2026-09-30 b08: no regex runtime on any backend,
  so `#"..."` stays refused at the reader and `clojure.string`/`String` splitting
  keeps literal semantics, pinned by the spec's `string-replace-and-split-stay-literal`
  case); `indexOf`
  answers `-1` when missing, like the oracle (where `clojure.string/index-of`
  answers nil); a zero-argument `Class/member` reads a static field (kept, b08: a
  zero-argument static method spells `(. Class m)`); non-string receivers go to
  `java:call` and fail there (kept, b08: only strings get the mapped core
  operation); `proxy` methods take the Java arguments only (no `this`, kept b08:
  nothing to close over); a head-position call to a `VARIABLE`-kind name holding
  a real function (a `let` binding of one, a `def`'d one) is a `funcall` of the
  value cell, while any other variable goes through the prelude dispatcher
  (decided 2026-10-01, b15: `defn` names stay direct and a
  `declare`d-but-never-defined name keeps its direct-call error, but a parameter
  may hold a collection -- `every?` over a `partial`-passed set runs).
- `def` inside a body sets the global when the body runs (decided 2026-09-30, b04:
  keep the `setq`, document it). `defn` inside a body works only in statement
  position, and a multi-arity one only at the top level (several `defun`s cannot
  splice into expression position -- a named refusal). `cond` keeps the lenient
  reading (an odd trailing arm is the default), deviating from the oracle, pinned by
  the spec's `if-when-cond-do-and-or` case (decided 2026-09-30, b04).
- Errors name the innermost form's source position: the reader's `LispReadException`s
  are prefixed (`file:line:column` when the file is known), and the lowering
  re-reports its own errors (`unknown name`, arity refusals, ...) against the
  reader's per-datum offsets, innermost first (`.kb/source-positions.md`).
- Printing: `print-method`/`pprint` are absent; `pr`/`prn`/`pr-str` are the
  readable arms of `print`/`println`/`str` (strings print quoted, `pr-str` joining
  with a space like `pr`); the print family answers nil, like the oracle. An atom
  prints unreadably (`#<Atom value>`) and a function as `#<procedure>` -- the
  b05/b08 values route through the same printer, so none leaks its wrapper.

## A session

`ClojureSession` keeps the lowering across buffers: every buffer declares its own
top-level `def`/`defn` names into the session's globals first, so a later buffer may
call what an earlier one defined. `SourceSession` prompts `clojure> `, echoes through the spliced `clojure.lisp`
printer (the `ECHO` shape, readable), and decides completeness by bracket counting over `()[]{}` 
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
pinned in `ClojureLoweringTest`. Binding and control forms lower the same way (b04):
sequential and map destructuring in `let`/`loop`/`fn`/`defn` parameters, the
threading family as datum rewrites around one temporary, multi-arity `defn` as
per-arity `defun`s plus a count dispatch (multi-arity `fn` through one `lambda`,
named `fn` through `labels`), `declare` as a pre-scan forward declaration,
`list*` as a `cons` fold -- each pinned in `clojure-spec.yaml` (run on all four
backends) or, for the refusals (`doseq`/`dotimes`/`for`, malformed patterns and
arities), in `ClojureLoweringTest`. State, dispatch, namespaces and reader
literals lower the same way (b05): `try`/`catch`/`finally`/`throw`,
`atom`/`deref`/`swap!`/`reset!`/`compare-and-set!` (and the `volatile!` trio),
`defmulti`/`defmethod`/`remove-method`/`get-method`, `ns` clauses wiring
`clojure.string` (plus `subs`), characters, radix integers and exact `M`
decimals -- each pinned in `clojure-spec.yaml` (run on all four backends) or,
for the refusals (regex, hierarchies, protocols, `ex-info`, `set!`, backquote,
`var`, metadata, unknown namespaces), in `ClojureReaderTest`/`ClojureLoweringTest`.
Macros lower the same way (b12): `defmacro` (multi-arity, `&` rest, docstring and
attr-map skip, `declare` pre-scan, whole-file pre-scan, session-aware) as one expander
lambda over the call's argument list plus a runtime table entry, call sites expanded
datum-to-datum at lower time through the macro evaluator (`eval/ClojureMacroTime`,
lazy, one per file or session), syntax-quote as `quote` with unquote splicing over the
mangled namespace (`~` lowers as code, `~@` splices into lists, vectors, maps and sets,
`x#` one gensym per expansion -- fresher than the oracle's per-compilation suffixes),
`macroexpand-1`/`macroexpand` over the table plus a demangling printer, `gensym` as the
ordinary uninterned symbol, `var`/`#'` still refused (bodies quote symbols instead),
`&form`/`&env` refused -- each pinned in `clojure-spec.yaml` (`chain_1..5` plus
`unless` plus `bench`, expansion answers and runtime answers, run on all four backends)
or, for the `~`/`~@` depth errors, the `x#` freshness across two expansions, the
`macroexpand-1` shape and the multi-arity macro dispatch, in `ClojureLoweringTest`;
the `x#` lexing in `ClojureReaderTest`, the buffer-to-buffer macro in
`ClojureSessionTest`. Interop (`.`, `..`, `Class/member`, `Class.`, `new`) lowers to the `java:`
surface, pinned by `ClojureInteropTest` on the interpreter and the JVM (wasm
rejects `java:`, so it cannot join the spec). Hierarchies, exception data and
the interop gaps lower the same way (b08): `derive`/`underive`/`isa?`/
`parents`/`ancestors`/`descendants`/`make-hierarchy`/`prefer-method` and
`defmulti` `:hierarchy` over a hierarchy runtime spliced once behind the false
binding (the unified dispatcher searches `isa?` candidates on a miss, most
specific wins, then preferences), `ex-info`/`ex-data`/`ex-message` over a
condition with message and data slots (`throw` signals one as its own
condition), `memfn` as a lambda over the instance call, single-interface
`proxy` through `java:proxy`, and the literal-only regex position -- each pinned
in `clojure-spec.yaml` (run on all four backends) or, for the interop legs
(`proxy`, host-object `memfn`, literal `String/split`), in `ClojureInteropTest`;
what stays refused (`defprotocol` and friends -- rejected by design, `set!`,
`var`/`#'`, metadata `^`, multi-interface `proxy`, regex literals)
stays pinned in `ClojureReaderTest`/`ClojureLoweringTest`. Head-position calls to
`VARIABLE`-kind names holding real functions lower to `funcall`, anything else
through the prelude dispatcher (b09, widened 2026-10-01 b15): a `let` binding of a
real function and a `def`'d one each call through the value cell, while a `defn`
parameter (which may hold a collection) dispatches, a `defn` name stays a direct
call and a `declare`d-but-never-defined name keeps its direct-call error --
pinned in `clojure-spec.yaml` (run on all four backends) and in
`ClojureLoweringTest` (the lowered shapes). Imperative loops and comprehensions lower the same way (b10):
`doseq` as nested `dolist` loops over the seq view answering `nil` (an empty vector
runs the body once), `dotimes` as the core `dotimes` over a truncated count,
`for` as nested `dolist` loops accumulating in reverse into a strict list
(`:when`/`:while`/`:let` per level, an inner `:while` ending only its level like the
oracle, destructuring through the `let` lowering, `dorun`/`doall` as the strict
companions) -- each pinned in `clojure-spec.yaml` (run on all four backends) or,
for the lowered shapes and the modifier/arity refusals, in `ClojureLoweringTest`.
Printing runs through the spliced library (b07): `println`/`print`/`pr`/`prn`
write straight to the stream, `str`/`pr-str` build strings, the `clojure> ` echo
renders readably, `throw`/`ex-message`/multimethod-miss messages render in
Clojure notation, cycles print with datum labels -- each pinned in
`clojure-spec.yaml` (run on all four backends) or, for the call shapes and the
`pr-str` value, in `ClojureLoweringTest`; the `clojure>` REPL transcript is
re-pinned in `RontoLispCliTest` (and `PlaygroundReplTest`), the splice in
`ClojureLibraryTest`, the package rule (`clojure` sees only the AST types and
`reader`; the `.lisp` resource is data) in `PackageCycleTest`. The verb backlog
lowers the same way (b15): the seq family (`keep`/`keep-indexed`/`map-indexed`/
`every?`/`some`/`remove`/`distinct`/`partition`/`take-while`/`drop-while`/
`interleave`/`interpose`/`zipmap`/`group-by`/`sort`/`sort-by`/`last`/`butlast`/
`second`), collections as functions (literals in call position and as values,
variables through the dispatcher), the map family (`update`/`update-in`/
`assoc-in`/`get-in`/`select-keys`/`merge-with`/`into`/`frequencies`), the
higher-order family (`comp`/`partial`/`complement`/`constantly`/`identity`/
`memoize`/`trampoline`), the predicates and casts (`coll?`/`string?`/`symbol?`/
`instance?`/`class`/`int`/`long`/`unchecked-add`), the conditional bindings
(`when-let`/`if-let`/`when-not`/`if-not`/`when-first`) and the IO entry points
(`spit`/`slurp`/`line-seq`, the Java-directive subset of `format`) -- each pinned
in `clojure-spec.yaml` (run on all four backends; the corpus slices are
`keep-indexed` `index_of_any`, `map-indexed` `exploring`, `partition`/`comp`/
`partial`/`every?` `functional` `count-runs`, `update-in` `note`), the refusals
(regex literals, `clojure.spec`/`xml`/`java.io` namespaces, transducers,
`file-seq`/`reader`, `%e`/`%g`/flags) in `ClojureLoweringTest`, and the file IO
plus the `keep`/`update` signals in `ClojureInteropTest` (no filesystem on wasm).
Predicate values answer `T`-or-false (so `(map odd? [1 2])` prints `(true false)`
like the oracle); `filter`/`remove` test Clojure truthiness around the call.
Multi-entry maps and multi-member sets never print in the spec (the walk order is
unspecified); only single-entry/single-member shapes pin the notation.

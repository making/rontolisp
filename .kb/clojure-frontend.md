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
  datums, its own case-sensitive reader), `ClojureLowering` (the dispatch hub:
  datums -> core forms) plus one slice per feature (`ClojureBindingLowering`,
  `ClojureSeqLowering` / `ClojureLazyLowering` / `ClojureLoopLowering` /
  `ClojureFilterLowering`, `ClojureCollectionLowering` / `ClojureFnLowering` /
  `ClojureUpdateLowering`, `ClojureStringLowering`, `ClojureStateLowering`,
  `ClojureDispatchLowering` / `ClojureHierarchyLowering` /
  `ClojureProtocolLowering`, `ClojureMacroLowering`, `ClojureNamespaceLowering`,
  `ClojureInteropLowering`, `ClojureTestLowering`, `ClojureCoreLowering` (the b57
  backlog), `ClojureTransducerLowering` (b60), each taking the hub as its first argument and
  re-entering it for subforms) and the stateless `ClojureLowerUtil`,
  `Clojure` (the facade), `ClojureSession` + `ClojureTopLevel`
  (the REPL session), `ClojureNsState` (one namespace's wiring and interns),
  `ClojureSourcePath` + `ClojureFiles` (where a required namespace's file is found, and
  the seam's file access -- "Namespaces and project files" below). The hub is the named cycle root in `PackageCycleTest`
  (recursive-descent lowering, like the two expression compilers).
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
| identifier `foo` | symbol `c%foo`, always prefixed; a global var of namespace `n` is `c%n/foo` (`user`'s keep `c%foo`) | the prefix holds a lowercase letter and `%`, so no name can reach a `LispNames` case label, a lambda-list keyword or `T`/`NIL`; the spelling is otherwise verbatim, so `Foo` and `foo` stay apart; `:` -> `%c`, `%` -> `%%` keeps the map injective; a local never carries a namespace ("Namespaces and project files" below) |
| `defn` | `defun` of the mangled name, called directly; a head-position call to a `VARIABLE`-kind name holding a real function (a `let` binding of one, a `def`'d one) is a `funcall` of the value cell instead, while any other variable goes through the prelude dispatcher (`rontolisp::%clojure-call`: functions through `apply`, collections through their lookup, like `IFn`), so higher-order `defn` parameters run on collections too; a `declare`d-but-never-defined name keeps its direct-call error; several arities one `defun` per arity plus a dispatch `defun` | keeps the direct call and the tree shaker. Pass one collects every top-level `def`/`defn` name (and every `declare` name), so a definition may use one below it; a real definition still wins over a declaration. Helpers are named `c%<name>%<arity>` (`%*` for the variadic clause) -- a lone `%` no mangled identifier spells, so they stay apart from user definitions. A wrong count signals (`wrong number of arguments passed to: f`); at most one variadic clause and one clause per arity, else a named refusal. A multi-arity `defn` in a body is refused by name (several `defun`s cannot splice into expression position). A `^:dynamic` one keeps its `defun`(s) and installs the function in the value cell behind a `defparameter` of it, so calls route through the value cell (a `funcall`, like a `def`'d function) and `binding` rebinds it with dynamic extent; `recur` and the arity-dispatch helpers stay direct calls to the function cell. The pre-scan registers a `^:dynamic` `defn` name as a variable, so even a forward call routes through the value cell |
| `declare` | nothing (`nil`) | a forward declaration in the pre-scan, so a session buffer may call what a later buffer defines |
| `defmacro` | one expander lambda over the call's argument list plus a runtime table entry, call sites expanded datum-to-datum at lower time | the expander is one lambda dispatching on the argument count (like the multi-arity `fn`), applying each arity's parameters with their destructuring prologue; the same lambda runs at lower time (through the macro evaluator) and at run time (through the `c%name%macro` table global, for `macroexpand-1`); a docstring and an attr map are skipped, `&` rest works, `&form`/`&env` are refused; the pre-scan registers the name, a call above its definition names the missing expander, a macro has no function value, a later `def`/`defn` wins the call sites back; a body sees the core builtins and the `clojure.lisp` library, not the program's definitions; a core name shadows (b63, "Core-named macros" below); four-backend parity by construction (expansion before backends), the interpreter's `eval` of a macro call expanding the same way |
| syntax-quote (`` ` ``) / `~` / `~@` | `quote` with unquote splicing over the mangled namespace | a symbol naming a var the defining namespace sees (own or referred; locals are no vars at read time, like the oracle) qualifies as `ns/name`, `user/` included (b56), so the expansion reaches it from any namespace; a core name and an unresolved symbol stay bare (the documented deviation: the oracle spells `clojure.core/let`, `user/x`); `~` lowers as code, `~@` splices a sequence into the enclosing list, vector (`apply vector`), map (plist `append`) or set (`dolist` accumulation); each `x#` binds one `(gensym "x")` per syntax-quote node (one symbol per expansion, the same at every occurrence, fresh across expansions -- fresher than the oracle's per-compilation suffixes); an unquote outside any syntax-quote and a splice outside a sequence are refusals; nested levels evaluate in the one expansion |
| `macroexpand-1` / `macroexpand` | the spliced `C%MACROEXPAND-1` / `C%MACROEXPAND` runtime over the table globals and the call site's macro scope | once / to the fixpoint, each answering the expansion as the mangled data itself, so `=` against a quoted form holds and the Clojure printer (which demangles `c%` symbols) spells the oracle's lowercase; a non-macro head answers the form itself; each names a function value; a head resolves through the call site's namespace (b56): the lowering hands a quoted alist of the spellings the table global cannot spell itself (a bare own macro outside `user`, a bare referred one, an alias-qualified one) to its table global, and a `user/m` spelling reads as `c%m%macro` |
| `gensym` | the ordinary `gensym` (uninterned `#:`-spelled symbol) | fresh per evaluation (per expansion in a macro, per call at run time); a string names the prefix, an integer suffix spells itself; names a function value |
| `def` | top-level `setq` of the mangled name | inside a body it still sets the global when the body runs (decided 2026-09-30, b04: keep the `setq`, document it); the value lowers against the OLD binding first, so `(def p (memoize p))` after a `(defn p ...)` captures the function cell (`#'c%p`), not the still-unbound value cell (decided 2026-10-02, b52) |
| `fn` / `#(...)` | `lambda`; several arities one `lambda` over `&rest` dispatching per arity; a named one a `labels` self-binding, an anonymous one the same binding only when a `recur` reaches it | `#(...)` arguments travel as one `&rest` list, `%`..`%9` as `(nth n args)`; at most 9 args; the body forms are wrapped as ONE call (`#(f a b)` -> `(f a b)`, matching the dominant spelling; multi-form bodies need an explicit `do`). The `fn` dispatch binds each arity's arguments through `let*` (no local functions, so clauses close over the outer scope); a name lowers to direct self-calls the `labels` expansion rewrites; a `recur` in any `fn` body (named or not) calls the enclosing clause directly, checked against its arity (decided 2026-10-01, b17) |
| destructuring (`let`/`loop`/`fn`/`defn` patterns) | `let*` pairs over one temporary per pattern | a vector pattern binds positionally through the seq view (`nth`, past the end nil; `&` the rest as a seq, itself a pattern; `:as` the whole); a map pattern through the table-aware read (`:keys` binding the short name when qualified, `:syms` from quoted symbols, `:strs` from strings, explicit locals from key expressions, `:as`, `:or` defaults); nested patterns recurse. Malformed shapes are named refusals |
| `let` | `let*` | Clojure's `let` is sequential |
| `letfn` | one `labels` over every entry, like a named `fn` self-binding per entry | every name is pre-scanned first (the `defn` mutual-recursion precedent), so siblings call each other directly and the body calls them; each entry lowers like a named-`fn` clause with its own recur target (destructuring included); an entry's name is a function value; an empty binding vector is just the body, and a later entry shadows
an earlier one with the same name (decided 2026-10-01, b17) |
| `loop`/`recur` | `labels` self call | `recur` targets the innermost enclosing `loop`, named or anonymous `fn`, `defn` clause or `letfn` entry (a recur-target stack, not a single loop slot -- a plain lambda pushes nothing, so a `recur` passes through it; decided 2026-10-01, b17); each multi-arity clause is its own boundary, so the count must match the enclosing clause (multi-arity calls route through the dispatch); a wrong count is a named refusal, `recur` outside any target stays one; the interpreter's tail calls (wasm `return_call`) make it constant-stack -- the spec's 5000-deep case overflows a non-tail expansion on wasm (about 3000 there) and fits the JVM worker's frame budget on every backend; inits are sequential and parameters destructure, like `let` (decided 2026-09-30, b04); a `recur` reaching a variadic clause splits into a worker plus its `&rest` head, like the multi-`defn` helpers (decided 2026-10-01, b27): the worker takes the rest as an ordinary parameter, so the `recur` call assigns exactly, while normal calls wrap through the head, like the oracle; single-arity `defn`/`fn`/`letfn` entries and multi-arity clauses with a used variadic target (an unused variadic keeps its single shape); a `recur` reaching a variadic stored-method lambda (`defmethod`, inline and extended protocol methods, `reify`) splits the same way (decided 2026-10-01, b36): the stored lambda becomes a worker plus its `&rest` head, so the `recur` call assigns exactly while normal dispatch wraps through the head, like the oracle (an unused variadic keeps its bare lambda); tail position and the `try` boundary are enforced like the oracle (decided 2026-10-01, b26): only a `recur` in its target body's tail position lowers -- `if` arms, `let*`/`progn` tails, `labels` bodies, `cond` arms, `do`, `when` and the dispatch lets -- anywhere else its `Can only recur from tail position` refusal; a `try` between the `recur` and its target is the oracle's `Cannot recur across try` refusal (a barrier beside the target stack, so a target opened inside the `try` still recurs); a `binding` body and a non-empty `with-open` body lower behind the same barrier (the oracle wraps both in a `try`, the empty `with-open` staying a bare `do`), the inits outside it -- a `recur` in an init is the tail refusal instead (decided 2026-10-01, b35); a `lazy-seq` body is its own zero-arity target (decided 2026-10-01, b28): a `recur` in its tail position calls the thunk itself, checked against arity 0 (a wrong count names 0), anywhere else its `Can only recur from tail position` refusal; `#()` recurs unchecked (its arity is the highest `%N`, known only after the body lowers), so a wrong count signals at run time |
| `->`/`->>`/`as->` | the threaded call, rewritten as datums | `->` inserts second, `->>` last; a bare name or keyword calls/reads with the value; `as->` is nested `let`s, so shadowing matches the oracle. A step over a collection literal signals (collections are not functions here) |
| `doto`/`cond->`/`cond->>`/`some->`/`some->>` | the threaded calls around one temporary | `doto` answers its (unchanged) target; `cond->` threads only on truthy tests; `some->` stops at `nil` but not at `false`, like the oracle |
| `list*` | a right fold of `cons` over the seq view | of one argument, just its seq (signalling for a non-collection, like the oracle) |
| `doseq` | nested `dolist` loops over the seq view around an implicit `do`, answering `nil` | `:when` skips, `:while` ends its level through a block (an outer level's ends the whole form), `:let` binds sequentially; patterns destructure like `let`; an empty vector runs the body once, `nil` never |
| `dotimes [i n]` | the core `dotimes` over `(truncate n)`, answering `nil` | the count runs through `truncate` first (the oracle's `intCast`: `2.5` counts `0 1`, a non-number signals there); exactly one plain name and count, else a named refusal |
| `for` | nested `dolist` loops accumulating in reverse into a strict list | `:when`/`:while`/`:let` per level like `doseq` (an inner `:while` ends only its level, measured on the oracle); empty is `nil` (the `rest`/`take` divergence, not `()`); unknown keywords the oracle's `Invalid ... keyword` refusal; an empty vector refused, like the oracle |
| `dorun`/`doall` | the strict companions: the collection (and the optional count) evaluated, answering `nil`/the collection itself, each a function value too | seqs are already strict, so realizing is evaluating; `doall` never coerces (a vector stays a vector) |
| `defmulti`/`defmethod`/`remove-method`/`get-method` | a method table plus a dispatcher `defun` | `defmulti` builds an `equal` table, a default value, a per-multimethod prefers table and an `Object`-method slot in four globals no identifier can spell (the suffix follows the mangled name, like the multi-arity helpers) plus a rest-args `defun` applying each call's dispatch value to the table (a keyword dispatch value takes the lookup plus an optional default, like `(:k m dflt)`, so multi-argument calls dispatch on it -- decided 2026-10-01, b39; set/vector values share the rest-tolerant shape while a map literal stays the attr-map, like the oracle); `defmethod` stores a parameter lambda (destructuring included) under the dispatch value lowered by `dispatchKeyForm` -- a class spelling (`String`, `Number`, ..., dotted/`java.lang`/imported names, known record/deftype names) onto the keyword the `class` dispatcher produces for it, `nil` onto the `(:C%NIL)` marker (the dispatcher maps a true nil onto it first, so no table ever keys on nil, while a dispatch value that literally is `:nil` keeps its keyword row, like the oracle; a `class` call inside the dispatch function answers nil itself for a nil
argument, so the null test maps it onto the marker too -- bare, wrapped in another
function, through a named `defn` / `def`'d function (the `defmulti` re-lowers the
recorded definition with the dispatch lowering, like the oracle), or a call to
one nested inside an inline dispatch datum (inlined at the call site the same
way), `Object` under the `:object` keyword plus the catch-all slot, `::`-keywords resolved like anywhere else, literal vectors element by element; an exact hit applies, else the `C%H-DISPATCH` helper searches every method the dispatch value descends from through the multimethod's hierarchy (the global value without `:hierarchy`, a per-call expression with it), the strictly most specific wins, `prefer-method` breaks ties, and an unbroken tie signals `Multiple methods ...`; a miss with no candidate tries the `Object` slot past the search but ahead of the default dispatch value (`:default` without an option, an arbitrary keyword with the `:default` option -- the corpus's `:everything-else`, stored per-multimethod like `:default` today) or signals `No method in ...` |
| `derive`/`underive`/`isa?`/`parents`/`ancestors`/`descendants`/`make-hierarchy`/`prefer-method` | the hierarchy runtime over the shared table runtime | a hierarchy value is a map of `:parents`/`:ancestors`/`:descendants` tables (children to wrapped sets); the global value lives in `C%H-GLOBAL`, rebound by two-argument `derive`/`underive` (answering `nil`), while three-argument forms answer an updated value (`make-hierarchy` an empty one); `isa?` is `equal`, element-wise vector derivation, or ancestor membership (two- or three-argument), answering `T`-or-false; the three reads answer (possibly empty) sets; `prefer-method` records into the multimethod's prefers table and answers the multimethod; the runtime (set helpers, transitive rebuild, dispatch search) is spliced once behind the false binding when used |
| `try`/`catch`/`finally`/`throw` | `handler-case` inside `unwind-protect`; `throw` over `error` | every catch class answers the catch-all `error` clause (first clause wins; the catch variable binds the CL condition); `throw` signals an `ex-info` value as its own condition and anything else through its Clojure-notation rendering (`rontolisp::%clojure-str-of`), so strings keep their message |
| `ex-info`/`ex-data`/`ex-message` | a condition with message and data slots | `ex-info` builds it through `make-condition` (its report prints the message); `ex-data` answers the map (`nil` for any other condition), `ex-message` the message (anything else through its Clojure-notation rendering); each works as a function value; the class plus the throw/data/message helpers are spliced once behind the false binding when used |
| `atom`/`deref`/`@`/`swap!`/`reset!`/`compare-and-set!` (and `volatile!`/`vswap!`/`vreset!`) | a tagged one-vector cell `(:C%ATOM #(value))`, like the set wrapper | every verb reads/writes the cell and answers the new value (`compare-and-set!` compares with `eql` and answers `T`-or-false); misuse signals; `seq`/`first`/`count`/`empty?`/`cons` onto one signal like the oracle instead of reading the wrapper as a list (b45, the b42 `conj` precedent); each works as a function value, so `(map deref atoms)` runs |
| `ref`/`dosync`/`alter`/`commute`/`ref-set`/`ensure` | the atom cell with a transaction discipline, over one spliced STM runtime | `ref` is the cell (a `:validator` registers in an identity-keyed alist); `dosync` binds the open depth one deeper around the body; `alter`/`commute` apply through the validator (a failed one signals and writes nothing -- the single-threaded rollback) and answer the new value; `ref-set` replaces through it; `ensure` answers the ref; every verb outside `dosync` signals `No transaction running`; `commute` runs once (the oracle may run it twice); `ref`/`alter`/`commute`/`ref-set` work as function values |
| `agent`/`send`/`send-off`/`await`/`shutdown-agents` | the atom cell as a synchronous agent, over the same runtime | `agent` is the cell (a `:validator` registers the same way); `send`/`send-off` apply at once with `*agent*` bound to the cell, through the validator, answering the cell; `await` checks each cell and answers `nil`; `shutdown-agents` is `nil`; there is no thread pool, so async ordering is out; `agent`/`send`/`send-off` work as function values |
| `binding` | `let*` over the bound names, sequentially like `let` | only `^:dynamic` vars (and `*out*`) may be bound -- anything else is the oracle's non-dynamic error as a named refusal; a `^:dynamic` `def`/`defonce` lowers to `defparameter` (always sets, like `def`, and proclaims the special, so the `let*` rebinds with dynamic extent); a `^:dynamic` `defn` keeps its `defun`(s) and adds a `defparameter` of the function, so its calls go through the value cell and the `let*` rebinds them the same way (`recur` still jumps straight to the function cell, like the oracle); the body closes over the scope the same way; the body lowers behind the `try` barrier, so a `recur` there is the oracle's `Cannot recur across try` refusal (decided 2026-10-01, b35) |
| `defonce` | `def` unless `boundp` | a reload keeps the root where `def` resets it; a `^:dynamic` one keeps through `defparameter` instead |
| `defstruct`/`struct`/`struct-map` | the key vector behind the name plus fresh-table builders | `defstruct` stores a vector of the keyword wrappers; `struct` pairs keys with values (missing `nil`, too many signal); `struct-map` seeds the keys and overrides pairwise |
| `defn-` | `defn` of a private var | private like `^:private` (b56): `use`/`:refer :all` never refer it, `:refer [x]` of it is the oracle's `x is not public`, and a qualified reference from another namespace is the oracle's compile error `var: #'n/x is not public`; inside its namespace it is an ordinary `defn` |
| `with-meta`/`meta`/`vary-meta` | `%clojure-with-meta` answers a shallow copy (a fresh table, `copy-seq`, `copy-list` of a list or wrapper, a wrapping closure) recorded in the eq side table `%clojure-meta-table` (made on first use); `meta` reads it, nil when absent | the oracle's new-object semantics without a new value shape: `=` and printing never see metadata; IObj kinds only (a string, number, keyword, boolean, atom, deftype, pattern signals, like the oracle's cast); deviations: a symbol answers itself without metadata (an interned symbol has no copy; macro idioms `(with-meta name ...)` keep working), a derived value (`assoc`, `conj`, ...) starts without metadata (the oracle carries it), and the table keeps every object for the program's lifetime (b62) |
| reader `^` metadata in value position | dropped: the object lowers as itself, except on a vector/map/set literal, where it attaches through `%clojure-put-meta` (no copy: the literal is fresh) | the reader spells `^m x` as `(%with-meta x m)` -- a head no Clojure call spells -- so a type hint never reaches the run-time `with-meta` (b62); layers merge with the outer winning, a keyword is `{:k true}`, a symbol/string `{:tag x}` (the oracle resolves a class symbol to `java.lang.String`; here it stays `String`); quoted data strips it (`'^:a x` is `x`, it printed `(with-meta x :a)` before); `^:private`/`^:dynamic`/`^{...}`/type hints parse and drop on names, parameter vectors, patterns and values; two pieces on a definition's name are read: `^:dynamic` (for `binding`) and `^:private` (b56: never referred, refused across namespaces), either bare or as an `^{...}` map entry whose value is not `false`/`nil`; `def`/`defn` skip a docstring and an attr map (an attr map only with a value behind it -- a lone map stays the value; since b50 a lone string stays the value too, so `(def g "hello")` binds `"hello"`) |
| `with-open` | `let*` plus `unwind-protect` closing in reverse order | a stream value closes through `close` on every backend, anything else through the `close` interop call (Java closeables need the interpreter or the JVM; wasm compiles `java:` to a call-time error); an empty vector is the plain body (the oracle's bare `do`, no barrier); a non-empty body lowers behind the `try` barrier, so a `recur` there is the oracle's `Cannot recur across try` refusal (decided 2026-10-01, b35) |
| `with-out-str` | `let*` rebinding `*standard-output*` (already special) to a fresh string stream | never a literal `with-output-to-string` (which flips a WASM module into EH mode); the stream is built with `make-string-output-stream` and read back, like `str` |
| `time` | the value timed with `get-internal-real-time`, reporting `Elapsed time: N msecs` | only the value pins (the count never does -- the spec pins the prefix); built straight to the stream like `println`, never re-lowered |
| `future`/`delay`/`force`/`promise`/`deliver`/`proxy-super` | refused by name | no thread pool, lazy memo cells or blocking rendezvous on any backend; proxy methods take the Java arguments only, with no super handle |
| `*out*`/`*in*` | `*standard-output*`/`*standard-input*`, not mangled names | the streams the print family writes to / reads from; `binding` may rebind either, like any special (decided 2026-10-01, b20) |
| `.write`/`.flush`/`.readLine` on a stream | `princ` (nil signals, like the oracle's NullPointerException -- b55) / `finish-output` / `read-line` (nil past the end, like the oracle) over the receiver | so `(. *out* write ...)` and `(.readLine *in*)` run on every backend; a non-stream receiver still goes to `java:call` |
| `ns`/`require`/`use`/`import`/`in-ns` | alias and refer wiring of the current namespace, a project namespace's file lowered ahead of the form (b56) | see "Namespaces and project files" below for the var model, the source path and loading; a bare library symbol names one library like the oracle (`(:use clojure.test)` refers it all; b55 -- it used to set a prefix and wire nothing), a `:reload`/`:reload-all`/`:verbose` flag is skipped, and a `:refer`/`:only`/`:exclude` list spells names like a vector (the oracle's `(reader)`); `:as` registers an alias, `:refer`/`:use` unqualified names (`use`'s `:only [...]` narrows the referred set, winning over the refer-all default, and `:exclude [...]` subtracts from it -- and from `:refer :all` -- like the oracle), `:import` simple class names, `(:refer-clojure :only/:exclude ...)` narrows the visible core; a bare `require`/`use` spells each libspec quoted (`(quote spec)`/`'spec`, the oracle's spelling) and shares the `ns`-clause spec parser; an unquoted vector spec stays accepted (a lenient superset -- the oracle rejects it with a `ClassNotFoundException`); a prefix list `(prefix [sub ...])` (quoted or bare, `use` and the `ns` `:require`/`:use` clauses included) wires each member (a bare or quoted symbol or vector) under the prefix, through the same parser; `clojure.string`, `clojure.java.io` (`reader` only) and `clojure.test` resolve (see below); any other `clojure.*` namespace is refused by name (`unknown namespace: clojure.set`), any other namespace is a project one; names are referred only by `use` or a `:refer` option -- a `require` with a bare `:only`/`:exclude` refers nothing, the oracle's `load-lib` (b56; it used to refer the `:only` list); `in-ns` switches the namespace, answering `nil` |
| `clojure.string` (`join`/`split`/`split-lines`/`upper-case`/`lower-case`/`capitalize`/`trim`/`triml`/`trimr`/`trim-newline`/`blank?`/`starts-with?`/`ends-with?`/`includes?`/`index-of`/`last-index-of`/`replace`/`replace-first`/`escape`/`re-quote-replacement`/`reverse`) | core string operations over lowered arguments | reached as `alias/var`, `clojure.string/var`, or a referred bare var; each works as a function value (a rest lambda dispatching on the count); `split`/`replace` take pattern values (around matches, through the regex runtime) as well as literal strings and characters (a plain string never compiles -- the b08 literal-only position holds for strings, pinned by `string-replace-and-split-stay-literal`); an empty literal-`split` input is nil (a pattern answers one empty part, like the oracle); a positive `split` limit caps (the last part holding the rest), a negative one keeps every part, otherwise trailing empties drop |
| `subs` | `subseq` (2/3-arity) | as a value a two-or-three-argument lambda |
| `clojure.test` (`deftest`/`deftest-`/`is`/`are`/`testing`/`run-tests`/`run-all-tests`/`successful?`) | `ClojureTestLowering` over the spliced `rontolisp::%clojure-test-*` runtime in `clojure.lisp` | see "clojure.test" below; `use-fixtures` refused by name, a macro var as a value is the oracle's `Can't take value of a macro` |
| Java interop (`.`, `..`, `.method`, `.-field`, `Class/member` in call and value position, `Class.`, `new`, `memfn`, `proxy`) | the `java:` surface (`.kb/java-interop.md`) | `(. obj m args)` / `(.m obj args)` an instance call (a known-class receiver whose overloads at that arity all answer a primitive boolean answers `T`-or-false -- a construction literal, a `let`/`if-let`/`when-let` local bound to one, or a `..` step's declared return; any other receiver keeps the shared `java:` unmarshal), `(. Class m args)` / `(Class/m args)` a static, `(Class. args)` / `(new Class args)` construction, `(Class/FIELD)` a static field (a zero-argument `(Class/m)` or `(. Class m)` is the static method when the host class has one, else the field -- decided 2026-10-01, b20, lifting the b08 deviation; a bare `Class/member` value reads the static field when the host class has one, else answers a member-as-value lambda dispatching per arity over the static call -- so `(every? Character/isWhitespace s)` runs -- and a variadic-only member is refused by name), `(.-f obj)` an instance field, `(.. obj (step args) name)` nested `.` datums, `(memfn m args...)` a lambda over the instance call, `(proxy [I] [] ...)` a `java:proxy` (kept gaps, b08: no `set!` field write -- the `java:` surface has no write primitive; non-string receivers go to `java:call` and fail there); classes resolve dotted, imported, or `java.lang`; a string receiver answers the mapped core operation (a Lisp string is no host object), anything else goes to `java:call`; interpreter and JVM only (wasm rejects `java:`) | `(. obj m args)` / `(.m obj args)` an instance call, `(. Class m args)` / `(Class/m args)` a static, `(Class. args)` / `(new Class args)` construction, `(Class/FIELD)` a static field (a zero-argument `(Class/m)` or `(. Class m)` is the static method when the host class has one, else the field; a bare `Class/member` value reads the field or answers an arity-dispatching member lambda), `(.-f obj)` an instance field, `(.. obj (step args) name)` nested `.` datums; classes resolve dotted, imported, or `java.lang`; a string receiver answers the mapped core operation (a Lisp string is no host object), anything else goes to `java:call`; interpreter and JVM only (wasm rejects `java:`) |
| `make-array`/`aget`/`aset`/`alength` | the general array (`make-array` dims, `aref`, `(setf aref)`, `array-dimension` 0) | the class spells the element type and is ignored -- every array here is general (the book's `interop.clj` shape); only the Clojure spellings are new, so all four backends |
| `defprotocol` | one `equal`-table global plus one dispatcher `defun` per method, over the shared `C%PROTOCOL-TAG` reader | the multimethod shape without the hierarchy search (decided 2026-10-01, b13, revisiting the b08 rejection: three corpus chapters use nothing else, and both halves -- the b05/b08 method table, the b02 `equal` table -- already run on all four backends); a call dispatches on the target's tag (exact match, then the `Object` row), a miss with no `Object` row signals, like the oracle; the `Object` row is a per-method table in the `%default` global (nil until the first `Object` extension; until b62 it was ONE lambda shared by every method, so a several-method `Object` extension ran its last method for all of them); the protocol name answers its table; single signature per method (several arities stay refused); `:extend-via-metadata true` adds a `%inline` table that body implementations (`defrecord`/`deftype`/`reify`) store into instead, and the dispatcher looks inline -> `(%clojure-meta-method target 'ns/method)` (invoked through `%clojure-call`, like the oracle's IFn) -> extension rows -> `Object` -- the oracle's order, measured: metadata beats `extend-type`, a body implementation beats metadata; `satisfies?` reads both tables, never metadata, like the oracle (b62) |
| `defrecord` / `deftype` | the positional and map constructors (records only -- the oracle defines no `map->` for deftypes) as mangled `defun`s, plus one table row per inline method | a record is `(:C%RECORD tag fields table class)` over the same `equal` table every map uses (no per-backend struct -- the b08 rejection reason), `class` the host class name string the printer spells (`#my_app.core.R{...}`: the defining `ns` with `-` munged to `_`, the name verbatim, like the oracle; b58) -- every rewrap (`rewrapRecord`) carries it, and the pre-scan tracks `ns` forms so a forward name gets the right one; a deftype shares the shape with an opaque `:C%TYPE` tag; names join the whole-file pre-scan (forward refs like `defn`); `(T. ...)`/`(new T ...)` rewrite to `->T`; inline bodies see the fields as locals (an explicit parameter shadows its field, like the oracle); a trailing keyword option is refused |
| `reify` | one fresh `:C%REIFY` tag per evaluation with a row per method in each protocol's table | a single-shot map plus methods (never `proxy`, which stays the `java:` surface); `=` is identity, like the oracle |
| `extend-protocol` / `extend-type` / `extend` | `defmethod` rows under the target's tag (`extend` from a map literal of method functions) | targets are the `class`-keyword kinds (`String`, `Number`/`Long`/`Double`, ..., `Map`/`Vector`/`Set`/`List`, plus `nil` and `Object` as the miss default) and known record/deftype names; anything else (an `Instant`, a `Date`, ...) is a named refusal; `extend-type` groups methods under protocol names |
| `satisfies?` | table membership (the tag's row, or the `Object` row) | the protocol is a literal name, like `defmethod`'s multimethod |
| `definterface` / `gen-class` / `gen-interface` | refused by name (`protocols are not supported yet: <name>`) | stay refused: no interface generation on any backend |
| `comment` | nothing (`nil`) | |
| characters (`\a`, lowercase names, `\uXXXX`, `\oNNN`) | `LispChar`, self-evaluating | exactly the oracle's spellings, case-sensitively; anything else is the oracle's `Unsupported character` refusal |
| strings (`"..."`) | `LispString`, self-evaluating | the standard escapes plus `\uXXXX` and octal `\0`-`\7` (up to three digits, capped at `\377` -- the escape stops at whitespace, `,` or a macro char, anything else is the oracle's `Invalid digit` refusal), like the oracle; anything else -- `\'` included -- is the oracle's `Unsupported escape character` refusal (b41 fixed the old default arm duplicating the next char; b44 added the octal arm and refused `\'` instead of keeping the lenient read; b47 reshaped the `\u` errors to the oracle's `Invalid unicode escape` (first digit) / `Invalid digit` (later digit) / `Invalid character length` (a short run before a stop), keeping `truncated \u escape` only for a buffer ending mid-escape so the session still waits for more input) |
| radix integers (`0x`, `Nr`, leading-`0` octal) | `LispInteger` (a `LispBigInteger` past the `long` range) | the sign applies outside; `2r101N` keeps the suffix rule; a shaped token that parses to nothing is the oracle's `Invalid number` refusal |
| `1M` | an exact ratio | `0.1M` is `1/10`: decimal arithmetic stays exact instead of the double's precision loss, printing as the ratio without its mark; `2N` narrows like any integer (a bignum past the `long` range) and prints without its mark |
| regex literals (`#"..."`) | `RONTOLISP::%CLOJURE-RE-COMPILE` over the source string (b21; verbatim backslashes since b50) | a pattern value `(:C%PATTERN stamp source ops ngroups)` over the spliced regex runtime, identical on all four backends; the stamp (a fresh gensym) keeps `=` identity, like the oracle; the reader keeps every escape verbatim like the oracle (so `#"\\d"` reads a literal backslash plus `d`), refusing unknown alphabetic escapes, a short/non-hex `\u`/`\x`, a `\0` with no octal digit behind it and a lone `\E` at read time |
| syntax-quote/unquote (`` ` ``, `~`, `~@`) | lowered, not refused (b12) | the reader parses them into marked lists (and `x#` into one identifier); the lowering qualifies, unquotes, splices and gensyms per the rows above |
| record literals (`#ns.Name{...}` / `#ns.Name[...]`) | the reader's `(%record ns.Name body)`, lowered to the record built in place over the QUOTED body (b58) | the oracle never evaluates the body: `#user.R{:a (+ 1 2)}` holds the list; missing fields `nil`, extra keys kept, a vector body takes exactly the field count; plain, quoted and syntax-quoted literals lower alike, and a macro answering a record decodes back to one (no constructor call, so it works at macro time); the class must match a defined record's printed class name, a deftype literal is refused by name; read-time refusals mirror the oracle: an undotted `#P{...}` is `No reader function for tag P` (`#inst`/`#uuid` stay `unsupported reader form`), a non-map/vector body `Unreadable constructor form`, a non-keyword or repeated key |
| `var`/`#'` | refused by name | `var` stays refused everywhere (macro bodies quote symbols instead); the lowering names what is missing instead of `unknown name` |
| metadata (`^`, legacy `#^`) | the reader's `(%with-meta form meta)` (`ClojureLowerUtil.READER_META`), parsed and dropped except on a collection literal | `#^` reads exactly like `^` (b58); every name position strips it (`stripMeta`), `ns` included |
| `set!` of a deftype mutable field (b61) | `(setf (aref slots i) v)` inside the type's inline methods | see "deftype mutable fields" below; every other target is the oracle's error or a named refusal |
| `set!` of a dynamic/core var or a host field, `gen-class`/`gen-interface`, `var` / `#'/` | refused by name | each names the missing design (a var `set!` needs a thread-binding test -- `binding` is a plain special `let*`, so nothing knows whether a var is bound; the `java:` surface has no field write; `var` needs its design) |
| `memfn` | a lambda over the instance-call path | `(memfn name args...)` is `(lambda (target args...) (. target (name args...)))`, so string receivers take the mapped core operation like any other instance call |
| `proxy` | `java:proxy` over every interface of the vector, with a name-dispatching lambda | one or more interfaces (b59) and no constructor arguments; each `(method [params...] body...)` (an empty body answers `nil`) becomes an `equal` arm applying a lambda to the Java arguments (which are the params -- no `this`), so a name several interfaces declare runs the one body, as the oracle's proxy does; a method left out raises `no proxy method: <name>` when called (the oracle: `UnsupportedOperationException` with the name); refused by name: a class in the vector (`isHostClass`, a lowering-time `Class.forName`; a name that does not load is left to `java:proxy`'s run-time error), constructor arguments, `toString`/`equals`/`hashCode` (`java:proxy` keeps `Object`'s three, so the body would never run -- the oracle runs it), multi-arity methods; all of the refused shapes are b71; interpreter and JVM only, like all interop |
| `if`/`when`/`cond`/`do`/`and`/`or` | the core forms | `cond` with an odd trailing arm treats it as the default; `:else` is true; every test treats `nil` and the false object as falsey (an explicit null-or-false check, the test bound once to a temporary) |
| `not` | an explicit null-or-false check answering `T`-or-false | |
| `<`/`>`/`<=`/`>=` | the Common Lisp operation, answering `T`-or-false | so `(= false nil)` is false and printing spells it `false` |
| `nil?` | `null`, answering `T`-or-false | `(nil? false)` is false |
| `false?`/`true?`/`boolean?` | their predicates (`eq` against the false object / `T`), answering `T`-or-false | as values, lambdas answering `T`-or-false too (every predicate value does, so `(map odd? [1 2])` prints `(true false)` like the oracle) |
| `map`/`filter`/`reduce`/`apply`/`concat` | `rontolisp::%clojure-map`/`-filter` over the collections, `reduce`/`apply` over the seq view, `rontolisp::%clojure-concat` over the member list | `map` takes any number of collections (stopping at the shortest, like the oracle) and answers a lazy wrapper when any input is lazy, the strict list otherwise; `filter`/`concat` likewise (a false object drops like nil); lists pass through untouched (no copy); every other collection coerces first, so vectors, strings, maps and sets all work; `reduce` is 2/3-arity with the Clojure argument order (`(reduce f val coll)`), one call to the spliced `%clojure-reduce`/`-reduce-init` (b60), which walk the seq view one element at a time (a lazy input reduces whole; it used to fold the wrapper's own cells) and stop at a `reduced` answer; the runtime funcalls, so a function form that may hold a collection is wrapped over the dispatcher at the call site and a plain reduce carries no dispatcher; `apply` spreads any leading arguments over the seq-coerced last one (`(apply f x args)`), like CL `apply`; `(concat)` is nil |
| `first`/`rest`/`next`/`seq`/`cons` | `car`/`cdr` over `rontolisp::%clojure-seq`, the view itself, `rontolisp::%clojure-cons` onto the collection | a seq is a list view that realizes a lazy wrapper one level and coerces strictly otherwise (b11): lists pass through, vectors/strings coerce, maps contribute one two-vector per entry and sets one member per element (both in the table's walk order, unspecified), nil and the false object are empty, anything else signals like the oracle; `cons` onto a lazy collection answers a wrapper, so no strict cons ever holds a lazy tail; non-listed verbs consume one level through the view (pass a `take`n prefix) |
| `nth` (2/3-arity) | the seq view indexed, past the end the default | the 2-arity answers nil past the end where the oracle throws; as a VALUE a lambda with the Clojure order (`(lambda (c i) ...)`), since a bare `#'NTH` takes the index first |
| `quot` | `truncate` | as a VALUE a two-argument lambda over `truncate` |
| `take`/`drop` | `rontolisp::%clojure-take`/`-drop` over the collection | stepping through one lazy element at a time, so `(take n infinite)` terminates with a strict prefix (realizing exactly what it answers); an over-long take/drop answers the whole/empty seq (nil, where the oracle prints `()`) |
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
| `mapv`/`filterv` | `rontolisp::%clojure-mapv`/`-filterv` over the fully realized lists, coerced to vectors | strict vectors, never lazy wrappers (lazy inputs realize fully); `filterv` tests Clojure truthiness like `filter`; `class` pins the vector answer; as values a rest lambda (`mapv`) / a two-argument lambda (`filterv`) |
| `mapcat` | `rontolisp::%clojure-mapcat`: the mapped seq views realized and appended | strict concat-of-maps, nil-safe like `concat` (a nil result contributes nothing); a lone function is the transducer (b60); as a value a rest lambda |
| `ffirst`/`nfirst` | `car`/`cdr` of the seq of the `car` of the seq view | each level seqs (a vector head coerces before its own head is read); of empty, nil; as values one-argument lambdas |
| `boolean` | the null-or-false test answering `T`-or-false | only nil and the false object are falsey (0, empty strings and empty collections are truthy); as a value a one-argument lambda |
| `char` | `rontolisp::%clojure-char`: itself for a character, `code-char` of the truncated number | anything else signals, like the oracle; as a value a one-argument lambda |
| `name`/`namespace` | `rontolisp::%clojure-name`/`-namespace` over the spelling (a keyword's verbatim, a symbol's demangled), split at the first `/` | `name` answers the part past the slash (a string answers itself); `namespace` the part before it (nil when absent, strings and anything else signal); as values one-argument lambdas |
| `keyword`/`symbol` | `rontolisp::%clojure-keyword-1/-2` / `%clojure-symbol-1/-2` | one argument: a keyword itself, a symbol's demangled spelling, a string verbatim (`a/b` stays whole) to a keyword (nil for anything else), or the spelled symbol behind the mangled prefix (else a signal); two arguments: the slash-joined spelling (a nil namespace drops for keywords, a nil name signals; a nil namespace is the one-argument shape for symbols, nil spelling `null`); as values rest-dispatch lambdas |
| `assert` | `if` on null-or-false around `error` | answers nil when truthy, signals otherwise; the message (an `Assert failed:`-prefixed `str`) sits in the else branch, so it evaluates only on failure; no function value, like `and`/`or` |
| `rand`/`rand-int` | `(random 1.0)` scaled (`rand`), truncated (`rand-int`) | one draw from the program-owned generator per call, never a host call per draw (`.kb/random.md`); no domain check (a negative bound answers negative, 0 answers 0), like the oracle's multiply-then-int; as values a rest lambda (`rand`) / a one-argument lambda (`rand-int`) |
| `rand-nth` | the realized list indexed by one scaled draw | nil answers nil (and the empty list with it, both being nil -- the seq-view past-the-end rule `nth` keeps); an empty vector, string or seq signals, like the oracle's throw; maps and sets signal too (none are indexed there); as a value a one-argument lambda |
| `shuffle` | `rontolisp::%clojure-shuffle`: Fisher-Yates over the realized members coerced to a fresh vector | membership and count pin, never order; nil, strings and maps signal (none shuffle on the oracle either); as a value a one-argument lambda |
| a set/map/vector literal in call position, or as a function value | the member / table-aware read / `nth` with an optional default | `(#{:h} :h)` is `:h`, `({:a 1} :b :d)` is `:dflt`, `([10 20] 5 :d)` is `:d` (the `nth` past-the-end-is-default position); `(filter #{:h} ...)` runs through the same lambda |
| `update`/`update-in`/`assoc-in`/`get-in` | a fresh table over the old pairs with the rewritten pair / the nested walk, rewrapped in the record it came from | `update` applies `(apply f (get m k) args...)`; `update-in` recurses (an empty key vector is refused, like the oracle's throw); `assoc-in` builds missing levels (no keys associates under nil, like the oracle); `get-in` threads the default through every level; records read and rewrite through the entry table, keeping the type; as values lambdas walking the key sequence at run time |
| `select-keys`/`merge-with`/`into`/`frequencies` | a fresh table over the present keys / grown map by map through `f` / a `conj` fold / one `dolist` pass | `select-keys` of nil is the empty map; `merge-with` of no maps is nil; `into` targets lists/vectors/maps/sets, through the reduce runtime (a lazy source pours in whole), and `(into to xform from)` is the spliced `%clojure-into-xf`, the oracle's `(transduce xform conj to from)` (b60); a record reads through its entry table -- `select-keys` and `into {}` answer plain maps (like the oracle), `merge-with`/`into` onto a record keep the type (like `merge`/`conj`); as values lambdas, with `merge-with`'s reading each rest map through its entry table and rewrapping the accumulator in the first rest map's record (a `nil` first map answers a plain map, like the oracle -- measured 2026-10-01) |
| `comp`/`partial`/`complement`/`constantly`/`identity`/`memoize`/`trampoline` | right-nested closures / fixed-plus-rest closures / the negated predicate / the kept value / the value itself / an `equal`-tabled closure / a labels self call over thunks | no functions is `identity` for `comp`; `complement` answers `T`-or-false; `memoize` keys the argument list structurally; `trampoline` invokes zero-argument results until a non-function answers; each a function value too |
| `when-let`/`if-let`/`when-not`/`if-not`/`when-first` | `let*` pairs over one temporary plus `if` on null-or-false | `when-let`/`if-let` destructure like `let` (testing the whole init); `when-not`/`if-not` swap the branches; `when-first` binds the head of the seq view |
| `coll?`/`string?`/`symbol?` | `or` over the shapes / `stringp` / `symbolp` minus the booleans and nil | `coll?` excludes strings (which the runtime stores as vectors) and nil, like the oracle; as values lambdas answering `T`-or-false |
| `instance?`/`class` | the class name mapped onto the shared predicates / a `cond` answering a kind keyword | only the core classes lower (`String`, `Long`, ...) plus known record/deftype names (a tag-equality test), anything else a named refusal; `class` answers `:map`/`:vector`/`:set`/`:list`/`:string`/`:number`/`:keyword`/`:symbol`/`:char`/`:boolean`/`:nil`/`:function`/`:atom` (host classes exist on no wasm backend) and a record/deftype answers its tag keyword; as values lambdas (`instance?` has none -- an arity error stays one) |
| `int`/`long`/`unchecked-add` | `truncate` (a character reads back through `char-code`, round-tripping `char`) / `+` | a non-number signals, like the oracle; `unchecked-add` never wraps (bignums); as values lambdas |
| `spit`/`slurp`/`line-seq`/`clojure.java.io/reader` | `with-open-file` writes of the `str` spelling / a `read-char` loop into a string stream / a `read-line` loop / an `open` input stream | on every backend; on wasm they need a `--dir` preopen covering the path, like `open`/`with-open-file` -- without one the open signals the file-error (pinned in `ClojureWasmFileRefusalTest`), with one both wasm backends read and write like the rest (pinned in `ClojureWasmFileIoTest`; measured 2026-10-02); `spit` writes `(str content)` -- a non-string through the `str` spelling, `nil` writing nothing, a string as before -- and supersedes unless `:append` is truthy (b74); `line-seq` takes a path or an open reader and answers strictly either way (a reader is read but never closed -- `with-open` owns closing, like the oracle); each a function value too; `file-seq` and every other `clojure.java.io` fn stay refused by name |
| `format` | the Java directives translated to Common Lisp over Clojure-notation arguments | the format string must be literal; `%s` converts like `str` (nil spells `"null"`), `%b` the boolean spelling, numbers the matching checked directive; `%e`/`%g`, flags and anything else are named refusals |
| `range` (with an end) | a labels self call building the strict list | 1/2/3-arity (`end` / `start end` / `start end step`); a zero step signals; an end-less `(range)` is refused by name -- an infinite seq cannot be spelled strictly (spell it with `iterate`, b11) |
| `lazy-seq` | `rontolisp::%clojure-make-lazy` over a zero-argument lambda of the body | the body (an implicit `do`) runs on first realization, at most once per seq object (memoized through `rplaca`/`rplacd` on the wrapper cell, primitives every backend already compiles -- no new runtime); the body is its own zero-arity `recur` target (decided 2026-10-01, b28): a `recur` in its tail position re-runs the thunk itself, checked against arity 0, and the thunk lambda wraps itself in a `labels` self-binding only when a `recur` reaches it (the anonymous-`fn` shape); `lazy-cat` desugars to `(concat (lazy-seq e) ...)` at datum time |
| `repeat`/`cycle`/`iterate`/`repeatedly` | `rontolisp::%clojure-repeat`/`-cycle`/`-iterate`/`-repeatedly` (infinite arities), strict-list builders (finite arities) | the infinite arities answer wrapper chains through the IFn dispatcher; `(repeat n x)`/`(repeatedly n f)` answer strict lists and print like the oracle; each names a function value too |
| a vector literal | a `vector` call | |
| `vector?`/`fn?` | `vectorp` minus `stringp` / `functionp`, answering `T`-or-false | a string is a CL vector but no Clojure vector (b55: `vectorp` alone answered true, the corpus `life_without_multi.clj` my-print shape); `fn?` is false for keywords, sets and maps; both are values too |
| `first`/`rest` | `car`/`cdr` over the seq view | see the seq-view row above; `count` stays the table-aware length (the fast path, no seq built) |
| `count` | a table-aware length | maps, sets and records answer `hash-table-count` (records their entries), a deftype or reify signals (a bare length would answer the wrapper's size), everything else `length` |
| `empty?` | a table/vector/string-aware null test, answering `T`-or-false | `nil`, an empty map/set/record/vector/string are empty; a deftype or reify signals, like the oracle's `seq` throw |
| `=`/`not=` | the spliced `rontolisp::%clojure-equal` over each neighbouring pair, answering `T`-or-false (as values `%clojure-equal-values` / its negation) | two maps compare structurally (nested included); a map and a set never compare equal; two records compare by tag plus entries (never equal to a plain map, like the oracle); a deftype or reify on either side is identity, like the oracle; two sequentials (lists, non-string vectors, lazy seqs, and nil as the empty list) compare element by element across kinds, like the oracle (decided 2026-10-02, b55: the inline labels compared vectors with `equal`, i.e. by identity, so `(= [1 2] [1 2])` was false -- the corpus test files compare vectors with seqs in nearly every assertion); `(= [] nil)` is therefore true where the oracle answers false; anything else is `equal`. One shared callee instead of a labels per call site: `(println (= 1 1))` 34,905 -> 33,016 B of wasm, five `=` calls 55,911 -> 34,395 B (2026-10-02, raw module totals) Measured 2026-10-02 (b57, x86-64, Java 25, raw wasm totals) against the inlined labels form per site it replaced: 36,790 -> 17,595 B for one `(println (= 1 2))`, 87,050 -> 34,004 B for ten sites |
| the b57 backlog: `drop-last`/`split-at`/`split-with`/`take-last`/`nthnext`/`nthrest`/`peek`/`pop`/`not-empty`/`dedupe`/`partition-all`/`partition-by`/`min-key`/`max-key`/`juxt`/`fnil`/`every-pred`/`some-fn`/`update-keys`/`update-vals`/`reduce-kv` | `ClojureCoreLowering`: one call to the fixed-parameter `rontolisp::%clojure-NAME` worker (`min-key`/`max-key` share `%clojure-extreme-key`) after a lower-time arity check worded like the oracle (`Wrong number of args (N) passed to: clojure.core/NAME`); as a value `#'rontolisp::%clojure-NAME-v`, a `&rest` entry checking the count at run time with the same wording | `dedupe`/`partition-all`/`partition-by`/`drop-last` answer a lazy wrapper over a lazy input and a strict list otherwise (`%clojure-lazy-or-strict`); `split-at`/`split-with` a two-vector, `take-last` a realized strict list; `dedupe`/`partition-by` compare with `%clojure-equal`; the transducer arities (`(dedupe)`, one-argument `partition-all`/`partition-by`) are transducers (b60), the `-v` entries included; a non-positive `partition-all` size or step signals (the oracle answers an endless seq of `()`); `peek`/`pop` take non-string vectors and plain lists (a strict seq is a list here, so it peeks where the oracle's LazySeq throws), `(pop [])` is the oracle's `Can't pop empty vector`; `some-fn` answers exactly the oracle's failing value (the last `(p x)` for one or two predicates over at most three arguments, else nil; argument-major for one or two predicates, predicate-major for three or more); `fnil`'s answer needs as many arguments as defaults (`.../fnil/fn`, no class-number suffix); `reduce-kv` walks maps/records/vectors (index keys), nil answers the init, a `reduced` answer stops it (b60); `update-vals` keeps a vector a vector, `update-keys` keys a vector by index; type errors signal with the CL wording, not the oracle's ClassCastException text (that names JVM classes) |
| `pmap` | `map` (`ClojureSeqLowering.mapForm`, as a value `mapValue`) | decided 2026-10-02, b57: no thread pool on any backend, the printed seq is the oracle's; the arity refusal is the oracle's wording |
| a call to a core name the program defines (`(defn peek ...)`) or binds locally | the program's own call | b57: `call` checks `known(name)` before the core switch (and before the `re-*` names), like the value position always did -- a `(defn second ...)` used to lose its call sites to the core verb; the whole-file pre-scan makes a definition shadow calls ABOVE it too, where the oracle's still reach the core verb (documented deviation) |
| `{k v ..}` | `rontolisp:plist-hash-table` over the lowered pairs | an `equal` table, never mutated in place: every verb builds a fresh one |
| `#{..}` | an `equal` table holding each member under itself, wrapped as `(:C%SET table)` | the wrapper tells verbs a set from a map; a repeated literal element is refused by spelling (`Duplicate key`) |
| `assoc`/`dissoc` | a fresh table over the old pairs plus/minus the keys, rewrapped in the record it came from | `assoc` onto nil builds from empty; `dissoc` of nil is nil; odd `assoc` pairs are refused; `assoc` keeps the record's tag and fields, like the oracle; `dissoc` keeps the record while every declared field is still present and drops to a plain map otherwise (removing a base field drops the type, removing an extension key keeps it), like the oracle; as values a map plus a rest list of pairs/keys (an odd `assoc` rest count signals at run time) |
| `get` | `gethash` with the default, or a bounds-checked `elt`/`char` | takes maps, records (through the entry table), sets (answering the member), vectors, strings and nil; a deftype or reify answers the default, like the oracle; a list answers the default; as a value the two- or three-argument read over a rest default |
| `contains?` | a sentinel-`gethash` presence test, or a bounds check | takes maps, records, sets, vectors and strings; anything else answers false; as a value a two-argument lambda |
| `keys`/`vals` | a `maphash` accumulation into a list | the order is the table's walk order, unspecified; of nil, nil; records read through the entry table; as values one-argument lambdas |
| `merge` | one fresh table over every argument's pairs, rewrapped when the merge starts from a record | later maps win; `(merge)` is nil; of all nil, nil; the result keeps a record's type only when the first argument is one, like the oracle (a `nil` first argument answers a plain map -- measured 2026-10-01) (`merge-with` the same); as a value a rest lambda over the maps |
| `conj` | a member onto a set, entries onto a map or record (keeping the type), at the end of a vector, at the front of a list | a set conjoined onto a map contributes its members one level deep; anything else conjoined onto a map is refused; onto an atom (or a ref/agent/volatile, the same cell), a deftype or reify signals, like the oracle; as a value a collection plus a rest list of items folded one by one, so `alter`/`swap!` over `conj` run |
| `disj` | a fresh set minus the members | of nil, nil; of a map, refused; as a value a set plus a rest list of members |
| `set`/`hash-map`/`array-map` | a set from a collection, a map from key/value pairs | `set` takes lists, vectors, maps (entry vectors) and sets; odd constructor pairs are refused; as values a one-argument lambda (`set`) / a rest lambda over the pairs (an odd rest count signals at run time) |
| `vec` | `coerce` of the fully realized seq view to a vector | `(vec nil)` is `[]`, `(vec "ab")` is the character vector, maps contribute one two-vector per entry and sets one member per element; lazy inputs realize fully (an infinite input hangs, like the oracle's); as a value a one-argument lambda |
| `str` | `concatenate 'string` over mapped parts | `(str)` is `""`; `nil` maps to `""`, `true`/`false` to `"true"`/`"false"`, a keyword to its colon spelling, collections in Clojure notation through `rontolisp::%clojure-str-of` |
| `pr-str` | `concatenate 'string` over mapped parts joined with a space | the readable arm of `str` (like `pr`): `(pr-str)` is `""`, `nil` maps to `"nil"` |
| `println`/`print`/`pr`/`prn` | one `rontolisp::%clojure-write-datum` call per part straight to `*standard-output*`, spaces as `write-char`, the newline as `terpri`, answering nil; with several parts and any computed one, every part binds to a temporary first (b55: a part that printed or threw split the line, where the oracle evaluates the arguments first) | parts joined with a single space, like Clojure; `pr`/`prn` convert readably, so strings print quoted; collections print in Clojure notation; no `with-output-to-string` ever reaches a compiled program (a literal one flips a WASM module into EH mode -- measured gate, `.todo/artefacts/b07-clojure-print/NOTES.md` finding 6); the print family answers nil, like the oracle |
| `true` | `LispTrue` (`T`) | a raw symbol spelled `T` is unbound -- `evalSymbolRef` looks the name up |
| `nil` | `NIL` | falsey |
| `false` | the value of `rontolisp::%clojure-false`, bound before anything else runs | a DISTINCT non-`NIL` symbol spelled `false` (the distinct-object treatment `scheme.lisp`'s `#f` uses); falsey in every conditional through the lowered tests; `eq`-comparable by name on every backend |
| a keyword `:foo` | the list `(:C%KEYWORD "foo")` holding its spelling verbatim (case-preserved) | data, compared by `equal` through the cons shape; `:a` and `:A` stay apart; every print arm spells it with its colon, nested or not |
| a keyword in call position `(:k m)` / `(:k m dflt)` | the same table-aware read `get` lowers to | the idiomatic map lookup, over b02's map runtime (sets answer their member, vectors/strings their element) |
| a keyword as a function value (`map`/`filter`/`reduce`/`apply` over `:k`) | a rest-tolerant lookup lambda over the same read (the second call argument is the default, extras ignored) | `(map :k coll)` reads the key out of each member; a keyword-dispatched multimethod takes several call arguments, like the oracle (decided 2026-10-01, b39 -- the value was a one-argument lookup and signalled) |
| a namespaced keyword `:a/b` | the same wrapper over the whole spelling | opaque data: prints and compares whole |
| `::kw` / `::alias/kw` | the same wrapper over the resolved spelling | `::kw` resolves against the current file `ns` name (the seam reads the whole file, so the form order decides; `user` without one), `::alias/kw` through the alias (a `:require` `:as`, the namespace's own name, or a known namespace without any require); a session tracks `*ns*` across buffers (`ns` switches it, `in-ns` switches it answering nil); opaque afterwards, so `derive`/`isa?`/dispatch compare whole spellings like `:a/b`; an unknown alias is the oracle's `Invalid token` refusal |
| a bare `(ns name)` | nothing, but switches to the namespace | a namespace declaration defines nothing itself; the definitions below it belong to the namespace, which it creates and marks loaded (b56); metadata on the name, a docstring and an attr map are skipped (b58); clauses wire aliases (see the `ns` row above); the file's `ns` name (or the session's `*ns*`) is what `::kw` resolves against |
| `quote` | `quote`, with symbols mangled and vectors re-emitted as `vector` calls | a quoted map or set is the construction over the quoted elements (each element's own quote form, so a symbol or list stays data); a quoted list holding a vector, map, set or regex literal at any depth is a `list` construction over the element forms (b55: the construction code used to land in the list as data, so `'(1 [2])` printed `(1 (VECTOR ...))` -- the `is` expected-form shape) |
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
  finds its set. Lists, strings, numbers and keywords key structurally. `=` itself is
  structural (`%clojure-equal`): only hash lookups keep the identity.
- A set literal refuses a repeated element BY SPELLING (`Duplicate key: 1`): two
  differently-spelled elements that are equal at run time still dedupe silently, and
  the spelling names the datum as the reader prints it.
- Verbs assume the right collection kind; misuse is unspecified and may signal the
  CL-level type error instead of the oracle's (e.g. `dissoc` of a set, `keys` of a
  vector). `conj` of a set onto a map goes one level deep; anything else conjoined
  onto a map signals. `(empty? false)` answers false where the oracle signals.
- The seq family runs over list views of every collection (strict since 2026-09-30,
  b03; lazy since 2026-10-01, b11). What still differs from the oracle: `rest`/`next`
  of empty is `nil`, where it prints `()`; `nth` past the end answers the default (nil
  without one) instead of throwing; a map/set seq's order is the table's walk order,
  unspecified; strings seq to characters, which print in Common Lisp notation; a lazy
  seq is the memoized-thunk wrapper `(:C%LAZY cell)` (b11: the body runs at most once
  per object, `take`/`drop`/`first`/`rest`/`next`/`seq`/`map`/`filter`/`concat` realize
  through it, printing refuses with `#<LazySeq>` instead of hanging, never a bare
  infinite print); there is no chunking, so an end-less `range` stays refused by name
  (spell it with `iterate`). `reduce`, `into` and the transducer consumers walk a lazy input
  whole (b60). Lazy inputs to the other seq verbs consume one level --
  pass a `take`n prefix.
- protocols (lowered in b13, below), `set!`, `var`/`#'`: `set!` (beyond a deftype
  mutable field, b61) and `var`/`#'`
  stay absent, each refused by name (backquote lowered in b12, below; regex
  literals lowered in b21, below). Reader metadata on names and locals parses and
  drops (b14), and only `binding` reads `^:dynamic`; value metadata (`with-meta`,
  `meta`, a collection literal's `^`) is real since b62 -- see the table rows above.
  Hierarchies and `ex-info` lowered in b08 (below); protocols lowered in b13 instead
  of the b08 rejection (a wrapper over the shared table runtime, not a per-backend
  value model -- the decision spike is `.todo/artefacts/b13-protocols/spike.md`). Catch clauses are catch-all in order (the first handles any condition, where the
  oracle dispatches by class); multimethod dispatch values compare like `equal`
  table keys (vectors by identity -- literal vector pairs still dispatch element-wise
  through the hierarchy search), widened by the hierarchy search (most specific
  wins, then `prefer-method`, like the oracle); a `defmethod` over a host class
  stores under the keyword `class` answers for it, merging every numeric spelling
  (`Long`, `Double`, ...) into `:number` where the oracle tells them apart;
  a true nil maps onto the `(:C%NIL)` marker first (no table ever keys on nil),
  while a dispatch value that literally is `:nil` keeps its keyword row, like the
  oracle (a `class` call inside the dispatch function answers nil itself for a nil
  argument, so the null test maps it onto the marker too -- bare, wrapped in
  another function, through a named `defn` / `def`'d function re-lowered from
  its recorded definition, or a call to one nested inside an inline dispatch
  datum (inlined at the call site the same way), like the oracle); an `Object` method catches past the search but
  ahead of the default, like the oracle's (which always beats `:default` there --
  measured on the oracle 2026-10-01); a hierarchy value prints as its
  `#<HASH-TABLE ...>` map and its reads answer wrapped sets; an `ex-info` value
  prints as its `#<C%E-EX-INFO ...>` condition; atoms print unreadably (`#<Atom value>`),
  functions as `#<procedure>`, a lazy seq as `#<LazySeq>` (a lazy tail truncates with
  ` ...`, so no bare infinite print ever hangs); a record prints as its literal
  `#user.R{:a 7}` like the oracle (b58, lifting the wrapper-print deviation), but `str`
  spells the literal too where the oracle answers `user.R@<hash>`; a
  deftype prints as its wrapper list (`(:C%TYPE ...)`), a reify as `(:C%REIFY ...)`; `class` of a
  record/deftype answers its tag keyword (the oracle answers a host class, which
  no wasm backend has); `assoc` onto a record keeps the type (like the oracle)
  while a `dissoc` that removes a declared field drops to a plain map (like the
  oracle); protocol dispatch merges `Long`/`Double` into `:number` (the oracle
  tells them apart) and reads no hierarchy (exact tag match plus the `Object`
  default); `split`/`replace` take pattern values as well as literal strings
  (decided 2026-10-01 b21: the regex runtime is spliced Lisp over the string
  primitives every backend already compiles, so no backend learns a regex name;
  plain strings stay literal -- the b08 position, still pinned by the spec's
  `string-replace-and-split-stay-literal` case); `indexOf`
  answers `-1` when missing, like the oracle (where `clojure.string/index-of`
  answers nil); a zero-argument `(Class/m)` or `(. Class m)` is the static method
  when the host class has one, else the static field read (decided 2026-10-01, b20,
  lifting the b08 deviation that read the field and spelled the method
  `(. Class m)` -- the corpus spells `(System/currentTimeMillis)` and
  `(System/nanoTime)`); a bare `Class/member` value reads the static field when the
  host class has one (dotted, imported, or `java.lang`, like the call position),
  else answers a member-as-value lambda dispatching per known fixed arity over the
  static call (a variadic-only member is refused by name; an unknown class or member
  reads the field, whose run-time error names it); a static call or member value
  whose overloads at that arity all answer a boolean answers `T`-or-false, like
  every predicate value (a static call WITH arguments keeps the shared `java:`
  unmarshal otherwise); an instance call whose receiver class is known at
  lowering (a construction literal, a `let`/`if-let`/`when-let` local bound to
  one -- single-shot bindings never rebind, so the inference is sound -- or a
  `..` step single declared return type; `loop` targets, parameters and globals
  stay unknown) wraps the same way when every overload at that arity answers a
  primitive boolean (decided 2026-10-01, b29: the shared unmarshal keeps mapping
  a host false to nil -- nil is Common Lisp's only false, so changing it would
  make host-false truthy in `java:` programs and move all three paths plus the
  bridge parity at once -- and Clojure wraps at lowering instead, where the
  member-value precedent `(map odd? [1 2])` prints `(true false)` already holds;
  extended 2026-10-01, b34: `if-let`/`when-let` record like `let`, `when-first`
  only forgets, and `..` threads each step declared return); any other host
  boolean keeps the unmarshal
  and prints `nil` for `false`; non-string receivers go to
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
- Single-threaded STM (b14): `dosync` never retries, `commute` runs its function
  once (the oracle may run it twice), validators run on the write and a failed
  one leaves the old value; every STM verb outside `dosync` signals. Agents are
  synchronous atoms: `send`/`send-off` apply at once and answer the cell (which
  prints `#<Atom ...>`, not the oracle's object), `await`/`shutdown-agents`
  answer `nil`, and `*agent*` is `nil` outside a send (unbound there). `binding`
  rebinds only `^:dynamic` vars, like the oracle's non-dynamic error for the
  rest; `with-open` closes stream values through `close` on every backend and
  anything else through the `close` method (Java closeables need the
  JVM); `time` answers its value but only its `Elapsed time:` prefix pins.
  `*out*`/`*in*` are `*standard-output*`/`*standard-input*`; `defonce` keeps the root where `def` resets
  it; refs and atoms share the cell, so STM verbs accept atom cells.

## Namespaces and project files (b56)

Decided 2026-10-02 against `clj` 1.12.6.1673 (run on the same files with
`-Sdeps '{:paths ["src" "test"]}'`). **Every namespace has its own vars.** A var is keyed
`ns/name` in every program-wide table (`globals`, `macros`, `protocols`, `types`,
`dynamicVars`, `globalDirectFuns`, `classDispatchFns`; `ClojureLowering.varKey`) and lowers to
`c%ns/name` (`varSym`) -- except `user`'s, `c%name`, so a program without an `ns` lowers as
before and a Common Lisp file calls its functions as `(c%name ...)`, a namespaced file's as
`(|c%my.ns/name| ...)`. A quoted `'n/x` is the symbol of var `n/x` (what syntax-quote relies
on); the JVM method mangle spells `/` and `.`. A local never carries a namespace.

- **Resolution** (`lookupVar`, `resolveVar` adding the privacy refusal; locals first, in
  the callers): unqualified, the current namespace's intern, then a refer to a project var;
  qualified, the head as an alias of the current namespace, the current namespace, or any
  namespace an `ns`/`in-ns` created (`createdNamespaces`) by its full name. `known`,
  `isFunction`, `isMacro`, `isDirectVar` and `symOf` go through it, so every slice takes a
  name as written. Another namespace's private var (`defn-`, `^:private`) is `var: #'n/x is
  not public`; a qualified name whose head is a project namespace lacking the var is
  `No such var: l/nope` (`refuseMissingVar`), never a class.
- **Per-namespace wiring** (`ClojureNsState`): aliases, refers, imports, the
  `:refer-clojure` filter and the interns with their privacy. The pre-scan follows `ns` and
  `in-ns`; a definition replaces a refer of its name (the oracle warns and replaces).
  `binding` rebinds the var's own symbol and adds no local, so the body reads the special.
- **Records** keep the simple-name dispatch tag (deviation: two namespaces' `R` share it);
  `types` is keyed by var key and `typeKeyOf` resolves a class spelling: own, an imported or
  dotted name matching `TypeDef.className`, else the only one of that simple name (the flat
  leniency kept). `(R. ...)`/`(new R ...)` call the defining namespace's `->R`.
- **Loading** (`ClojureNamespaceLowering.loadNamespace`, `ClojureLowering.loadFile`): an
  `ns` form marks its namespace loaded AFTER its clauses ran (the oracle's `ns` adds
  `*loaded-libs*` last; marking first hid the cycle), so a single-file program's later
  `(:require [a])` reads nothing -- the `clojure-spec.yaml` case runs on all four backends
  for that reason. Any other non-library namespace reads `my_app/core.clj`, lowers both
  passes from a clean cursor (no local, recur target, `try` depth or syntax gensym of the
  requiring form leaks in; all restored after), starting in the requiring namespace like
  the oracle's `load` (a file without `ns` defines there). Its forms are HOISTED ahead of
  the top-level datum that loaded it (`hoisted`, drained by `topLevels`); once per lowering.
  An unknown `clojure.*` stays `unknown namespace`. Refusals in the oracle's words:
  `Could not locate a/b.clj on the source path: <roots>`, `Cyclic load dependency: [ /a
  ]->/b->[ /a ]` (newest request first, then the loading stack innermost first),
  `namespace 'x' not found after loading '/x'`, `x does not exist`, `x is not public`.
- **The source path** (`ClojureSourcePath`, on the first project require only): the root
  the entry file's namespace names (`src` for `src/demo/main.clj` declaring `demo.main`; the
  file's directory when the path does not spell the namespace; the working directory for a
  session), then the `:paths` of the nearest `deps.edn` walking up from the entry file's
  directory (read by `ClojureReader` as EDN; `["src"]` when absent or unreadable, alias
  keywords skipped), else `src` under the working directory -- the `clj` default. Chosen over
  a `--source-path` flag: `deps.edn` is the oracle's own declaration, so a project runs with
  no new option. Files come through `ClojureFiles` (the `SchemeFiles` shape):
  `eval/SourceLanguage.clojureFiles` adapts the site's loader (the parent is absolutized so
  the walk passes the top of a relative entry path); no loader is `NONE`, refused by name.
- **Hoisting is the measured deviation**: the corpus `preface` test
  (`(with-out-str (use :reload 'examples.preface))` inside a `deftest`) prints its
  `hello` ahead of the test form and captures `""`. Loading at the `require` site
  (`:reload`, once per program across separately lowered files) is .todo/b72: one init
  function per namespace hits the JVM's 64 KB method and wasm's body cap for a large
  namespace (top-level forms are chunked, a lambda body is not), `GlobalVarCollector` sees
  a `setq` only nested in a top-level non-`defun` form, and `SpecialVarCollector` reads
  `defparameter` only at a form's head.
- **Syntax-quote** resolves through `lookupVar` (no locals, no privacy: the refusal belongs
  to the expansion's site, like the oracle's compile); see the row above.

Corpus (2026-10-02, the 27 `code/test/**` namespaces of shcloj4, each through a driver
`(require 'ns) (clojure.test/run-tests 'ns)` read from `test/`, the project's own `deps.edn`
naming `src`; no inlining any more): 16 print the oracle's bytes (`chat` -- its tests are
named like the functions under test -- joins the b55 nine -- plus 6 of the 7 `macros*`
since b73). `preface` fails on the hoisting
above; of the 7 `macros*`, 6 print the oracle's bytes since b73 (the expander answers the
mangled data itself, so `=` holds and printing spells the oracle's lowercase;
`examples.macros.chain-4/chain` qualifies like it) while `macros/bench-1` still fails on
the syntax-quote qualification deviation alone (the oracle spells `clojure.core/let`,
`examples.macros.bench-1/start` and `java.lang.System/nanoTime`; b73's follow-up): the rest
stop at other gaps (`read`, `meta`/`#'`, `String` as a value, the lazy `for` input, the host
stack overflow, `clojure.set`, `proxy` over a class -- measured after b57/b59/b60 merged).
The source files load too: `wallingford` beside its `examples.replace-symbol` (the two
`replace-symbol`s apart), and `concurrency` over `examples.chat :refer :all` until `spit`
of a non-string (.todo/b74).

Pinned by `ClojureProjectNamespacesTest` (a `deps.edn` project in a temp dir: the entry under
`test/`, aliases, refers, `use :only`, a file without `ns`, a second `require`, the chat
shape under `clojure.test`, on the interpreter, the JVM and both wasm backends; the
refusals' words), `ClojureLoweringTest` (`aRequiredNamespaceLowersAheadOfTheFormThatLoadsItOnce`,
`eachNamespaceHasItsOwnVars`, `requireRefersOnlyThroughReferOrUse`,
`syntaxQuoteQualifiesTheVarsItsNamespaceSees`, `macroexpandCarriesTheCallSitesMacroScope`,
over `MemoryClojureFiles`), `ClojureSessionTest` (a buffer's require, a later buffer's
call), and `clojure-spec.yaml` (`namespaces-in-one-program-resolve-qualified-and-referred`,
`macroexpand-keeps-strings-and-keywords`, every line the oracle's).

## Core-named macros (b63)

Decided 2026-10-02 against `clj` 1.12.6.1673. **A program macro wins over every lowering
row of its name from its definition on; above the definition the core meaning holds**, like
the oracle's form-by-form compile.

- **Dispatch**: `lowerInner` tries `ClojureMacroLowering.macroCall` before any row (it used
  to sit in `call`, after the rows, so a `when-not` macro was silently ignored). Never for
  `isReservedHead`: the oracle's `Compiler.specials` (`SPECIAL_FORMS`; `if`/`do`/`let*`/`new`
  ...) and `READER_HEADS` (`syntax-quote`/`unquote*`/`deref`/`fn` -- the reader spells `@x`,
  `#(...)` with them, where the oracle reads `clojure.core/deref` -- plus `ns`/`in-ns`, which
  the pre-scan reads; `^m x` reads as `%with-meta` since b62, reserved by its `%`, so
  `with-meta` left the set and a `with-meta` macro shadows the call only). A `defmacro` of one is refused by name (the oracle
  accepts and, for a special form, ignores it).
- **Above the definition**: the pre-scan still registers every macro, but `lookupVar` hides a
  `pendingCoreMacro` -- MACRO kind, no expander yet, a name in `ClojureCoreNames` (the
  oracle's 679 `ns-publics` of `clojure.core`, a static list) -- so every resolution site
  (`call`, `atom`, `fnValue`, the `is` predicate test) takes the core path. A non-core macro
  above its definition stays `macro ... used before its definition`.
- **Syntax-quote**: a name `shadowedCoreName` reports (pending, so defined further down)
  spells `clojure.core/name`, the oracle's read-time resolution; every other core name
  stays bare (qualifying them all would change every expansion's printed form and every
  structural head check). Session gap: a macro lowered in an earlier buffer keeps the bare
  spelling, so its expansion reaches a shadow a later buffer defines.
- **`clojure.core/name`** (`ClojureCoreNames.coreSpelling`): head position lowers the row
  or builtin with `coreOnly` (no macro, no program var, no `:refer-clojure` filter), value
  position goes through `coreValue`; a name outside the list is the oracle's `No such var`,
  a listed one the lowering lacks `unknown name: clojure.core/x`. The lowering's own datum
  rewrites (`letDatum`, `some->`'s `nil?`, `lazy-cat`'s `concat`/`lazy-seq`, the `re-*`
  value's `nth`) spell their heads this way, so a program macro never captures them.
- **Pre-scan**: a definition head (`declare`, `defstruct`, `defn`, ...) whose name a macro
  defined above shadows (`scannedMacro`) pre-declares nothing; the expansion defines.
- **Quoted data**: `quote` and syntax-quote build data symbols with `dataSym`, so `'*out*`
  is the symbol `*out*`, never the `*STANDARD-OUTPUT*` alias `idSym` gives code (a
  syntax-quoted `*out*` used to decode as an unreadable symbol).
- `(new java.io.StringWriter)` in an expansion lowers at the call site (`java:new`), but
  binding `*out*` to a host `Writer` fails on every backend: .todo/b76.

Corpus (the 27 shcloj4 drivers, interpreter): byte-identical before and after. Pinned by
`ClojureLoweringTest` (`aCoreNamedMacroShadowsTheLoweringBelowItsDefinitionOnly`,
`aCoreNamedMacroBelowAMacroKeepsTheCoreMeaningInItsSyntaxQuote`,
`aMacroShadowsADefinitionHeadForThePreScanBelowIt`, `clojureCoreSpellingsNameTheCoreVar`,
the refusals in `defmacroRefusesCoreFormsAndEnvironments`) and `clojure-spec.yaml`
(`a-core-named-macro-shadows-the-lowering-below-its-definition`, all four backends).

## clojure.test (b55)

Decided 2026-10-02 against `clj` 1.12.6.1673. The shapes are the oracle's macro expansions,
lowered; the runtime is Lisp in `clojure.lisp`, so all four backends run one code path:

- `deftest name body` -> `(defun c%name%body () body)` (the `recur` target, the oracle's
  inner `fn`), `(defun c%name () (%clojure-test-var "name" #'c%name%body "(f:l)"))` and
  `(%clojure-test-register "ns" "name" #'c%name)`. The pre-scan registers the name as a
  FUNCTION from the spelling (`deftest`/`deftest-`/`x/deftest`), so `(name)` calls it like
  the oracle. Registry = per namespace in definition order (a redefinition replaces in place).
- `is` -> `(let ((E 'form) (M msg)) (%clojure-test-try (lambda () ASSERT) E M "(f:l)"))`,
  lowered behind the `try` barrier. ASSERT picks the oracle's `assert-expr` kind: `thrown?`
  / `thrown-with-msg?` (bare names only, like the oracle's symbol dispatch); a PREDICATE when
  the head resolves to a function var -- not a local, keyword, `.`/`Class.` spelling, user
  macro, `def`'d non-function, or a name in `ClojureTestLowering.CORE_MACROS` -- whose
  arguments bind to `%is-argN` locals first (literals and function/class names stay in
  place, so `instance?`/`format` still see their literal) and whose failure shows
  `(not (f values...))`; anything else the ANY kind (failure shows the value).
- `are` substitutes its template per argument group at lower time (postwalk-replace; reader
  markers stay), each `is` reporting the `are` line; a count that does not divide is the
  oracle's `The number of args doesn't match are's argv.`
- Reports go to `%clojure-test-out`, captured by `%clojure-test-init` (the runtime start
  every test-using program runs first, like the STM runtime) -- the oracle's `*test-out*`,
  so `with-out-str` never captures a report. The init also takes a lambda over the ex-info
  readers (forced on: `usedExInfo`), so the library never names a program-generated
  function, and an ex-info error prints the oracle's `clojure.lang.ExceptionInfo: msg` plus
  the data line.
- `(file:line)` is a lower-time string: the reader's position of the `is`/`are`/`deftest`
  form, the file's last path segment (the oracle's stack-frame file) or `NO_SOURCE_FILE`;
  an unlocated form (a macro expansion) names the enclosing `deftest`. `ClojureSpecE2eTest`
  compares the suffix as `(spec.clj:N)` (the file differs per leg, the line moves with the
  cases above); `ClojureLoweringTest` pins the position.
- `run-tests` takes symbols or strings (as a value too, so `(apply run-tests nss)`), bakes
  the namespaces seen so far (`namespacesSeen`, `user` first) into the call so an unknown
  one is the oracle's `No namespace: x found`; `run-all-tests` runs those plus every
  registered one, narrowed by `re-matches`.

Deviations: tests run in definition order (the oracle's is its ns-interns map order);
`thrown?` matches any condition (the catch-all `try` precedent); error reports print the
condition's message, no stack trace, at the `is` line (the oracle names the throwing frame);
a failed `thrown-with-msg?` shows the message (the oracle `#error {...}`); a host
StackOverflowError is no CL condition, so `(is (thrown? StackOverflowError ...))` ends the
program (the corpus `functional` test); `run-all-tests` lists only the program's namespaces; a thrown host `Throwable` (`(throw (Exception. "boom"))`) reaches the report as `#<java java.lang.Exception>`, so `thrown-with-msg?` cannot match its message (b66).

Corpus (2026-10-02, after b58; the 27 `code/test/**` namespaces of the shcloj4 corpus, each
inlined with the example sources it requires since b56 is open, run on the interpreter, the
oracle on the real project): 9 agree with the oracle's summary line (`fail`, `index-of-any`,
`life-without-multi` -- interpreter only, b65 -- `male-female`, `male-female-seq`,
`memoized-male-female`, `replace-symbol`, `trampoline`, `wallingford`); 9 run but differ:
`macroexpand-1` answers uppercase data (the documented deviation; the 6 `macros*` files plus
`macros`), a lazy `for` input (`lazy-index-of-any`), and `chat`, whose test names equal the
functions under test (a `deftest` redefines them in the flat namespace, b56); 9 stop at
other gaps: `meta`/`#'` (`introduction`, `exploring`), `drop-last` (b57, `sequences`),
`proxy` over a class (b71, `snake`: `[JPanel ActionListener KeyListener]`; since b59 it
stops at `proxy over a class is not supported yet: javax.swing.JPanel`, not at the interface
count), `String` as a value (`multimethods`, `interop`),
`read` (`concurrency`), project-local namespaces (b56, `preface`), the host stack overflow
(`functional`). The 153,129 B raw wasm of a one-test program
(`(deftest a (is (= 1 1))) (run-tests)`) is the printer plus the EH-mode handlers.
Superseded by the b56 run without inlining ("Namespaces and project files" above).

## A session

`ClojureSession` keeps the lowering across buffers: every buffer declares its own
top-level `def`/`defn` names into the session's globals first, so a later buffer may
call what an earlier one defined, but never clobbers what earlier buffers already
defined for the pre-scan -- a buffer's inits evaluate against the OLD binding
(decided 2026-10-02, b52: `(def p (memoize p))` in a later buffer captures the
earlier `defn`'s function cell, like the same two forms in one file). `SourceSession` prompts `clojure> `, echoes through the spliced `clojure.lisp`
printer (the `ECHO` shape, readable), and decides completeness by bracket counting over `()[]{}` 
(outside strings and `;` comments) plus a reader probe for a trailing dispatch prefix
(`'`, `` ` ``, `~`, `@`, `^`, `#'`, `#_`, `#(`).
A buffer's `require` loads a project namespace from the working directory's source path
(`SourceSession` hands its loader through `SourceLanguage.clojureFiles`); its forms ride
with that buffer, a later `require` loads nothing, and an `ns` buffer echoes nothing
(b56).

## Tests

`ClojureReaderTest`, `ClojureLoweringTest` (b55: `clojureTest*`), `ClojureSessionTest`,
`ClojureProjectNamespacesTest` (b56: programs split across files, all four backends),
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
opaque data and `::` resolved against the file `ns` name (`::alias/kw` through the
alias) -- each pinned in `clojure-spec.yaml`
(`auto-resolved-keywords-use-the-file-ns`, run on all four backends) or, for the
refusals (a bare `:`, an unknown alias), in `ClojureLoweringTest`. Seqs over every
collection lower to list views (b03 strict, b11 lazy-aware): `first`/`rest`/`next`/
`seq`/`cons`/`concat`/`map`/`filter`/`reduce`/`apply`/`take`/`drop`/finite `range`,
each pinned in `clojure-spec.yaml` (run on all four backends); laziness pins
`lazy-seq-memoizes-once`, `lazy-seq-body-is-a-zero-arity-recur-target`,
`lazy-cat-fibs-prefix`,
`take-over-repeat-cycle-iterate-repeatedly`, `logging-seq-realizes-only-take` and
the `primes-prefix-over-lazy-sieve` case there too, the lowered shapes (and the
end-less-`range` refusal) in `ClojureLoweringTest`. Printing joins `println`/`print` parts with a space and
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
for the refusals (hierarchies, protocols, `ex-info`, `set!`, backquote,
`var`, metadata, unknown namespaces), in `ClojureReaderTest`/`ClojureLoweringTest`.
Host-class dispatch and `::`-auto-resolve lower the same way (b19): `defmethod`
class spellings onto the keyword `class` answers (`String`, every numeric spelling
merged into `:number`, dotted/`java.lang`/imported names, known record/deftype tags,
a true nil mapped onto the `(:C%NIL)` marker at dispatch (a literal `:nil` keeps its keyword row, like the oracle), `Object` past the search but ahead of the default), literal
vectors element by element, `::kw` against the file `ns` name (`::alias/kw` through
the alias, `*ns*` tracked across session buffers) -- each pinned in
`clojure-spec.yaml` (`auto-resolved-keywords-use-the-file-ns`,
`class-dispatch-maps-host-spellings-to-kind-keywords`, run on all four backends;
the corpus slices are the `multimethods.clj` interest/service-charge decisions with
`derive ::savings ::account` + `isa?`, and the `pi.clj` `run-simulation` 3-method
shape) or, for the lowered shapes and the refusals (a bare `:`, an unknown alias, a
non-core class), in `ClojureLoweringTest`/`ClojureSessionTest`. The nil-marker slice keeps the same shape (b32, oracle `clj` 1.12.6.1673): a nil method answers a true nil only, a literal `:nil` falls past it to the `:nil` method or the default, and a bare `class` dispatch still answers the nil method for nil -- pinned in `clojure-spec.yaml` (`nil-method-stays-distinct-from-nil-keyword`, all four backends). A `class` call wrapped in another function answers the nil method for nil the same way (b38, same oracle: the dispatch lowering answers nil itself there, so an explicit `:nil` keeps its keyword row) -- pinned in `clojure-spec.yaml` (`wrapped-class-dispatch-still-hits-the-nil-method`, all four backends). A `class`
call through a named `defn` or a `def`'d function value answers the nil method
for nil the same way (b40, same oracle: the `defmulti` re-lowers the recorded
definition with the dispatch lowering, so a direct call to the definition keeps
answering `:nil` and an explicit `:nil` out of the named dispatch keeps its
keyword row) -- pinned in `clojure-spec.yaml`
(`named-class-dispatch-hits-the-nil-method`, all four backends). A call to a
named `defn` or a `def`'d function value nested inside an inline dispatch datum
answers the nil method for nil the same way (b46, same oracle: the call site
inlines the recorded definition with the dispatch lowering; a shadowed name
keeps its call, and a name already being inlined keeps its direct call so a
(mutually) recursive definition still terminates the lowering) -- pinned in
`clojure-spec.yaml` (`indirect-named-class-dispatch-hits-the-nil-method`, all
four backends).
Macros lower the same way (b12): `defmacro` (multi-arity, `&` rest, docstring and
attr-map skip, `declare` pre-scan, whole-file pre-scan, session-aware) as one expander
lambda over the call's argument list plus a runtime table entry, call sites expanded
datum-to-datum at lower time through the macro evaluator (`eval/ClojureMacroTime`,
lazy, one per file or session), syntax-quote as `quote` with unquote splicing over the
mangled namespace (`~` lowers as code, `~@` splices into lists, vectors, maps and sets,
`x#` one gensym per expansion -- fresher than the oracle's per-compilation suffixes),
`macroexpand-1`/`macroexpand` over the table answering the mangled data itself (b73:
`=` against a quoted form holds, printing spells the oracle's lowercase), `gensym` as the
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
condition), `memfn` as a lambda over the instance call, `proxy` of one or
more interfaces (b59) through `java:proxy`, and the literal-only string position beside the
pattern arms -- each pinned in `clojure-spec.yaml` (run on all four backends)
or, for the interop legs (`proxy`, host-object `memfn`, literal `String/split`),
in `ClojureInteropTest`; what stays refused (`definterface`/`gen-class`/`gen-interface`,
multi-arity protocol methods, `set!`,
`var`/`#'`, `proxy` over a class or naming an `Object` method)
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
(`clojure.spec`/`xml` namespaces,
`file-seq`/unknown-`clojure.java.io`-fn, `%e`/`%g`/flags) in `ClojureLoweringTest`, and the file IO
(interpreter and JVM in `ClojureInteropTest`, the wasm preopen case in `ClojureWasmFileIoTest`,
the un-preopened refusal in `ClojureWasmFileRefusalTest`)
plus the `keep`/`update` signals in `ClojureInteropTest`. A `def` after a `defn`
lowers the same way (b52): the value against the OLD binding, so `(def p
(memoize p))` captures the function cell -- pinned in `clojure-spec.yaml`
(`def-after-defn-memoize-captures-function`, all four backends), the lowered
`#'` shapes in `ClojureLoweringTest` and the cross-buffer shape in
`ClojureSessionTest`.
The reader-object IO slice lowers the same way (b22): `clojure.java.io` resolves
for exactly `reader` (an `open` input stream over the file-stream runtime, so
`line-seq` reads it and `with-open` closes it), `line-seq` takes a path or an
open reader (strict either way; a reader is never closed by the read), and the
`ns`/`require` `:as`/`:refer` wiring follows the `clojure.string` row -- pinned
in `clojure-spec.yaml` (`jio-reader-resolves-on-every-backend`, resolution only,
all four backends), `ClojureLoweringTest` (the lowered shapes, the
unknown-`jio`-fn and bare-`reader` refusals), `ClojureInteropTest` (the
hangman `available-words` shape, the `non-blank-lines` count, the observable
`with-open` close and the path regression over the vendored fixture, interpreter
and JVM) and `ClojureWasmFileRefusalTest` (both wasm backends refuse without a
preopen); `file-seq`/`load-string`/`read-string`/`eval` stay refused. The
quoted-libspec slice lowers the same way (b24, oracle `clj` 1.12.6.1673): a bare
`require`/`use` unwraps `(quote spec)`/`'spec` items (quoted `:as`/`:refer`/bare
specs, quoted `use` filters) and shares the `ns`-clause spec parser, so the
`life_without_multi.clj` `my-print-vector` and `sequences.clj` `non-blank?`
shapes run; an unquoted vector spec stays accepted (a lenient superset -- the
oracle rejects it with a `ClassNotFoundException`) -- pinned in
`clojure-spec.yaml` (`bare-require-accepts-quoted-libspecs`, all four backends),
`ClojureLoweringTest` (the quoted wiring, the quoted unknown-namespace refusal)
and `ClojureInteropTest` (the two book shapes over the vendored fixture,
interpreter and JVM). The prefix-list slice lowers the same way (b30, oracle
`clj` 1.12.6.1673): a `(prefix member...)` list datum -- quoted (`'(prefix ...)`,
the oracle's bare-`require` spelling) or bare (the unquoted-vector leniency
extended, shared with the `ns` clauses which quote implicitly) -- resolves each
member (a bare or quoted symbol, or a `[...]` vector with the usual options)
under the prefix through `requireOne`, so `(require '(clojure [string :as s]))`
wires `s/join` and `(use '(clojure [string :only [upper-case]]))` refers
`upper-case`; a bare `(prefix)` names the prefix library itself, like a bare
symbol, and a non-symbol head stays refused with the same `takes library specs`
message -- pinned in `clojure-spec.yaml` (`bare-require-accepts-prefix-lists`,
all four backends) and in `ClojureLoweringTest` (the quoted/bare wiring, the
`use` and `ns`-clause paths, the non-symbol-head refusal). `use` ignores its
`:only`/`:exclude` filters (the follow-up). The
interop-as-value slice lowers the same way (b20): a bare `Class/member` value reads
the static field (dotted, imported, or `java.lang`, like the call position) or
answers an arity-dispatching member lambda (a variadic-only member is refused by
name; an unknown class or member reads the field, whose run-time error names it), a
zero-argument `(Class/m)` or `(. Class m)` is the static method when the host class
has one else the field (lifting the b08 deviation), `make-array`/`aget`/`aset`/
`alength` lower to the general array forms (the class is ignored), and `*in*` is
`*standard-input*` (rebindable through `binding`; `.readLine` reads through
`read-line`, nil past the end) -- pinned in `clojure-spec.yaml`
(`make-array-aget-aset-and-alength-round-trip`, `in-is-bound-to-standard-input`,
all four backends), `ClojureLoweringTest` (the lowered shapes, the variadic
refusal), `ClojureInteropTest` (the corpus slices: `blank?` from `introduction`,
`painstakingly-create-array` from `interop`, `take-guess` from `hangman` over a
mocked `*in*`, interpreter and JVM) and `ClojureWasmInteropRefusalTest` (both wasm
backends refuse the `java:` legs with the undefined-function call-time error);
`proxy` over a class plus `proxy-super` stay refused (b71; the snake GUI files stay
non-goals, b16). The host-boolean slice lowers the same way (b29, oracle `clj`
1.12.6.1673): an instance call on a construction literal -- on a `let` local bound to one, and since b34 on an `if-let`/`when-let` local bound to one or on a `..` step single declared return --
whose overloads at that arity all answer a primitive boolean answers
`T`-or-false (a shadowing binding hides the class again; `loop` targets, parameters, `when-first` members
and unknown receivers keep the shared unmarshal, printing `nil` for `false`) -- pinned
in `ClojureLoweringTest` (the wrapped and bare shapes, the shadow, the `if-let`/`when-let`/`..` shapes) and
`ClojureInteropTest` (the `contains`/`isEmpty` prints incl. the `if-let`/`when-let` and `..` shapes, the `if`/`=`/`str` values, the
untouched non-boolean answers, interpreter and JVM) with the refusal legs beside
`ClojureWasmInteropRefusalTest`.
Predicate values answer `T`-or-false (so `(map odd? [1 2])` prints `(true false)`
like the oracle); `filter`/`remove` test Clojure truthiness around the call.
Multi-entry maps and multi-member sets never print in the spec (the walk order is
unspecified); only single-entry/single-member shapes pin the notation.
State, dynamic scope and the small imperative companions lower the same way (b14):
metadata-ignored (`defn-`, `^:private`/`^:dynamic`/`^{...}`/type hints on names,
parameter vectors, patterns and values, `def` docstrings and attr maps; `with-meta`
attaches since b62), `defstruct`/`struct`/`struct-map` over key vectors,
`defonce` as `def` unless `boundp`, `ref`/`dosync`/`alter`/`commute`/`ref-set`/
`ensure` over the atom cell with the spliced STM runtime (depth, identity-keyed
validator registry, agent var), `agent`/`send`/`send-off`/`await`/
`shutdown-agents` as synchronous atoms over the same runtime, `binding` over
`defparameter`-proclaimed specials, `with-open`/`with-out-str`/`time`, `*out*`
as `*standard-output*`, and the stream `.write`/`.flush`/`.close` methods -- each pinned
in `clojure-spec.yaml` (run on all four backends; the corpus slices are the
`chat.clj` validator room `refs-transact-singly`, the `concurrency.clj` dynamic
memo `binding-rebinds-dynamic-vars`, the `pi.clj` agent partition
`agents-send-synchronously`, plus `metadata-is-parsed-and-dropped`,
`defstruct-struct-and-struct-map`, `defonce-keeps-its-root`,
`with-out-str-captures-output-and-time-answers` and
`streams-write-through-out-and-import-answers-nil`; concurrency timing is never
asserted, answers only, no sleeps in the spec), the lowered shapes and the
non-dynamic-`binding` shape in `ClojureLoweringTest`, and the deferred refusals
(`future`/`delay`/`force`/`promise`/`deliver`, `proxy-super`, the
`with-open`-over-Java shape) in `ClojureLoweringTest` (`with-open` has no spec
case: its closer is `java:call`, which wasm only warns past). `def` keeps a lone
map value (an attr map needs a value behind it -- the `cycles` regression that
caught the first draft). `letfn` and `recur`-to-`fn` lower the same way (b17):
`letfn` as one `labels` over pre-scanned entries, `recur` through the
recur-target stack (`loop`, named/anonymous `fn`, `defn` clauses, `letfn`
entries, stored method lambdas labels-wrapped iff used) with per-clause arity
checks -- each pinned in `clojure-spec.yaml` (`letfn-binds-mutual-fns-and-
closes-over-outer`, `recur-targets-a-named-fn-and-a-defn`,
`recur-fibs-and-primes-prefix`, the 5000-deep
`deep-recur-answers-on-every-backend`; the corpus slices are `functional.clj`
fibs and the `primes.clj` prefix, run on all four backends) or, for the lowered
shapes and the refusals (wrong-count `recur`, `recur` outside any target,
variadic-`recur`, malformed `letfn`), in `ClojureLoweringTest`; the innermost
binding wins for calls now, so a local shadows an outer one (pinned there too).
Regex literals and `re-*` lower the same way (b21, oracle `clj` 1.12.6.1673):
`#"..."` reads to a `(%regex source)` datum and lowers through
`RONTOLISP::%CLOJURE-RE-COMPILE` to a `(:C%PATTERN stamp source ops ngroups)`
value (the stamp a fresh gensym, so `=` is identity like the oracle);
greedy, reluctant and possessive quantifiers plus backreferences lower too;
`re-pattern`/`re-matcher`/`re-find`/`re-seq`/`re-matches`/`re-groups` lower to
the spliced runtime (each a value too), and `split`/`replace`/`replace-first`
branch on the pattern at run time (plain strings stay literal, still pinned by
`string-replace-and-split-stay-literal`). The engine is a backtracking matcher
in `clojure.lisp` over the string primitives every backend already compiles --
decision (a) with no per-backend code: literals compile AND runtime strings
widen, sharing one runtime (a host `java.util.regex` import would have served
only two backends, a WASM-side engine is the same code with a new language).
Patterns print `#"..."` (`str` spells the source), matchers `#<Matcher source>`;
`class` answers `:pattern`/`:matcher`, `coll?` is false, `count`/`empty?`/the
seq view signal, `conj` onto one signals. Unsupported constructs
(lookarounds, named groups, inline flags, POSIX
classes, `&&`, `\G`), bad escapes and bad ranges signal `unsupported regex`
at construction; `$` past the groups and a trailing `$` signal like the
oracle. Pinned in `clojure-spec.yaml`
(`regex-literals-compile-and-round-trip`, `re-find-re-seq-groups-and-matches`,
`re-matcher-loop-pins-sequences-demo`,
`string-split-and-replace-take-patterns` -- the corpus slices are
`exploring.clj` `indexable-words`/`ellipsize`, `sequences.clj`
`demo-mutable-re`, `utils.clj` `jar-urls` -- run on all four backends), the
lowered shapes and arity refusals in `ClojureReaderTest`/`ClojureLoweringTest`,
and the run-time signals plus value legs in `ClojureInteropTest`
(interpreter and JVM). b50 keeps the reader verbatim like the oracle (the old
read halved every `\\` run, so `#"\\d"` answered the digit class; now it reads
a literal backslash plus `d`, pinned by `regex-literal-backslashes-stay-verbatim` --
no runtime change, the `clojure.lisp` parser already reads the escapes), and
fixes `def` eating a lone string value as a docstring (pinned by
`def-lone-string-is-the-value-not-a-docstring`, the
`examples/clojure/demo.clj` greeting regression).
Cost (2026-10-01, x86-64 Linux, Java 25, raw module totals --
`.kb/size-measurement.md`: the target number is the raw total a downloader
pays): `(println (re-find #"a+" "aaab"))` compiles to 63,300 B of wasm
(64,563 B as a component); the same program over a literal split
(`(s/split "a,b" ",")`) is 52,597 B, over a pattern split 72,269 B, so the
spliced runtime costs about 19.7 KB raw when referenced (a program without
any of it, `(println (+ 1 2))`, is 9,581 B -- the pruner drops the runtime
wholly, like the STM/hierarchy runtimes). The literal arm itself is untouched
code beside a run-time branch, so literal-only programs pay nothing new.

## Transducers (b60)

Decided 2026-10-02 against `clj` 1.12.6.1673 (every spec line diffed). **A transducer is
the oracle's: a function from a reducing function to a reducing function**, built by a
spliced `rontolisp::%clojure-xf-*` worker (`clojure.lisp`, "Reduction and transducers"),
not a tagged closure the consumers interpret. So `comp` composes transducers left to right
with no help, a program's own `(fn [rf] (fn ([] ...) ([acc] ...) ([acc x] ...)))` runs beside
the core ones, and `(apply comp [...])` needs nothing either. A built-in reducing function
is an `&optional` lambda telling init / completion / step apart by the supplied-p flags;
per-reduction state (take's count, partition-all's buffer) lives in variables the
`(lambda (rf) ...)` closes over, created when the transducer meets its reducing function,
like the oracle's volatiles -- so the completion step is NOT skipped: `partition-all` /
`partition-by` flush their tail, after an early `take` stop too.

- Lowering: `ClojureTransducerLowering`. `xformCall` intercepts at the top of the hub's
  `builtin` (after `coreAllowed`) when a verb in its table has the transducer arity:
  `map`/`filter`/`remove`/`keep`/`keep-indexed`/`map-indexed`/`take`/`drop`/`take-while`/
  `drop-while`/`take-nth`/`mapcat`/`partition-all`/`partition-by`/`interpose` (one
  argument), `dedupe`/`distinct` (none). `xformValue` widens the fixed-arity verbs' value
  lambdas with an `&optional` collection (`map`/`mapcat` value lambdas answer the
  transducer for an empty rest, the b57 `-v` entries in Lisp). `callOf`/`valueOf` take
  `transduce`, `eduction`, `sequence`, `completing`, `reduced`, `reduced?`, `unreduced`,
  `ensure-reduced`, `cat`, `take-nth` (arity errors in the oracle's wording).
- `reduced` is the `(:C%REDUCED x)` wrapper; `reduce`, `reduce-kv`, `transduce`, `into`
  and the stepping consumers stop at it and unwrap it; `deref` reads it (the atom test's
  else arm calls `%clojure-deref-other`). `(conj)` is `[]` (the init `(transduce xf conj
  coll)` calls).
- `sequence`/`eduction` step their inputs through the transducer one element at a time
  behind a lazy wrapper (`%clojure-xf-puller` batches the outputs of one input step), so a
  lazy input stays lazy and `take` of an infinite one terminates; a strict input answers
  the realized strict list (the lazy-or-strict rule). Several collections step in
  lockstep, spread as the step's arguments (only `map`'s reducing function takes them).
- Deviations: an `eduction` is that seq, computed once, where the oracle re-runs the
  transformation each time it is reduced; `println` therefore prints it as the seq (the
  oracle prints the object; `prn` agrees); a reduced value prints as its wrapper list;
  `take-nth`'s seq arity signals on a zero step and steps by the magnitude of a negative
  one (the oracle repeats the first member forever); `halt-when`/`random-sample` are absent.
- Fixed beside it: `comp` as a VALUE (`(apply comp fns)`) spliced the inner result as a
  call form, so it called the result (`Not a function: 2`); the spec's apply-comp line pins it.
- Cost (2026-10-02, x86-64, Java 25, raw wasm module totals, base = HEAD before b60):
  `(println (reduce + [1 2 3]))` 31,773 -> 33,340 B (the lazy-aware, reduced-aware walk;
  a first cut that called the IFn dispatcher from the runtime measured 40,365 B, so the
  dispatcher wrap moved to the call site); `(println (into [] [1 2]))` 42,259 -> 43,613;
  `(println @(atom 1))` 30,674 -> 30,867; `(println (+ 1 2))` 9,585 unchanged. A
  transducer program: `(into [] (map inc) [1 2])` 53,436, `(transduce (map inc) + [1 2])`
  42,415, `(sequence (map inc) [1 2])` 46,677.
- Pinned by `clojure-spec.yaml` (`b60-into-transduce-and-composition`,
  `b60-seq-verbs-have-transducer-arities`, `b60-sequence-eduction-and-reduced`, all four
  backends), `ClojureLoweringTest.transducerAritiesBuildTheSplicedTransducers`, and the
  eager.clj `non-blank-lines` / `line-count` shapes over a file in
  `ClojureInteropTest.filesRoundTripThroughReaderAndLineSeq` (interpreter and JVM). The
  corpus has no other transducer use (`eager.clj`'s `preds` needs `all-ns`).

b62 (2026-10-02) lowers `:extend-via-metadata` (the `note.clj` `MidiNote` protocol of
the shcloj4 corpus) plus the value metadata it needs (the table rows above). The todo's
premise that an `extend-type` row wins over metadata was overturned on the oracle (`clj`
1.12.6): the order is body implementation, metadata, extension rows, `Object`; the key
is the protocol's namespace-qualified method symbol (`` `msec `` = `user/msec`), never
`Proto/method`. It also fixed the shared `Object` slot (above), which the corpus shape
hit first. Pinned by `protocols-extend-via-metadata` and
`object-extension-keeps-a-row-per-method` in `clojure-spec.yaml` (every line diffed
against `clj`, all four backends), `metadata-is-parsed-and-dropped` (a type hint in
value position stays dropped), `ClojureLoweringTest.varStaysRefusedWhileReaderMetadataDrops`
and the flag assertions in the protocol refusal test, `ClojureReaderTest` (`%with-meta`).

b58 (2026-10-02) closes three reader/metadata refusals: `#^` reads like `^`
(`ClojureReaderTest.legacyHashCaretMetadataReadsLikeTheCaret`), `ns` strips
metadata on its name (`ClojureLoweringTest.nsSkipsMetadataOnItsNameLikeDefAndDefn`),
and record literals read and records print as `#ns.Name{...}`
(`ClojureReaderTest.recordLiteral*`,
`ClojureLoweringTest.recordLiteralsBuildTheRecordOverTheQuotedBody`, and
`record-literals-read-and-print-like-the-oracle` in `clojure-spec.yaml`, every line
diffed against `clj` 1.12). The todo's premise that a simple `#Rec{...}` reads was
overturned on the oracle: an undotted tag is a tagged literal (`No reader function
for tag Rec`), so only the dotted class name is a record literal.

b57 (2026-10-02) lowers the core backlog (the table rows above, oracle `clj`
1.12.6.1673, every printed line diffed): pinned in `clojure-spec.yaml`
(`b57-split-and-tail-verbs`, `b57-peek-pop-and-not-empty`,
`b57-dedupe-partition-all-and-partition-by`,
`b57-key-extremes-juxt-fnil-and-predicate-combinators`,
`b57-update-keys-update-vals-and-reduce-kv`, `b57-backlog-verbs-as-values-and-pmap`,
`b57-equality-is-sequential-and-deep`, all four backends) and `ClojureLoweringTest`
(`coreBacklogVerbsCallTheirSplicedWorkers`,
`coreBacklogArityAndTransducerRefusalsUseTheOracleWording`,
`aProgramDefinitionOrLocalShadowsACoreNameInCallPosition`). The oracle's own traps
for a probe: `(partition-all 0 ...)` prints forever, and `dedupe` over an infinite
seq that repeats forever hangs there (its transducer realizes ahead) where the
lazy wrapper here answers the `take`n prefix. `add-watch`/`remove-watch` stay
refused (no corpus case).

## deftype mutable fields (b61, 2026-10-02)

A field marked `^:unsynchronized-mutable` / `^:volatile-mutable` (`nameHasMetaFlag`;
ClojureScript's `^:mutable` is no marker -- the oracle answers
`Cannot assign to non-mutable` for it) leaves the public table: the constructor appends
`(vector m1 m2 ...)` as the deftype's fifth element (absent without mutable fields, so
other deftypes keep the 4-list), `.-field` misses it (`No such field`, the oracle
`No matching field found`), `defrecord` refuses the markers with the oracle's wording.
An inline method binds the slot vector to a temp and wraps its body in
`(symbol-macrolet ((field (aref slots i))) ...)` (the shared shadow-aware walker,
`.kb/symbol-macrolet.md`), so every read is live -- a read after another method's
`set!` on the same instance sees it, like the oracle's field access -- and
`set!` lowers to `(setf (aref slots i) v)`. The scope kind `MUTABLE_FIELD` plus
`ClojureLowering.mutableFieldPlaces` say which name is the field; `set!` of any other
local is `Cannot assign to non-mutable`. Closure boundaries copy: the oracle compiles
`fn`/`#()`/`letfn`/`reify`/`lazy-seq`/`for`/`dosync`/`proxy` to a class whose
constructor copies each field it reads, so `capturingMutableFields` rebinds the visible
fields as plain locals around those forms (only the ones the lowered form mentions), and
`letfn` re-establishes the symbol macros for its body. A parameter named like a field
now shadows it (the field `let*` sat inside the lambda list and shadowed it back -- a
b13 bug for immutable fields too). `^:volatile-mutable` gives no cross-thread ordering.

The todo's premise was overturned on the corpus and the oracle: no shcloj4 program
declares a mutable field, and `^:mutable` is not Clojure's marker. The corpus `set!` is
`(set! *warn-on-reflection* true)` (`instant.clj`, a `clojure.main`-bound var), left to
b75 with the dynamic-var case; a non-dynamic global lowers to the oracle's run-time
`Can't change/establish root binding of: ... with set`. Pinned by
`deftype-mutable-fields-assign-through-set` in `clojure-spec.yaml` (every line diffed
against `clj` 1.12, all four backends), `ClojureLoweringTest.setBang*`.

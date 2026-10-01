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
| `defn` | `defun` of the mangled name, called directly; a head-position call to a `VARIABLE`-kind name holding a real function (a `let` binding of one, a `def`'d one) is a `funcall` of the value cell instead, while any other variable goes through the prelude dispatcher (`rontolisp::%clojure-call`: functions through `apply`, collections through their lookup, like `IFn`), so higher-order `defn` parameters run on collections too; a `declare`d-but-never-defined name keeps its direct-call error; several arities one `defun` per arity plus a dispatch `defun` | keeps the direct call and the tree shaker. Pass one collects every top-level `def`/`defn` name (and every `declare` name), so a definition may use one below it; a real definition still wins over a declaration. Helpers are named `c%<name>%<arity>` (`%*` for the variadic clause) -- a lone `%` no mangled identifier spells, so they stay apart from user definitions. A wrong count signals (`wrong number of arguments passed to: f`); at most one variadic clause and one clause per arity, else a named refusal. A multi-arity `defn` in a body is refused by name (several `defun`s cannot splice into expression position). A `^:dynamic` one keeps its `defun`(s) and installs the function in the value cell behind a `defparameter` of it, so calls route through the value cell (a `funcall`, like a `def`'d function) and `binding` rebinds it with dynamic extent; `recur` and the arity-dispatch helpers stay direct calls to the function cell. The pre-scan registers a `^:dynamic` `defn` name as a variable, so even a forward call routes through the value cell |
| `declare` | nothing (`nil`) | a forward declaration in the pre-scan, so a session buffer may call what a later buffer defines |
| `defmacro` | one expander lambda over the call's argument list plus a runtime table entry, call sites expanded datum-to-datum at lower time | the expander is one lambda dispatching on the argument count (like the multi-arity `fn`), applying each arity's parameters with their destructuring prologue; the same lambda runs at lower time (through the macro evaluator) and at run time (through the `c%name%macro` table global, for `macroexpand-1`); a docstring and an attr map are skipped, `&` rest works, `&form`/`&env` are refused; the pre-scan registers the name, a call above its definition names the missing expander, a macro has no function value, a later `def`/`defn` wins the call sites back; a body sees the core builtins and the `clojure.lisp` library, not the program's definitions; four-backend parity by construction (expansion before backends), the interpreter's `eval` of a macro call expanding the same way |
| syntax-quote (`` ` ``) / `~` / `~@` | `quote` with unquote splicing over the mangled namespace | every symbol qualifies behind `c%` (the documented deviation: no namespaces); `~` lowers as code, `~@` splices a sequence into the enclosing list, vector (`apply vector`), map (plist `append`) or set (`dolist` accumulation); each `x#` binds one `(gensym "x")` per syntax-quote node (one symbol per expansion, the same at every occurrence, fresh across expansions -- fresher than the oracle's per-compilation suffixes); an unquote outside any syntax-quote and a splice outside a sequence are refusals; nested levels evaluate in the one expansion |
| `macroexpand-1` / `macroexpand` | the spliced `C%MACROEXPAND-1` / `C%MACROEXPAND` runtime over the table globals | once / to the fixpoint, each answering the expansion demangled and uppercased for printing (case folds, print-only); a non-macro head answers the form itself, demangled the same way; each names a function value; their data takes bare operator names |
| `gensym` | the ordinary `gensym` (uninterned `#:`-spelled symbol) | fresh per evaluation (per expansion in a macro, per call at run time); a string names the prefix, an integer suffix spells itself; names a function value |
| `def` | top-level `setq` of the mangled name | inside a body it still sets the global when the body runs (decided 2026-09-30, b04: keep the `setq`, document it) |
| `fn` / `#(...)` | `lambda`; several arities one `lambda` over `&rest` dispatching per arity; a named one a `labels` self-binding, an anonymous one the same binding only when a `recur` reaches it | `#(...)` arguments travel as one `&rest` list, `%`..`%9` as `(nth n args)`; at most 9 args; the body forms are wrapped as ONE call (`#(f a b)` -> `(f a b)`, matching the dominant spelling; multi-form bodies need an explicit `do`). The `fn` dispatch binds each arity's arguments through `let*` (no local functions, so clauses close over the outer scope); a name lowers to direct self-calls the `labels` expansion rewrites; a `recur` in any `fn` body (named or not) calls the enclosing clause directly, checked against its arity (decided 2026-10-01, b17) |
| destructuring (`let`/`loop`/`fn`/`defn` patterns) | `let*` pairs over one temporary per pattern | a vector pattern binds positionally through the seq view (`nth`, past the end nil; `&` the rest as a seq, itself a pattern; `:as` the whole); a map pattern through the table-aware read (`:keys` binding the short name when qualified, `:syms` from quoted symbols, `:strs` from strings, explicit locals from key expressions, `:as`, `:or` defaults); nested patterns recurse. Malformed shapes are named refusals |
| `let` | `let*` | Clojure's `let` is sequential |
| `letfn` | one `labels` over every entry, like a named `fn` self-binding per entry | every name is pre-scanned first (the `defn` mutual-recursion precedent), so siblings call each other directly and the body calls them; each entry lowers like a named-`fn` clause with its own recur target (destructuring included); an entry's name is a function value; an empty binding vector is just the body, and a later entry shadows
an earlier one with the same name (decided 2026-10-01, b17) |
| `loop`/`recur` | `labels` self call | `recur` targets the innermost enclosing `loop`, named or anonymous `fn`, `defn` clause or `letfn` entry (a recur-target stack, not a single loop slot -- a plain lambda pushes nothing, so a `recur` passes through it; decided 2026-10-01, b17); each multi-arity clause is its own boundary, so the count must match the enclosing clause (multi-arity calls route through the dispatch); a wrong count is a named refusal, `recur` outside any target stays one; the interpreter's tail calls (wasm `return_call`) make it constant-stack -- the spec's 5000-deep case overflows a non-tail expansion on wasm (about 3000 there) and fits the JVM worker's frame budget on every backend; inits are sequential and parameters destructure, like `let` (decided 2026-09-30, b04); a `recur` reaching a variadic clause splits into a worker plus its `&rest` head, like the multi-`defn` helpers (decided 2026-10-01, b27): the worker takes the rest as an ordinary parameter, so the `recur` call assigns exactly, while normal calls wrap through the head, like the oracle; single-arity `defn`/`fn`/`letfn` entries and multi-arity clauses with a used variadic target (an unused variadic keeps its single shape); a `recur` reaching a variadic stored-method lambda (`defmethod`, inline protocol methods) stays refused by name; tail position and the `try` boundary are enforced like the oracle (decided 2026-10-01, b26): only a `recur` in its target body's tail position lowers -- `if` arms, `let*`/`progn` tails, `labels` bodies, `cond` arms, `do`, `when` and the dispatch lets -- anywhere else its `Can only recur from tail position` refusal; a `try` between the `recur` and its target is the oracle's `Cannot recur across try` refusal (a barrier beside the target stack, so a target opened inside the `try` still recurs); a `binding` body and a non-empty `with-open` body lower behind the same barrier (the oracle wraps both in a `try`, the empty `with-open` staying a bare `do`), the inits outside it -- a `recur` in an init is the tail refusal instead (decided 2026-10-01, b35); a `lazy-seq` body is its own zero-arity target (decided 2026-10-01, b28): a `recur` in its tail position calls the thunk itself, checked against arity 0 (a wrong count names 0), anywhere else its `Can only recur from tail position` refusal; `#()` recurs unchecked (its arity is the highest `%N`, known only after the body lowers), so a wrong count signals at run time |
| `->`/`->>`/`as->` | the threaded call, rewritten as datums | `->` inserts second, `->>` last; a bare name or keyword calls/reads with the value; `as->` is nested `let`s, so shadowing matches the oracle. A step over a collection literal signals (collections are not functions here) |
| `doto`/`cond->`/`cond->>`/`some->`/`some->>` | the threaded calls around one temporary | `doto` answers its (unchanged) target; `cond->` threads only on truthy tests; `some->` stops at `nil` but not at `false`, like the oracle |
| `list*` | a right fold of `cons` over the seq view | of one argument, just its seq (signalling for a non-collection, like the oracle) |
| `doseq` | nested `dolist` loops over the seq view around an implicit `do`, answering `nil` | `:when` skips, `:while` ends its level through a block (an outer level's ends the whole form), `:let` binds sequentially; patterns destructure like `let`; an empty vector runs the body once, `nil` never |
| `dotimes [i n]` | the core `dotimes` over `(truncate n)`, answering `nil` | the count runs through `truncate` first (the oracle's `intCast`: `2.5` counts `0 1`, a non-number signals there); exactly one plain name and count, else a named refusal |
| `for` | nested `dolist` loops accumulating in reverse into a strict list | `:when`/`:while`/`:let` per level like `doseq` (an inner `:while` ends only its level, measured on the oracle); empty is `nil` (the `rest`/`take` divergence, not `()`); unknown keywords the oracle's `Invalid ... keyword` refusal; an empty vector refused, like the oracle |
| `dorun`/`doall` | the strict companions: the collection (and the optional count) evaluated, answering `nil`/the collection itself, each a function value too | seqs are already strict, so realizing is evaluating; `doall` never coerces (a vector stays a vector) |
| `defmulti`/`defmethod`/`remove-method`/`get-method` | a method table plus a dispatcher `defun` | `defmulti` builds an `equal` table, a default value, a per-multimethod prefers table and an `Object`-method slot in four globals no identifier can spell (the suffix follows the mangled name, like the multi-arity helpers) plus a rest-args `defun` applying each call's dispatch value to the table; `defmethod` stores a parameter lambda (destructuring included) under the dispatch value lowered by `dispatchKeyForm` -- a class spelling (`String`, `Number`, ..., dotted/`java.lang`/imported names, known record/deftype names) onto the keyword the `class` dispatcher produces for it, `nil` onto the `(:C%NIL)` marker (the dispatcher maps a true nil onto it first, so no table ever keys on nil, while a dispatch value that literally is `:nil` keeps its keyword row, like the oracle; a bare `class` dispatch answers that keyword only for a nil argument, so it maps onto the marker too -- a `class` call wrapped in another function keeps the keyword and still misses the nil method), `Object` under the `:object` keyword plus the catch-all slot, `::`-keywords resolved like anywhere else, literal vectors element by element; an exact hit applies, else the `C%H-DISPATCH` helper searches every method the dispatch value descends from through the multimethod's hierarchy (the global value without `:hierarchy`, a per-call expression with it), the strictly most specific wins, `prefer-method` breaks ties, and an unbroken tie signals `Multiple methods ...`; a miss with no candidate tries the `Object` slot past the search but ahead of the default dispatch value (`:default` without an option, an arbitrary keyword with the `:default` option -- the corpus's `:everything-else`, stored per-multimethod like `:default` today) or signals `No method in ...` |
| `derive`/`underive`/`isa?`/`parents`/`ancestors`/`descendants`/`make-hierarchy`/`prefer-method` | the hierarchy runtime over the shared table runtime | a hierarchy value is a map of `:parents`/`:ancestors`/`:descendants` tables (children to wrapped sets); the global value lives in `C%H-GLOBAL`, rebound by two-argument `derive`/`underive` (answering `nil`), while three-argument forms answer an updated value (`make-hierarchy` an empty one); `isa?` is `equal`, element-wise vector derivation, or ancestor membership (two- or three-argument), answering `T`-or-false; the three reads answer (possibly empty) sets; `prefer-method` records into the multimethod's prefers table and answers the multimethod; the runtime (set helpers, transitive rebuild, dispatch search) is spliced once behind the false binding when used |
| `try`/`catch`/`finally`/`throw` | `handler-case` inside `unwind-protect`; `throw` over `error` | every catch class answers the catch-all `error` clause (first clause wins; the catch variable binds the CL condition); `throw` signals an `ex-info` value as its own condition and anything else through its Clojure-notation rendering (`rontolisp::%clojure-str-of`), so strings keep their message |
| `ex-info`/`ex-data`/`ex-message` | a condition with message and data slots | `ex-info` builds it through `make-condition` (its report prints the message); `ex-data` answers the map (`nil` for any other condition), `ex-message` the message (anything else through its Clojure-notation rendering); each works as a function value; the class plus the throw/data/message helpers are spliced once behind the false binding when used |
| `atom`/`deref`/`@`/`swap!`/`reset!`/`compare-and-set!` (and `volatile!`/`vswap!`/`vreset!`) | a tagged one-vector cell `(:C%ATOM #(value))`, like the set wrapper | every verb reads/writes the cell and answers the new value (`compare-and-set!` compares with `eql` and answers `T`-or-false); misuse signals; each works as a function value, so `(map deref atoms)` runs |
| `ref`/`dosync`/`alter`/`commute`/`ref-set`/`ensure` | the atom cell with a transaction discipline, over one spliced STM runtime | `ref` is the cell (a `:validator` registers in an identity-keyed alist); `dosync` binds the open depth one deeper around the body; `alter`/`commute` apply through the validator (a failed one signals and writes nothing -- the single-threaded rollback) and answer the new value; `ref-set` replaces through it; `ensure` answers the ref; every verb outside `dosync` signals `No transaction running`; `commute` runs once (the oracle may run it twice); `ref`/`alter`/`commute`/`ref-set` work as function values |
| `agent`/`send`/`send-off`/`await`/`shutdown-agents` | the atom cell as a synchronous agent, over the same runtime | `agent` is the cell (a `:validator` registers the same way); `send`/`send-off` apply at once with `*agent*` bound to the cell, through the validator, answering the cell; `await` checks each cell and answers `nil`; `shutdown-agents` is `nil`; there is no thread pool, so async ordering is out; `agent`/`send`/`send-off` work as function values |
| `binding` | `let*` over the bound names, sequentially like `let` | only `^:dynamic` vars (and `*out*`) may be bound -- anything else is the oracle's non-dynamic error as a named refusal; a `^:dynamic` `def`/`defonce` lowers to `defparameter` (always sets, like `def`, and proclaims the special, so the `let*` rebinds with dynamic extent); a `^:dynamic` `defn` keeps its `defun`(s) and adds a `defparameter` of the function, so its calls go through the value cell and the `let*` rebinds them the same way (`recur` still jumps straight to the function cell, like the oracle); the body closes over the scope the same way; the body lowers behind the `try` barrier, so a `recur` there is the oracle's `Cannot recur across try` refusal (decided 2026-10-01, b35) |
| `defonce` | `def` unless `boundp` | a reload keeps the root where `def` resets it; a `^:dynamic` one keeps through `defparameter` instead |
| `defstruct`/`struct`/`struct-map` | the key vector behind the name plus fresh-table builders | `defstruct` stores a vector of the keyword wrappers; `struct` pairs keys with values (missing `nil`, too many signal); `struct-map` seeds the keys and overrides pairwise |
| `defn-` | `defn`, private by convention only | metadata never affects dispatch, so there is nothing to enforce |
| `with-meta`/`^` metadata | dropped: the object lowers as itself | `^:private`/`^:dynamic`/`^{...}`/type hints parse and drop on names, parameter vectors, patterns and values; `with-meta` works as a function value (the first argument); only `binding` reads one piece (`^:dynamic`); `def`/`defn` skip a docstring and an attr map (an attr map only with a value behind it -- a lone map stays the value) |
| `with-open` | `let*` plus `unwind-protect` closing in reverse order | a stream value closes through `close` on every backend, anything else through the `close` interop call (Java closeables need the interpreter or the JVM; wasm compiles `java:` to a call-time error); an empty vector is the plain body (the oracle's bare `do`, no barrier); a non-empty body lowers behind the `try` barrier, so a `recur` there is the oracle's `Cannot recur across try` refusal (decided 2026-10-01, b35) |
| `with-out-str` | `let*` rebinding `*standard-output*` (already special) to a fresh string stream | never a literal `with-output-to-string` (which flips a WASM module into EH mode); the stream is built with `make-string-output-stream` and read back, like `str` |
| `time` | the value timed with `get-internal-real-time`, reporting `Elapsed time: N msecs` | only the value pins (the count never does -- the spec pins the prefix); built straight to the stream like `println`, never re-lowered |
| `future`/`delay`/`force`/`promise`/`deliver`/`proxy-super` | refused by name | no thread pool, lazy memo cells or blocking rendezvous on any backend; proxy methods take the Java arguments only, with no super handle |
| `*out*`/`*in*` | `*standard-output*`/`*standard-input*`, not mangled names | the streams the print family writes to / reads from; `binding` may rebind either, like any special (decided 2026-10-01, b20) |
| `.write`/`.flush`/`.readLine` on a stream | `princ` / `finish-output` / `read-line` (nil past the end, like the oracle) over the receiver | so `(. *out* write ...)` and `(.readLine *in*)` run on every backend; a non-stream receiver still goes to `java:call` |
| `ns`/`require`/`use`/`import`/`in-ns` | alias wiring, defining nothing | `:as` registers an alias, `:refer`/`:use` unqualified names (`use`'s `:only [...]` narrows the referred set, winning over the refer-all default, and `:exclude [...]` subtracts from it -- and from `:refer :all` -- like the oracle), `:import` simple class names, `(:refer-clojure :only/:exclude ...)` narrows the visible core; a bare `require`/`use` spells each libspec quoted (`(quote spec)`/`'spec`, the oracle's spelling) and shares the `ns`-clause spec parser; an unquoted vector spec stays accepted (a lenient superset -- the oracle rejects it with a `ClassNotFoundException`); a prefix list `(prefix [sub ...])` (quoted or bare, `use` and the `ns` `:require`/`:use` clauses included) wires each member (a bare or quoted symbol or vector) under the prefix, through the same parser; `clojure.string` and `clojure.java.io` (`reader` only) resolve (see below); an unknown namespace is an error; `in-ns` answers `nil` (the namespace is flat) |
| `clojure.string` (`join`/`split`/`split-lines`/`upper-case`/`lower-case`/`capitalize`/`trim`/`triml`/`trimr`/`trim-newline`/`blank?`/`starts-with?`/`ends-with?`/`includes?`/`index-of`/`last-index-of`/`replace`/`replace-first`/`escape`/`re-quote-replacement`/`reverse`) | core string operations over lowered arguments | reached as `alias/var`, `clojure.string/var`, or a referred bare var; each works as a function value (a rest lambda dispatching on the count); `split`/`replace` match literal strings only (regex literals are refused at the reader); an empty `split` input is nil; a positive `split` limit caps (the last part holding the rest), a negative one keeps every part, otherwise trailing empties drop |
| `subs` | `subseq` (2/3-arity) | as a value a two-or-three-argument lambda |
| Java interop (`.`, `..`, `.method`, `.-field`, `Class/member` in call and value position, `Class.`, `new`, `memfn`, `proxy`) | the `java:` surface (`.kb/java-interop.md`) | `(. obj m args)` / `(.m obj args)` an instance call (a known-class receiver whose overloads at that arity all answer a primitive boolean answers `T`-or-false -- a construction literal, or a `let` local bound to one; any other receiver keeps the shared `java:` unmarshal), `(. Class m args)` / `(Class/m args)` a static, `(Class. args)` / `(new Class args)` construction, `(Class/FIELD)` a static field (a zero-argument `(Class/m)` or `(. Class m)` is the static method when the host class has one, else the field -- decided 2026-10-01, b20, lifting the b08 deviation; a bare `Class/member` value reads the static field when the host class has one, else answers a member-as-value lambda dispatching per arity over the static call -- so `(every? Character/isWhitespace s)` runs -- and a variadic-only member is refused by name), `(.-f obj)` an instance field, `(.. obj (step args) name)` nested `.` datums, `(memfn m args...)` a lambda over the instance call, `(proxy [I] [] ...)` a `java:proxy` (kept gaps, b08: no `set!` field write -- the `java:` surface has no write primitive; non-string receivers go to `java:call` and fail there); classes resolve dotted, imported, or `java.lang`; a string receiver answers the mapped core operation (a Lisp string is no host object), anything else goes to `java:call`; interpreter and JVM only (wasm rejects `java:`) | `(. obj m args)` / `(.m obj args)` an instance call, `(. Class m args)` / `(Class/m args)` a static, `(Class. args)` / `(new Class args)` construction, `(Class/FIELD)` a static field (a zero-argument `(Class/m)` or `(. Class m)` is the static method when the host class has one, else the field; a bare `Class/member` value reads the field or answers an arity-dispatching member lambda), `(.-f obj)` an instance field, `(.. obj (step args) name)` nested `.` datums; classes resolve dotted, imported, or `java.lang`; a string receiver answers the mapped core operation (a Lisp string is no host object), anything else goes to `java:call`; interpreter and JVM only (wasm rejects `java:`) |
| `make-array`/`aget`/`aset`/`alength` | the general array (`make-array` dims, `aref`, `(setf aref)`, `array-dimension` 0) | the class spells the element type and is ignored -- every array here is general (the book's `interop.clj` shape); only the Clojure spellings are new, so all four backends |
| `defprotocol` | one `equal`-table global plus one dispatcher `defun` per method, over the shared `C%PROTOCOL-TAG` reader | the multimethod shape without the hierarchy search (decided 2026-10-01, b13, revisiting the b08 rejection: three corpus chapters use nothing else, and both halves -- the b05/b08 method table, the b02 `equal` table -- already run on all four backends); a call dispatches on the target's tag (exact match, then the `Object` row), a miss with no `Object` row signals, like the oracle; the protocol name answers its table; single signature per method (several arities stay refused) |
| `defrecord` / `deftype` | the positional and map constructors (records only -- the oracle defines no `map->` for deftypes) as mangled `defun`s, plus one table row per inline method | a record is `(:C%RECORD tag fields table)` over the same `equal` table every map uses (no per-backend struct -- the b08 rejection reason); a deftype shares the shape with an opaque `:C%TYPE` tag; names join the whole-file pre-scan (forward refs like `defn`); `(T. ...)`/`(new T ...)` rewrite to `->T`; inline bodies see the fields as locals (an explicit parameter shadows its field, like the oracle); a trailing keyword option is refused |
| `reify` | one fresh `:C%REIFY` tag per evaluation with a row per method in each protocol's table | a single-shot map plus methods (never `proxy`, which stays the `java:` surface); `=` is identity, like the oracle |
| `extend-protocol` / `extend-type` / `extend` | `defmethod` rows under the target's tag (`extend` from a map literal of method functions) | targets are the `class`-keyword kinds (`String`, `Number`/`Long`/`Double`, ..., `Map`/`Vector`/`Set`/`List`, plus `nil` and `Object` as the miss default) and known record/deftype names; anything else (an `Instant`, a `Date`, ...) is a named refusal; `extend-type` groups methods under protocol names |
| `satisfies?` | table membership (the tag's row, or the `Object` row) | the protocol is a literal name, like `defmethod`'s multimethod |
| `definterface` / `gen-class` / `gen-interface` | refused by name (`protocols are not supported yet: <name>`) | stay refused: no interface generation on any backend |
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
| `map`/`filter`/`reduce`/`apply`/`concat` | `rontolisp::%clojure-map`/`-filter` over the collections, `reduce`/`apply` over the seq view, `rontolisp::%clojure-concat` over the member list | `map` takes any number of collections (stopping at the shortest, like the oracle) and answers a lazy wrapper when any input is lazy, the strict list otherwise; `filter`/`concat` likewise (a false object drops like nil); lists pass through untouched (no copy); every other collection coerces first, so vectors, strings, maps and sets all work; `reduce` is 2/3-arity with the Clojure argument order (`(reduce f val coll)`) mapped onto CL `reduce` `:initial-value`; `apply` spreads any leading arguments over the seq-coerced last one (`(apply f x args)`), like CL `apply`; `(concat)` is nil |
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
| `mapcat` | `rontolisp::%clojure-mapcat`: the mapped seq views realized and appended | strict concat-of-maps, nil-safe like `concat` (a nil result contributes nothing); a lone function is the oracle's transducer shape and stays refused; as a value a rest lambda |
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
| `select-keys`/`merge-with`/`into`/`frequencies` | a fresh table over the present keys / grown map by map through `f` / a `conj` fold / one `dolist` pass | `select-keys` of nil is the empty map; `merge-with` of no maps is nil (a transducer argument is refused); `into` targets lists/vectors/maps/sets; a record reads through its entry table -- `select-keys` and `into {}` answer plain maps (like the oracle), `merge-with`/`into` onto a record keep the type (like `merge`/`conj`); as values lambdas, with `merge-with`'s reading each rest map through its entry table and rewrapping the accumulator in the first non-nil rest map's record |
| `comp`/`partial`/`complement`/`constantly`/`identity`/`memoize`/`trampoline` | right-nested closures / fixed-plus-rest closures / the negated predicate / the kept value / the value itself / an `equal`-tabled closure / a labels self call over thunks | no functions is `identity` for `comp`; `complement` answers `T`-or-false; `memoize` keys the argument list structurally; `trampoline` invokes zero-argument results until a non-function answers; each a function value too |
| `when-let`/`if-let`/`when-not`/`if-not`/`when-first` | `let*` pairs over one temporary plus `if` on null-or-false | `when-let`/`if-let` destructure like `let` (testing the whole init); `when-not`/`if-not` swap the branches; `when-first` binds the head of the seq view |
| `coll?`/`string?`/`symbol?` | `or` over the shapes / `stringp` / `symbolp` minus the booleans and nil | `coll?` excludes strings (which the runtime stores as vectors) and nil, like the oracle; as values lambdas answering `T`-or-false |
| `instance?`/`class` | the class name mapped onto the shared predicates / a `cond` answering a kind keyword | only the core classes lower (`String`, `Long`, ...) plus known record/deftype names (a tag-equality test), anything else a named refusal; `class` answers `:map`/`:vector`/`:set`/`:list`/`:string`/`:number`/`:keyword`/`:symbol`/`:char`/`:boolean`/`:nil`/`:function`/`:atom` (host classes exist on no wasm backend) and a record/deftype answers its tag keyword; as values lambdas (`instance?` has none -- an arity error stays one) |
| `int`/`long`/`unchecked-add` | `truncate` (a character reads back through `char-code`, round-tripping `char`) / `+` | a non-number signals, like the oracle; `unchecked-add` never wraps (bignums); as values lambdas |
| `spit`/`slurp`/`line-seq`/`clojure.java.io/reader` | `with-open-file` writes / a `read-char` loop into a string stream / a `read-line` loop / an `open` input stream | interpreter and JVM only (no filesystem on wasm -- a wasm module run without a preopened directory refuses with the file-error, pinned in `ClojureWasmFileRefusalTest`; measured 2026-10-01: with a `--dir` preopen both wasm backends read like the rest); `spit` supersedes unless `:append` is truthy; `line-seq` takes a path or an open reader and answers strictly either way (a reader is read but never closed -- `with-open` owns closing, like the oracle); each a function value too; `file-seq` and every other `clojure.java.io` fn stay refused by name |
| `format` | the Java directives translated to Common Lisp over Clojure-notation arguments | the format string must be literal; `%s` converts like `str` (nil spells `"null"`), `%b` the boolean spelling, numbers the matching checked directive; `%e`/`%g`, flags and anything else are named refusals |
| `range` (with an end) | a labels self call building the strict list | 1/2/3-arity (`end` / `start end` / `start end step`); a zero step signals; an end-less `(range)` is refused by name -- an infinite seq cannot be spelled strictly (spell it with `iterate`, b11) |
| `lazy-seq` | `rontolisp::%clojure-make-lazy` over a zero-argument lambda of the body | the body (an implicit `do`) runs on first realization, at most once per seq object (memoized through `rplaca`/`rplacd` on the wrapper cell, primitives every backend already compiles -- no new runtime); the body is its own zero-arity `recur` target (decided 2026-10-01, b28): a `recur` in its tail position re-runs the thunk itself, checked against arity 0, and the thunk lambda wraps itself in a `labels` self-binding only when a `recur` reaches it (the anonymous-`fn` shape); `lazy-cat` desugars to `(concat (lazy-seq e) ...)` at datum time |
| `repeat`/`cycle`/`iterate`/`repeatedly` | `rontolisp::%clojure-repeat`/`-cycle`/`-iterate`/`-repeatedly` (infinite arities), strict-list builders (finite arities) | the infinite arities answer wrapper chains through the IFn dispatcher; `(repeat n x)`/`(repeatedly n f)` answer strict lists and print like the oracle; each names a function value too |
| a vector literal | a `vector` call | |
| `first`/`rest` | `car`/`cdr` over the seq view | see the seq-view row above; `count` stays the table-aware length (the fast path, no seq built) |
| `count` | a table-aware length | maps, sets and records answer `hash-table-count` (records their entries), a deftype or reify signals (a bare length would answer the wrapper's size), everything else `length` |
| `empty?` | a table/vector/string-aware null test, answering `T`-or-false | `nil`, an empty map/set/record/vector/string are empty; a deftype or reify signals, like the oracle's `seq` throw |
| `=`/`not=` | a labels self call comparing maps entry by entry and sets member by member, deep, answering `T`-or-false | two maps compare structurally (nested included); a map and a set never compare equal; two records compare by tag plus entries (never equal to a plain map, like the oracle); a deftype or reify on either side is identity, like the oracle; anything else is `equal` |
| `{k v ..}` | `rontolisp:plist-hash-table` over the lowered pairs | an `equal` table, never mutated in place: every verb builds a fresh one |
| `#{..}` | an `equal` table holding each member under itself, wrapped as `(:C%SET table)` | the wrapper tells verbs a set from a map; a repeated literal element is refused by spelling (`Duplicate key`) |
| `assoc`/`dissoc` | a fresh table over the old pairs plus/minus the keys, rewrapped in the record it came from | `assoc` onto nil builds from empty; `dissoc` of nil is nil; odd `assoc` pairs are refused; `assoc` keeps the record's tag and fields, like the oracle; `dissoc` keeps the record while every declared field is still present and drops to a plain map otherwise (removing a base field drops the type, removing an extension key keeps it), like the oracle; as values a map plus a rest list of pairs/keys (an odd `assoc` rest count signals at run time) |
| `get` | `gethash` with the default, or a bounds-checked `elt`/`char` | takes maps, records (through the entry table), sets (answering the member), vectors, strings and nil; a deftype or reify answers the default, like the oracle; a list answers the default; as a value the two- or three-argument read over a rest default |
| `contains?` | a sentinel-`gethash` presence test, or a bounds check | takes maps, records, sets, vectors and strings; anything else answers false; as a value a two-argument lambda |
| `keys`/`vals` | a `maphash` accumulation into a list | the order is the table's walk order, unspecified; of nil, nil; records read through the entry table; as values one-argument lambdas |
| `merge` | one fresh table over every argument's pairs, rewrapped when the merge starts from a record | later maps win; `(merge)` is nil; of all nil, nil; the result keeps a record's type only when the first non-nil argument is one, like the oracle (`merge-with` the same); as a value a rest lambda over the maps |
| `conj` | a member onto a set, entries onto a map or record (keeping the type), at the end of a vector, at the front of a list | a set conjoined onto a map contributes its members one level deep; anything else conjoined onto a map is refused; onto a deftype or reify signals, like the oracle; as a value a collection plus a rest list of items folded one by one, so `alter`/`swap!` over `conj` run |
| `disj` | a fresh set minus the members | of nil, nil; of a map, refused; as a value a set plus a rest list of members |
| `set`/`hash-map`/`array-map` | a set from a collection, a map from key/value pairs | `set` takes lists, vectors, maps (entry vectors) and sets; odd constructor pairs are refused; as values a one-argument lambda (`set`) / a rest lambda over the pairs (an odd rest count signals at run time) |
| `vec` | `coerce` of the fully realized seq view to a vector | `(vec nil)` is `[]`, `(vec "ab")` is the character vector, maps contribute one two-vector per entry and sets one member per element; lazy inputs realize fully (an infinite input hangs, like the oracle's); as a value a one-argument lambda |
| `str` | `concatenate 'string` over mapped parts | `(str)` is `""`; `nil` maps to `""`, `true`/`false` to `"true"`/`"false"`, a keyword to its colon spelling, collections in Clojure notation through `rontolisp::%clojure-str-of` |
| `pr-str` | `concatenate 'string` over mapped parts joined with a space | the readable arm of `str` (like `pr`): `(pr-str)` is `""`, `nil` maps to `"nil"` |
| `println`/`print`/`pr`/`prn` | one `rontolisp::%clojure-write-datum` call per part straight to `*standard-output*`, spaces as `write-char`, the newline as `terpri`, answering nil | parts joined with a single space, like Clojure; `pr`/`prn` convert readably, so strings print quoted; collections print in Clojure notation; no `with-output-to-string` ever reaches a compiled program (a literal one flips a WASM module into EH mode -- measured gate, `.todo/artefacts/b07-clojure-print/NOTES.md` finding 6); the print family answers nil, like the oracle |
| `true` | `LispTrue` (`T`) | a raw symbol spelled `T` is unbound -- `evalSymbolRef` looks the name up |
| `nil` | `NIL` | falsey |
| `false` | the value of `rontolisp::%clojure-false`, bound before anything else runs | a DISTINCT non-`NIL` symbol spelled `false` (the distinct-object treatment `scheme.lisp`'s `#f` uses); falsey in every conditional through the lowered tests; `eq`-comparable by name on every backend |
| a keyword `:foo` | the list `(:C%KEYWORD "foo")` holding its spelling verbatim (case-preserved) | data, compared by `equal` through the cons shape; `:a` and `:A` stay apart; every print arm spells it with its colon, nested or not |
| a keyword in call position `(:k m)` / `(:k m dflt)` | the same table-aware read `get` lowers to | the idiomatic map lookup, over b02's map runtime (sets answer their member, vectors/strings their element) |
| a keyword as a function value (`map`/`filter`/`reduce`/`apply` over `:k`) | a one-argument lambda over the same read | `(map :k coll)` reads the key out of each member |
| a namespaced keyword `:a/b` | the same wrapper over the whole spelling | opaque data: prints and compares whole |
| `::kw` / `::alias/kw` | the same wrapper over the resolved spelling | `::kw` resolves against the current file `ns` name (the seam reads the whole file, so the form order decides; `user` without one), `::alias/kw` through the alias (a `:require` `:as`, the namespace's own name, or a known namespace without any require); a session tracks `*ns*` across buffers (`ns` switches it, `in-ns` switches it answering nil -- the namespace stays flat, every definition still global); opaque afterwards, so `derive`/`isa?`/dispatch compare whole spellings like `:a/b`; an unknown alias is the oracle's `Invalid token` refusal |
| a bare `(ns name)` | nothing, but records the name for `::` | a namespace declaration defines nothing; clauses wire aliases (see the `ns` row above); the file's `ns` name (or the session's `*ns*`) is what `::kw` resolves against |
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
- The seq family runs over list views of every collection (strict since 2026-09-30,
  b03; lazy since 2026-10-01, b11). What still differs from the oracle: `rest`/`next`
  of empty is `nil`, where it prints `()`; `nth` past the end answers the default (nil
  without one) instead of throwing; a map/set seq's order is the table's walk order,
  unspecified; strings seq to characters, which print in Common Lisp notation; a lazy
  seq is the memoized-thunk wrapper `(:C%LAZY cell)` (b11: the body runs at most once
  per object, `take`/`drop`/`first`/`rest`/`next`/`seq`/`map`/`filter`/`concat` realize
  through it, printing refuses with `#<LazySeq>` instead of hanging, never a bare
  infinite print); there is no chunking, so an end-less `range` stays refused by name
  (spell it with `iterate`). Lazy inputs to the other seq verbs consume one level --
  pass a `take`n prefix.
- protocols (lowered in b13, below), `set!`, regex
  literals, `var`/`#'`: `set!`, regex literals and `var`/`#'` stay absent, each
  refused by name (backquote
  lowered in b12, below). Metadata instead parses and drops (b14):
  `^`/`with-meta` lower to the object itself, and only `binding` reads
  `^:dynamic` -- see the table rows above.
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
  oracle (a bare `class` dispatch answers that keyword only for a nil argument, so it
  maps onto the marker too; a `class` call wrapped in another function keeps the
  keyword and still misses the nil method); an `Object` method catches past the search but
  ahead of the default, like the oracle's (which always beats `:default` there --
  measured on the oracle 2026-10-01); a hierarchy value prints as its
  `#<HASH-TABLE ...>` map and its reads answer wrapped sets; an `ex-info` value
  prints as its `#<C%E-EX-INFO ...>` condition; atoms print unreadably (`#<Atom value>`),
  functions as `#<procedure>`, a lazy seq as `#<LazySeq>` (a lazy tail truncates with
  ` ...`, so no bare infinite print ever hangs); a record prints as its wrapper
  list (`(:C%RECORD :R (:a) {:a 7})` where the oracle prints `#user.R{:a 7}`), a
  deftype likewise with `:C%TYPE`, a reify as `(:C%REIFY ...)`; `class` of a
  record/deftype answers its tag keyword (the oracle answers a host class, which
  no wasm backend has); `assoc` onto a record keeps the type (like the oracle)
  while a `dissoc` that removes a declared field drops to a plain map (like the
  oracle); protocol dispatch merges `Long`/`Double` into `:number` (the oracle
  tells them apart) and reads no hierarchy (exact tag match plus the `Object`
  default); `split`/`replace` match literal strings, never patterns (the documented
  literal-only position, decided 2026-09-30 b08: no regex runtime on any backend,
  so `#"..."` stays refused at the reader and `clojure.string`/`String` splitting
  keeps literal semantics, pinned by the spec's `string-replace-and-split-stay-literal`
  case); `indexOf`
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
  lowering (a construction literal, or a `let` local bound to one -- `let`
  bindings never rebind, so the inference is sound; `loop` targets, parameters
  and globals stay unknown) wraps the same way when every overload at that
  arity answers a primitive boolean (decided 2026-10-01, b29: the shared
  unmarshal keeps mapping a host false to nil -- nil is Common Lisp's only
  false, so changing it would make host-false truthy in `java:` programs and
  move all three paths plus the bridge parity at once -- and Clojure wraps at
  lowering instead, where the member-value precedent `(map odd? [1 2])` prints
  `(true false)` already holds); any other host boolean keeps the unmarshal
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
for the refusals (regex, hierarchies, protocols, `ex-info`, `set!`, backquote,
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
non-core class), in `ClojureLoweringTest`/`ClojureSessionTest`. The nil-marker slice keeps the same shape (b32, oracle `clj` 1.12.6.1673): a nil method answers a true nil only, a literal `:nil` falls past it to the `:nil` method or the default, and a bare `class` dispatch still answers the nil method for nil -- pinned in `clojure-spec.yaml` (`nil-method-stays-distinct-from-nil-keyword`, all four backends).
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
what stays refused (`definterface`/`gen-class`/`gen-interface`, multi-arity protocol
methods, `:extend-via-metadata`, `set!`,
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
(regex literals, `clojure.spec`/`xml` namespaces, transducers,
`file-seq`/unknown-`clojure.java.io`-fn, `%e`/`%g`/flags) in `ClojureLoweringTest`, and the file IO
plus the `keep`/`update` signals in `ClojureInteropTest` (no filesystem on wasm).
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
multi-interface `proxy` plus `proxy-super` stay refused (the snake GUI files stay
non-goals, b16). The host-boolean slice lowers the same way (b29, oracle `clj`
1.12.6.1673): an instance call on a construction literal -- or on a `let` local
bound to one -- whose overloads at that arity all answer a primitive boolean answers
`T`-or-false (a shadowing binding hides the class again; `loop` targets, parameters
and unknown receivers keep the shared unmarshal, printing `nil` for `false`) -- pinned
in `ClojureLoweringTest` (the wrapped and bare shapes, the shadow) and
`ClojureInteropTest` (the `contains`/`isEmpty` prints, the `if`/`=`/`str` values, the
untouched non-boolean answers, interpreter and JVM) with the refusal legs beside
`ClojureWasmInteropRefusalTest`.
Predicate values answer `T`-or-false (so `(map odd? [1 2])` prints `(true false)`
like the oracle); `filter`/`remove` test Clojure truthiness around the call.
Multi-entry maps and multi-member sets never print in the spec (the walk order is
unspecified); only single-entry/single-member shapes pin the notation.
State, dynamic scope and the small imperative companions lower the same way (b14):
metadata-ignored (`defn-`, `^:private`/`^:dynamic`/`^{...}`/type hints on names,
parameter vectors, patterns and values, `with-meta` as call and as value, `def`
docstrings and attr maps), `defstruct`/`struct`/`struct-map` over key vectors,
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

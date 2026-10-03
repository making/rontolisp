# Semantics

Every Clojure form lowers, at file-read time, to the Common Lisp core forms the rest of
rontolisp already consumes; no backend learns a Clojure name. This page is the map of
those lowerings and the forms refused by name.

## Names and calls

An identifier lowers to a symbol behind the `c%` prefix, so `Foo` and `foo` stay apart
and no name reaches a core-form case label. `defn` is a `defun` called directly; a
head-position call to a parameter, a `let`/`loop` binding or a `def`'d variable holding
a real function is a `funcall` of the value cell, so `(defn call-it [f x] (f x))` runs;
a variable that may hold a collection goes through the prelude dispatcher instead
(`rontolisp::%clojure-call`: functions through `apply`, sets/maps/vectors/keywords
through their lookup, like `IFn`); a `declare`d-but-never-defined name keeps its
direct-call error. `def` is a top-level `setq` -- inside a body it
still sets the global when the body runs.

`defn` with several arities is one `defun` per arity plus a dispatch `defun` picking by
argument count (a single variadic clause takes any count past its fixed parameters); any
other count signals. `fn` with several arities is one `lambda` dispatching the same way,
and a named `fn` binds itself for self-calls; `#(...)` is a `lambda` whose arguments
travel as one rest list (`%`..`%9`, `%&`), its body forms wrapped as one call. `declare`
names what is defined below, so a definition may use one.

## Namespaces and files

Every namespace has its own vars. A definition belongs to the current namespace (`user`
until an `ns` or `in-ns` switches it); a name resolves to a local, then to the current
namespace's own var, then to a referred one, and `alias/name` or `full.name/name` reaches
another namespace's var -- a private one (`defn-`, `^:private`) is refused, like the
oracle's compiler. A var of `user` lowers to `c%name`, any other to `c%ns/name`, which is
what a Common Lisp file loading the program calls.

A `require`, `use` or `ns` clause naming a namespace the program has not declared loads its
file: `my-app.core` is `my_app/core.clj`, read from the first source root holding it --
the directory the entry file's own namespace names (`src` for `src/demo/main.clj` declaring
`demo.main`, the file's directory without an `ns`), then the `:paths` of the nearest
`deps.edn` at or above the entry file (`["src"]` when it names none), or `src` under the
working directory when there is no `deps.edn`. A file lowers once per program: its definitions stay ahead of
the form that required it, while its other top-level forms run when the `require` runs --
including a `require` inside a function body, which loads when the body runs. A second
`require` loads nothing; `:reload` runs the file again (`def` resets, `defonce` keeps its
root) and `:reload-all` re-runs its dependencies first, like the oracle. A file without an `ns` form
defines into the requiring namespace. `use` and `:refer :all` bring in every public var; a
file no root holds, a cycle of requires and a refer of a missing or private var are errors
in the oracle's words.

```bash
# deps.edn holds {:paths ["src"]}; demo.main and demo.main-test require demo.lib
rontolisp src/demo/main.clj          # roots: src (its ns), src (deps.edn)
rontolisp test/demo/main_test.clj    # roots: test (its ns), src (deps.edn)
```

## Binding

`let` is a `let*` (Clojure's `let` is sequential); `letfn` is one `labels` over
pre-scanned entries, so siblings call each other; `loop`/`recur` is a `labels` self call,
constant-stack on the interpreter, with sequential inits. `recur` also reaches a named or
anonymous `fn`, a `defn` clause, a `letfn` entry or a `lazy-seq` body of arity 0
(each multi-arity clause its own target); a wrong count is a named refusal. Parameters and bindings
destructure: a vector pattern binds positionally through the seq view (`&` the rest as a
seq, itself a pattern; `:as` the whole), a map pattern through the table-aware read
(`:keys`/`:syms`/`:strs`, explicit locals, `:as`, `:or` defaults) -- in `let`, `loop` and
`fn`/`defn` parameters alike; nested patterns recurse. Malformed shapes are named
refusals.

## Macros

`defmacro` defines a compile-time expander, stored beside the lowering: each call
site expands datum to datum while lowering -- the argument forms travel quoted into
one application of the lowered body, the answer decodes back to a datum and lowers
like any other form -- so every backend, and the interpreter's own `eval` of a macro
call, runs expanded code. Parameters bind unevaluated forms (`&` rest, destructuring
and several arities like `defn`; a docstring and an attr map are skipped);
`&form`/`&env` are refused. A body sees the core builtins and the `clojure.lisp`
library, not the program's own definitions. The definition also registers a runtime
table entry of the same expander, answers `nil`, and works session-wide; a call above
its definition is an error, a macro has no function value, and a later `def`/`defn` of
the same name wins back the call sites. A `defmacro` of a core name (`with-out-str`,
`when-not`, `inc`, `declare`, ...) shadows it from its definition on, like the oracle's
form-by-form compile: a call site above the definition, and a syntax-quote in a macro
defined above it, keep the core meaning. `clojure.core/name` always names the core var,
whatever the program defines under that name. A special form, or a head the reader
spells (`deref` for `@x`, `with-meta`, `fn` for `#(...)`), cannot name a macro.

`` `form `` builds a form as data over the mangled namespace: a symbol naming a var the
defining namespace sees qualifies with that var's namespace like the oracle (a special
form stays bare; a core name spells `clojure.core/name`, any other unresolved spelling
the defining namespace), `~` inserts its form's value, `~@` splices a sequence into the enclosing
list, vector, map or set, and each `x#` binds one `(gensym "x")` per syntax-quote --
one symbol per expansion, the same at every occurrence within it. An unquote outside
any syntax-quote is an error, as is a splice outside a sequence. `macroexpand-1`
expands once and `macroexpand` to the fixpoint, each answering the expansion as the
mangled data itself, so `=` against a quoted form holds and printing spells the
oracle's lowercase; `gensym` answers a fresh uninterned symbol per
evaluation.

```clojure
(defmacro sem-unless [c t] (list 'if c nil t))
(println (sem-unless false 42))
(println (macroexpand-1 '(sem-unless true 1)))
```

## Threading and flow

`->`/`->>` insert the value second/last, a bare name or keyword calling/reading with it;
`as->` rebinds its name step by step (nested `let`s, so shadowing matches the oracle);
`doto` answers its (unchanged) target; `cond->`/`cond->>` thread only on truthy tests;
`some->`/`some->>` stop at `nil` but not at `false`. `if`/`when`/`cond`/`do`/`and`/`or`
are the core forms: every test treats `nil` and the false object as falsey, and `cond`
keeps the lenient reading (an odd trailing arm is the default). `list*` folds `cons` over
the seq view.

## Iteration

`doseq` iterates the seq view for side effects and answers `nil`: one loop per
binding pair nested left to right, the body an implicit `do`. Each loop steps through a
lazy input one element at a time, so a `:while` stops an infinite one. `dotimes` binds `0` below
its count the same way and answers `nil`; the count runs through `truncate` first, so
`2.5` counts `0 1` and a non-number signals, like the oracle's `intCast`. `for` answers
its body over every combination: realized at once, a strict list, while every collection
it steps over is strict (an empty one is `nil`, where the oracle prints `()`), and a lazy
seq from the first lazy collection on, realized as it is consumed -- so `first`/`take`
realize only what they answer, and an infinite collection ends behind them. Each pair
takes any collection the seq view takes, and patterns destructure like `let`. The
`:when`/`:while`/`:let` modifiers trail their binding in order: `:when` skips the
element, `:while` ends its level (an outer level's ends the whole form), `:let` binds
sequentially; any other keyword is refused. `dorun` walks a collection to its end for
effect (a lazy one realizes) and answers `nil`, `doall` answers the collection itself.

## Collections

A vector literal is a `vector` call; a map literal an `equal` hash table, never mutated in
place -- every verb builds a fresh one, so persistence holds observably; a set literal the
same table with each member stored under itself, wrapped so verbs tell a set from a map.
Keys find each other by `=`: a vector, list, map or set key is stored under the first `=`
key of its kind the program stored, so an `equal` table finds it. A
keyword is its spelling wrapped as `(:C%KEYWORD name)`: data compared by `equal`, and in
call position (`(:k m)`, with an optional default) or as a function value the map lookup.
Arrays are general: `(make-array Class dim...)` builds a general array ignoring the
class, read through `aget`, written through `aset`, measured through `alength` (the
book's `interop.clj` shape; only the Clojure spellings are new, so all four backends).

The seq family runs over list views of every collection: lists pass through
untouched, vectors and strings coerce, maps contribute one two-vector per entry and sets
one member per element (both in the table's walk order, unspecified); `nil` and `false`
are empty; anything else signals like the oracle. Strict collections coerce up front,
while a lazy seq (`lazy-seq`, `lazy-cat`, `repeat`, `cycle`, `iterate`, `repeatedly`)
realizes one element at a time through the same view: `take` steps through it and
terminates on infinite seqs, `drop`/`first`/`rest`/`next`/`seq` realize through it, and
`cons`/`concat`/`map`/`filter` answer lazy again when any input is lazy (strict lists
otherwise). A `lazy-seq` body runs at most once per seq object; printing realizes a
lazy seq like the oracle (an empty one prints `()`, an infinite one without end). There
is no chunking. `count`/`empty?`/`=` reach maps and sets (`=` deeply
and structurally); `get` takes an optional default and reads maps, sets, vectors,
strings and nil.

A lazy input reaches every seq verb. The verbs that walk the whole collection (`count`,
`last`, `sort`, `apply`, `reverse`, `set`, `frequencies`, `keep` and friends, `reduce`,
`into`) realize it first -- an infinite one never answers, like the oracle's -- and the
ones that stop early (`second`, `nth`, `some`, `every?`, `take-while`, `drop-while`,
`zipmap`, `interleave`, positional destructuring, `doseq`/`for`) step through it, so an
infinite input still answers.

## State and dynamic scope

Reader metadata (`^:private`, `^:dynamic`, `^{...}` attr maps, type hints) on a name
or a local parses and drops: it never affects dispatch, except that `^:dynamic`
on a `def`/`defonce`/`defn` name marks the var rebindable -- a `^:dynamic`
`defn` keeps its direct definition but its calls go through the var, so
`binding` reaches them. Only `binding` rebinds through it. `defn-` is a
private-by-convention `defn`; `def` takes a docstring
and an attr map like `defn`; `defonce` is `def` unless bound, so a reload keeps
the root.

Value metadata is real: [with-meta](reference/with-meta.md) answers a copy carrying the
map, [meta](reference/meta.md) reads it, [vary-meta](reference/vary-meta.md) updates
it, and reader metadata on a vector, map or set literal attaches like `with-meta`.
`=` ignores it. Two deviations: a value derived from a copy (`assoc`, `conj`, ...)
starts without metadata, where the oracle keeps it, and a symbol carries none (its
`with-meta` answers the symbol).

`#'x` (`(var x)`) answers the var of a program definition, one object per name that
prints `#'ns/x`, derefs and invokes through its root. Its metadata is what the newest
definition above the `#'` recorded: a `def`/`defn`/`defn-`/`defmacro` gives
`:arglists`, the docstring as `:doc`, the name's metadata and attr map (evaluated
where the definition stands, so `^{:test (fn [] ...)}` works), `:line`/`:column`/`:file`,
`:name` and `:ns`; [test](reference/core-test.md) calls its `:test` fn. A local is no
var, and a `clojure.core` var is refused by name.

A `ref` is the atom cell with a transaction discipline: `dosync` opens the
extent (single-threaded, so no retries and no isolation), `alter`/`commute`
apply through the `:validator` (a failed one signals and writes nothing),
`ref-set` replaces through it, and `ensure` answers the ref -- every verb
requiring the extent. An `agent` is the same cell updated by `send`/`send-off`,
which apply at once (there is no thread pool, so async ordering is out) and
answer the agent; `*agent*` is bound while one runs. `await` rendezvous and
`shutdown-agents` answer `nil`. `future`/`delay`/`force`/`promise`/`deliver`
stay refused by name, and so does `proxy-super` (proxy methods take no super
handle).

`binding` rebinds `^:dynamic` vars (and `*out*`/`*in*`, which are `*standard-output*`/
`*standard-input*`)
with dynamic extent; anything else is refused. `defstruct` holds its key vector
behind the name; `struct`/`struct-map` build fresh maps over it. `with-out-str`
binds `*standard-output*` to a string stream (never a literal
`with-output-to-string`) and answers what printed; `time` reports
`Elapsed time: N msecs` and answers its value. `with-open` binds and closes in
reverse order through `unwind-protect`, calling the `close` method (Java
closeables need the JVM, like all interop); `(. stream write x)` prints through
`princ` on every backend, and `(.readLine stream)` reads through `read-line`
(`nil` past the end, like the oracle).

## Protocols, records and types

A `defprotocol` declares methods; each method lowers to a dispatcher over the
target's tag (the multimethod shape without the hierarchy search: an exact tag
match, then the `Object` row). `extend-protocol`/`extend-type`/`extend` add rows
under a target's tag; `satisfies?` tests membership. Extend targets are the kinds
`class` answers (`String`, `Number`, `Boolean`, `Keyword`, `Symbol`, `Character`,
`Map`, `Vector`, `Set`, `List`/`Seq`, plus `nil` and `Object` as the miss
default) and known record/deftype names; anything else is a named refusal. A miss
with no `Object` row signals, like the oracle. Each method takes one parameter
vector (several arities stay refused). A protocol declared `:extend-via-metadata true`
also finds a method in the target's metadata under the namespace-qualified method
symbol -- after an implementation in a `defrecord`/`deftype`/`reify` body and before
the extension rows, like the oracle (see [defprotocol](reference/defprotocol.md)).

A `defrecord` value is a map with a type tag: the entry table every map uses,
wrapped as `(:C%RECORD tag fields table class)`, so the map verbs read through it
(`get`/`contains?`/`keys`/`vals`/`count`/`seq`/`select-keys` read the entries;
`assoc`/`update`/`conj`/`merge` rebuild the table and keep the tag; `dissoc`
keeps the record while every declared field is still present and drops to a plain
map otherwise, like the oracle). `=` compares two records by tag plus entries
and never equals a plain map, like the oracle. A `deftype` shares the shape with
an opaque tag: reads miss, writers and `seq`/`count`/`empty?` signal, and `=`
is identity, like the oracle. `reify` answers one fresh tag per evaluation with
a row per method in each protocol's table. Constructors are mangled functions:
`->Type` positionally, `map->Type` from a map (records only -- the oracle defines
none for deftypes); `(Type. ...)`/`(new Type ...)` rewrite to `->Type`.
`instance?` of a record/deftype name tests the tag; `(.-field x)` reads the field
table (missing fields signal, like the oracle). Inline method bodies see the
fields as locals (an explicit parameter shadows its field, like the oracle);
type hints (`^String`, `^H`) parse and drop, never affecting dispatch.

A deftype field marked `^:unsynchronized-mutable` or `^:volatile-mutable` is
assignable: `(set! field value)` inside the type's own inline methods writes it and
answers the value, and a later read (in this call or after another method's write)
sees the new value. Such a field is private to the methods (`.-field` misses it), a
closure created in a method (`fn`, `#()`, `letfn`, `reify`, `lazy-seq`, `for`, `dosync`) copies
it at creation, and `defrecord` refuses the markers, all like the oracle.
ClojureScript's `^:mutable` is no marker. `set!` of a local, a parameter or an
immutable field is the oracle's `Cannot assign to non-mutable: ...`; of a non-dynamic
global it signals `Can't change/establish root binding of: ... with set` at run time.

## Not yet

Each refusal names the missing design, never `unknown name`:

| Refused | Message shape | Why |
|---|---|---|
| end-less `range` | `infinite range is not supported: range needs an end` | an infinite seq cannot be spelled strictly -- spell it with `iterate` |
| `transient`, `persistent!`, `assoc!`, `dissoc!`, `conj!`, `disj!` | `transients are not supported yet: ...` | no transient runtime behind the tables |
| `definterface`, `gen-class`, `gen-interface` | `protocols are not supported yet: ...` | no interface generation on any backend |
| multi-arity protocol methods | `multi-arity protocol methods are not supported yet: ...` | one parameter vector per method |
| `set!` of a dynamic or core var (`*warn-on-reflection*`), of a host field | `set! of a var is not supported yet: ...`, `set! of a host field is not supported yet: ...` | no thread-bound var to assign; the `java:` surface has no field write |
| `future`, `delay`/`force`, `promise`/`deliver` | by name | no thread pool, lazy memo cells or blocking rendezvous on any backend |
| `proxy-super` outside a proxy method | `proxy-super outside a proxy method` | a `proxy-super` calls the superclass implementation on the method's `this` |
| `proxy` with a second class, a duplicate method, a final superclass | `... is a class, not an interface`, `proxy defines method ... twice`, `proxy cannot extend final class ...` | one superclass only, one body per method name, no final superclass |
| `toString`/`equals`/`hashCode` in an interface-only `proxy` | `proxy cannot override ... yet` | `java:proxy` keeps `Object`'s three, so the body would never run (a class proxy runs it) |
| a variadic-only static member as a value | `... is variadic and has no value form` | no rest-spread reaches `java:static` |
| `&form`/`&env` in `defmacro` parameters | by name | macros receive no compilation environment |
| `::alias/kw` with an unknown alias | `Invalid token: ...` | only required aliases, the file's own ns and known namespaces resolve |
| `--no-gc` builds | by name | that backend has no pairs, symbols or closures |
| `file-seq`, `clojure.java.io` (except `reader`) | `file-seq` / `unknown name: clojure.java.io/...` | no directory walks; only `reader` resolves, opening a file-stream reader |

## Errors and positions

A lowering error names the innermost form's position (`file:line:column` when the file is
known); the reader's errors are prefixed the same way.

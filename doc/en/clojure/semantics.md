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
the same name wins back the call sites. A `defmacro` shadows a core function at call
sites (never a special form, which intercepts first).

`` `form `` builds a form as data over the mangled namespace: every symbol qualifies
behind `c%`, `~` inserts its form's value, `~@` splices a sequence into the enclosing
list, vector, map or set, and each `x#` binds one `(gensym "x")` per syntax-quote --
one symbol per expansion, the same at every occurrence within it. An unquote outside
any syntax-quote is an error, as is a splice outside a sequence. `macroexpand-1`
expands once and `macroexpand` to the fixpoint, each answering the expansion as data,
demangled and uppercased for printing; `gensym` answers a fresh uninterned symbol per
evaluation. `var`/`#'` stays refused: bodies quote symbols instead.

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

`doseq` iterates the seq view for side effects and answers `nil`: one `dolist` per
binding pair nested left to right, the body an implicit `do`. `dotimes` binds `0` below
its count the same way and answers `nil`; the count runs through `truncate` first, so
`2.5` counts `0 1` and a non-number signals, like the oracle's `intCast`. `for` answers
the strict list of its body over every combination, accumulated in reverse; an empty
result is `nil`, where the oracle prints `()`. Each pair takes any collection the seq
view takes, and patterns destructure like `let`. The `:when`/`:while`/`:let` modifiers
trail their binding in order: `:when` skips the element, `:while` ends its level's loop
(an outer level's ends the whole form), `:let` binds sequentially; any other keyword is
refused. `dorun` realizes a collection for effect and answers `nil`, `doall` answers the
collection itself; seqs are already strict, so realizing is evaluating.

## Collections

A vector literal is a `vector` call; a map literal an `equal` hash table, never mutated in
place -- every verb builds a fresh one, so persistence holds observably; a set literal the
same table with each member stored under itself, wrapped so verbs tell a set from a map. A
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
otherwise). A `lazy-seq` body runs at most once per seq object; only `take`n prefixes
print -- a bare lazy seq prints `#<LazySeq>` (a lazy tail truncates with `...`) instead
of hanging. There is no chunking. `count`/`empty?`/`=` reach maps and sets (`=` deeply
and structurally); `get` takes an optional default and reads maps, sets, vectors,
strings and nil.

Lazy inputs to the other seq verbs (`doseq`/`for`/`reduce`, `keep` and friends) consume
one level through the seq view: pass a `take`n prefix first.

## State and dynamic scope

Metadata (`^:private`, `^:dynamic`, `^{...}` attr maps, type hints, `with-meta`)
parses and drops everywhere: it never affects dispatch, except that `^:dynamic`
on a `def`/`defonce`/`defn` name marks the var rebindable -- a `^:dynamic`
`defn` keeps its direct definition but its calls go through the var, so
`binding` reaches them. Only `binding` rebinds through it. `defn-` is a
private-by-convention `defn`; `def` takes a docstring
and an attr map like `defn`; `defonce` is `def` unless bound, so a reload keeps
the root.

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
vector (several arities stay refused).

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
| `:extend-via-metadata` | `extend-via-metadata is not supported yet: ...` | metadata never affects dispatch |
| `set!` of a dynamic or core var (`*warn-on-reflection*`), of a host field | `set! of a var is not supported yet: ...`, `set! of a host field is not supported yet: ...` | no thread-bound var to assign; the `java:` surface has no field write |
| `var`/`#'` | by name | no var system; macro bodies quote symbols instead |
| `future`, `delay`/`force`, `promise`/`deliver` | by name | no thread pool, lazy memo cells or blocking rendezvous on any backend |
| `proxy-super` | by name | proxy methods take the Java arguments only, with no super handle |
| `proxy` over a class, constructor arguments | `proxy over a class is not supported yet: ...`, `proxy constructor arguments are not supported yet: ...` | `java:proxy` implements interfaces only; no subclass is generated |
| `toString`/`equals`/`hashCode` in a `proxy` | `proxy cannot override ... yet` | `java:proxy` keeps `Object`'s three, so the body would never run |
| a variadic-only static member as a value | `... is variadic and has no value form` | no rest-spread reaches `java:static` |
| `&form`/`&env` in `defmacro` parameters | by name | macros receive no compilation environment |
| `::alias/kw` with an unknown alias | `Invalid token: ...` | only required aliases, the file's own ns and known namespaces resolve |
| `--no-gc` builds | by name | that backend has no pairs, symbols or closures |
| `file-seq`, `clojure.java.io` (except `reader`) | `file-seq` / `unknown name: clojure.java.io/...` | no directory walks; only `reader` resolves, opening a file-stream reader |

## Errors and positions

A lowering error names the innermost form's position (`file:line:column` when the file is
known); the reader's errors are prefixed the same way.

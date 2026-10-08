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
(`rontolisp::%clojure-call`: functions through `apply`, sets/maps/vectors/keywords/symbols
through their lookup, like `IFn`; a keyword or symbol takes one or two arguments and signals the arity error otherwise); calling a `declare`d-but-never-defined name
signals the oracle's `Attempting to call unbound fn` (in the REPL the call stays direct,
since a later input may define it). `def` is a top-level `setq` -- inside a body it
still sets the global when the body runs.

`defn` with several arities is one `defun` per arity plus a dispatch `defun` picking by
argument count (a single variadic clause takes any count past its fixed parameters); any
other count signals. `fn` with several arities is one `lambda` dispatching the same way,
and a named `fn` binds itself for self-calls; `#(...)` reads as the oracle's `(fn*
[p1__N# ...] (body))`, a `lambda` over the parameters up to the highest `%N` used plus a
rest parameter for `%&`, its body forms wrapped as one call. `declare`
names what is defined below, so a definition may use one.

## Namespaces and files

Every namespace has its own vars. A definition belongs to the current namespace (`user`
until an `ns` or `in-ns` switches it); a name resolves to a local, then to the current
namespace's own var, then to a referred one, and `alias/name` or `full.name/name` reaches
another namespace's var -- a private one (`defn-`, `^:private`) is refused, like the
oracle's compiler. A var of `user` lowers to `c%name`, any other to `c%ns/name`, which is
what a Common Lisp file loading the program calls.

A `require`, `use` or `ns` clause naming a namespace the program has not declared loads its
file: `my-app.core` is `my_app/core.clj`, read from the first source root holding it, else
`my_app/core.cljc` from the first holding that (a `.clj` under any root wins, like the oracle) --
the directory the entry file's own namespace names (`src` for `src/demo/main.clj` declaring
`demo.main`, the file's directory without an `ns`), then the project's `:paths` and the
roots of its dependencies ([Projects](#projects-depsedn)). A file lowers once per program: its definitions stay ahead of
the form that required it, while its other top-level forms run when the `require` runs --
including a `require` inside a function body, which loads when the body runs. A second
`require` loads nothing; `:reload` runs the file again (`def` resets, `defonce` keeps its
root) and `:reload-all` re-runs its dependencies first, like the oracle. A file without an `ns` form
defines into the requiring namespace. `(load "path")` and the `(:load "path" ...)` ns clause read a file (`path.clj`, else
`path.cljc`) relative to the directory of the current namespace's file (a leading slash: a source root) and run it in the current namespace, again at
every call, answering `nil`; its own `in-ns` does not outlive it. The path is a string literal: the file is read while the
program lowers. `use` and `:refer :all` bring in every public var; a
file no root holds, a cycle of requires and a refer of a missing or private var are errors
in the oracle's words. A [built-in namespace](reference/namespaces.md#built-in-namespaces)
needs no file.

`*ns*` is the current namespace as a value, switched by `ns` and `in-ns` where they run and
read when the reading code runs, so a function answers its caller's namespace. While a
required file runs, `*ns*` is rebound (the file's `ns` switches it, and the requiring
namespace is back afterwards), `*file*` holds the file's path below its root
(`my_app/core.clj`) and `*source-path*` its name; the entry file's `*file*` is its absolute
path. [the-ns](reference/the-ns.md), [find-ns](reference/find-ns.md) and
[ns-name](reference/ns-name.md) reach a namespace by name.

```bash
# deps.edn holds {:paths ["src"]}; demo.main and demo.main-test require demo.lib
rontolisp src/demo/main.clj          # roots: src (its ns), src (deps.edn)
rontolisp test/demo/main_test.clj    # roots: test (its ns), src (deps.edn)
```

## Projects: deps.edn

A program's project is the nearest `deps.edn` at or above the entry file (the working
directory's for a REPL), read whole the way the oracle's `clj` reads one and merged over
two maps before it: the built-in root map (`:paths ["src"]` and `org.clojure/clojure`),
then the user-level `deps.edn` (`$CLJ_CONFIG`, else `$XDG_CONFIG_HOME/clojure`, else
`~/.clojure`). Without a `deps.edn`, the working directory is the project. A key the oracle
does not use is ignored, as it is there; a value the oracle's spec refuses, a coordinate of
no known type and a dependency the oracle cannot resolve are errors in its words.

- `:paths` names the project's source roots, relative to its `deps.edn`; an alias keyword
  among them stands for the paths its alias lists.
- `:deps` names the dependencies. `{:local/root "../lib"}` is a directory, whose own
  `deps.edn` gives its `:paths` (relative to it, `["src"]` by default) and its own `:deps`,
  or a jar, whose `.clj` and `.cljc` files are read in place. The libraries are selected
  as the oracle selects them: a top-level dependency wins, otherwise the newest version
  across the tree; `:exclusions` drop a library below the coordinate naming it; a cycle
  ends at the library already selected.
- The source path is the entry file's own root, the project's `:paths`, each selected
  library's roots in the oracle's classpath order (the top of the tree first), then the
  built-in namespaces. A root's `data_readers.clj` and `data_readers.cljc` give the
  program's data readers ([Tagged literals](syntax.md#tagged-literals)).
- `org.clojure/clojure`, `org.clojure/spec.alpha` and `org.clojure/core.specs.alpha` are
  this front end at any version. `ring/ring-core` and `ring/ring-codec` at a Maven version
  up to the one shipped (ring-core 1.15.5, ring-codec 1.3.0) are the built-in Ring
  namespaces; a newer one is refused when a Ring namespace loads.
- A `clojure.*` namespace that is not part of Clojure itself (an `org.clojure` contrib
  library) loads from the source path like any other.
- A Maven coordinate (`:mvn/version`) is fetched from the `:mvn/repos` (Maven Central, then
  Clojars, then any a map adds; `nil` removes one) into the `:mvn/local-repo`, else
  `~/.m2/repository`. Its POM's compile and runtime dependencies, not the optional ones,
  join the tree; its jar is read in place. `"[1.0]"` is 1.0. A version range is the highest
  version the repositories' `maven-metadata.xml` lists in it; `RELEASE`, `LATEST` and a
  `SNAPSHOT` resolve through that metadata too, which the local repository keeps and asks
  for again once a day. An `http:` repository is refused. `settings.xml` (`~/.m2/settings.xml`
  merged over `$MAVEN_HOME/conf/settings.xml`) applies as it does for the oracle: its mirrors,
  proxies, and servers' credentials and `httpHeaders` (a password encrypted with
  `mvn --encrypt-password` included), not its `localRepository`, `offline` or profiles'
  repositories.
- A git coordinate (`:git/url`, or the URL an `io.github.user/repo` name implies, with
  `:git/sha`, `:git/tag`, `:deps/root`) is checked out at its commit with the `git` command,
  into `~/.rontolisp/gitlibs` (`$RONTOLISP_DIST_HOME/gitlibs`). A tag must name the commit,
  an abbreviated sha needs a tag, and of two commits of one library the descendant is the
  newer. Its `deps.edn` is read like a `:local/root` directory's.
- A `:local/root` jar's own `pom.xml` gives its dependencies. A `pom.xml` project (a
  directory or commit with a `pom.xml` and no `deps.edn`) is read as Maven builds its model,
  its parent looked for at `<relativePath>` (by default `../pom.xml`) before the
  repositories: its compile and runtime dependencies, optional ones included, and as
  source roots its build's source directory (by default `src/main/java`),
  `src/main/clojure`, its resource directories (by default `src/main/resources`) and the
  `add-source` / `add-resource` directories of `build-helper-maven-plugin`, read off the
  first plugin as the oracle reads them. Both POMs are checked at Maven's strict validation
  level, as the oracle checks them: a POM it refuses is refused, naming Maven's problems.
- A dependency's jar holding classes joins the program's Java class path: the interpreter
  and the JVM call its classes, and `-o app.jar` copies it beside the jar. WebAssembly keeps
  refusing Java when called.
- The project resolves before the program runs, so a dependency that cannot be fetched
  stops it. A second run reads the local repository and the git cache, with no network.
  The command line fetches; an embedder (`JvmSourceCompiler`) and the browser playground
  fetch nothing, and refuse a namespace only a Maven or git coordinate could hold, naming
  the coordinate. `:aliases` apply when a run selects them (below).

```console
$ cat deps.edn
{:paths ["src"]
 :deps {my/util {:local/root "../util"}
        org.clojure/data.json {:mvn/version "2.5.1"}
        io.github.me/lib {:git/tag "v1.0" :git/sha "1a2b3c4"}}}
$ rontolisp src/app/main.clj   # roots: src, the lib checkout's src, ../util/src, the data.json jar
```

## Running a project: -A, -M, -X, test

The flags are `clj`'s, and they act on the working directory's `deps.edn` as `clj` does.
`-A:dev:test` selects aliases; every Clojure file of the run is read under them.
`-M[:aliases]` and `-X[:aliases]` select aliases too and end rontolisp's options: what
follows belongs to the run, so `-o` and the other options come first.

- A selected alias applies as in the oracle. `:extra-paths` go ahead of `:paths`;
  `:extra-deps` join `:deps`; `:override-deps` replaces a library's coordinate wherever
  it appears, and `:default-deps` gives one to a library written with `nil`;
  `:replace-paths` and `:replace-deps` replace the project's own (the root map's
  `org.clojure/clojure` stays); `:classpath-overrides` points a library at another
  directory or jar, or drops it with `""`. Several aliases merge the oracle's way: maps
  merge, path lists append without repeats, and `:main-opts`, `:exec-fn` and
  `:ns-default` take the last alias's. An alias two `deps.edn` files define is one map.
  An alias no file defines is warned of. `:jvm-opts` is ignored.
- `-M` runs `clojure.main` over the aliases' `:main-opts` followed by the arguments.
  `-m my.app a b` calls `my.app/-main` with `"a" "b"`, which `*command-line-args*` also
  holds; a path runs that file; nothing starts the REPL.
- `-X` calls one function with one map. The arguments are `[fn] [key value]... [map]`,
  each read as EDN. The function is the one the arguments or `:exec-fn` name, qualified
  by `:ns-default` and `:ns-aliases`. The map is `:exec-args` with each value set at its
  key (a vector key is a path), the trailing map merged over it.
- With `-o`, the run compiles like any program. The command line's arguments are fixed
  into the artifact, and the arguments it is started with follow them.
- `System/exit` ends the program with its status on every backend.
- `rontolisp test` in a directory holding a `deps.edn` runs the project's tests: every
  namespace whose name ends in `-test` below the `:test` alias's `:extra-paths` (`test`,
  from the root map) goes through `clojure.test/run-tests`. The exit code is 0 when every
  test passed and 1 when one failed, errored, or none ran. `-A:...` selects other aliases
  in place of `:test`; a file argument runs that one namespace.

```console
$ cat deps.edn
{:paths ["src"]
 :aliases {:dev {:extra-paths ["dev"]}
           :run {:main-opts ["-m" "my.app"]}
           :greet {:exec-fn my.app/greet :exec-args {:name "you"}}}}
$ rontolisp -M:dev:run a b            # (my.app/-main "a" "b")
$ rontolisp -X:greet :name '"deps"'   # (my.app/greet {:name "deps"})
$ rontolisp -o app.wasm -M:run a      # wasmtime run app.wasm b: (my.app/-main "a" "b")
$ rontolisp test                      # clojure.test over test/**/*_test.clj
```

## Binding

`let` is a `let*` (Clojure's `let` is sequential); `letfn` is one `labels` over
pre-scanned entries, so siblings call each other; `loop`/`recur` is a `labels` self call,
constant-stack on every backend, with sequential inits. `recur` also reaches a named or
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
`&form`/`&env` are refused. A body sees the core builtins, the `clojure.lisp`
library and the program's top-level definitions above the call site -- its own file's,
a required namespace's, an earlier REPL input's -- like the oracle's form-by-form load;
a `def`'s value is built for an expansion only when the body reads it. The definition
also registers a runtime
table entry of the same expander, answers `nil`, and works session-wide; a call above
its definition is an error, a macro has no function value, and a later `def`/`defn` of
the same name wins back the call sites. A `defmacro` of a core name (`with-out-str`,
`when-not`, `inc`, `declare`, ...) shadows it from its definition on, like the oracle's
form-by-form compile: a call site above the definition, and a syntax-quote in a macro
defined above it, keep the core meaning. `clojure.core/name` always names the core var,
whatever the program defines under that name. A special form, or a head the reader
spells (`deref` for `@x`, `with-meta`) cannot name a macro. A `fn` macro is allowed: it
captures `fn` call sites, never `#(...)` (read as the special form `fn*`).

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
key of its kind the program stored, so an `equal` table finds it. A vector is never
mutated either: `assoc`, `update`, `assoc-in` and `update-in` on one copy it whole with the
index replaced, the index equal to the count appending. A
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
`cons`/`concat`/`map`/`filter`, `remove`, `keep`, `keep-indexed`, `map-indexed`,
`distinct`, `interpose`, `partition` and `interleave` answer lazy again when any input
is lazy (strict lists otherwise). A `lazy-seq` body runs at most once per seq object;
printing realizes a lazy seq like the oracle (an empty one prints `()`, an infinite one
without end). There is no chunking. `count`/`empty?`/`=` reach maps and sets (`=` deeply
and structurally); `get` takes an optional default and reads maps, sets, vectors,
strings and nil.

A lazy input reaches every seq verb. The verbs that walk the whole collection (`count`,
`last`, `sort`, `apply`, `reverse`, `set`, `frequencies`, `reduce`, `into`) realize it
first -- an infinite one never answers, like the oracle's -- and the ones that stop
early (`second`, `nth`, `some`, `every?`, `take-while`, `drop-while`, `zipmap`,
positional destructuring, `doseq`/`for`) step through it, so an infinite input still
answers.

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
var. A name no program definition claims, or a `clojure.core/` spelling, is the core var
(`#'clojure.core/inc`): its root is the core value, a macro's root signals, and its
metadata is `:name`, `:ns` and a macro's `:macro`. A core var with no value here
(`#'all-ns`) is refused.

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

`binding` rebinds `^:dynamic` vars and the `clojure.core` specials with dynamic
extent; anything else is refused. `*out*`/`*in*`/`*err*` are `*standard-output*`/
`*standard-input*`/`*error-output*`; the flags hold the oracle's values under
`clojure -M` (`*print-length*` `nil`, `*assert*` `true`, `*data-readers*` the program's
data readers, `{}` without one,
`*command-line-args*` the program's arguments, `*clojure-version*` 1.12.6, ...), and
the printer honours `*print-length*`, `*print-level*`, `*print-readably*`,
`*print-meta*` and `*print-namespace-maps*` (a map whose keys share a namespace prints
`#:a{:b 1}`), `assert` reads `*assert*` where it expands, and the others are plain
values. `*ns*`, `*file*` and `*source-path*` follow the load (above), `*repl*` is
`false`, and `*1`/`*2`/`*3`/`*e` are `nil` outside the [REPL](repl.md). `with-in-str` binds `*in*` to a string
reader, which `read-line`, `read` and `(.read *in*)` take from. `defstruct` holds its key vector
behind the name; `struct`/`struct-map` build fresh maps over it. `with-out-str`
binds `*standard-output*` to a string stream (never a literal
`with-output-to-string`) and answers what printed; `time` reports
`Elapsed time: N msecs` (a double count, like the oracle's) and answers its value.
`with-open` binds and closes in
reverse order through `unwind-protect`, calling the `close` method (Java
closeables need the JVM, like all interop); `(. stream write x)` prints through
`princ` on every backend, `(.readLine stream)` reads through `read-line`
(`nil` past the end, like the oracle) and `(.read stream)` answers the next
character's code (`-1` past the end).

## Protocols, records and types

A `defprotocol` declares methods; each method lowers to a dispatcher over the
target's tag (the multimethod shape without the hierarchy search: an exact tag
match, then a class the target extends or implements that the protocol was extended to,
then the `Object` row). `extend-protocol`/`extend-type`/`extend` add rows
under a target's tag; `satisfies?` tests membership. Extend targets are the kinds
`class` answers (`String`, `Number`, `Boolean`, `Keyword`, `Symbol`, `Character`,
`Map`, `Vector`, `Set`, `List`/`Seq`, plus `nil` and `Object` as the miss
default), known record/deftype names, and any other class a value may be an instance of
(a throwable, an interface such as `clojure.lang.IRef`, `java.util.Date`, a host class on
the interpreter and the JVM), which are tried like the oracle's: the superclasses, then the
interfaces. A name no class has is refused. A miss
with no `Object` row signals, like the oracle. A method declares one parameter vector
per arity: an inline body names the method again for another arity, an extension spells
`fn` clauses, and the row stores one lambda applying the arity of the call's count. A
record, deftype or `reify` with its own row of `clojure.core.protocols/CollReduce` or
`IKVReduce` reduces through it under `reduce`, `reduce-kv` and the verbs built on them
([reduce](reference/reduce.md)). A protocol declared `:extend-via-metadata true`
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

## Reading

`read-string` reads the first datum of a string and `read` one datum from a reader, at
run time on every backend, answering what a quote of the same text answers: the same
numbers, strings, characters, keywords (`::kw` in the calling namespace) and collections,
metadata dropped, `#_` discarding. A record literal builds the record of a class the
program defines; `#=` read-time evaluation is refused like in source, a tagged literal
reads through `*data-readers*`, the default `#inst` and `#uuid` readers and
`*default-data-reader-fn*` in that order, like the oracle's,
and reader conditionals read under `{:read-cond :allow}` like in a `.cljc` file. A reader is a `clojure.java.io/reader`, `*in*`, or a
`java.io.PushbackReader`/`BufferedReader`/`InputStreamReader` over one or over a `java.io.StringReader`,
which is a stream on every backend; `read` leaves it right after the datum. `str` of a
collection quotes the strings inside it, like the oracle's, so what `spit` writes reads
back. `eval` and `load-string` stay absent: no compiler runs at run time.

## Host boundary

`rontolisp.wasm/defimport` and `rontolisp.wasm/export` lower to `rontolisp:wasm-import` and
`rontolisp:wasm-export`, `rontolisp.wit/import`, `export` and `provide` to the three WIT
directives, so every backend binds a Clojure program's boundary the way it binds a Common Lisp
one's ([WASM host functions](reference/wasm.md), [WIT contracts](reference/wit.md)). The host
sees each name as written, never the mangled symbol. `false` crosses as the host's false and
comes back as `false`; an `:s-expr` crosses as the Clojure printer's text. A richer WIT value
crosses in its Clojure spelling both ways -- a record as a map, an enum or a variant's case as
a keyword or `[:case payload]`, flags as a set, a tuple or a list as a vector -- and a
`result`'s error arm as an `ExceptionInfo` holding the error value. A WIT import's vars
exist below its form, like a `require`'s alias, while a `defimport` may be called above it, like
a `defn`. An export resolves its var once the whole file has lowered, so the var may be
defined below it.

## HTTP client

`rontolisp.http-client` is a built-in namespace written in Clojure whose every request calls
`rontolisp:fetch`, so a Clojure program fetches through whatever transport the target gives
fetch, and a target without one refuses the program when it compiles
([HTTP client](reference/http-client.md)). Its future under `:async true` is a rontolisp
future: `deref` waits for it through the same mechanism as `rontolisp:await`.

## Not yet

Each refusal names the missing design, never `unknown name`:

| Refused | Message shape | Why |
|---|---|---|
| end-less `range` | `infinite range is not supported: range needs an end` | an infinite seq cannot be spelled strictly -- spell it with `iterate` |
| `transient`, `persistent!`, `assoc!`, `dissoc!`, `conj!`, `disj!` | `transients are not supported yet: ...` | no transient runtime behind the tables |
| `definterface`, `gen-class`, `gen-interface` | `protocols are not supported yet: ...` | no interface generation on any backend |
| a `reify`/`deftype`/`defrecord` body naming an interface other than the core functions' ([reify](reference/reify.md#host-interfaces)) | `... is not supported yet as an interface of ...` | the collection interfaces (`ISeq`, `IPersistentMap` ...) and the host ones have no consulting functions yet |
| `set!` of a core var that is no special (`inc`), of a host field | `set! of a var is not supported yet: ...`, `set! of a host field is not supported yet: ...` | no var to assign; the `java:` surface has no field write |
| `future`, `delay`/`force`, `promise`/`deliver` | by name | no thread pool, lazy memo cells or blocking rendezvous on any backend |
| `proxy-super` outside a proxy method | `proxy-super outside a proxy method` | a `proxy-super` calls the superclass implementation on the method's `this` |
| `proxy` with a second class, a duplicate method, a final superclass | `... is a class, not an interface`, `proxy defines method ... twice`, `proxy cannot extend final class ...` | one superclass only, one body per method name, no final superclass |
| `toString`/`equals`/`hashCode` in an interface-only `proxy` | `proxy cannot override ... yet` | `java:proxy` keeps `Object`'s three, so the body would never run (a class proxy runs it) |
| a variadic-only static member, instance method (`Class/.m`) or constructor (`Class/new`) as a value | `... is variadic and has no value form` | no rest-spread reaches `java:static`, `java:call` or `java:new` |
| `&form`/`&env` in `defmacro` parameters | by name | macros receive no compilation environment |
| `::alias/kw` with an unknown alias | `Invalid token: ...` | only required aliases, the file's own ns and known namespaces resolve |
| `--no-gc` builds | by name | that backend has no pairs, symbols or closures |
| `file-seq`, `clojure.java.io` (except `reader`) | `file-seq` / `unknown name: clojure.java.io/...` | no directory walks; only `reader` resolves, opening a file-stream reader |
| `with-redefs` of a `clojure.core` var, a macro, a multimethod or protocol method; in the REPL, of a `defn` an earlier input defined without `^:redef` | `with-redefs of ... is not supported...`, `... define it ^:redef to redefine it` | core verbs lower inline; only a `def`/`defn`/`declare` var has a root to replace, and a REPL input already ran with direct calls |
| an asynchronous Ring handler (`run-server` with `:async? true`) | `asynchronous handlers (:async? true) are not supported` | no respond/raise protocol under the transports |
| `:async` on a `rontolisp.wasm` declaration, an `async func` WIT member or export | `:async is not supported yet ...`, `... is an async func ...` | the future a suspending crossing answers is no Clojure future |
| a WIT member taking or answering a stream or a future | `... which the Clojure tier does not carry yet (file.wit:N)` | the async canonical ABI's handles answer no Clojure future yet |
| `:bytes` in a `rontolisp.wasm` declaration | `:bytes does not cross from Clojure ...` | it transfers an `(unsigned-byte 8)` vector, which no Clojure value is |

## Errors and positions

A lowering error names the innermost form's position (`file:line:column` when the file is
known); the reader's errors are prefixed the same way.

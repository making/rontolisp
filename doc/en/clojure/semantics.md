# Semantics

Every Clojure form lowers, at file-read time, to the Common Lisp core forms the rest of
rontolisp already consumes; no backend learns a Clojure name. This page is the map of
those lowerings and the forms refused by name.

## Names and calls

An identifier lowers to a symbol behind the `c%` prefix, so `Foo` and `foo` stay apart
and no name reaches a core-form case label. `defn` is a `defun` called directly; a
head-position call to a parameter, a `let`/`loop` binding or a `def`'d variable is a
`funcall` of the value cell, so `(defn call-it [f x] (f x))` runs; a `declare`d-but-never-
defined name keeps its direct-call error. `def` is a top-level `setq` -- inside a body it
still sets the global when the body runs.

`defn` with several arities is one `defun` per arity plus a dispatch `defun` picking by
argument count (a single variadic clause takes any count past its fixed parameters); any
other count signals. `fn` with several arities is one `lambda` dispatching the same way,
and a named `fn` binds itself for self-calls; `#(...)` is a `lambda` whose arguments
travel as one rest list (`%`..`%9`, `%&`), its body forms wrapped as one call. `declare`
names what is defined below, so a definition may use one.

## Binding

`let` is a `let*` (Clojure's `let` is sequential); `loop`/`recur` is a `labels` self call,
constant-stack on the interpreter, with sequential inits. Parameters and bindings
destructure: a vector pattern binds positionally through the seq view (`&` the rest as a
seq, itself a pattern; `:as` the whole), a map pattern through the table-aware read
(`:keys`/`:syms`/`:strs`, explicit locals, `:as`, `:or` defaults) -- in `let`, `loop` and
`fn`/`defn` parameters alike; nested patterns recurse. Malformed shapes are named
refusals.

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

The seq family runs over strict list views of every collection: lists pass through
untouched, vectors and strings coerce, maps contribute one two-vector per entry and sets
one member per element (both in the table's walk order, unspecified); `nil` and `false`
are empty; anything else signals like the oracle. There is no laziness, chunking or
memoisation. `count`/`empty?`/`=` reach maps and sets (`=` deeply and structurally);
`get` takes an optional default and reads maps, sets, vectors, strings and nil.

## Not yet

Each refusal names the missing design, never `unknown name`:

| Refused | Message shape | Why |
|---|---|---|
| `lazy-seq`, `cycle`, `repeat`, `repeatedly`, `iterate`, end-less `range` | `lazy sequences are not supported: ...` | seqs are strict; an infinite seq cannot be spelled |
| `transient`, `persistent!`, `assoc!`, `dissoc!`, `conj!`, `disj!` | `transients are not supported yet: ...` | no transient runtime behind the tables |
| regex literals `#"..."` | `regex literals are not supported yet` | no regex runtime on any backend |
| `defprotocol`, `defrecord`, `deftype`, `definterface`, `reify`, `extend-protocol`, `extend-type`, `extend`, `satisfies?`, `gen-class`, `gen-interface` | `protocols are not supported yet: ...` | rejected by design -- type dispatch and a record value representation on all four backends |
| `set!` | by name | no field-write primitive and no record type to mutate |
| backquote/unquote, `var`/`#'`, metadata `^`/`with-meta` | by name | each awaits its design |
| `::`-auto-resolve keywords | by name | no namespace to resolve against |
| `--no-gc` builds | by name | that backend has no pairs, symbols or closures |

## Errors and positions

A lowering error names the innermost form's position (`file:line:column` when the file is
known); the reader's errors are prefixed the same way.

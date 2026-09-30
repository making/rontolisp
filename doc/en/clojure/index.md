# Clojure (experimental)

**Experimental.** rontolisp reads a small subset of Clojure -- `def`/`defn`, `fn` and
`#(...)`, `let`/`loop`/`recur`, `if`/`when`/`cond`/`do`/`and`/`or`, `first`/`rest`/
`next`/`seq`/`cons`/`map`/`filter`/`reduce`/`apply`/`concat`, `nth` (with an optional
default) and `take`/`drop`/`range` with an end, vector, keyword, map and set literals,
`assoc`/`dissoc`/`get`/`contains?`/`keys`/`vals`/`merge`/`conj`/`disj`/`set`/
`hash-map`/`array-map`, `println`/`print`/`pr`/`prn`/`str` -- just
large enough to run a Clojure-shaped program on every backend. Conformance is partial
by design and nothing here is a subset or compatibility promise. Use it to try a
Clojure program on the JVM or WebAssembly; write Common Lisp for anything you need to
keep working.

A `.clj` file is read as Clojure; `--source-language clojure` says so for any other
file. The language is picked per file, so one program may mix the two.

```bash
rontolisp hello.clj                                # interpreter
rontolisp hello.clj -o Hello.class && java Hello   # JVM
rontolisp hello.clj -o hello.wasm && wasmtime run hello.wasm
rontolisp hello.clj -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp prog.txt --source-language clojure       # any extension
```

`--no-gc` is refused: that backend has no pairs, symbols or closures.

```clojure
(defn fact [n]
  (if (< n 2) 1 (* n (fact (- n 1)))))

(println (fact 10))
(println (reduce + 0 (map #(* % %) (filter odd? '(1 2 3 4 5)))))
```

## What lowers to what

Every identifier mangles behind `c%`, so no Clojure name can collide with a core form
or built-in. `defn` is a `defun` (direct call), `def` a top-level `setq`, `fn` and
`#(...)` a `lambda` (the `#(...)` arguments travel as one rest list), `let` a `let*`,
`loop`/`recur` a `labels` self call, a vector literal a `vector` call, a map literal
an `equal` hash table (never mutated in place -- every verb builds a fresh one, so
persistence holds observably), a set literal the same table with each member stored
under itself, and a seq is a strict
list view over any collection (`first`/`rest`/`next`/`seq`/`cons`/`concat`/`map`/
`filter`/`reduce`/`apply`/`nth`/`take`/`drop` all coerce through it -- lists pass
through untouched, vectors and strings coerce, maps contribute one two-vector per
entry and sets one member per element). A keyword is its spelling
wrapped as `(:C%KEYWORD name)`, case-preserved, so `:a` and `:A` stay apart; it
prints with its colon, and in call position (`(:k m)`, with an optional default)
it is the map lookup. `range` with an end builds the strict list;
an end-less `range` and `lazy-seq` (with `cycle`/`repeat`/`repeatedly`/`iterate`)
are refused -- there are no lazy seqs here. `nth`/`quot` as values are lambdas with
the Clojure argument order, as are `inc`/`dec`/`str` and the seq verbs
(`seq`/`first`/`rest`/`cons`/`count`/`map`/`filter`/`reduce`/`concat`/`take`/
`drop`/`range`), so higher-order calls take them bare; `apply` spreads leading
arguments over the seq-coerced last one. `count`/`empty?`/`=` reach maps
and sets; `get` takes an optional default; transients (`assoc!` and friends) are
refused. `::`-auto-resolve is refused; a namespaced `:a/b` is opaque data that
prints and compares whole.

## REPL

With no file, `--source-language clojure` starts a Clojure REPL (`clojure> ` prompt).
A form may span lines. Definitions typed at separate prompts see each other, as they
would in one file.

```console
$ rontolisp --source-language clojure
clojure> (defn twice [x] (* 2 x))
clojure> (twice 21)
42
```

## Deviations

`false` is a distinct object from `nil` (both are falsey, so `if`/`when`/`cond`/`and`/
`or`/`not` treat them alike, while `=` and `nil?` tell them apart); `false?`/`true?`/
`boolean?` answer accordingly. `println`/`print` spell the three values `true`/`false`/
`nil` and `str` spells them `true`/`false`/`""`, joining `println`/`print` parts with
a single space (like Clojure) while `str` concatenates bare; `pr`/`prn` are the
readable arms (strings print quoted). A keyword prints with its colon (`:a`), case-preserved. Collections
print in Common Lisp notation (`#((C%KEYWORD a) (C%KEYWORD b))` for `[:a :b]`, with
`T`/`NIL` for a nested `true`/`nil`); a keyword nested in a collection shows its
`(:C%KEYWORD name)` wrapper, like a set shows its wrapper. Maps print as `#<HASH-TABLE :TEST EQUAL :COUNT n>` and
sets as `(C%SET #<HASH-TABLE ...>)`; vector and table keys compare by identity, so a
vector key misses a lookup its oracle answers; a repeated set-literal element is
refused by spelling; the seq family runs over strict list views of every collection
(lists pass through untouched; an empty result is `nil`, where the oracle prints
`()`; `nth` past the end answers the default instead of throwing; map/set seq order
is the table's walk order, unspecified; strings seq to characters printing in Common
Lisp notation). Destructuring, threading macros,
`atom`, lazy seqs, metadata and `var` are absent. A lowering error names the innermost
form's position (`file:line:column` when the file is known).

# Clojure (experimental)

**Experimental.** rontolisp reads a small subset of Clojure -- `def`/`defn` (single- and
multi-arity) and `declare`, `fn` (named, multi-arity) and `#(...)`, `let`/`loop`/`recur`
with sequential and map destructuring, `if`/`when`/`cond`/`do`/`and`/`or`, threading
(`->`/`->>`/`as->`/`doto`/`cond->`/`cond->>`/`some->`/`some->>`), `first`/`rest`/
`next`/`seq`/`cons`/`list*`/`map`/`filter`/`reduce`/`apply`/`concat`, `nth` (with an optional
default) and `take`/`drop`/`range` with an end, vector, keyword, map and set literals,
`assoc`/`dissoc`/`get`/`contains?`/`keys`/`vals`/`merge`/`conj`/`disj`/`set`/
`hash-map`/`array-map`, `subs`, `println`/`print`/`pr`/`prn`/`str`, `comment`,
`try`/`catch`/`finally`/`throw`, `atom`/`deref`/`swap!`/`reset!`/`compare-and-set!`
(and `volatile!`/`vswap!`/`vreset!`), `defmulti`/`defmethod`/`remove-method`/
`get-method`, `ns` with `clojure.string` (`join`/`split`/`split-lines`/`upper-case`/
`lower-case`/`capitalize`/`trim`/`triml`/`trimr`/`trim-newline`/`blank?`/`starts-with?`/
`ends-with?`/`includes?`/`index-of`/`last-index-of`/`replace`/`replace-first`/`escape`/
`re-quote-replacement`/`reverse`), and Java interop (`.`, `..`, `Class/member`,
`Class.`, `new`) -- just
large enough to run a Clojure-shaped program on every backend. Conformance is partial
by design and nothing here is a subset or compatibility promise. Use it to try a
Clojure program on the JVM or WebAssembly; write Common Lisp for anything you need to
keep working. Interop runs on the interpreter and the JVM only: the wasm backends
reject the `java:` surface it lowers to.

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

`defn` with several arities is one `defun` per arity plus a dispatch `defun` picking
by argument count (a single variadic clause takes any count past its fixed parameters);
any other count signals. `fn` with several arities is one `lambda` dispatching the
same way, and a named `fn` binds itself for self-calls. `declare` names what is
defined below. A vector binding pattern binds positionally through the seq view
(`&` the rest as a seq, `:as` the whole), a map pattern through the table-aware read
(`:keys`/`:syms`/`:strs`, explicit locals, `:as`, `:or` defaults) -- in `let`,
`loop` and `fn`/`defn` parameters alike. `->`/`->>` insert the value second/last,
`as->` rebinds its name step by step, `doto` answers its (unchanged) target,
`cond->`/`cond->>` thread only on truthy tests, `some->`/`some->>` stop at `nil` (but
not at `false`), and `list*` folds `cons` over the seq view. `doseq`/`dotimes`/`for` are refused by name, as are hierarchies (`derive`, `isa?`,
`prefer-method`), protocols (`defprotocol`, `defrecord`, `deftype`, `reify`,
`extend-protocol` and friends, `proxy`, `gen-class`), `ex-info`/`ex-data`, `set!`,
regex literals (`#"..."`), backquote, `var`/`#'` and metadata (`^`).

## State, errors, dispatch, namespaces and interop

An atom is a tagged cell `(:C%ATOM #(value))` every verb reads and writes: `(atom
1)` builds it, `@a` and `(deref a)` read it, `(swap! a f x...)` applies `f` to the
value and the extra arguments and stores the answer, `(reset! a v)` stores `v`,
and `(compare-and-set! a old new)` stores `new` only when the value is `eql` to
`old` -- value comparison for numbers, identity otherwise -- answering `true` or
`false`. Each answers the new value (the comparison its boolean), and each works
as a function value too, so `(map deref atoms)` runs.

```clojure
(def a (atom 1))
(println @a)                 ; 1
(println (swap! a + 10 20))  ; 31
(println (reset! a 2))       ; 2
(println (compare-and-set! a 2 3)) ; true
```

`try` guards a `handler-case` inside an `unwind-protect`: `(try body...
(catch Class var body...)... (finally ...))`. Every catch class answers the
catch-all `error` clause -- classes are not distinguished, so the first clause
handles any condition -- and the catch variable binds the Common Lisp condition.
`throw` signals through `error`, rendering its value with `princ-to-string`, so a
thrown string keeps its message.

A multimethod is a method table plus a dispatcher `defun`: `(defmulti name
docstring? dispatch-fn :default default?)` builds both (the default dispatch
value is `:default`), `(defmethod name value [params...] body...)` stores a
method, `(remove-method name value)` drops one, `(get-method name value)` reads
one. A miss with no method for the default signals.

`(ns name (:require [clojure.string :as s :refer [join]]) (:use ...) (:import ...))`
wires its clauses and defines nothing: `:as` registers an alias, `:refer`/`:use`
unqualified names, `:import` class names for interop, `(:refer-clojure :only/
:exclude ...)` narrows the visible core. Requiring an unknown namespace is an
error. Top-level `require`/`use`/`import` do the same and answer `nil`.

```clojure
(ns demo (:require [clojure.string :as s]))
(println (s/join "," ["a" "b"])) ; a,b
(println (s/upper-case "hi"))    ; HI
```

Interop lowers to the `java:` surface (`.kb/java-interop.md`): `(. obj method
args...)` and `(.method obj args...)` call an instance method, `(. Class method
args...)` and `(Class/static args...)` a static one, `(Class. args...)` and
`(new Class args...)` construct, `(Class/FIELD)` reads a static field (a
zero-argument static method spells `(. Class method)` instead), `(.-field obj)`
an instance field, and `(.. obj (step args...) name...)` nests. Class names
resolve dotted as written, through `:import`, or through `java.lang`. A string
receiver answers the mapped core operation (a Lisp string is no host object),
anything else goes to `java:call` directly.

```clojure
(println (.toUpperCase "hi"))    ; HI
(println (Integer/parseInt "42")) ; 42
```

Characters read as characters (`\a`, the lowercase `newline`/`space`/`tab`/
`return`/`backspace`/`formfeed` names, `\uXXXX`, `\oNNN` -- anything else is an
`Unsupported character` refusal) and integers read in radix (`0xFF`, `2r101`,
`8r17`, leading-`0` octal; a shaped token that parses to nothing is an `Invalid
number` refusal). `1M` lowers to an exact ratio (`0.1M` is `1/10`, so decimal
arithmetic stays exact and prints as the ratio), and a `2N` past the `long` range
is a bignum; either prints without its mark.

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
Lisp notation). `doseq`/`dotimes`/`for` comprehensions and loops, hierarchies,
protocols, `ex-info`, `set!`, regex literals, backquote, `var` and metadata are
absent. Catch clauses are catch-all in order (the first handles any condition);
multimethod dispatch values compare like `equal` table keys (vectors by identity);
atoms print as their `(:C%ATOM #(value))` wrapper; `split`/`replace` match literal
strings, never patterns; `indexOf` answers `-1` when missing, like the oracle. `def` inside a body
sets the global when the body runs; `defn` inside a body works only in statement
position (a multi-arity one only at the top level). `cond` keeps the lenient reading:
an odd trailing arm is the default, where Clojure signals. A threading step over a
collection literal signals (collections are not functions here). A lowering error
names the innermost form's position (`file:line:column` when the file is known).

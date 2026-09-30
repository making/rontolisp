# Clojure (experimental)

**Experimental.** rontolisp reads a small subset of Clojure -- `def`/`defn`, `fn` and
`#(...)`, `let`/`loop`/`recur`, `if`/`when`/`cond`/`do`/`and`/`or`, `map`/`filter`/
`reduce`/`apply`/`concat`, vector and keyword literals, `println`/`print`/`str` -- just
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
`loop`/`recur` a `labels` self call, a vector literal a `vector` call, the seq
functions list operations (`map`/`filter`/`reduce`/`apply`/`concat` to
`mapcar`/`remove-if-not`/`reduce`/`apply`/`append`).

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

`false` folds into `nil` (both falsey); keywords print upcased (`:A` for `:a`) and
collide case-insensitively; map and set literals are refused; the seq family runs over
lists only; `println` concatenates its parts with no separator and collections print
in Common Lisp notation (`#(A B)` for `[:a :b]`). Destructuring, threading macros,
`atom`, lazy seqs, metadata and `var` are absent. Errors carry no source position yet.

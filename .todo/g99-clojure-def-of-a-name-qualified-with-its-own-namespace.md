# Clojure: a def of a name qualified with its own namespace defines another var

Difficulty: Low

`(def user/x 1)` in `user` -- and so every literal def name a macro template spells, which
syntax-quote qualifies -- defines a var no later `x` reaches:

```clojure
(def user/foo1 2)
(println foo1 user/foo1)   ;; clj 1.12.6: 2 2   rontolisp: unknown name: foo1
(defmacro m0 [] `(def foo0 1))
(m0)
(println foo0)             ;; clj: 1           rontolisp: unknown name: foo0
```

The oracle's `def` takes a symbol qualified with the current namespace as its bare name and
refuses any other namespace (`Can't create defs outside of current ns`); `defn` refuses every
qualified name through its spec (`user/foo2 - failed: simple-symbol?`).
`ClojureBindingLowering.defForms` interns the spelling as written (`ctx.intern(name, ...)`).

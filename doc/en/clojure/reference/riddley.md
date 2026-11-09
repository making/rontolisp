# riddley

[riddley](https://github.com/clj-commons/riddley) walks code knowing the special forms and the
locals they bind; specter's `path` and potemkin's types walk the code they are handed with it.
Its own jar drives the Clojure compiler's internals (`Compiler/LOCAL_ENV`, and a Java class
building the compiler's `LocalBinding`s), which rontolisp does not have, so `riddley.walk` and
`riddley.compiler` are built in: Clojure source written for rontolisp from riddley's documented
behavior, loaded like a project file and the same on every backend. In a `deps.edn`,
`riddley/riddley` at a Maven version up to 0.2.2 is this built-in one and fetches nothing --
specter names 0.1.12 -- and a newer version is refused when a riddley namespace loads.

| Var | Behavior |
|---|---|
| `riddley.walk/macroexpand-all` | `(macroexpand-all form)`: every macro call in `form` expanded, each with the locals in reach at the call as its `&env` |
| `riddley.walk/walk-exprs` | `(walk-exprs predicate handler form)`, `(walk-exprs predicate handler special-form? form)`: `form` walked as code, every macro call expanded; `handler` answers for each expression `predicate` picks, which the walk does not enter, and every other one is rebuilt from its walked operands |
| `riddley.walk/macroexpand` | `(macroexpand form)`, `(macroexpand form special-form?)`: `form` expanded while its head names a macro; a head naming a local in reach, or one `special-form?` picks, stays as written |
| `riddley.walk/special-form?` | Whether a symbol is the head of a special form `walk-exprs` walked |
| `riddley.compiler/locals` | The locals in reach, the map `&env` is; outside a walk, `nil` where the program runs and in a macro body whose call sees no local |
| `riddley.compiler/with-base-env`, `with-lexical-scoping`, `register-local`, `register-arg` | The scope a walk starts in, a scope of its own, and a local or a parameter registered in the innermost scope |
| `riddley.compiler/tag-of` | The symbol naming the class a form's `:tag` metadata names |

```clojure
(require '[riddley.walk :as rw] '[riddley.compiler :as rc])
(rw/macroexpand-all '(when a (-> b c)))
; => (if a (do (c b)))
(rw/walk-exprs symbol? #(if (contains? (rc/locals) %) (list 'local %) %) '(let [x 1] (list x y)))
; => (let* [x 1] (list (local x) y))
(defmacro env-keys [] `'~(vec (sort (map str (keys &env)))))
(defmacro walked [form] (rw/macroexpand-all form))
(let [outer 1] (walked (let [inner 2] (env-keys))))
; => ["inner" "outer"]
```

A macro body's [macroexpand-1](macroexpand-1.md), `macroexpand` and
`clojure.walk/macroexpand-all` hand the call's locals on as `&env` too; what riddley adds is the
locals bound inside the walked form: through `clojure.walk/macroexpand-all`, the last call above
answers `["outer"]`.

## Differences

- No function carries an inline expansion ([definline](definline.md) defines a plain function),
  so a call such as `(+ n 1)` stays a call, where riddley expands it to
  `(. clojure.lang.Numbers (add n 1))`.
- Each local in `locals` maps to its symbol, as in `&env`, where riddley holds the compiler's
  `LocalBinding`; a symbol keys the map without its metadata ([Deviations](../deviations.md)).
- `walk-exprs` evaluates a `(var x)` form its predicate picks, like riddley: on the JVM a program
  whose run reaches that carries the compiler ([eval](eval.md)), and WebAssembly refuses it when
  it runs.

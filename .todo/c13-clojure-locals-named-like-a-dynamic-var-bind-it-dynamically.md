# c13. Clojure locals named like a ^:dynamic var bind it dynamically

Difficulty: Medium

A Clojure local is lexical: a function parameter or a `let`/`loop` binding named like a
`^:dynamic` var shadows the var in its scope, and a function called there reads the var.
The Clojure lowering spells the local and the var as one CL symbol (`ClojureLowerUtil.idSym`),
and a `^:dynamic` var is a `defparameter`, so the local binds the CL special dynamically.

Measured 2026-10-03 (after c06, which made a special-named parameter dynamic on the compile
paths too):

```clojure
(def ^:dynamic *x* 1)
(defn show [] *x*)
(defn f [*x*] (show))
(println (f 2))
(println (let [*x* 5] (show)))
(println (binding [*x* 7] (show)))
(println ((fn [*x*] (show)) 3))
```

Oracle `1 1 7 1`. Every backend now `2 5 7 3`; before c06 the interpreter answered `2 5 7 3`
and the JVM and both WASM `1 5 7 1` (a parameter bound lexically, by accident). Listed in
`doc/*/clojure/deviations.md` and `.kb/clojure-frontend.md`, "Deviations".

## Plan

- Give a local whose name a `^:dynamic` var also spells a lowered symbol of its own, chosen
  where the local is bound (fn parameters, multi-arity clauses, destructuring, `let`, `loop`,
  `letfn`, `for`/`doseq`/`dotimes`, `catch`, `with-open`, `if-let`/`when-let`, ...) and found
  again where a reference resolves to the local (`ClojureLowering.scopes`); `binding` keeps
  the var's symbol. The `^:dynamic` names must be known for the whole program, a var defined
  after the local included (the special proclamation is program-wide).
- Only a colliding local is renamed, so a program without one lowers byte-identically.
- Pin it in `clojure-spec.yaml` (all four backends) and drop the deviation entries.

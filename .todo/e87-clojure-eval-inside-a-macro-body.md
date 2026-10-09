# e87. Clojure: `eval` inside a macro body at expansion time

Difficulty: Medium

A macro body runs at lower time (`eval/ClojureMacroTime`, `.kb/clojure-frontend.md`
"Macros"), but `eval` is an unknown name there as at run time. Libraries choose code by the
oracle's version with a `compile-if` macro evaluating its test while expanding:

```clojure
(defmacro compile-if [test then else] (if (eval test) then else))
(compile-if (resolve 'clojure.core/hash-unordered-coll) (hash-unordered-coll this) (.hashCode this))
```

Measured 2026-10-08: it is the first stop of data.priority-map 1.2.0
(`priority_map.clj:216:7: unknown name: eval`) and of instaparse 1.5.0
(`auto_flatten_seq.clj:13:15`), once their collection interfaces load (`.kb/clojure-frontend.md`
"Collection interfaces"; priority-map expanded by hand runs whole on all four backends).

## Plan

1. Measure on clj 1.12.6 what such tests read: `resolve` of a core name the front end has or
   lacks (`hash-unordered-coll`, `mix-collection-hash`), `*clojure-version*`, a class lookup.
2. `eval` of a form in a macro body evaluates it in the macro-time environment (the
   definitions above the call site, like the body itself); `resolve` there answers a var for
   a name the program or the front end defines and nil otherwise. Run-time `eval` stays
   refused.
3. Re-probe both libraries; they then need `hash` (e88).

# Clojure: a multimethod answers `clojure.lang.MultiFn`'s `getMethod` and `dispatchFn`

Difficulty: Medium

spec.alpha's `multi-spec` (loaded from its jar since g90) conforms through
`((.dispatchFn ^MultiFn @mmvar) x)` and `(.getMethod mm dispatch-value)`; here a multimethod
is its dispatch function, so both are `No matching field found: dispatchFn for class
clojure.lang.AFunction` and `(s/valid? (s/multi-spec mm :tag) x)` refuses
(`.kb/clojure-frontend.md`, "clojure.spec.alpha (g90)").

Approach: the `defmulti` registry record (`%clojure-multimethods`, g40) gains the dispatch
function and a method finder; `.dispatchFn` and `.getMethod` become value-method rows on a
function the registry holds. `getMethod` is `MultiFn.getMethod`: the exact row, the hierarchy
search with prefers (an ambiguity refused in the oracle's words), the default, else nil --
so factor `C%H-DISPATCH`'s lookup from its call and share it. Then re-run the spec probe's
`multi-spec` lines (`valid?`, `explain-str` of a missing method, `conform`) against clj
1.12.6 on all four backends.

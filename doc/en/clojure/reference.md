# Reference

One page per name the Clojure front end provides: every form, verb and interop entry of
the experimental subset, grouped by area. **Each name in a table links to its own page**,
which gives the signature, the behavior, the deviation from the oracle where there is one
and a worked example. The refused forms are not listed here -- [Semantics](semantics.md)
has them.

| Page | Contents |
|---|---|
| [Syntax and definition](reference/syntax.md) | `def`/`defn`/`fn`, binding, conditionals, `quote`, `comment`, `declare` |
| [Threading](reference/threading.md) | `->`, `->>`, `as->`, `doto` and the conditional threaders |
| [Seqs](reference/seqs.md) | The seq family over strict list views of every collection |
| [Iteration](reference/iteration.md) | `doseq`/`dotimes`/`for` and the strict `dorun`/`doall` |
| [Maps, sets and vectors](reference/collections.md) | The persistent collection verbs over `equal` hash tables |
| [Numbers and predicates](reference/numbers.md) | Arithmetic, comparison and the type predicates |
| [State](reference/state.md) | `atom`/`deref`/`swap!` and the volatile trio |
| [Multimethods and hierarchies](reference/multimethods.md) | `defmulti`/`defmethod`, `derive` and the hierarchy reads |
| [Errors](reference/errors.md) | `try`/`catch`/`finally`, `throw`, `ex-info` and its readers |
| [Namespaces](reference/namespaces.md) | `ns` and the top-level `require`/`use`/`import` |
| [(clojure.string)](reference/string.md) | The string library, plus the core `subs` |
| [Java interop](reference/interop.md) | `.`, `..`, construction, `memfn`, `proxy` |

# f00. Clojure: a run-time `macroexpand` resolves a bare head in `*ns*`

Difficulty: Medium

The oracle's `macroexpand` resolves the head in `*ns*` at run time; here it resolves through
the call site's namespace (`ClojureMacroLowering.macroScope`, an alist built at lower time).
They differ where the call site is a library: `clojure.walk/macroexpand-all` called from
another namespace expands none of that namespace's bare macro names. Measured 2026-10-09
on all four backends:

```clojure
(ns mac5 (:require [clojure.walk :as w]))
(defmacro twice [x] `(* 2 ~x))
(prn (w/macroexpand-all '(+ 1 (twice (twice 3)))))
```

prints `(+ 1 (twice (twice 3)))`; the oracle (loading the file, `*ns*` is `mac5`) prints
`(+ 1 (clojure.core/* 2 (clojure.core/* 2 3)))`. In `user` it works (the table spells a
`user` macro's bare name).

## Plan

1. Resolve a bare head against the namespace `*ns*` names when the expansion runs: the
   aliases and refers of every namespace the program defines, as data the run-time
   expander can read, keyed by namespace (only where the program expands at run time).
2. Pin the case above in `clojure-spec.yaml`.

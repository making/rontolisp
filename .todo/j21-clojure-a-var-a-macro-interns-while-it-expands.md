# Clojure: a var a macro `intern`s while it expands is unknown to its own expansion

Difficulty: High

specter 1.1.6's `path` macro (behind `select`, `transform`, `setval` ...; `com/rpl/specter.cljc:268-345`)
implements an inline cache per call site: at expansion time it creates a fresh var holding a
mutable cell, then emits code reading and writing it.

```clojure
(let [cache-sym (vary-meta (gensym "pathcache") merge {:no-doc true :private true}) ...]
  (if (= platform :clj)
    (i/intern* *ns* cache-sym (i/mutable-cell)))          ; macro-time side effect
  `(let [info# (try (i/get-cell ~cache-sym)
                    (catch ClassCastException e#
                      (if (bound? (var ~cache-sym)) ... (alter-var-root (var ~cache-sym) ...))))
         ...]
     ...))
```

Every `select`/`transform` stops at lowering with `Unable to resolve var: #:pathcache223 in
this context`.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defmacro cached [x]
  (let [s (gensym "cache")]
    (intern *ns* s (atom nil))
    `(let [c# ~s] (when (nil? @c#) (reset! c# ~x)) [@c# (bound? (var ~s))])))
(defn f [] (cached 42))
(prn (f) (f))
```

- oracle: `[42 true] [42 true]`
- interpreter and JVM class: `error: f.clj:5:12: Unable to resolve var: #:cache1 in this context`
  (`ClojureVarLowering`). The `#:cache1` spelling in the message also looks wrong (a gensym
  symbol printed as a namespaced-map prefix?) -- check it.

## Analysis

In the oracle the macro runs inside the compiler, so the var exists before the expansion is
analyzed, with the cell the macro put in it. Here the macro runs in the macro-time image
(`.kb/clojure-frontend.md`, "Macros"), whose `intern` does not reach the program being lowered:
the lowering's var table and the compiled program both need the definition. What the program must
see at run time is a var named `cache1` in the namespace, initialized to WHAT the macro put there
-- a value created at macro time (`(atom nil)`, specter's `MutableCell`). Options to weigh:

1. Record every macro-time `intern`/`def` of the program's own namespaces as a definition the
   lowering emits before the form (the initial value re-created by evaluating... what? the value
   is not a form -- an atom must become `(atom nil)`; a `MutableCell` a constructor call. Only
   values with a load form (KB `.kb/make-load-form.md` is the CL precedent) can be emitted).
2. Emit the var as declared-but-unbound, so the expansion's `ClassCastException`/`bound?` fallback
   (specter has exactly this path for AOT, the comment explains it) initializes it at run time.
   For specter that is enough and correct; for the repro the oracle prints `true` from `bound?`
   on the first call while option 2 would print... measure.

Pick by what the oracle's semantics require, not by specter alone; KB "Macros" gets the rule.

## Done when

- The repro prints the oracle's line on all four backends (ci-spec), or the KB and
  `doc/{en,ja}/clojure/deviations.md` state the chosen deviation with the reason.
- specter's probe runs without the `#?(:clj `(comp-paths ~@path) ...)` hunk of j07's
  `specter/patches.diff` (the inline-cached path), with the oracle's lines.

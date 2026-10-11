# f41. Clojure: `alter-var-root` and `alter-meta!` of a var

Difficulty: Medium

instaparse 1.5.0 (verbatim, interpreter, 2026-10-11) stops at
`core.clj:25:3: unknown name: clojure.core/alter-var-root`: its `set-global-var!` macro takes
the clj branch now that `&env` is read, `(alter-var-root (var ~v) (constantly ~value))`, and
`enable-tracing!` alters `#'gll/TRACE`; its `defclone` macro copies var metadata with
`(alter-meta! (var ~here) assoc :doc ... :arglists ...)`. Both are unknown names here.

Measured against clj 1.12.6:

| program | oracle |
|---|---|
| `(def ^:dynamic *fmt* :hiccup) (alter-var-root #'*fmt* (constantly :enlive)) *fmt*` | `:enlive` |
| `(def counter 1) [(alter-var-root #'counter + 10) counter]` | `[11 11]` |
| `(defn f [] 1) (alter-var-root #'f (constantly (fn [] 2))) (f)` | `2` |
| `(defn g "doc of g" [x] x) (def h g) (alter-meta! #'h assoc :doc (:doc (meta #'g))) (:doc (meta #'h))` | `"doc of g"` |

## What decides the design

- `.kb/clojure-frontend.md` "Vars and metadata": a var is `(:C%VAR "ns/name" getter)` with no
  setter, its metadata recorded at lower time into constants or `|c%x%meta|`; "with-redefs":
  a `defn` is a direct call unless redefinable, the same question `alter-var-root` of a
  function var asks.
- A literal `#'x`/`(var x)` (instaparse's every use) knows its root at lower time; a computed
  var needs a setter in the var value.

## Plan

1. `alter-var-root` of a literal var: the root replaced by `(apply f root args)`, answered;
   a `defn` var made redefinable like a `with-redefs` target; decide a computed var.
2. `alter-meta!` (and `reset-meta!`) of a var: a run-time metadata cell the `meta` of `#'x`
   reads.
3. Re-probe instaparse; pin both on all four backends.

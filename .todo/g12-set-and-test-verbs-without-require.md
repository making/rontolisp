# g12: qualified clojure.set/clojure.test calls work without require, oracle refuses

Measured 2026-10-14 (oracle `clj` 1.12.6.1673 vs `exec.jar` from this commit, one
qualified call per namespace, no `require`):

| namespace | oracle | rontolisp |
|---|---|---|
| `data`, `math`, `pprint`, `template`, `zip`, `xml`, `datafy`, `stacktrace`, `repl` | `ClassNotFoundException: <ns>` | `No such class: <ns>` (same shape) |
| `string`, `edn`, `walk`, `instant`, `uuid` | works (boot-loaded) | works (lowered directly / startup preload) |
| **`set`, `test`** | `ClassNotFoundException` | **works** (`(clojure.set/union)` answers, `(clojure.test/run-tests)` runs; `find-ns` still nil -- the namespace is never loaded) |

So the user's `clojure.data` refusal is correct behavior (a non-startup namespace
needs `require` first); but `set`/`test` verbs lower directly off the qualified
spelling with no load gate (`ClojureSetLowering`, and whatever answers the test
vars), an undocumented leniency in the permissive direction: runs here, dies
there. The stated rule ("Not a startup namespace: a qualified name without a
`require` is the oracle's class lookup failure", e.g. the `clojure.math`
passage) says these should refuse.

Decide in the item: refuse like the oracle (gate the direct lowerings on load
state, or route unknown-load qualified heads through the class failure), or keep
and document as a leniency alongside the unquoted-libspec / odd-cond-arm ones
in "Deviations". Audit every directly-lowered namespace for the same hole first
(`string`/`edn` coincide with the oracle's boot set and stay as they are either
way; `math` already refuses).

Acceptance:
- the matrix above re-runs green-oracle-identical on the interpreter and the JVM
  (new `clojure-spec.yaml` case per decided behavior + unit pin on the refusal
  wording or the leniency).
- `.kb` updated ("Namespaces and project files" startup passage and/or
  "Deviations" leniencies).
- full suite green (merge `origin/develop` right before the run, one maven run).

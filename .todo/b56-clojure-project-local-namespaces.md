# b56. project-local namespaces: `require`/`use` of files under a source root

Difficulty: High

Corpus programs split across files
(`(ns examples.chat)`, then `(ns examples.concurrency (:require [examples.chat :as c]))`)
stop at `unknown namespace: examples.chat` -- only `clojure.string` and
`clojure.java.io` resolve today. Multi-file programs are the baseline shape for
real Clojure code.

## Design questions to settle first

- Source root discovery: the deps.edn `:paths` convention (`src`, `test`) vs an
explicit `--source-path` flag; a name `examples.chat` maps to
`<root>/examples/chat.clj`.
- Each required file lowers through the same Clojure front end; the session
pre-scan must extend across files so forward/qualified refs resolve
(`c/naive-add-message`, `examples.macros/bench`).
- `defonce`/reload semantics on a second `require` (the oracle keeps the first).
- What `use` / `:refer :all` exports (every public `def`/`defn` name).

## Oracle

```bash
mkdir -p /tmp/nsdemo/src/demo && cd /tmp/nsdemo
printf '(ns demo.lib) (defn f [x] (inc x))\n' > src/demo/lib.clj
printf '(ns demo.main (:require [demo.lib :as l])) (println (l/f 1))\n' > src/demo/main.clj
clj -M -Sdeps '{:paths ["src"]}' -M src/demo/main.clj    ;# prints 2
```

Also pin: alias call, `:refer [...]`, `use` + `:only`, qualified var
`demo.lib/f` in value position, and the `ClassNotFoundException` wording for a
missing file.

## Acceptance

- The multi-file corpus programs (chat, concurrency, wallingford, sequences,
the macros chain) lower and run with outputs matching the oracle.
- Pinned in `ClojureInteropTest`/`ClojureSessionTest` (JVM legs) plus a
resolution-only `clojure-spec.yaml` case if the shape is backend-clean.

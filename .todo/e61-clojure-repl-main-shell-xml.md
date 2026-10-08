# e61. Clojure: `clojure.repl`, `clojure.main`, `clojure.java.shell`, `clojure.xml`

Difficulty: Medium

These clojure.jar namespaces are refused as not built in. Across the 72 measured jars
(`.kb/clojure-frontend.md`, "clojure.jar namespaces"): `clojure.repl` 1, `clojure.xml` 1
(clj-http's `parse`), `clojure.java.shell` 1 (babashka process's `sh`), `clojure.main` 0.

## What decides each

- `clojure.repl`: `doc` reads var metadata (a `def`'s docstring is recorded here), `dir`/
  `apropos`/`find-doc` need `ns-publics`/`ns-interns`/`all-ns`, `source` needs the
  definition's text (not kept at run time), `pst` is `clojure.stacktrace`'s with no frames,
  `demunge` is string work.
- `clojure.xml/parse`: a portable XML parser answering `{:tag :attrs :content}` maps, or a
  refusal naming the host it needs.
- `clojure.java.shell/sh`: a process API exists on which backends? Refuse where none.
- `clojure.main`: `repl`, `demunge`, `ex-triage`/`ex-str` (error reports, which the
  `clojure` REPL here prints its own way).

## Plan

1. Decide per namespace: ship (Clojure source over what exists) or a refusal that names the
   reason (`ClojureBuiltinNamespaces` left-out words), with the measurement behind it.
2. clojure-spec lines for what ships; `doc/*/clojure/reference/namespaces.md`.

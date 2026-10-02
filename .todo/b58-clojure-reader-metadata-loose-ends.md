# b58. reader/metadata loose ends: `#^`, record literals `#ns.Rec{...}`, `ns` attr-map

Difficulty: Medium

Three small refusals measured 2026-10-02 (CLI, corpus programs):

1. `unsupported reader form #^` -- the legacy metadata reader macro, spelled
`#^{...}`/`#^kw`; reads exactly like `^`. Two corpus files use it.
2. A record value prints as the wrapper list today; the oracle prints and reads
`#user.Rec{:a 1}` / `#Rec{...}` (simple and qualified). Add the read syntax
(simple + namespaced, map body -> record construction) and decide whether the
printer switches to it -- the wrapper print is a documented deviation; closing
it here makes records print like the oracle (`#examples.chat.Message{...}`
appears literally in corpus assertions).
3. `ns takes a name, not (with-meta ...)` -- an attr-map / docstring after the
`ns` name is refused; skip it like `def`/`defn` do.

## Oracle

```bash
clj -M -e "(defrecord P [a]) (println (pr-str (P. 1))) (read-string \"#P{:a 2}\")"
clj -M -e '(ns foo.bar "doc" {:author :a}) (println *ns*)'
```

`#P{...}` read-back equality with `(P. 1)` (record `=`), `#^` equivalence with
`^` on the same forms.

## Acceptance

- All three lower/read on every backend (reader + lowering, no host deps);
`#^` and the attr-map pinned in `ClojureReaderTest`/`ClojureLoweringTest`,
record read/print round-trip in `clojure-spec.yaml`.

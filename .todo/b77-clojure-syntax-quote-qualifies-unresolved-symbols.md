# b77. Syntax-quote qualifies even unresolved symbols with the defining namespace

Difficulty: Medium

Found closing b73 (2026-10-02): `macroexpand` now answers the mangled data, so 6 of
the 7 shcloj4 namespaces failing on expansion shape (`macros`, `macros/chain_1..5`)
print the oracle's bytes. `macros/bench_1` still fails on a second, independent gap:
the oracle's syntax-quote qualifies symbols with the DEFINING namespace even when
they resolve to nothing -- `clojure.core/let`, `examples.macros.bench-1/start`,
`java.lang.System/nanoTime` -- while ours leaves an unresolvable symbol bare
(`let`, `start`; documented deviation in `.kb/clojure-frontend.md`, syntax-quote row:
"a core name and an unresolved symbol stay bare").

Oracle probe (needs the corpus on the classpath):

```bash
clj -M -Sdeps '{:paths ["src" "test"]}' \
    -e "(require 'examples.macros.bench-1) (println (macroexpand-1 '(examples.macros.bench-1/bench 1)))"
```

## Acceptance

- A syntax-quoted unresolved symbol qualifies with the defining namespace like the
  oracle (`user/x` for a bare one -- already the case; extend to core names and
  otherwise-unresolved spellings without breaking referred/aliased resolution).
- `.kb/clojure-frontend.md` syntax-quote row updated; `macros/bench_1` re-run
  against the oracle; `clojure-spec.yaml` pins the qualification on all four backends.

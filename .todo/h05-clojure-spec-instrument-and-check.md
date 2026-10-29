# Clojure: spec's `instrument` and `check`

Difficulty: High

spec.alpha, `clojure.spec.gen.alpha` and `clojure.spec.test.alpha` load from their jars
(g90). On the interpreter, gen's `dynaload` (a computed `require` + `resolve`, lowered as the
program runs) reaches test.check from a project's `deps.edn`, so `(gen/sample (s/gen #{:a
:b}) 4)` answers. `s/fdef` registers. What stops the rest, measured 2026-10-29 against clj
1.12.6 with `{:deps {org.clojure/test.check {:mvn/version "1.1.3"}}}`:

1. `(stest/instrument 'user/f)` refuses: `alter-var-root of #'user/f is not supported: its
   calls do not go through the var`. A `defn`'s calls are direct unless the var is
   redefinable (`.kb/clojure-frontend.md`, "Vars and metadata", `with-redefs` and the reseed
   pass); `instrument` changes the root of a var it resolves as the program runs, so the
   lowering cannot see which. Decide what makes a `defn` redefinable here: every var an
   `s/fdef` names (the registry key is a quoted symbol the lowering sees), or every `defn` of
   a program that requires `clojure.spec.test.alpha`.
2. `s/exercise` of `int?` (and any spec over `large-integer`) crawls: test.check's
   `int-rose-tree` builds the whole shrink tree eagerly here (`.kb/clojure-frontend.md`,
   "Library probes", test.check, g33).
3. `stest/check` runs both, plus `quick-check` over the fspec's generator.
4. A compiled program refuses the run-time `require`/`resolve` (`.todo/h00` is the JVM's
   half; wasm keeps the refusal).

Done when the probe's `instrument`/`unstrument` lines and a seeded `check` of a small
`fdef` print the oracle's lines on the interpreter (and the JVM once `h00` lands).

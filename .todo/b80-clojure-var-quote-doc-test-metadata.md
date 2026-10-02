# b80. `var`/`#'`, `:doc`/`:test` metadata, `clojure.core/test` (introduction/exploring unblocked)

Difficulty: Medium

Measured 2026-10-02, oracle `clj` 1.12.6.1673 (`Clojure CLI version 1.12.6.1673`)
vs `target/rontolisp-0.1.0-SNAPSHOT-exec.jar` at `ec836db36`, from
`/tmp/opencode/shcloj4/code` (`-Sdeps '{:paths ["src" "test"]}'`):

- `(def x 1) (println #'x)` oracle `#'user/x` (a `clojure.lang.Var`);
  ronto `error: var is not supported yet: #'x needs a design`
  (`ClojureLowering.java:2166`).
- `(println String)` is b83; here `(test #'busted)` fails earlier at the head:
  `src/examples/exploring.clj:80-82`
  `(defn ^{:test (fn [] (assert (nil? (busted))))} busted [] "busted")`,
  `test/examples/test/exploring.clj:70`
  `(is (thrown? AssertionError (test #'busted)))` ->
  ronto `error: .../exploring.clj:70:31: unknown name: test`.
  Oracle: `test` is `clojure.core/test` (not `clojure.test/test`;
  `(contains? (ns-publics 'clojure.test) 'test)` is `false`),
  `(:doc (meta #'clojure.core/test))` finds "fn at key :test in var metadata",
  and `(test #'examples.exploring/busted)` throws
  `AssertionError: Assert failed: (nil? (busted))`.
- `test/examples/test/introduction.clj:32`
  `(:doc (meta #'hello))` expects
  `"Writes hello message to *out*. Calls you by username.\n  Knows if you have been here before."`
  Oracle `(keys (meta #'examples.introduction/hello))` is
  `(:arglists :column :doc :file :line :name :ns)`, `:arglists ([username])`.
  Ronto stops at the same `var is not supported yet` refusal, and even past it
  the docstring is dropped (`ClojureBindingLowering.java:64` skips it), so `:doc`
  would answer `nil`.
- Both namespaces are otherwise green past the refusal: `blank?`
  (`Character/isWhitespace` member-as-value), `struct`/`struct-map`,
  `ref`/`commute`, `lazy-cat` fibs, `hello-docstring` capture-by-value,
  `is-small-*`, `demo-loop`, `countdown`, `greeting` multi-arity all run.

## Plan

- `#'x` value design (the refused core): answer a var object carrying
  metadata + root. `meta` on it reads the existing `%clojure-meta-table`
  side table. Compose with the `%defN` redefinition scheme (first `hello`
  keeps the bare name, later ones take the suffix; `hello-docstring` keeps
  the first, `#'hello` sees the newest).
- Capture `def`/`defn` docstring as `:doc` (plus `:arglists`) into the side
  table at each definition; capture `^{:test fn}` (and `^{:doc ...}`) name
  metadata the same way (`readerMetaOf` currently only attaches to
  collection literals).
- Lower `clojure.core/test` as a call and as a value: look up `:test` in
  `(meta v)` and apply it (throwing the fn's exception through, so
  `thrown? AssertionError` holds). It is a `clojure.core` var, not a
  `clojure.test` refer, so wire it in `ClojureCoreLowering` beside the other
  core verbs.
- Full var identity/mutation (`alter-var-root`, `with-redefs`, non-dynamic
  `binding`) stays out; `var` special-form semantics beyond `#'` stays refused.

## Pin

- `ClojureLoweringTest`: `#'` lowered shape, docstring/`:test` capture,
  redefinition keeps newest meta.
- `clojure-spec.yaml` (all four backends): `(meta #'busted)` keys,
  `(test #'busted)` throws `AssertionError`, exact `:doc` string above,
  `hello-docstring` (first) vs `hello` (second) split.
- E2E: `examples.test.introduction` + `examples.test.exploring` drivers
  byte-identical to the oracle.
- Docs: `doc/*/clojure/reference/` var/test rows if the reference lists them.

# b05: Clojure state, dispatch, platform and reader literals

Difficulty: High

## Premise (measured, 2026-09-30)

`atom`/`swap!`/`deref`, `defmulti`/`defmethod`, protocols, `try`/`catch`,
interop, `ns` clauses, regex/char literals and radix numbers are absent. The
reader parses `@x`, `^meta`, backquote, `~` into marked lists the lowering
refuses (`.kb/clojure-frontend.md`, "Deviations"). Measured on the interpreter
(Clojure CLI 1.12 as oracle):

- `(def a (atom 1))` is `unknown name: atom` here; `@a`, `(swap! a inc)`,
  `(reset! a 2)`, `(compare-and-set! a 1 2)` likewise.
- `(defmulti area :shape)` / `(defmethod area :circle [m] 1)` are `unknown name`
  here; `defprotocol`/`defrecord`/`deftype`/`reify`/`extend-protocol` likewise.
- `(try (/ 1 0) (catch Exception e "caught"))` is `unknown name: try` here.
- `(.toUpperCase "hi")`, `String/upperCase`, `Class.` construction are `unknown
  name` here (no interop surface at all).
- `(ns foo (:require [clojure.string :as s]))` SKIPS now (the file-level skip was
  fixed with the spec -- a bare check that matched only the symbol `ns`), but
  the clauses are ignored: `(s/join "," ["a" "b"])` is still `unknown name`.
- `#"\d+"` is `unsupported reader form #` here, a regex there; `\a` is `unknown
  name` here, a character there; `0xFF`/`2r101`/`8r17` are `unknown name` here
  (the number parser reads decimal + `M`/`N` suffixes only), numbers there.
- `1M` lowers to a double (`1.0`), losing BigDecimal precision; `2N` strips to
  a fixnum (fine until it overflows); ratios with radix marks are identifiers.
- `^:k v`, `#'x`, backquote/`~`/`~@` lower to `with-meta`/`var`/`syntax-quote`/
  `unquote` heads the lowering refuses by name (`unknown name: with-meta`, ...);
  `#_`-discard and `;`/`,` comments are the only metadata-adjacent forms that
  work (pinned by the spec's reader case).

## Shape

- Each bullet is independently shippable; file follow-ups per bullet, not one
  big branch. Suggested order: reader literals (chars, radix ints, regex
  refusal-with-a-name) -> `ns` clauses (require/use/import as `load`?) ->
  `try`/`catch`/`throw` (over the condition system) -> atoms (over threads +
  the printer) -> multimethods/protocols (dispatch design) -> interop (against
  `java:` interop, `.kb/java-interop.md`).
- BigDecimal-as-double: decide exact (ratio-backed?) vs documented precision
  loss.

## Tests

- `clojure-spec.yaml` cases per bullet as each lands; `ClojureSpecE2eTest` on
  all four backends.

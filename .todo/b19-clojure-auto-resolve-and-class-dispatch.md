# Clojure: `::` auto-resolve + multimethod host-class dispatch (shcloj4)

Difficulty: Medium (reader + lowering only; dispatch still runs on the existing
`equal` table + hierarchy search -- no backend change).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

1. `(println ::checking)` -> rontolisp:
   `error: auto-resolved keywords are not supported yet: ::checking`.
   Oracle in `ns foo.bar` answers `:foo.bar/checking`. Corpus:
   `multimethods.clj` (`::checking`/`::savings`/`::account`,
   `derive ::savings ::account`, vector dispatch
   `[::basic ::checking]`), `hangman` specs namespace.
2. Host-class `defmethod` targets are rejected positionally:
   `(defmulti mm class) (defmethod mm String [s] ...)` ->
   `error: unknown name: String`.
   Oracle dispatches `(mm "a")` -> `"str:a"`. Corpus: `multimethods.clj`
   (`String`, `Number`, `nil`, `:default`, `java.util.Collection`,
   `clojure.lang.IPersistentVector`), `pi.clj` (`Number`, `java.util.Map`,
   `java.util.Map Number` vector), `my-class` (`nil`, `:default`).
3. Custom default: `(defmulti my-print class :default :everything-else)` +
   `(defmethod my-print :everything-else ...)` -- lowering only accepts
   `:default` as the default marker (check before implementing; corpus
   `multimethods.clj`).

## Design sketch

- `::kw` / `::alias/kw`: resolve against the current file's `ns` name
  (the seam knows the file; REPL/session tracks `*ns*` minimally) to the
  existing `(:C%KEYWORD "ns/kw")` wrapper -- opaque afterwards, so
  `derive`/`isa?`/dispatch compare whole spellings like today.
  `:a/b` stays opaque (existing row).
- `defmethod` dispatch values: accept the class-keyword kinds the
  `extend-protocol` row already targets (`String`/`Number`/`Long`/`Double`
  -> the `class`-keyword the dispatcher actually produces, `Map`/`Vector`/
  `Set`/`List`/`nil`/`Object`, dotted/`java.lang` names, imported names)
  plus literal vectors of the same (for `service-charge2/3` pairs).
  `class` as a dispatch fn already answers kind keywords -- map each
  method spelling onto the keyword it produces, document the
  `Long`/`Double`->`:number` merge (existing deviation).
- `:default` option: allow an arbitrary keyword value
  (`:everything-else`), stored per-multimethod like `:default` today.

## Acceptance

- `clojure-spec.yaml`: `::` in the file's own ns (+ `::a/b` with alias),
  `String`/`Number`/`nil`/`:default` methods over a `class` multi,
  `java.util.Map` method, vector dispatch pair, custom `:default` name,
  `prefer-method` still breaking ties -- identical on all four backends.
- Corpus pins: `multimethods.clj` interest/service-charge decisions,
  `derive ::savings ::account` + `isa?`, `pi.clj` `run-simulation` 3-method
  shape (with b20's interop where it overlaps, or stubbed dispatch fn).
- `.kb/clojure-frontend.md`: keyword row + `defmulti`/`defmethod` rows
  widened; deviations (`Long`/`Double` merge, vector-by-identity unless
  widened) stated; doc pages en+ja.

## Depends on

b16 (harness). Uses b18's `/`-split only if shared; otherwise independent
of b17/b20-b22.

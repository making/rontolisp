# Clojure: `defn ^:dynamic` is rebindable by `binding` (shcloj4 follow-up)

Difficulty: Low (one registration: the `def`/`defonce` path already does
it; no runtime, no backend change).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673; transcripts in
`.todo/artefacts/b16-shcloj4-inventory/` probes `gap-defn-dynamic`,
`green-def-dynamic`)

| Probe | oracle 1.12.6 | rontolisp today |
|---|---|---|
| `(defn ^:dynamic slow [n] (* n 2)) (binding [slow (memoize slow)] (slow 21))` | `42` | `error: binding slow needs a ^:dynamic var: only dynamic vars rebind` |
| `(def ^:dynamic s (fn [n] …)) (binding [s …] …)` | `42` | `42` (already green) |

Only `def`/`defonce` register `dynamicVars` (`ClojureLowering.java`:
`dynamicVars.add` appears in `def` and `defonceForm` only); `defn`
parses and drops the metadata, so a `^:dynamic` `defn` is silently
non-dynamic. `.kb/clojure-frontend.md`'s `binding` row currently claims
"a `^:dynamic` `defn` lowers to `defparameter`" -- correct the row in
this item (it does not today).

Corpus block: `concurrency.clj` `demo-memoize`
(`(binding [slow-double (memoize slow-double)] (calls-slow-double))`
over `(defn ^:dynamic slow-double …)`).

## Design sketch

- In the `defn` lowering, when the name datum carries `^:dynamic`
  (`nameIsDynamic`, same helper `def` uses): register `dynamicVars` and
  lower the definition through `defparameter` instead of the plain
  `defun`-plus-`setq` shape, so `binding` rebinds with dynamic extent.
  Arity-dispatch helpers stay direct calls.

## Acceptance

- `clojure-spec.yaml`: `binding` over a `^:dynamic` `defn` (value +
  memoized-value shapes), refusal without the marker intact, green on
  all four backends.
- Corpus pin: `concurrency.clj` `demo-memoize` shape (memoized call
  answered from the binding).
- `.kb/clojure-frontend.md` `binding` row corrected; doc pages en+ja if
  the row has user-facing surface.

## Depends on

b16 (harness). Independent of b17-b24.

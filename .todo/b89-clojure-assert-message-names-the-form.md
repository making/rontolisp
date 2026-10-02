# b89. `assert`'s message names the failed form

Difficulty: Low

Measured 2026-10-02, oracle `clj` 1.12.6.1673 vs the exec jar after b80:

- `(try (assert (nil? 1)) (catch AssertionError e (ex-message e)))`
  oracle `"Assert failed: (nil? 1)"`, ronto `"Assert failed"`.
- `(assert (nil? 1) "m")` oracle `"Assert failed: m\n(nil? 1)"`,
  ronto `"Assert failed: m"`.
- Lowering: `ClojureFnLowering.assertOf` builds the message without the form.
  Visible through `clojure.core/test` (b80): a `:test` fn's failed assert
  reports without its form.

## Plan

- The oracle's `(str "Assert failed: " (pr-str 'x))` /
  `(str "Assert failed: " message "\n" (pr-str 'x))`: the form as quoted data
  through the existing `pr-str` lowering, only in the failure branch, so the
  passing path costs nothing. Measure the wasm size delta of one `assert`.

## Pin

- `clojure-spec.yaml` (all four backends): both messages above via `ex-message`.

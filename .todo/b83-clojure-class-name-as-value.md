# b83. Class names as values (`String`) plus `.getClass`

Difficulty: Low

Measured 2026-10-02, oracle `clj` 1.12.6.1673 vs exec jar at `ec836db36`:

- `(println String)` oracle `java.lang.String` / ronto
  `error: unknown name: String`.
- `examples.test.interop:39` `(is (= String (poor-class-available? "java.lang.String")))`,
  `examples.test.multimethods:29` (`String` as an `are` expected value) ->
  whole-file refusals; zero tests run.
- This is "class as a VALUE", not class dispatch: `(defmethod m String ...)`
  over `(defmulti m class)`, `(instance? String "a")` -> `true`, and
  `(make-array String 3)` already work (`ClojureDispatchLowering`
  `DISPATCH_CLASS_KEYWORDS`, `instanceOf`, `makeArrayOf`).
- Second half: `(my-class "foo")` (`(.getClass x)` default method) answers
  `Unhandled condition: java:call expects a java object as the first
  argument, got "foo"` (Lisp strings never reach `java:` by design;
  `stringMethod` has no `getClass` arm). Note `(class "a")` is `:string`
  here by design; `my-class` needs `.getClass`, not `class`.
- Caveat: `test-describe-class` also asserts
  `(thrown? IllegalArgumentException ...)` / `(thrown? ClassCastException ...)`
  (`#^Class` hints are dropped); those two assertions may need the
  host-exception mapping and can stay failing after the positive shapes pass.

## Plan

- In `ClojureLowering.atom()`'s unknown-name branch
  (`ClojureLowering.java:3162-3187`): when the name is classlike
  (`ClojureNamespaceLowering.isClasslike`/`resolveClass`: imported,
  `java.lang`, dotted, capitalized) and not a program var, emit the class
  object via the existing static-call shape (the exact oracle value, i.e.
  what `(Class/forName "<resolved>")` answers).
- Add a `getClass` arm to `stringMethod`
  (`ClojureInteropLowering.java:1094+`) returning the same class-object form
  for the mapped string spellings (`String`/`CharSequence` ->
  `java.lang.String`; other core classes only if cheap).
- Interpreter + JVM only; wasm keeps the established `java:` call-time
  refusal (`ClojureWasmInteropRefusalTest` pattern).

## Pin

- `ClojureInteropTest` (interpreter + JVM): bare `String` value,
  `(= String (Class/forName "java.lang.String"))`, `(.getName String)`,
  `(.getClass "s")`; plus a wasm-refusal case in
  `ClojureWasmInteropRefusalTest`.
- E2E: `examples.test.interop` + `examples.test.multimethods` drivers
  (positive shapes; the two exception assertions tracked explicitly).

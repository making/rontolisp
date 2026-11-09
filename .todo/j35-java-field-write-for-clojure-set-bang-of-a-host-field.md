# `java:` has no field write, so Clojure `(set! (.f host-obj) v)` refuses

Difficulty: Medium

Since j24, a Clojure field-form `set!` (`(.f x)`, `(.-f x)`, `(. x f)`) is decided when it runs
(`ClojureProtocolLowering.hostFieldSetOf`): a fn's `__methodImplCache` and every Lisp value
answer like the oracle, but a host object's field and a static `(. Class f)` are the
`UnsupportedOperationException` `set! of a host field is not supported yet`, because the
`java:` surface has only `java:field` (a read).

## Repro (interpreter, JVM class)

```clojure
(def p (java.awt.Point. 1 2))
(prn (set! (.-x p) 5) (.-x p))
(prn (set! (. p y) 7) (.-y p))
```

- oracle: `5 5` / `7 7` (Reflector.setInstanceField: `boxArg` narrows a Long to the int field,
  answers the value)
- here: `set! of a host field is not supported yet: the java: surface has no field write`

## Plan

- A `java:` field write (a new operator, or `java:field` with a value): the interpreter
  (`eval/JavaInterop`), the JVM bridge (`JavaBridgeTemplate`) and direct sites
  (`JavaSiteResolver`, `JvmJavaDirectSites`), the native binary's class-file resolution, the
  operator lists (`LispNames.JAVA_OPERATORS_QUALIFIED`) and `.kb/java-interop.md` / user docs.
  The value converts to the field's type as the oracle's `boxArg` does (a number narrowed to a
  primitive field); a final field is the oracle's `IllegalAccessException`; wasm keeps the
  call-time refusal.
- `hostFieldSetOf`'s host arm calls it; a static field (`(set! (. Class f) v)`,
  `(set! Class/f v)`) likewise.

## Done when

The repro prints the oracle's lines on the interpreter and the JVM class, as a clojure-spec case
with wasm legs skipped (or a host test), and the KB "Java interop" `set!` bullet is updated.

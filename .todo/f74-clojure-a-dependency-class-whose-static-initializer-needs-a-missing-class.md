# f74. Clojure: a dependency's class whose static initializer needs a missing class

Difficulty: Medium

Found beside f65 (`.kb/clojure-frontend.md`, "Classes compiled against
`clojure.lang`", 2026-10-13): f65 converts `NoClassDefFoundError` at a `java:` site
into the catchable `Cannot link class C: missing class M` refusal, but a class that
Enum-like fails later -- static fields of the missing type, as in
`sci.impl.FISupport` (`private static final IPersistentSet ...`, one of six
field-only classes in the census) -- initializes with `ExceptionInInitializerError`,
which still propagates raw on the interpreter and the JVM (a JVM crash for an
uncaught one, not a Clojure exception a program could catch).

## What decides the design

- Whether an `ExceptionInInitializerError` whose cause chain holds a
  `NoClassDefFoundError` converts to the same refusal (naming the missing class from
  the cause, the site's class from the site): the class did start linking, so the
  wording stays honest, and the cause chain is the only thing distinguishing it from
  a genuinely broken initializer, which must keep propagating as it does.
- The census's field-only classes (`sci.impl.FISupport`, one `hawk`, two `selmer`,
  two `shadow-cljs`) as the pinning set: construction and static-field reads refuse
  catchably on the interpreter and the JVM.

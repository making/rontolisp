# Object's methods on an interface-typed java: receiver are left to run time

Difficulty: Medium

Found 2026-09-26 (a16). `(java:call (the (java:object "java.util.List") x) "toString")` -- or on a
`java:reify` / `java:proxy` object, whose receiver type is its interface -- is not resolved before
it runs: `class java.util.List has no public method toString`. `Class.getMethods()` of an
interface lists none of `Object`'s methods it does not redeclare, and both lookups
(`compiler/ReflectiveJavaClasses`, `codegen/jvm/JvmClassFileLookup`) follow it. Java's own view
(JLS 9.2) is that an interface has `Object`'s public methods as members, and `invokeinterface` of
`I.toString()` links (JVMS 5.4.3.4 falls back to `Object`). The call works at run time through the
bridge, but `--java-static` refuses it, and the interpreter and the compiler agree on leaving it.

Plan: for an interface receiver, add `Object`'s public non-final methods not already present to
the candidates of `JavaSiteResolver.resolveCall` (the one resolver both paths use), pin both
lookups' answers in `JvmClassFileLookupTest`, and a site calling `toString` / `hashCode` /
`equals` on a declared `java.util.List` and on a `java:reify` object in both backend tests
(direct call: `invokeinterface`).

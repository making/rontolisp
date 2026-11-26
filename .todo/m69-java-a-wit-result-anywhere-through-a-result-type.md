# m69. Java: a WIT `result` anywhere, through a `Result` type of rontolisp-java-api

Difficulty: Medium

A `@WitImport` interface takes a `result<T, E>` only as a method's answer: the ok arm is the
value, the error arm a thrown `WitException` (`doc/en/java/wit.md`, "The types"). A `result`
anywhere else -- in a `list`, an `option`, a record field, a variant case, a parameter -- is
refused at the declaration, and so is a tuple in an error arm, as no Java type is written for it
(`.kb/java-frontend.md`, "WIT imports").

The user's decision (2026-11-26): `rontolisp-java-api` gets a type a `result` maps to, so a
`result` is written wherever the WIT has one. A shape to start from:

```java
public sealed interface Result<T, E> {
    record Ok<T, E>(T value) implements Result<T, E> {}
    record Err<T, E>(E error) implements Result<T, E> {}
}
```

- A method's answer takes either spelling: `T` plus `WitException` as now, or `Result<T, E>`;
  the binding follows the declaration, so existing interfaces stay as they are.
- An arm carrying nothing is `Void`; a tuple error arm becomes writable (`Result<T, SomeRecord>`).
- Plain Java, no dependency, the same under `javac` (the library's rule).

Where: `JavaWitImports.Checker` (the type check, every nesting), `JavaWitImports.Writer` (the
binding's conversion between the boundary's envelope and `Ok`/`Err`, nested ones included), the
library, `doc/{en,ja}/java/wit.md`. Pinned by `JavaWitImportTest` (JDK, interpreter, JVM,
component, `--no-wasi`); extend its existing cases before adding one.

What to decide: the names (`Result`/`Ok`/`Err`, or names that do not clash with a program's
own), accessors and helpers worth giving (`map`, `orElseThrow` ...) against the library's
"only what rontolisp gives a program" rule.

m70 (`wit-export` of compound types) reuses this type for an exported `result`.

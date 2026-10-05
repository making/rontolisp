# d14. Clojure: `.substring` and `.charAt` of a string carry no `StringIndexOutOfBoundsException`

Difficulty: Low

A string receiver answers the mapped core operation, and its bound refusal names no class,
where `subs` already carries the oracle's (measured 2026-10-05 against clj 1.12.6, caught by
`Throwable` and shown as `[(class t) (.getMessage t)]`):

| form | oracle | interpreter | JVM | wasm |
|---|---|---|---|---|
| `(.substring "abc" 5)` | `StringIndexOutOfBoundsException` | `:java.lang.RuntimeException` | same | same |
| `(.charAt "abc" 5)` | `StringIndexOutOfBoundsException` | `:java.lang.IndexOutOfBoundsException` (`CHAR: The value 5 is not of type ...`) | `:java.lang.RuntimeException` (`index out of bounds`) | an empty line, no error |

Route `.substring` through the refusal family's `subseq` alias `subs` uses, and `.charAt`
through a checked read carrying the class, so all four backends agree. The wasm `.charAt` row
is the unchecked string index of `.todo/186`. Pin in clojure-spec.

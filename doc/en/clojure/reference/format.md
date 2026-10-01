# format

`(format fmt args...)`

Answers the Java-format string rendered: `%s` converts like `str` (`nil` spells
`"null"`, like `String/valueOf`), `%b` the boolean spelling, `%d`/`%x`/`%X`/
`%o` integers (checked), `%c` a character (checked), `%f` a float with optional
width and precision, `%%` and `%n` themselves. The format string must be
literal, since its directives translate at lowering time. Flags, `%e`/`%g`,
dates, hashes and anything else are named refusals instead of wrong answers.

```clojure
(println (format "%s=%d" :a 5)) ; :a=5
(println (format "%5s|%.2f" "ab" 3.14159)) ;    ab|3.14
```

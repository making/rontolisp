# format

`(format fmt args...)`

Answers the format string rendered as `java.util.Formatter` renders it: `%s`/`%S`
convert like `str`, `%b`/`%B` the boolean spelling, `%c`/`%C` a character,
`%d`/`%o`/`%x`/`%X` an integer (the octal and hexadecimal of a negative long in two's
complement), `%e`/`%E`/`%f`/`%g`/`%G` a double (rounded half up from the shortest
decimal it prints as), `%%` and `%n` themselves. Every flag (`-` `#` `+` space `0` `,`
`(`), width, precision and argument index (`%2$s`, `%<s`) is the oracle's, and `nil`
spells `null` under any conversion.

A malformed format string, a flag its conversion does not take, a missing argument or
one of the wrong class is the oracle's refusal (`MissingFormatWidthException`,
`IllegalFormatConversionException` ...) with its message, signalled after the
arguments are evaluated. The format string must be literal; `%h`, `%a` and the date
conversions `%t` are refused when the program is read.

```clojure
(println (format "%s=%d" :a 5)) ; :a=5
(println (format "%5s|%.2f" "ab" 3.14159)) ;    ab|3.14
(println (format "%%x%04x|%,d|%+.1e" 10 1234567 -0.5)) ; %x000a|1,234,567|-5.0e-01
(println (format "%2$s %1$s %<s" "a" "b")) ; b a a
```

# (scheme char)

Character classes, case mappings and case-insensitive comparisons, by Unicode. The properties and mappings are the JDK's, so every backend answers alike.

| Name | Example | Result |
|---|---|---|
| `char-alphabetic?` | `(char-alphabetic? #\a)` | `#t` |
| `char-numeric?` | `(char-numeric? #\7)` | `#t` |
| `char-whitespace?` | `(char-whitespace? #\space)` | `#t` |
| `char-upper-case?` | `(char-upper-case? #\A)` | `#t` |
| `char-lower-case?` | `(char-lower-case? #\a)` | `#t` |
| `digit-value` | `(digit-value #\3)` | `3` |
| `char-upcase` | `(char-upcase #\a)` | `#\A` |
| `char-downcase` | `(char-downcase #\A)` | `#\a` |
| `char-foldcase` | `(char-foldcase #\A)` | `#\a` |
| `char-ci=?` | `(char-ci=? #\a #\A)` | `#t` |
| `char-ci<?` | `(char-ci<? #\a #\B #\c)` | `#t` |
| `char-ci>?` | `(char-ci>? #\c #\B #\a)` | `#t` |
| `char-ci<=?` | `(char-ci<=? #\a #\A #\b)` | `#t` |
| `char-ci>=?` | `(char-ci>=? #\b #\B #\a)` | `#t` |
| `string-ci=?` | `(string-ci=? "Hello" "hELLO")` | `#t` |
| `string-ci<?` | `(string-ci<? "apple" "BANANA")` | `#t` |
| `string-ci>?` | `(string-ci>? "b" "A")` | `#t` |
| `string-ci<=?` | `(string-ci<=? "a" "A" "b")` | `#t` |
| `string-ci>=?` | `(string-ci>=? "b" "B" "a")` | `#t` |
| `string-upcase` | `(string-upcase "Hello")` | `"HELLO"` |
| `string-downcase` | `(string-downcase "HELLO")` | `"hello"` |
| `string-foldcase` | `(string-foldcase "Straße")` | `"strasse"` |

# Regular expressions

A `#"..."` literal reads to a pattern value (printed `#"..."`, spelled bare by
`str`); `re-pattern` compiles a string and answers a pattern back. Patterns
compare by identity, like the oracle. `re-find`, `re-seq`, `re-matches` and the
`re-matcher`/`re-groups` loop run over one spliced engine, a port of
`java.util.regex`'s parser and matcher, so every backend answers as the oracle does.

| Name | Example | Result |
|---|---|---|
| `re-pattern` | `(re-pattern "a+")` | `#"a+"` |
| `re-find` | `(re-find #"a+" "aaab")` | `aaa` |
| `re-seq` | `(re-seq #"a+" "aaabbaa")` | `(aaa aa)` |
| `re-matcher` | `(do (def m (re-matcher #"\w+" "hi there")) (re-find m))` | `hi` |
| `re-groups` | `(do (def g (re-matcher #"(\w+)@(\w+)" "user@host")) (re-find g) (re-groups g))` | `[user@host user host]` |
| `re-matches` | `(re-matches #"a+" "aaa")` | `aaa` |

The syntax is `java.util.regex`'s: escapes and classes (unions `[a[bc]]`,
intersections `[a-z&&[^aeiou]]`, `\h` `\v` `\R`, `\x{h...h}`), groups (capturing,
`(?:...)`, named `(?<name>...)` with `\k<name>`, atomic `(?>...)`), lookahead `(?=...)`
`(?!...)` and lookbehind `(?<=...)` `(?<!...)` (with a maximum length, as the oracle
requires), back references, greedy, reluctant and possessive quantifiers, and the inline
flags `i` `m` `s` `x` `d` `u` -- `(?i)` holds to the end of the enclosing group,
`(?i:...)` inside it, `(?-i)` turns one off. A malformed pattern is the oracle's
`PatternSyntaxException` with its message. `clojure.string/split`, `replace` and
`replace-first` take patterns too (plain strings stay literal); a string replacement
expands `$n` and `${name}` as `Matcher.appendReplacement` does (`re-quote-replacement`
quotes them), a function applies to the match through `str`.

What the engine cannot match signals `unsupported regex`: `\p{...}` and `\P{...}`,
`\N{...}`, `\X`, `\b{g}`, `\G`, `\d` `\s` `\w` `\b` under `(?U)`, and a character class
under `(?c)`.

Deviations: the reader keeps backslashes verbatim, like the oracle (`#"\\d"` reads a
literal backslash followed by `d`, so classes spell with single backslashes); an unknown
ASCII-letter escape, a short or non-hex `\u`/`\x`, a `\0` with no octal digit behind it and
a lone `\E` are read-time refusals, any other malformed pattern is refused where it is
compiled; `re-seq` answers a strict list (the oracle's lazy prints the same); indices count
code points, not UTF-16 units; a non-spacing mark after a letter is no word character for
`\b`; a replacement function renders through `str`, so `nil` answers `""` where the oracle
throws.

```clojure
(println (re-find #"\d+" "abc123"))  ; 123
(println (re-seq #"(?<=\$)\d+" "$10 20 $30"))  ; (10 30)
(println (re-find #"(?i)hello" "Say HELLO"))  ; HELLO
(println (clojure.string/replace "2026-10" #"(?<y>\d+)-(?<m>\d+)" "${m}/${y}"))  ; 10/2026
```

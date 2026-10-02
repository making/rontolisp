# Regular expressions

A `#"..."` literal reads to a pattern value (printed `#"..."`, spelled bare by
`str`); `re-pattern` compiles a string and answers a pattern back. Patterns
compare by identity, like the oracle. `re-find`, `re-seq`, `re-matches` and the
`re-matcher`/`re-groups` loop run over one spliced backtracking runtime, so
every backend answers alike.

| Name | Example | Result |
|---|---|---|
| `re-pattern` | `(re-pattern "a+")` | `#"a+"` |
| `re-find` | `(re-find #"a+" "aaab")` | `aaa` |
| `re-seq` | `(re-seq #"a+" "aaabbaa")` | `(aaa aa)` |
| `re-matcher` | `(do (def m (re-matcher #"\w+" "hi there")) (re-find m))` | `hi` |
| `re-groups` | `(do (def g (re-matcher #"(\w+)@(\w+)" "user@host")) (re-find g) (re-groups g))` | `[user@host user host]` |
| `re-matches` | `(re-matches #"a+" "aaa")` | `aaa` |

Supported: literals and escapes (`\w` `\W` `\s` `\S` `\d` `\D`, `.` except
`\n`/`\r`, `^` `$` `\b` `\B` `\A` `\z`, `\t` `\n` `\r` `\f` `\a` `\e`, `\xhh`
`\uhhhh` `\cX` octal, `\Q..\E`, classes with ranges and negation,
backreferences), groups (`(...)`, `(?:...)`), `|` alternation, and greedy,
reluctant and possessive `*` `+` `?` `{n,m}`. `clojure.string/split`, `replace`
and `replace-first` take patterns too (plain strings stay literal); a string
replacement interpolates `$` groups (`$0` the whole; `re-quote-replacement`
quotes them), a function applies to the match through `str`. Anything else --
lookarounds, named groups, inline flags, POSIX classes, `&&`, `\G` -- signals
`unsupported regex` instead of answering wrongly.

Deviations: the reader halves `\\` runs, so `#"\\d"` reads as the digit class
where the oracle reads a literal backslash followed by `d` (spell classes with
single backslashes); `re-seq` answers a strict list (the oracle's lazy prints
the same); `split` answers a seq, never a vector; `.` excludes only `\n`/`\r`,
`$` matches at the end or just before one final newline, and `\s`/`\b` are
ASCII; a replacement function renders through `str`, so `nil` answers `""`
where the oracle throws.

```clojure
(println (re-find #"\d+" "abc123"))  ; 123
(println (re-seq #"a+" "aaabbaa"))  ; (aaa aa)
(println (clojure.string/split "a b  c" #"\s+"))  ; (a b c)
```

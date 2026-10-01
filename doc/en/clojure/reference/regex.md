# Regular expressions

A `#"..."` literal reads to a pattern value (printed `#"..."`, spelled bare by
`str`); `re-pattern` compiles strings and answers patterns back. Patterns
compare by identity, like the oracle. `re-find`, `re-seq`, `re-matches` and the
`re-matcher`/`re-groups` loop run over one spliced backtracking runtime, so
every backend answers alike; `clojure.string/split`, `replace` and
`replace-first` take patterns too (plain strings stay literal).

Supported: literals and escapes (`\w` `\W` `\s` `\S` `\d` `\D`,
`.` except `\n`/`\r`, `^` `$` `\b` `\B` `\A` `\z`, `\t` `\n` `\r` `\f` `\a` `\e`,
`\xhh` `\uhhhh` `\cX` octal, `\Q..\E`, classes with ranges and negation,
backreferences), groups (`(...)`, `(?:...)`), `|` alternation, greedy,
reluctant and possessive `*` `+` `?` `{n,m}`. A string replacement interpolates `$` groups
(`$0` the whole; `re-quote-replacement` quotes them); a function applies to the
match through `str`. Anything else -- lookarounds, named groups, inline flags,
POSIX classes, `&&`, `\G` -- signals `unsupported
regex` instead of answering wrongly.

Deviations: `re-seq` answers a strict list (the oracle's lazy prints the same);
`split` answers a seq, never a vector; `.` excludes only `\n`/`\r`, `$`
matches at the end or just before one final newline, and `\s`/`\b` are ASCII; a
replacement function renders through `str`, so `nil` answers `""` where the
oracle throws.

```clojure
(println (re-find #"\\d+" "abc123")) ; 123
(println (clojure.string/split "a b  c" #"\\s+")) ; (a b c)
```

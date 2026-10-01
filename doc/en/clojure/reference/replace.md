# clojure.string/replace

`(clojure.string/replace s match replacement)`

Answers `s` with every occurrence of `match` replaced by `replacement`: a
pattern value (`#"..."`, `re-pattern`) matches by pattern, a literal string or
character matches literally (a character pairs with a character). A string
replacement over a pattern interpolates `$` groups (`$1`, `$0` the whole;
`re-quote-replacement` quotes them); anything else applies to the match through
`str`. Reached also as `alias/var` or a referred bare `replace`; works as a
function value too.

Deviation: `match` is a pattern value, a literal string or a character -- a
string never compiles to a pattern.

```clojure
(println (clojure.string/replace "aaa" "a" "b")) ; bbb
(println (clojure.string/replace "aaa" \a \b)) ; bbb
(println (clojure.string/replace "aaa" "." "b")) ; aaa
(println (clojure.string/replace "aaa" #"a" "b")) ; bbb
(println (clojure.string/replace "abc123def" #"(\\d+)" "<$1>")) ; abc<123>def
```

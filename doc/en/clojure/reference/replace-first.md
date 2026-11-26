# clojure.string/replace-first

`(clojure.string/replace-first s match replacement)`

Answers `s` with only the first occurrence of `match` replaced: a pattern value
(`#"..."`, `re-pattern`) matches by pattern, a literal string or character
matches literally. A string replacement over a pattern interpolates `$` groups;
anything else applies to the match through `str`. Works as a function value too.

Deviation: `match` is a pattern value, a literal string or a character -- a
string never compiles to a pattern.

```clojure
(println (clojure.string/replace-first "aaa" "a" "b")) ; baa
(println (clojure.string/replace-first "aaa" "a+" "b")) ; aaa
(println (clojure.string/replace-first "aaa" #"a+" "b")) ; b
```

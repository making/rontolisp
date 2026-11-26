# comment

`(comment expr...)`

Answers `nil` without evaluating anything -- it lowers to nothing. For the reader-level
`;` line comment and `#_` discard, see [Syntax](syntax.md).

```clojure
(println (comment (undefined-thing 1))) ; nil
```

# char-downcase

`(char-downcase char)`

Returns the lowercase of `char` by the Unicode simple mapping, or `char` itself when it has none.

```scheme
(char-downcase #\A) ; => #\a
(char-downcase #\Σ) ; => #\σ
```

# char-foldcase

`(char-foldcase char)`

Returns `char` folded by Unicode simple case folding: usually its lowercase, but `ς` folds to `σ`, `İ` and `ı` do not fold, and a Cherokee letter folds to its capital. Gauche folds Cherokee by tables older than Unicode 8.

```scheme
(char-foldcase #\A) ; => #\a
(char-foldcase #\ς) ; => #\σ
```

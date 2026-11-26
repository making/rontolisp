# char->integer

`(char->integer char)`

`char` の Unicode コードポイントを正確な整数として返します。

```scheme
(char->integer #\A) ; => 65
(char->integer #\space) ; => 32
```

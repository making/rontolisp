# character

`(character designator)`

文字指定子が表す文字を返します。文字はそれ自身を、ちょうど 1 文字の文字列と、名前がちょうど 1 文字のシンボルはその文字を表します。それ以外は `type-error` を通知します。

```lisp
(list (character "a") (character #\b) (character 'c)) ; => (#\a #\b #\C)
```

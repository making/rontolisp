# if

`(if test consequent)` `(if test consequent alternate)`

`test` を評価し、その値が `#f` 以外なら `consequent` を、そうでなければ `alternate` を評価します。空リストや `0` は真です。`alternate` がなく test が偽のときの値は未規定値で、`#!unspecific` と書き出され、REPL は何も表示しません。

```scheme
(if (> 3 2) 'yes 'no) ; => yes
(if '() 'true 'false) ; => true
(list (if #f #f)) ; => (#!unspecific)
```

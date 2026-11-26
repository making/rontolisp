# symbol?

`(symbol? obj)`

`obj` がシンボルなら `#t`、それ以外なら `#f` を返します。空リストと真偽値はシンボルではありません。

```scheme
(symbol? 'foo) ; => #t
(symbol? "foo") ; => #f
(symbol? '()) ; => #f
```

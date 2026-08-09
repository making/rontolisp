# parse-namestring

`(parse-namestring thing &optional host defaults)`

名前文字列をパースしてパス名を返し、第 2 値としてパースが止まった位置を
返します。ライト版: rontolisp の名前文字列にはパース対象のホスト成分が
ないため、文字列全体が名前文字列になり、第 2 値はその長さ、`host` /
`defaults` は受け付けて無視します。パス名引数はそれ自身を返します。

```lisp
(parse-namestring "d/a.txt")   ; => #P"d/a.txt"
```

`(multiple-value-list (parse-namestring "d/a.txt"))` は `(#P"d/a.txt" 7)` です。

## バックエンド対応

全 4 バックエンド -- rontolisp ソースの 1 つの定義が、参照されたときに
プログラムへスプライスされます。

# namestring

`(namestring pathname)`

パス名指定子の名前文字列を返します。パス名値は保持している名前文字列に
展開され、文字列はすでに名前文字列なのでそのまま通り、それ以外の値は
パス名指定子ではないのでシグナルを発生させます。移植性のあるコードは、
パス名オブジェクトを表示・連結したり Lisp の外へ渡す前に文字列へ変換する
ために呼びます。

`uiop:namestring` も同じ関数を指します。本家 UIOP が Common Lisp の
`namestring` を再エクスポートしているのと同じです。`uiop:native-namestring`
も同じです (rontolisp の名前文字列はもともとホストの綴りです)。

```lisp
(namestring #P"/tmp/data.json")   ; => "/tmp/data.json"
```

`(namestring "/tmp/data.json")` は文字列そのものです。

## バックエンドサポート

4 バックエンドすべてです。rontolisp ソースで 1 つだけ定義されており、参照されたときに
プログラムへ差し込まれます。

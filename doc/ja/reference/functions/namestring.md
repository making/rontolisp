# namestring

`(namestring pathname)`

パス名の名前文字列を返します。rontolisp のパス名はパス名文字列そのものなので、文字列に
対しては恒等関数であり、それ以外の値はパス名指定子ではないのでシグナルを発生させます。
移植性のあるコードは、パス名オブジェクトを表示したり開いたりする前に文字列へ変換する
ために呼びますが、ここではその変換は済んでいます。

`uiop:namestring` も同じ関数を指します。本家 UIOP が Common Lisp の `namestring` を
再エクスポートしているのと同じです。

```lisp
(namestring "/tmp/data.json")   ; => "/tmp/data.json"
```

## バックエンドサポート

4 バックエンドすべてです。rontolisp ソースで 1 つだけ定義されており、参照されたときに
プログラムへ差し込まれます。

# coerce

`(coerce object result-type)`

`object` を指定されたシーケンス型に変換します。`result-type` はリテラル型 `'list`、`'vector`、`'string` のいずれかでなければなりません。リテラルでない場合やその他の型はコンパイル時/展開時エラーになります。`'string` を結果とするには文字のシーケンスが必要です。すでに要求された型の値はそのまま返されます。`coerce` は第一級の関数値ではありません (`#'coerce` は利用できません) ので、直接呼び出してください。

```lisp
(coerce '(1 2 3) 'vector) ; => #(1 2 3)
(coerce (vector 1 2 3) 'list) ; => (1 2 3)
(coerce "ab" 'list) ; => (#\a #\b)
(coerce '(#\a #\b) 'string) ; => "ab"
```

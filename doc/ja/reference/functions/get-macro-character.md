# get-macro-character

`(get-macro-character char &optional (readtable *readtable*))`

`char` のリーダーマクロ関数と、2番目の値としてそれが非終端かどうかを返します。マクロ文字でない文字には `nil` を返します。`readtable` の `nil` は標準リードテーブルを指します。標準のマクロ文字は標準の関数を返し、それはどのリードテーブルでも同じオブジェクトなので、別の文字に設定できます。標準の関数を呼び出すと、現在のリードテーブルがその文字に何を設定していても、その文字の標準の構文でデータを読みます。そのため、リーダーマクロ関数は自分が置き換えた標準の関数に処理を委ねられます。4つのバックエンドすべてで動作します。

```lisp
(list (functionp (get-macro-character #\()) (get-macro-character #\a)) ; => (T NIL)
```

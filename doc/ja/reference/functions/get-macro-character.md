# get-macro-character

`(get-macro-character char &optional (readtable *readtable*))`

`char` のリーダーマクロ関数と、2番目の値としてそれが非終端かどうかを返します。マクロ文字でない文字には `nil` を返します。`readtable` の `nil` は標準リードテーブルを指します。標準のマクロ文字は標準の関数を返し、それはどのリードテーブルでも同じオブジェクトなので、別の文字に設定できます。4つのバックエンドすべてで動作します。

```lisp
(list (functionp (get-macro-character #\()) (get-macro-character #\a)) ; => (T NIL)
```

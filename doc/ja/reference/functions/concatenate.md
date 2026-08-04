# concatenate

`(concatenate result-type &rest sequences)`

シーケンス引数を連結し、`result-type` の新しいシーケンスを 1 つ返します。サポートする結果型は 3 系統です: `'string`(`'simple-string` / `'base-string` も同じ)、`'list`(`'cons` も同じ)、`'vector`(`'simple-vector` / `'array` / `'bit-vector`、および `'(vector (unsigned-byte 8))` のような複合指定子も同じ。rontolisp のベクタは要素型を持たないため、要素型は無視されます)。どの系統もシーケンスの要素を走査するため、引数はリスト・ベクタ・文字列を自由に混在させられます。`'string` 系も任意の文字シーケンスを受け付け、`nil`(空リスト)も渡せます。ユーザー定義の `deftype` 名を `result-type` に書くと、登録された展開を通じていずれかの系統に解決されます。シーケンスを 1 つも渡さない場合はその型の空シーケンスを返します。結果は常に新しいシーケンスで、どの引数とも構造を共有しません。コンパイル系バックエンドでは `result-type` をリテラルの引用指定子として書く必要があります(インタプリタは実行時に計算された指定子も受け付けます)。

```lisp
(concatenate 'string "foo" "bar") ; => "foobar"
(concatenate 'string "a" '(#\b #\c) nil "d") ; => "abcd"
(concatenate 'list '(1 2) "ab" #(3)) ; => (1 2 #\a #\b 3)
(concatenate 'vector '(1 2) #(3)) ; => #(1 2 3)
(concatenate '(vector (unsigned-byte 8)) #(1) #(2 3)) ; => #(1 2 3)
(progn (deftype octet-vector () '(simple-array (unsigned-byte 8) (*)))
       (concatenate 'octet-vector #(1) #(2 3))) ; => #(1 2 3)
```

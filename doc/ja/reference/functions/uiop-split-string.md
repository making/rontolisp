# uiop:split-string

`(uiop:split-string string &key max separator)`

`string` を `separator` シーケンス(文字列または文字のリスト。既定はスペースとタブ)の
いずれかの文字で分割し、部分文字列のリストを返します。セマンティクスは上流 UIOP の
とおりです: 走査は右から左へ進むため、`:max` は分割数を制限しつつ「分割されなかった
残り」を先頭要素として残します。空文字列は `("")` になります。sxql はドット区切りの
カラム名を `(uiop:split-string name :separator ".")` でトークン化します。

```lisp
(uiop:split-string "a.b.c" :separator ".")   ; => ("a" "b" "c")
```

```lisp
(uiop:split-string "a.b.c.d.e" :max 3 :separator ".")   ; => ("a.b.c" "d" "e")
```

```lisp
(uiop:split-string "a-b_c" :separator "-_")   ; => ("a" "b" "c")
```

## バックエンドサポート

4 つすべてのバックエンドで動作します: rontolisp 自身で書かれたプレリュード定義であり、
使用時にプログラムへ組み込まれてコンパイルされます。

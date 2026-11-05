# find-all-symbols

`(find-all-symbols symbol &optional package)`

`symbol` と名前が `string=` で等しい、重複のない全シンボルのうち
`package` から到達可能なもの -- 省略時は全登録パッケージが対象である。各結果はコードが綴る形
(`cl` メンバーは裸で読める)であるため、`member`
による検査が機能する。キーワードが列挙されることはない: intern
テーブルを持たないため、歩けるキーワード宇宙が存在しない。

プログラムが読んだだけのシンボルも、全バックエンドで対象になる。コンパイルされたプログラムは実行前に自身の全体を読んでいるため、
ソースの後方で読む名前も見つける。インタプリタが見つけるのは、その時点までに読んだものである。

```lisp
(find-all-symbols 'car) ; => (CAR)
(find-all-symbols 'car :cl-user) ; => (CAR)
```

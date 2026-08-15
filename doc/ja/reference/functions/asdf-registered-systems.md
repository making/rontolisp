# asdf:registered-systems

`(asdf:registered-systems)`

登録済みのすべてのシステムの小文字化された名前を、登録順で返します。
[`asdf:defsystem`](asdf-defsystem.md) や解析済み `.asd` が宣言したシステム、導出済みの
package-inferred サブシステム、および読み込まれた組み込みシムシステムが含まれます。

```lisp
(asdf:defsystem :demo-a :components ((:file "main")))
(asdf:defsystem :demo-b :components ((:file "main")))
(asdf:registered-systems) ; => ("demo-a" "demo-b")
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。インタプリタは生きたレジストリから答え、
コンパイルされたプログラムはコンパイル時に焼き込まれたレジストリから答えます。

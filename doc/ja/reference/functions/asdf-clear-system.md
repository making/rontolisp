# asdf:clear-system

`(asdf:clear-system system)`

システムを忘れます。以後 `asdf:registered-system` は nil を返し、次の
[`asdf:find-system`](asdf-find-system.md) は探索し直して新しいインスタンスを返します。その後の
ロードはもう一度ロードします。登録されていない名前を指定してもエラーにはなりません。`t` を返します。

```lisp
(asdf:defsystem :cleared :components ())
(asdf:clear-system :cleared)
(asdf:registered-system :cleared) ; => NIL
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。コンパイル済みプログラムはスプライスしたものを保持するので、
クリアの後の `find-system` は同じレコードから新しいインスタンスを返し、ロードは何もロードしません。

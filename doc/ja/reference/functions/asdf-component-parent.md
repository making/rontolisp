# asdf:component-parent

`(asdf:component-parent component)`

リーダー: コンポーネントの親コンポーネントを返します。その子を持つシステムまたは
モジュールで、システム自身では `nil` です。

```lisp
(asdf:defsystem :demo-par :components ((:file "main")))
(asdf:component-parent (asdf:find-system :demo-par)) ; => NIL
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。
[`asdf:component-system`](asdf-component-system.md) はこれをシステムまでたどります。

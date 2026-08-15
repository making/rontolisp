# asdf:component-system

`(asdf:component-system component)`

[`asdf:component-parent`](asdf-component-parent.md) をたどって、コンポーネントが属する
システムを返します。システム自身は自分を返します。

```lisp
(asdf:defsystem :demo-cs :components ((:file "main")))
(let ((sys (asdf:find-system :demo-cs)))
  (eq (asdf:component-system (car (asdf:component-children sys))) sys)) ; => T
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。

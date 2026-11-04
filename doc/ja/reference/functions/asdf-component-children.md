# asdf:component-children

`(asdf:component-children parent)`

リーダー: システムまたはモジュールの子を、定義に書かれた順で返します。`:depends-on`
が順序を変えるときはロード順と一致しません。子はそのクラスのインスタンスです。
`:module` は `asdf:module`、`:file` は `asdf:cl-source-file`、`:static-file` は
`asdf:static-file` になります。package-inferred のサブシステムは本物の ASDF と同じく
`"lisp"` という名前の子を 1 つ持ちます。各子の
[`asdf:component-pathname`](asdf-component-pathname.md) は解決済みのパス、
[`asdf:component-parent`](asdf-component-parent.md) はその子を持つシステムまたは
モジュールです。ファイルは子を持たないので、ファイルに対する呼び出しはエラーを通知します。

```lisp
(asdf:defsystem :demo-ch
  :components ((:file "one") (:module "m" :components ((:file "two")))))
(mapcar (lambda (c) (asdf:component-name c))
        (asdf:component-children (asdf:find-system :demo-ch))) ; => ("one" "m")
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。

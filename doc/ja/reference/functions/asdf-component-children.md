# asdf:component-children

`(asdf:component-children parent)`

リーダー: 親コンポーネントの子をロード順で返します。システムの子はその
コンポーネントファイルで、**ファイルごとに 1 つの `asdf:cl-source-file`** です。
`:module` はネストしたインスタンスではなく、ファイル名へのパス接頭辞として現れます。
package-inferred のサブシステムはちょうど 1 つの子を持ちます。これは本物の ASDF の
形です。各子の [`asdf:component-pathname`](asdf-component-pathname.md) は解決済みの
ソースパス、[`asdf:component-parent`](asdf-component-parent.md) はそのシステムです。

```lisp
(asdf:defsystem :demo-ch
  :components ((:file "one") (:module "m" :components ((:file "two")))))
(mapcar (lambda (c) (asdf:component-name c))
        (asdf:component-children (asdf:find-system :demo-ch))) ; => ("one" "m/two")
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。

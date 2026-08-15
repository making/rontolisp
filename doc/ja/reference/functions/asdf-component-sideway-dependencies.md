# asdf:component-sideway-dependencies

`(asdf:component-sideway-dependencies system)`

リーダー: システムの `:depends-on` の名前を順に返します。package-inferred システムでは
コンポーネントファイル自身の `defpackage` から導出された名前で、サブシステム名も
含まれます。rove の `package-inferred-system-component-names` がプライマリの接頭辞で
フィルタするのはこの値です。

```lisp
(asdf:defsystem :demo-deps :depends-on ("demo-base") :components ((:file "main")))
(asdf:defsystem :demo-base :components ((:file "base")))
(asdf:component-sideway-dependencies (asdf:find-system :demo-deps)) ; => ("demo-base")
```

## バックエンドサポート

4 つすべてのバックエンドで動作します。

# asdf:component-version

`(asdf:component-version component)`

リーダー: システムの `defsystem` が宣言した `:version` 文字列を返します。宣言が
なければ `nil` です。記録されるのは素の文字列リテラルだけです。`.asd` は **データ**
として解析されるため、ASDF の `(:read-file-form "version.sexp")` のような間接指定
（その他の計算された書き方も同様）は評価されず `nil` になります。コンポーネント
ファイル自身はバージョンを持ちません。

```lisp
(asdf:defsystem :demo-cv :version "0.9.15" :components ((:file "main")))
(asdf:component-version (asdf:find-system :demo-cv)) ; => "0.9.15"
```

## バックエンドサポート

4 つすべてのバックエンドで、あらゆるコンポーネントオブジェクト
（[`asdf:find-system`](asdf-find-system.md) のシステムと、その
[`asdf:component-children`](asdf-component-children.md)）に対して動作します。

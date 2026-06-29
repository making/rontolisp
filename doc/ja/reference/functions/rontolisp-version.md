# rontolisp:version

`(rontolisp:version)`

実行中の rontolisp のビルドおよびバージョン情報を、キー `:version`、
`:build-timestamp`、`:git-commit`、`:git-branch` を持つプロパティリストとして返します。
これは `rontolisp --version` が表示するのと同じ情報です。値 (タイムスタンプと git
リビジョン) はビルドに依存するため、結果はビルドごとに異なり、コンパイルされた
ランタイムの `eval`/`load` の内部ではサポートされません。

```lisp
(getf (rontolisp:version) :version)
```

この呼び出しは `(:version "0.1.0-SNAPSHOT" :build-timestamp
"..." :git-commit "..." :git-branch "...")` のような plist を返します。単一の
フィールドを読み取るには `getf` を使用してください。

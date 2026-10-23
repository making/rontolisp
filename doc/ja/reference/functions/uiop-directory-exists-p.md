# uiop:directory-exists-p

`(uiop:directory-exists-p pathname)`

*ディレクトリ*が存在するかどうかを答えます。存在すれば [`probe-file`](probe-file.md) が
返すのと同じ（末尾に `/` を付け、シンボリックリンクを解決した）真名を、存在しないか、
パス名がファイルを指す場合は `nil` を返します。[`uiop:file-exists-p`](uiop-file-exists-p.md) の
ディレクトリ版で、ライブラリはディレクトリルートを走査する前の検証に使います。

*空の*ディレクトリと存在しないディレクトリを区別できるのもこの関数です。
[`directory`](directory.md) はどちらにも `nil` を返します。

```lisp
(uiop:directory-exists-p "definitely-missing-dir")   ; => NIL
```

## バックエンドサポート

4 バックエンドすべてで、[`probe-file`](probe-file.md) の上に定義されています。そのため、ファイルシステムを持たないホスト（ブラウザプレイグラウンド）
では失敗せずに `nil` を返し、WASM モジュールは `--dir` を付けずに実行するとすべて `nil` を
返します。

# uiop:directory-exists-p

`(uiop:directory-exists-p pathname)`

*ディレクトリ*が存在するかどうかを答えます。存在すれば（末尾に `/` を付けた）パス名を、
存在しなければ `nil` を返します。[`uiop:file-exists-p`](uiop-file-exists-p.md) の
ディレクトリ版で、ライブラリはディレクトリルートを走査する前の検証に使います。

rontolisp が提供**しない**ものに注意してください。ディレクトリを一覧する手段はありません。
`uiop:collect-sub*directories` と `uiop:directory-files` は名前としては解決されますが、
呼び出すと通知します。*名前を指定した*ファイルの読み書きが、どのバックエンドでも rontolisp
が公開するファイルシステム機能のすべてであり、WASM バックエンドにはそもそもファイル
システムがないためです。

```lisp
(uiop:directory-exists-p "definitely-missing-dir")   ; => NIL
```

## バックエンドサポート

インタプリタのみです。この検査は `probe-file` と同じソースローダー抽象を通るため、
ファイルシステムを持たないホスト（ブラウザプレイグラウンド）では失敗せずに `nil` を返します。
JVM と WASM バックエンドでは、バックエンド横断の意味を持たない他の `uiop:` メンバーと
同様、呼び出しは実行時エラーへコンパイルされます。

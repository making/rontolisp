# uiop:merge-pathnames*

`(uiop:merge-pathnames* specified &optional defaults)`

`specified` のパスを `defaults` にマージして返します — UIOP による
`merge-pathnames` のデフォルト考慮版であり、可搬なライブラリがデータディレクトリ
基準のパスを組み立てるときに呼ぶものです。引数はパス名と名前文字列のどちらの綴りでも
受け付け、結果はパス名です。

```lisp
(uiop:merge-pathnames* "b.txt" "/tmp/")   ; => #P"/tmp/b.txt"
```

`defaults` を省略すると `""`（作業ディレクトリを指す名前文字列）に対してマージされ、
`specified` はそのまま残ります — 初期値の `*default-pathname-defaults*` のもとで
`uiop:get-pathname-defaults` が返す答えと同じです。

## バックエンドサポート

4 つのバックエンドすべてで動作します。[`merge-pathnames`](merge-pathnames.md) の上に
書かれた Lisp ソースの定義で、使用時にプログラムへコンパイルされます。加えてコンパイル
パスは、コンパイル時に解決できる引数（文字列リテラル、または文字列を束縛したトップ
レベルの `defparameter` への参照）を持つ呼び出しを**リテラルへ畳み込み**ます。
バンドルされたライブラリ内の
`(uiop:merge-pathnames* *data-directory* "UnicodeData.txt")` が実行時コストゼロに
なるのはこれによります。

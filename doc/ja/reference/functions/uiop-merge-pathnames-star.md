# uiop:merge-pathnames*

`(uiop:merge-pathnames* specified &optional defaults)`

`specified` のパスを `defaults` にマージして返します — UIOP による
`merge-pathnames` のデフォルト考慮版であり、可搬なライブラリがデータディレクトリ
基準のパスを組み立てるときに呼ぶものです。rontolisp ではパス名は名前文字列そのもの
なので、引数も結果も文字列です。

```lisp
(uiop:merge-pathnames* "b.txt" "/tmp/")   ; => "/tmp/b.txt"
```

`defaults` を省略すると `""`（作業ディレクトリを指す名前文字列）に対してマージされ、
`specified` はそのまま残ります — `uiop::get-pathname-defaults` が返す答えと同じです。

## バックエンドサポート

インタプリタでは通常の実行時関数です。JVM と 2 つの WASM バックエンドでは、コンパイラが
**リテラルへ畳み込める**呼び出しのみが動作します。引数が文字列リテラル、または文字列を
束縛したトップレベルの `defparameter` への参照であれば、コンパイル時にマージされます
（バンドルされたライブラリ内の
`(uiop:merge-pathnames* *data-directory* "UnicodeData.txt")` はこれで解決されます）。
実行時にしか値が定まらない呼び出しは、そこでは undefined-function エラーになります。

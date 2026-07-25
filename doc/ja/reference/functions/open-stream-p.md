# open-stream-p

`(open-stream-p stream)`

ストリームハンドルが開いているストリームを指している間は `t`、閉じられた後は `nil` を返します。「開いていれば閉じる」というイディオムが二重クローズもリークも避けるために問い合わせる述語です。インタプリタと JVM はストリームテーブルから答え ([`close`](close.md) がエントリを削除します)、相手側から閉じられたソケットについても `nil` を返します。

WASM の `--component` バックエンドではソケットについてまったく同じ答えになります (ソケットテーブルが Lisp 側の状態だからです)。それ以外のストリーム指定子は、非 nil であれば `t` を返します。Preview 1 はディスクリプタごとの開閉状態を保持していません。

```lisp
(with-input-from-string (s "x") (open-stream-p s)) ; => T
```

クローズ後の挙動はファイルを触るため、静的に示します:

```console
(let ((s (open "f.txt" :direction :input)))
  (open-stream-p s)   ; => T
  (close s)
  (open-stream-p s))  ; => NIL
```

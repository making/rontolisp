# unread-char

`(unread-char character &optional stream)`

直前に読み取った文字である `character` を押し戻し、次の読み取りが再びその文字を返すようにして `nil` を返します。[Gray ストリーム](../../guides/gray-streams.md)のインスタンスでは `rontolisp:stream-unread-char` にディスパッチし、その既定メソッドはプロトコル自身が持つ 1 文字ぶんの押し戻しスロットに文字を保管します (自前でソースを巻き戻せるクラスは、代わりにこのジェネリックを定義します)。ストリーム**ハンドル** -- ファイル、文字列入力ストリーム、ソケット -- では、ハンドル側の押し戻しに文字が入り、`read-char` / `peek-char` / `read-char-no-hang` / `read-line` がそれを消費します。

どちらのセルも保持できるのは 1 ストリームにつき 1 文字だけで、これは CL が約束している範囲そのものです。まだ埋まっている状態での 2 回目の `unread-char` は通知します。`read-byte` / `read-sequence` / `read` はハンドル側のセルを参照しません。

```lisp
(let* ((s (make-string-input-stream "abc"))
       (c (read-char s)))
  (unread-char c s)
  (list c (peek-char nil s) (read-char s) (read-line s))) ; => (#\a #\a #\a "bc")
```

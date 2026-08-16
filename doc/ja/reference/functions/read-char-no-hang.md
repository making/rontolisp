# read-char-no-hang

`(read-char-no-hang &optional stream eof-error-p eof-value)`

待たずに取得できる文字があれば `stream` (デフォルトは標準入力) から 1 文字読み取って返します。ストリーム**ハンドル** -- ファイル、文字列入力ストリーム、ソケット -- では `read-char` と同じ答えになります。rontolisp が開けるソースには「読めば 1 文字返るが、いま読むとブロックする」を区別して報告できるものがなく、CL は処理系がそう振る舞うことを許しています。[Gray ストリーム](../../guides/gray-streams.md)のインスタンスに対しては `rontolisp:stream-read-char-no-hang` にディスパッチします。これは本当に非ブロッキングなソースを持つクラスがオーバーライドするためのジェネリックで、その既定メソッド自体は `stream-read-char` です。入力の終端では `end-of-file` コンディションを通知しますが、`eof-error-p` が `nil` の場合は `eof-value` (デフォルト `nil`) を返します。

```lisp
(with-input-from-string (s "hi")
  (list (read-char-no-hang s)
        (read-char-no-hang s)
        (read-char-no-hang s nil :end))) ; => (#\h #\i :END)
```

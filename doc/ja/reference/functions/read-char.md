# read-char

`(read-char &optional stream eof-error-p eof-value)`

`stream` (デフォルトは標準入力) から 1 文字読み取って返します。ストリームには `open`/`with-open-file` で開いたファイルストリーム、または `with-input-from-string` の文字列入力ストリームを渡せます。入力の終端では `end-of-file` コンディションを通知しますが、`eof-error-p` が `nil` の場合は `eof-value` (デフォルト `nil`) を返します。通知されるのは登録済みの `end-of-file` クラスなので、CL のレキサーによくある形 -- 読み取りループを `(handler-case ... (end-of-file (e) ...))` で囲む -- がそのまま終了します。インタープリターと JVM バックエンドでは文字は UTF-16 コード単位で、文字列表現の他の部分と一致します。WASM バックエンドでは文字列がバイト単位でインデックスされる (`char`/`schar` と同じ) ため、文字の読み取りはバイトの読み取りになります。

```lisp
(with-input-from-string (s "hi")
  (let* ((c1 (read-char s))
         (c2 (read-char s))
         (c3 (read-char s nil :end)))
    (list c1 c2 c3))) ; => (#\h #\i :end)
```

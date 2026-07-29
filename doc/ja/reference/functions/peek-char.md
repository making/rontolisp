# peek-char

`(peek-char &optional peek-type stream eof-error-p eof-value)`

`stream` (デフォルトは標準入力) の次の文字を **消費せずに** 返します。続く `read-char` は同じ文字を返します。`peek-type` は先に読み飛ばす対象を選びます。`nil` (デフォルト) は何も読み飛ばさず、`t` は空白を読み飛ばし、文字を渡すとその文字までの入力を読み飛ばします。いずれの場合も、返した文字はストリームに残ります。入力の終端では `end-of-file` コンディションを通知しますが、`eof-error-p` が `nil` の場合は `eof-value` (デフォルト `nil`) を返します。

```lisp
(with-input-from-string (s "  ab")
  (list (peek-char t s) (read-char s) (peek-char nil s) (read-char s))) ; => (#\a #\a #\b #\b)
```

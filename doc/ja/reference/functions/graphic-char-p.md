# graphic-char-p standard-char-p

`(graphic-char-p character)` -- `(standard-char-p character)`

文字述語です。`graphic-char-p` は印字可能な文字（コードポイント 32〜126 と 160 以上のすべて）に対して真になるので、`#\Space` は含まれ `#\Newline` は含まれません。`standard-char-p` は 96 個の標準文字、すなわち印字可能な ASCII の範囲に `#\Newline` を加えたものに対して真になります。`#\Newline` がこの 2 つの述語で答えの分かれる唯一の文字です。

```lisp
(list (graphic-char-p #\a) (graphic-char-p #\Space) (graphic-char-p #\Newline)
      (standard-char-p #\Newline) (standard-char-p #\Tab)) ; => (T T NIL T NIL)
```

# alphanumericp

`(alphanumericp character)`

`character` が英字または 10 進数字なら真、それ以外は `nil` を返します。数字の場合の戻り値はその重み(`digit-char-p` と同様)、英字の場合は `t` で、どちらも真です。WASM バックエンドでは英字判定は ASCII の `a`-`z`、`A`-`Z` のみを認識します。

```lisp
(alphanumericp #\x) ; => T
```

```lisp
(alphanumericp #\-) ; => NIL
```

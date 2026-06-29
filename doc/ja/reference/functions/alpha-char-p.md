# alpha-char-p

`(alpha-char-p character)`

`character` がアルファベットの文字であれば `t` を、そうでなければ `nil` を返します（たとえば数字は `nil` になります）。WASM バックエンドでは、ASCII 文字の `a`-`z` と `A`-`Z` のみを判定対象とします。

```lisp
(alpha-char-p #\x) ; => t
```

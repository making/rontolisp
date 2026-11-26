# alpha-char-p

`(alpha-char-p character)`

`character` がアルファベットの文字であれば `t` を、そうでなければ `nil` を返します（たとえば数字は `nil` になります）。WASM バックエンドでは、ASCII 文字の `a`-`z` と `A`-`Z` のみを判定対象とします。文字でない引数は、演算子名を付けた `type-error` を通知します。

```lisp
(alpha-char-p #\x) ; => T
```

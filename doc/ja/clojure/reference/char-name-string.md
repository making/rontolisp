# char-name-string

`(char-name-string ch)`

文字 `ch` の名前（`space`、`tab`、`newline`、`return`、`backspace`、`formfeed`）。他の文字も文字以外も `nil`。oracle と同じ。値としては 1 引数を取る。

```clojure
(prn (char-name-string \newline) (char-name-string \a)) ; "newline" nil
```

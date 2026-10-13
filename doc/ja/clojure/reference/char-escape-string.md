# char-escape-string

`(char-escape-string ch)`

文字 `ch` のエスケープ（`\t`、`\r`、`\n`、`\f`、`\b`、`\\`、`\"`）。他の文字も文字以外も `nil`。oracle と同じ。値としては 1 引数を取る。

```clojure
(prn (char-escape-string \newline) (char-escape-string \a)) ; "\\n" nil
```

# substring

`(substring string start end)`

`string` の添字 `start`（含む）から `end`（含まない）までの文字を持つ新しい文字列を返します。両方の添字が必須です。

```scheme
(substring "hello" 1 3) ; => "el"
(substring "hello" 0 5) ; => "hello"
```

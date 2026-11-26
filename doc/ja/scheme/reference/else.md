# else

`(cond ... (else expression...))` `(case key ... (else expression...))`

補助構文です。`cond` や `case` の最後の節で、それより前のどの節も選ばれなかったときに選ばれます。`case` では `(else => receiver)` が key を引数に `receiver` を呼びます。単独では意味を持ちません。

```scheme
(cond ((> 1 2) 'a) (else 'b)) ; => b
(case 9 ((1 2) 'low) (else 'high)) ; => high
```

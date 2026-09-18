# number->string

`(number->string z)` `(number->string z radix)`

`z` の外部表現を文字列で返します。`radix`（2、8、10、16 のいずれかで、既定値は 10）は正確数に適用され、分数では分子と分母の両方に適用されます。浮動小数点数は 10 進で書かれます。浮動小数点数は `1e21` 未満では位取り表記で、それ以上（および `1e-6` 未満）では指数付きで書かれます。

```scheme
(number->string 255 16) ; => "ff"
(number->string 10 2) ; => "1010"
(number->string 1/3 2) ; => "1/11"
(number->string 3.5) ; => "3.5"
```

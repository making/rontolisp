# utf8->string

`(utf8->string bytevector)` `(utf8->string bytevector start)` `(utf8->string bytevector start end)`

`bytevector` の `start`（含む、既定は 0）から `end`（含まない、既定は末尾）までのバイトを UTF-8 として復号した文字列を返します。正しい UTF-8 の列を始めないバイトや範囲で途切れた列は、エラーにせずそのバイト値をコードとする文字になります（`#u8(255 65)` は `"ÿA"`）。

```scheme
(utf8->string #u8(65 66 67 227 129 130)) ; => "ABCあ"
(utf8->string #u8(65 66 67 68) 1 3) ; => "BC"
```

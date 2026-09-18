# bytevector-u8-ref

`(bytevector-u8-ref bytevector k)`

`bytevector` の `k` 番目（0 始まり）の要素を 0〜255 の正確な整数として返します。範囲外の添字はエラーです。

```scheme
(bytevector-u8-ref #u8(10 20 30) 1) ; => 20
```

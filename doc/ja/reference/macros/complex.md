# complex

`(complex real &optional imaginary)`

簡易版: 複素数表現は存在しないため、虚部がゼロ(または省略)なら実部を返し、それ以外はエラーをシグナルします。複素数の分岐が実際には通らないソース — parse-number の `#C(...)` パーサなど — を全バックエンドでロード可能に保つためのものです。

```lisp
(complex 9 0) ; => 9
```

```console
CL-USER> (complex 1 2)
Error: complex numbers are not supported (imaginary part 2)
```

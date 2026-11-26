# rontolisp:string-to-octets

`(rontolisp:string-to-octets string)`

`string` を UTF-8 としてエンコードし、新しい packed `(unsigned-byte 8)` ベクタを返します。これを逆にするデコーダが `rontolisp:octets-to-string` です。

```lisp
(rontolisp:string-to-octets "Hi") ; => #(72 105)
(rontolisp:string-to-octets "")   ; => #()
```

このエンコードは**全域的**です。`0` から `#x10FFFF` までのすべてのコードポイント -- rontolisp の文字はこれより狭い範囲を持たないため、サロゲートも含みます -- はちょうど 1 つの (最短の) UTF-8 エンコーディングを持つため、`string-to-octets` が拒否する入力はありません。

この対は整形式の入力に対して往復しますが、一般にはデコードしてからエンコードする方向でのみ成り立ちます。`rontolisp:octets-to-string` は不正なバイト列に対して寛容です (詳細はそのページを参照) が、その答えが常に同じバイト列に再エンコードされるとは限りません -- それが成り立つのは、完全で正規なシーケンスだけです。

```lisp
(rontolisp:string-to-octets (rontolisp:octets-to-string #8@(226 130 172))) ; => #(226 130 172)
```

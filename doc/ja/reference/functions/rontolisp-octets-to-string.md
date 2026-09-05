# rontolisp:octets-to-string

`(rontolisp:octets-to-string octets)`

packed な `(unsigned-byte 8)` ベクタをデコードし、そのバイト列が表す UTF-8 テキストを新しい文字列として返します。これを逆にするエンコーダが `rontolisp:string-to-octets` です。

```lisp
(rontolisp:octets-to-string (rontolisp:string-to-octets "Hello, 世界!")) ; => "Hello, 世界!"
```

このデコードは**全域的かつ寛容**です。`octets` が拒否されることはありません。どのシーケンスの先頭にもならないバイトや、ベクタの終端で途切れたシーケンスは、シグナルを送る代わりにそれぞれ自身のバイト値を持つ 1 文字としてデコードされます -- これは生バイトとして運ばれた HTTP ボディをデコードするのと同じ規則で、どの経路で運ばれたボディでも同じようにデコードされます。過長エンコーディング (overlong encoding) と UTF-8 でエンコードされたサロゲートも拒否されません。それぞれのビットが組み立てるコードポイントとしてデコードされます -- rontolisp の文字はサロゲートを含む `0` から `#x10FFFF` までの任意のコードポイントを許容するためです。

```lisp
(rontolisp:octets-to-string #8@()) ; => ""
(mapcar #'char-code (coerce (rontolisp:octets-to-string #8@(#xE2 #x82)) 'list)) ; => (226 130)
```

2 つ目の例は 3 バイトシーケンス (`€`) が最初の継続バイトの後で切り詰められたものです。残りの 2 バイトは、破棄されたりシグナルを送られたりせず、それぞれ自身の値としてデコードされます。

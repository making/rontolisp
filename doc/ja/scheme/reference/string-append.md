# string-append

`(string-append string ...)`

与えられた文字列の文字を順に並べた新しい文字列を返します。引数がなければ空文字列を返します。

```scheme
(string-append "foo" "bar" "baz") ; => "foobarbaz"
(string-append) ; => ""
```

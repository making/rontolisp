# syntax-rules

`(syntax-rules (literal...) (pattern template)...)` `(syntax-rules ellipsis (literal...) (pattern template)...)`

構文定義の変換子です。利用は各 `pattern` と順に照合され（先頭要素のキーワードは無視されます）、最初に一致したものの `template` が、パターン変数に一致したもので具体化されます。パターン中では、`literal` は同じ束縛にだけ一致し、`_` は何にでも一致し、`...`（または指定した `ellipsis`）は直前の部分パターンを繰り返します。入れ子、後続パターン付き、ドット対の末尾、ベクタ内でも使えます。テンプレート中では `...` が直前の部分テンプレートを繰り返し、`(... ...)` は文字どおりの `...` を表します。どの規則にも一致しない利用は、ファイルを読む時点でエラーになります。

```scheme
(let-syntax ((first (syntax-rules () ((_ a b ...) 'a)))) (first x y z)) ; => x
(define-syntax my-if
  (syntax-rules (then else)
    ((_ c then t else e) (if c t e))))
(my-if #f then 'yes else 'no) ; => no
(define-syntax tagged
  (syntax-rules ::: ()
    ((_ x :::) '(x ::: ...))))
(tagged 1 2) ; => (1 2 ...)
```

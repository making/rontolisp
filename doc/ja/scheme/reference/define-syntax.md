# define-syntax

`(define-syntax keyword (syntax-rules ...))`

`keyword` をマクロに束縛します。`(keyword ...)` という利用は、プログラムの実行前に変換子が返す展開形に置き換えられます。トップレベルと本体の先頭に置けます。マクロは定義に展開されても構いません。マクロは定義位置からファイル（または本体）の終わりまで見えます。`(load ...)` したファイルからは見えず、そのファイルのマクロも見えません。変換子は `syntax-rules` のみ受け付けます。マクロは健全です。テンプレートが束縛する変数は利用側が書いた名前を捕捉せず、テンプレートが自由に使う名前はマクロを定義した場所での意味を保ちます。

```scheme
(define-syntax swap!
  (syntax-rules ()
    ((_ a b) (let ((tmp a)) (set! a b) (set! b tmp)))))
(define x 1)
(define tmp 2)
(swap! x tmp)
(list x tmp) ; => (2 1)
(let () (define-syntax two (syntax-rules () ((_) 2))) (* (two) 3)) ; => 6
```

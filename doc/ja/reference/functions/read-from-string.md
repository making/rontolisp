# read-from-string

`(read-from-string string)`

与えられた文字列から 1 つのデータム（datum）を解析して返します。[`read`](read.md) と同じリーダーを再利用するため、コンパイル系バックエンドでもフロントエンドと同等の構文を受け付けます（`#S(...)`、`#(...)`、`#\a`、比、基数付き整数など。`#.`、`#+`/`#-`、リーダーラベルはシグナルします。WASM リーダーの数値は任意の大きさの整数を扱えますが、浮動小数点の指数表記は読めません）。`(read-from-string (prin1-to-string x))` は往復します。インタプリタは `#.` データムをその場で評価します（標準に従い、`*read-eval*` を `nil` に束縛するとシグナルします）。データムを含まない入力は `end-of-file` を、不正な形式のデータムは `reader-error` をシグナルします。どちらも問題のテキスト上の文字列入力ストリームを運びます（`stream-error-stream`）。コンパイルされたバックエンドでは同じ失敗は捕捉可能な `simple-error` としてシグナルされます。オプションの `eof-error-p`/`eof-value` および `:start`/`:end` キーワード引数はサポートされておらず、単一の文字列引数のみを受け付けます。3 つすべてのバックエンドで動作し、第一級の値として利用できます（`#'read-from-string`）。

```lisp
(read-from-string "(+ 1 2)") ; => (+ 1 2)
```

結果は評価ではなくデータとして解析されたリスト `(+ 1 2)` です。値 `3` が欲しい場合は `eval` に渡してください。

シンボルはリーダーの[大文字化](../../guides/reader-case.md)に従って読まれ、これはすべてのバックエンドで同一です: ユーザーのシンボルも標準の名前も同様に大文字化されます(小文字の綴りへ畳み込むことはありません)。

```lisp
(read-from-string "foo") ; => FOO
```


## 停止インデックスと `*read-suppress*`

Common Lisp と同じく、`read-from-string` は第 2 の値として「読まれなかった最初の文字のインデックス」を返します。終端となった空白は、それを終端させたデータム（リストや文字リテラルもトークンと同様に）とともに消費され、終端マクロ文字は戻されるため、`"abc  def"` は 4、`"(1 2) x"` は（`)` の後の空白まで進んだ）6 で停止します。取得するには[多値](../macros/multiple-value-bind.md)の消費側を使ってください。データムだけが欲しい呼び出し側には何のコストもかかりません。

```lisp
(multiple-value-list (read-from-string "abc")) ; => (ABC 3)
(multiple-value-list (read-from-string "abc  def")) ; => (ABC 4)
(nth-value 1 (read-from-string "(1 2) x")) ; => 6
```

`*read-suppress*` を真に束縛すると、リーダーは実際の読み取りと同じだけの文字を消費したうえで `nil` を返し、そのデータムが本来シグナルするはずのエラー（未知のパッケージ、不正な文字名、範囲外の数字）をすべて抑制します。これは `#+`/`#-` のガードがスキップするフォームに対して行っていることと同じで、インタプリタの挙動です。コンパイル出力のランタイムリーダーに抑制モードはありません。

```lisp
(let ((*read-suppress* t)) (read-from-string "nonexistent-package::foo")) ; => NIL
(let ((*read-suppress* t)) (multiple-value-list (read-from-string "123.45"))) ; => (NIL 6)
```

# read-from-string

`(read-from-string string &optional (eof-error-p t) eof-value &key (start 0) end preserve-whitespace)`

与えられた文字列から 1 つのデータム（datum）を解析して返します。[`read`](read.md) と同じリーダーを再利用するため、コンパイル系バックエンドでもフロントエンドと同等の構文を受け付けます（`#S(...)`、`#(...)`、`#\a`、比、基数付き整数など。`#.`、`#+`/`#-`、リーダーラベルはシグナルします）。`(read-from-string (prin1-to-string x))` は往復します。インタプリタは `#.` データムをその場で評価します（標準に従い、`*read-eval*` を `nil` に束縛するとシグナルします）。データムを含まない入力は `end-of-file` を、不正な形式のデータムは `reader-error` をシグナルします。どちらも問題のテキスト上の文字列入力ストリームを運びます（`stream-error-stream`）。途中で終わるデータムも `end-of-file` を、何も閉じない `)` は `reader-error` を、どのバックエンドでもシグナルします。コンパイル系バックエンドではこの 2 つのコンディションはストリームを持たず（`stream-error-stream` は `nil` を返します）、それ以外の不正な形式のデータム（`#x` の不正な桁、未知の文字名など）は捕捉可能な `simple-error` をシグナルします。4 バックエンドすべてで動作し、第一級の値として利用できます（`#'read-from-string`）。

```lisp
(read-from-string "(+ 1 2)") ; => (+ 1 2)
```

結果は評価ではなくデータとして解析されたリスト `(+ 1 2)` です。値 `3` が欲しい場合は `eval` に渡してください。

シンボルはリーダーの[大文字化](../../guides/reader-case.md)に従って読まれ、これはすべてのバックエンドで同一です: ユーザーのシンボルも標準の名前も同様に大文字化されます(小文字の綴りへ畳み込むことはありません)。

```lisp
(read-from-string "foo") ; => FOO
```


## 省略可能引数とキーワード引数

文字列の後ろの引数は Common Lisp のものです。4 バックエンドすべてで、呼び出し位置でも `#'read-from-string` 経由でも同じです。

- `:start` / `:end` は読むテキストの範囲を決めます。停止インデックスは文字列全体の先頭から数えます。不正な範囲（負、整数でない、`nil` の start、長さやフィルポインタを超える、start が end より後ろ）は、何も読む前に [`subseq`](subseq.md) と同じ `type-error` をシグナルします。
- データムを含まない入力（または範囲）、つまり空白とコメントだけのものは、`eof-error-p` が真なら `end-of-file` をシグナルし、偽なら `eof-value` を返します。このときの停止インデックスは範囲の終端です。データムの途中でテキストが終わる場合は `eof-error-p` にかかわらず `end-of-file` を、何も閉じない `)` は `reader-error` をシグナルします。
- `:preserve-whitespace` が真なら終端の空白を読まずに残し、停止インデックスはその空白を指します。
- 未知のキーワードや奇数個のキーワード引数は `program-error` をシグナルします。`:allow-other-keys` と、同じキーワードの繰り返し（最初のものが使われる）は Common Lisp と同じです。

```lisp
(multiple-value-list (read-from-string " 12 34" t nil :start 3)) ; => (34 6)
(multiple-value-list (read-from-string "   " nil :none)) ; => (:NONE 3)
(multiple-value-list (read-from-string "123  " t nil :preserve-whitespace t)) ; => (123 3)
```

このような呼び出しは [`read`](read.md) と同じ方法でデータムを読むため、すべてのバックエンドで `#+`/`#-` ガードを実行時の `*features*` に対して解決します。成り立たないガードはその後ろのフォームをスキップし、呼び出しは次のデータムを返します。引数 1 つの呼び出しがガードを解決するのはインタプリタだけで、コンパイル系のリーダーはそこではどの `#+`/`#-` でもシグナルします。

```lisp
(multiple-value-list (read-from-string "#+nope (a b) c" nil nil)) ; => (C 14)
```

## 停止インデックスと `*read-suppress*`

Common Lisp と同じく、`read-from-string` は第 2 の値として「読まれなかった最初の文字のインデックス」を返します。終端となった空白は、それを終端させたデータム（リストや文字リテラルもトークンと同様に）とともに消費され、終端マクロ文字は戻されるため、`"abc  def"` は 4、`"(1 2) x"` は（`)` の後の空白まで進んだ）6 で停止します。インデックスはバイトではなく文字を数え、`"日本 x"` は 3 で停止し、補助面の文字も全バックエンドで 1 文字と数えます。取得するには[多値](../macros/multiple-value-bind.md)の消費側を使ってください。データムだけが欲しい呼び出し側には何のコストもかかりません。関数オブジェクト `#'read-from-string` も同じ 2 つの値を返します。読まれるのは最初のデータムだけで、その後ろのテキストは調べられません。そのため `"5.)"` と `"abc)"` は全バックエンドでそれぞれ `5` と `ABC` を返し、完結したデータムの後ろに不正な部分があってもエラーになりません。

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

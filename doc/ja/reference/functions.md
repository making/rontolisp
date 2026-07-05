# 関数

このページはクイックリファレンスの表です。**表中の各関数名はそれぞれのページにリンクしています**。各ページには、より詳しい説明と、ブラウザで評価できる実行可能な例があります。横断的なトピックには専用の場所があります。`make-array`/`aref`
とハッシュテーブル演算子は、データ型ページの
[配列](data-types.md#arrays) と [ハッシュテーブル](data-types.md#hash-tables)
で説明されており、各関数のCommon Lispからの逸脱はそれぞれのページに記載されています。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `+` | `(+ 1 2 3)`, `(+ 1.5 2.5)` | `6`, `4.0` |
| `-` | `(- 10 3)`, `(- 3.5 1.5)` | `7`, `2.0` |
| `*` | `(* 3 4)`, `(* 2.0 3.0)` | `12`, `6.0` |
| `/` | `(/ 1 2)`, `(/ 10 2)`, `(/ 7.0 2.0)` | `1/2`(正確なratio)、`5`, `3.5` |
| `mod` | `(mod 10 3)`, `(mod -13 4)` | `1`, `3`(結果は除数の符号を取ります) |
| `rem` | `(rem 13 4)`, `(rem -13 4)` | `1`, `-1`(結果は被除数の符号を取ります) |
| `=` | `(= 1 1)`, `(= 3 3 3)` | `t`(可変長引数) |
| `eq` | `(eq 'foo 'foo)`, `(eq 1.5 1.5)` | `t`, `nil`(オブジェクトの同一性: シンボルと小さい整数は等しく比較されますが、浮動小数点とratioは別々のオブジェクトなので決して `eq` になりません。consセルは参照同一性です) |
| `eql` | `(eql 1.5 1.5)`, `(eql 3 3.0)` | `t`, `nil`(`eq` と同様ですが、同じ型かつ同じ値の数値は等しくなります — 例: 浮動小数点やratio) |
| `equal` | `(equal '(1 2 (3)) '(1 2 (3)))`, `(equal "abc" "abc")` | `t`, `t`(構造的等価性: consセルはcarとcdrで再帰的に比較され、それ以外は `eql` と同様) |
| `<` | `(< 1 2)`, `(< 1 2 3)` | `t`(可変長引数。狭義単調増加のとき真) |
| `>` | `(> 2 1)`, `(> 3 2 1)` | `t`(可変長引数) |
| `<=` | `(<= 1 1)` | `t`(可変長引数) |
| `>=` | `(>= 2 1)` | `t`(可変長引数) |
| `print` | `(print 42)` | `42` を改行付きで印字します |
| `prin1` | `(prin1 42)` | `print` と同様ですが改行なし |
| `princ` | `(princ "hello")` | 引用符なし・改行なしで印字します |
| `terpri` | `(terpri)` | 改行のみを印字します |
| `fresh-line` | `(fresh-line)` | 標準出力がまだ行頭にない場合のみ改行を印字します。nilを返します |
| `princ-to-string` | `(princ-to-string '(1 "x"))` | `"(1 x)"` -- `princ` が印字する文字列 |
| `prin1-to-string` | `(prin1-to-string "abc")` | `"\"abc\""` -- `prin1` が印字する文字列(読み戻し可能な形式) |
| `concatenate` | `(concatenate 'string "foo" "bar")` | `"foobar"`(サポートされるのは `'string` 結果型のみ。コンパイラはリテラルの `'string` を要求します) |
| `string-upcase` | `(string-upcase "abc")` | `"ABC"`(WASMバックエンドでは大小文字変換はASCII限定です) |
| `string-downcase` | `(string-downcase "ABC")` | `"abc"` |
| `string-capitalize` | `(string-capitalize "hello world")` | `"Hello World"`(各単語の最初の文字) |
| `subseq` | `(subseq "hello" 1 3)` | `"el"`(文字列とリストで機能します。例: `(subseq '(1 2 3 4) 1 3)` => `(2 3)`。`end` 引数は省略可能) |
| `string=` | `(string= "abc" "abc")` | `t`(大小文字を区別する文字列等価) |
| `string-equal` | `(string-equal "ABC" "abc")` | `t`(大小文字を区別しない、ASCII) |
| `string-trim` | `(string-trim " " "  hi  ")` | `"hi"`(指定した文字集合の文字を両端から取り除きます) |
| `string-left-trim` | `(string-left-trim "x" "xxhi")` | `"hi"` |
| `string-right-trim` | `(string-right-trim "x" "hixx")` | `"hi"` |
| `read-line` | `(read-line)`, `(read-line stream)` | 標準入力(または入力ストリーム)から1行読み込み、文字列として返します。EOFでは `nil` |
| `open` | `(open "f.txt")`, `(open "f.txt" :output)`, `(open "f.bin" :input '(unsigned-byte 8))` | ファイルを開いてストリームを返します。方向はリテラルの `:input`(デフォルト、読み込み)または `:output`(作成/切り詰め、書き込み)でなければなりません。省略可能な要素型はリテラルの `'character`(デフォルト、テキスト)または `'(unsigned-byte 8)`(バイナリ)でなければなりません |
| `close` | `(close stream)` | `open` で開いたストリームを閉じます。`t` を返します |
| `write-line` | `(write-line "hi" stream)`, `(write-line "hi")` | 文字列と改行を出力ストリーム(または標準出力)に書き込みます。文字列を返します |
| `read-byte` | `(read-byte stream)`, `(read-byte stream nil -1)` | バイナリ入力ストリームから 1 バイト(0-255)を読み込みます。EOF ではエラーを通知し、`eof-error-p` が `nil` の場合は `eof-value` を返します |
| `write-byte` | `(write-byte 255 stream)` | バイナリ出力ストリームに生の 1 バイト(0-255)を書き込みます。バイトを返します |
| `read-sequence` | `(read-sequence buf stream)`, `(read-sequence buf stream :start 2 :end 4)` | バイナリ入力ストリームのバイトでベクタを埋めます。充填位置を返します。`:start`/`:end` はリテラルのキーワードでなければなりません |
| `write-sequence` | `(write-sequence buf stream)`, `(write-sequence buf stream :start 1 :end 3)` | バイト(0-255)のベクタをバイナリ出力ストリームに書き込みます。シーケンスを返します。`:start`/`:end` はリテラルのキーワードでなければなりません |
| `read` | `(read)`, `(read stream)` | 標準入力(または `open`/`with-open-file` で開いた入力ストリーム)からS式を1つ読み込みます(3つのバックエンドすべて)。EOFでは `nil` |
| `read-from-string` | `(read-from-string "(+ 1 2)")` | 文字列からデータを1つパースします(3つのバックエンドすべて)。省略可能な `eof-error-p`/`eof-value` および `:start`/`:end` 引数はサポートされません |
| `parse-integer` | `(parse-integer "42")`, `(parse-integer "ff" :radix 16)`, `(parse-integer "12x" :junk-allowed t)` | 文字列から整数をパースします。すべてのバックエンドで `:start`/`:end`/`:radix`/`:junk-allowed` をサポートします。パース停止位置が 2 番目の値になり、`multiple-value-bind` で観測できます。`:junk-allowed` がない場合、末尾の非空白文字はエラーです |
| `char` `schar` | `(char "hello" 1)` | `#\e` -- 0始まりの文字列インデックスの文字 |
| `char-code` | `(char-code #\A)` | `65` -- 文字のコードポイント |
| `code-char` | `(code-char 66)` | `#\B` -- 指定したコードポイントの文字 |
| `char=` `char<` `char<=` | `(char< #\a #\b #\c)` | `t`(コードポイントによる可変長引数比較) |
| `char-upcase` `char-downcase` | `(char-upcase #\a)` | `#\A`(WASMバックエンドではASCII大小文字変換) |
| `characterp` | `(characterp #\a)` | `t` |
| `alpha-char-p` | `(alpha-char-p #\x)`, `(alpha-char-p #\5)` | `t`, `nil`(WASMバックエンドではASCII文字) |
| `digit-char-p` | `(digit-char-p #\7)`, `(digit-char-p #\f 16)` | `7`, `15` -- 指定した基数(デフォルト10)での桁の重み、またはnil |
| `eval` | `(eval '(+ 1 2))` | 式を評価します(3つのバックエンドすべて)。結果を返します |
| `load` | `(load "bar.lisp")` | ファイル内のすべてのトップレベルフォームをグローバル環境で読み込んで評価します(3つのバックエンドすべて)。`t` を返します |
| `require` | `(require :util)`, `(require :util "lib/util.lisp")` | モジュールのファイル(require するファイルの隣の `<name>.lisp`、または明示パス)を、まだ `provide` されていなければロードします。モジュール名を返します。コンパイルパスではリテラルなトップレベルフォームである必要があります |
| `provide` | `(provide :util)` | モジュールをロード済みとして登録し、以後の `require` を no-op にします。モジュール名を返します。コンパイルパスではリテラルなトップレベルフォームである必要があります |
| `gensym` | `(gensym)`, `(gensym "tmp")` | `#:g1`, `#:tmp2` -- マクロの一時変数のための新しいシンボル(カウンタはプログラム全体で共有) |
| `make-symbol` | `(make-symbol "temp")` | `#:temp` -- 新しいアンインターンドシンボル(gensym の `#:` 規約、カウンタなし) |
| `intern` | `(intern "foo")` | シンボル `foo`(名前はそのまま使用。カレントパッケージは無視され、パッケージ引数はエラー) |
| `find-symbol` | `(find-symbol "car")` | 名前が既知(cl シンボル・キーワード・ユーザー定義)なら `car`、なければ `nil`(コンパイラ: リテラル文字列のみ) |
| `symbol-name` | `(symbol-name 'foo)` | `"foo"` -- 格納された名前そのまま(ケース保存。CL のように大文字化されない) |
| `symbol-value` | `(symbol-value '*level*)` | グローバル変数の値。未束縛の名前はエラー(レキシカルな束縛は見えない) |
| `boundp` | `(boundp '*level*)` | シンボルが束縛されたグローバル変数を指すとき `t`(t/nil/キーワードは自己束縛) |
| `fboundp` | `(fboundp 'car)` | 関数・マクロ・特殊形式に対して `t`(コンパイラ: 計算された引数は関数のみ判定) |
| `macroexpand-1` | `(macroexpand-1 '(unless c x))` | `(if c nil x)` -- トップレベルのフォームを 1 段階だけ展開します(ユーザーマクロと組み込みマクロ) |
| `macroexpand` | `(macroexpand '(outer 41))` | 完全な展開結果: `macroexpand-1` を変化しなくなるまで繰り返します |
| `null` | `(null nil)` | `t` |
| `not` | `(not nil)` | `t`(`null` と同一) |
| `atom` | `(atom 1)` | `t` |
| `numberp` | `(numberp 42)` | `t` |
| `integerp` | `(integerp 42)` | `t` |
| `floatp` | `(floatp 3.14)` | `t` |
| `rationalp` | `(rationalp 1/2)` | `t`(整数とratio) |
| `numerator` | `(numerator 3/4)` | `3`(整数自身がその分子) |
| `denominator` | `(denominator 3/4)` | `4`(整数では `1`) |
| `symbolp` | `(symbolp 'foo)` | `t` |
| `stringp` | `(stringp "hello")` | `t` |
| `listp` | `(listp '(1 2))` | `t` |
| `consp` | `(consp '(1 2))` | `t` |
| `keywordp` | `(keywordp :foo)` | `t` |
| `cons` | `(cons 1 2)` | `(1 . 2)` |
| `car` | `(car (cons 1 2))` | `1`(`(car nil)` は `nil`) |
| `cdr` | `(cdr (cons 1 2))` | `2`(`(cdr nil)` は `nil`) |
| `caar`..`cddddr` | `(cadr '(1 2 3))` | `2`(`car`/`cdr` の合成、2~4段) |
| `first` | `(first '(1 2 3))` | `1`(`car` と同じ) |
| `rest` | `(rest '(1 2 3))` | `(2 3)`(`cdr` と同じ) |
| `nth` | `(nth 1 '(1 2 3))` | `2`(0始まりのインデックス) |
| `second` `third` `fourth` | `(second '(1 2 3))` | `2` |
| `list` | `(list 1 2 3)` | `(1 2 3)` |
| `nthcdr` | `(nthcdr 2 '(1 2 3))` | `(3)`(先頭のn要素をスキップ) |
| `length` | `(length '(1 2 3))`, `(length "abc")`, `(length #(1 2 3))` | `3`, `3`, `3`(リスト、文字列、ベクタ。nilでは `0`) |
| `reverse` | `(reverse '(1 2 3))` | `(3 2 1)` |
| `member` | `(member 2 '(1 2 3))` | `(2 3)`(carが要素と `eql` になる末尾、またはnil。省略可能な `:test`/`:key` キーワードを取ります。例: `(member '(a d) '((a b) (a d)) :test 'equal)` -> `((a d))`) |
| `find` | `(find 2 '(1 2 3))` | `2`(要素と `eql` になる最初の要素、またはnil。省略可能な `:test`/`:key` キーワードを取ります) |
| `find-if` | `(find-if #'evenp '(1 3 6 7))` | `6`(述語を満たす最初の要素、またはnil) |
| `find-if-not` | `(find-if-not #'evenp '(2 4 5 6))` | `5`(述語を満たさない最初の要素、またはnil) |
| `member-if` | `(member-if #'oddp '(2 4 5 6))` | `(5 6)`(述語を満たす最初の要素から始まる末尾、またはnil) |
| `position` | `(position 3 '(1 2 3))` | `2`(要素と `eql` になる最初の要素の0始まりインデックス、またはnil。省略可能な `:test`/`:key` キーワードを取ります) |
| `position-if` | `(position-if #'evenp '(1 3 6 7))` | `2`(述語を満たす最初の要素の0始まりインデックス、またはnil) |
| `count` | `(count 2 '(1 2 3 2 2))` | `3`(要素と `eql` になる要素の数。省略可能な `:test`/`:key` キーワードを取ります) |
| `count-if` | `(count-if #'evenp '(1 2 3 4))` | `2`(述語を満たす要素の数) |
| `assoc` | `(assoc 'b '((a . 1) (b . 2)))` | `(b . 2)`(carがキーに一致する最初のペア、またはnil。既定では `eql` で比較し、省略可能な `:test`/`:key` キーワードを取ります。例: `(assoc "b" '(("a" . 1) ("b" . 2)) :test #'equal)`) |
| `assoc-if` | `(assoc-if #'oddp '((2 a) (3 b)))` | `(3 b)`(carが述語を満たす最初のペア、またはnil) |
| `getf` | `(getf '(:a 1 :b 2) :b)` | `2`(プロパティリスト中で指標に続く値、またはnil。`remf` の相棒。引数は2つのみで `&optional default` はありません) |
| `last` | `(last '(1 2 3))` | `(3)`(最後のconsセル、空リストではnil) |
| `butlast` | `(butlast '(1 2 3))` | `(1 2)`(最後の要素を除いたコピー。空または単一要素のリストではnil) |
| `remove` | `(remove 2 '(1 2 3 2))` | `(1 3)`(指定した要素と `eql` になる要素を除いた新しいリスト。省略可能な `:test`/`:key` キーワードを取ります) |
| `remove-if` | `(remove-if #'evenp '(1 2 3 4))` | `(1 3)`(述語を満たす要素を除いた新しいリスト) |
| `remove-if-not` | `(remove-if-not #'evenp '(1 2 3 4))` | `(2 4)`(述語を満たす要素のみを残した新しいリスト) |
| `remove-duplicates` | `(remove-duplicates '(1 2 1 3))` | `(2 1 3)`(重複要素を除き、最後の出現を残したコピー。既定では `eql` 比較で、省略可能な `:test`/`:key` キーワードを取ります) |
| `delete` | `(delete 2 '(1 2 3 2))` | `(1 3)`(破壊的な `remove`。マッチするセルをその場で切り出します。省略可能な `:test`/`:key` キーワードを取ります。先頭が変わる場合があるので戻り値を使ってください) |
| `delete-if` | `(delete-if #'evenp '(1 2 3 4))` | `(1 3)`(破壊的な `remove-if`) |
| `delete-if-not` | `(delete-if-not #'evenp '(1 2 3 4))` | `(2 4)`(破壊的な `remove-if-not`) |
| `substitute` | `(substitute 0 2 '(1 2 3 2))` | `(1 0 3 0)`(旧要素と `eql` になるすべての要素を新要素に置き換えたコピー。省略可能な `:test`/`:key` キーワードを取ります) |
| `nsubstitute` | `(nsubstitute 0 2 '(1 2 3 2))` | `(1 0 3 0)`(破壊的な `substitute`。マッチするcarをその場で書き換えます。省略可能な `:test`/`:key` キーワードを取ります) |
| `nconc` | `(nconc (list 1 2) (list 3 4) (list 5))` | `(1 2 3 4 5)`(任意個数のリストを破壊的に連結し、最初の非 `nil` 引数を返します) |
| `copy-list` | `(copy-list '(1 2 3))` | `(1 2 3)`(リストの浅いコピー) |
| `nreverse` | `(nreverse '(1 2 3))` | `(3 2 1)`(各 `cdr` を繋ぎ替えてリストを破壊的に反転します。戻り値を使ってください) |
| `make-list` | `(make-list 3)` | `(nil nil nil)`(n個のnil要素のリスト。`:initial-element` なし) |
| `union` | `(union '(1 2 3) '(2 3 4))` | `(4 1 2 3)`(集合の和。既定では `eql` 比較で、省略可能な `:test`/`:key` キーワードを取ります。結果順序は未規定) |
| `intersection` | `(intersection '(1 2 3) '(2 3 4))` | `(3 2)`(集合の積。既定では `eql` 比較で、省略可能な `:test`/`:key` キーワードを取ります。結果順序は未規定) |
| `set-difference` | `(set-difference '(1 2 3) '(2))` | `(3 1)`(第1リストにあって第2リストにない要素。既定では `eql` 比較で、省略可能な `:test`/`:key` キーワードを取ります。結果順序は未規定) |
| `adjoin` | `(adjoin 1 '(2 3))` | `(1 2 3)`(すでにメンバーでない限り要素を先頭に追加します。既定では `eql` 比較で、省略可能な `:test`/`:key` キーワードを取ります) |
| `list*` | `(list* 1 2 '(3 4))`, `(list* 1 2 3)` | `(1 2 3 4)`, `(1 2 . 3)`(先頭の引数を最後の引数の末尾にconsします) |
| `acons` | `(acons 'a 1 nil)` | `((a . 1))`(`(key . value)` ペアを連想リストの先頭に追加します) |
| `endp` | `(endp nil)`, `(endp '(1))` | `t`, `nil`(リスト終端テスト。`null` の同義語で、不正リストのエラーは緩和されています) |
| `elt` | `(elt '(a b c) 1)` | `b`(0始まりの要素アクセス。リストのみで文字列インデックスはありません) |
| `rassoc` | `(rassoc 2 '((a . 1) (b . 2)))` | `(b . 2)`(cdrが値に一致する最初のペア、またはnil。既定では `eql` で比較し、省略可能な `:test`/`:key` キーワードを取ります) |
| `pairlis` | `(pairlis '(a b) '(1 2))` | `((a . 1) (b . 2))`(キーのリストと値のリストを組にして連想リストを作ります。省略可能な第3引数は末尾に連結されます) |
| `copy-alist` | `(copy-alist '((a . 1)))` | `((a . 1))`(連想リストの背骨と各ペアセルをコピーします。キーと値自体は共有されます) |
| `revappend` | `(revappend '(1 2 3) '(4 5))` | `(3 2 1 4 5)`(第1リストを反転して第2リストを追加します) |
| `nreconc` | `(nreconc '(1 2 3) '(4 5))` | `(3 2 1 4 5)`(破壊的な `revappend`。`(nconc (nreverse x) y)` に展開され、第1リストのconsセルを再利用します) |
| `maplist` | `(maplist #'identity '(1 2 3))` | `((1 2 3) (2 3) (3))`(連続する末尾に適用し、結果を集めます。単一リスト形式) |
| `mapcon` | `(mapcon (lambda (x) (list (car x))) '(1 2 3))` | `(1 2 3)`(連続する末尾に適用し、結果リストを連結します。単一リスト形式) |
| `sort` | `(sort '(3 1 2) #'<)` | `(1 2 3)`(比較述語でリストを破壊的にソートします。安定ではありません) |
| `rplaca` | `(rplaca x val)` | consセルのcarを破壊的に置き換え、そのセルを返します |
| `rplacd` | `(rplacd x val)` | consセルのcdrを破壊的に置き換え、そのセルを返します |
| `1+` | `(1+ 41)` | `42`(`(+ x 1)` と同じ) |
| `1-` | `(1- 43)` | `42`(`(- x 1)` と同じ) |
| `zerop` | `(zerop 0)` | `t` |
| `plusp` | `(plusp 3)` | `t` |
| `minusp` | `(minusp -3)` | `t` |
| `evenp` | `(evenp 4)` | `t` |
| `oddp` | `(oddp 3)` | `t` |
| `abs` | `(abs -5)`, `(abs -3.14)` | `5`, `3.14` |
| `min` | `(min 3 5)`, `(min 5 2 8 1)` | `3`, `1`(可変長引数) |
| `max` | `(max 3 5)`, `(max 5 2 8 1)` | `5`, `8`(可変長引数) |
| `float` | `(float 42)` | `42.0`(doubleに変換) |
| `truncate` | `(truncate 3.7)`, `(truncate -7 2)` | `3`, `-3`(ゼロ方向。除数を与えると除算の商になり、剰余は `multiple-value-bind` で観測できます) |
| `floor` | `(floor 3.7)`, `(floor 7 2)` | `3`, `3`(負の無限大方向。除数を与えると除算の商になり、剰余は `multiple-value-bind` で観測できます) |
| `ceiling` | `(ceiling 3.2)`, `(ceiling 7 2)` | `4`, `4`(正の無限大方向。除数を与えると除算の商になります) |
| `round` | `(round 3.5)`, `(round 2.5)` | `4`, `2`(銀行家の丸め。オプションの除数を与えると除算の商を丸めます) |
| `sqrt` | `(sqrt 16)`, `(sqrt 2)` | `4.0`, `1.4142135623730951`(常に浮動小数点) |
| `isqrt` | `(isqrt 17)` | `4`(整数平方根、実数根の床) |
| `expt` | `(expt 2 10)`, `(expt 2.0 3)` | `1024`, `8.0` |
| `random` | `(random 100)`, `(random 1.0)` | `[0, 100)` / `[0.0, 1.0)` の範囲の値(結果型は上限に従います。`(random 1)` は常に `0`)。インタプリタとJVMは `Math.random` から取得します。WASMはPreview 1モードではWASIの `random_get` ホスト関数から、`--component` モードでは `wasi:random@0.3.0` から実際のエントロピーを取得するため、列は実行ごとに異なります |
| `get-universal-time` | `(get-universal-time)` | 1900-01-01 GMTからの秒数。インタプリタとJVMは整数を返します。WASMはクロック(Preview 1では実際のホストクロック、`--component` モードでは `wasi:clocks@0.3.0`)を読み、31ビット整数では値を保持できないため **浮動小数点** を返します(そのため生の値を印字するのではなく比較/差分で使ってください) |
| `get-internal-real-time` | `(get-internal-real-time)` | 経過実時間(ミリ秒)(インタプリタ/JVMでは整数、WASMでは浮動小数点) |
| `get-internal-run-time` | `(get-internal-run-time)` | 消費した実行時間(ミリ秒)(インタプリタ/JVMでは整数、WASMでは浮動小数点) |
| `getenv` | `(getenv "PATH")` | 環境変数の値を文字列として、未設定の場合は `nil` を返します。3つのバックエンドすべて。WASMはPreview 1では実際のホスト環境を、`--component` モードでは `wasi:cli/environment@0.3.0` を読みます(wasmtimeに `--env`/`-S inherit-env` を渡してください) |
| `exp` | `(exp 0)` | `1.0`(インタプリタ/JVMは `Math.exp` を使用。WASMはソフトウェア近似を使用) |
| `log` | `(log 1)` | `0.0`(自然対数。インタプリタ/JVMのみ) |
| `sin` `cos` `tan` | `(sin 0)`, `(cos 0)` | `0.0`, `1.0`(インタプリタ/JVMのみ) |
| `asin` `acos` `atan` | `(atan 0)` | `0.0`(インタプリタ/JVMのみ) |
| `sinh` `cosh` `tanh` | `(tanh 0)` | `0.0`(インタプリタ/JVMのみ) |
| `gcd` | `(gcd 12 18)`, `(gcd 24 36 60)` | `6`, `12`(可変長引数。最大公約数、`(gcd)` は `0`) |
| `lcm` | `(lcm 4 6)`, `(lcm 2 3 4)` | `12`, `12`(可変長引数。最小公倍数。いずれかの引数が `0` なら `0`、`(lcm)` は `1`) |
| `signum` | `(signum -5)`, `(signum 3.5)` | `-1`, `1.0`(符号。整数/浮動小数点の型を保ちます) |
| `logand` | `(logand 12 10)`, `(logand 12 10 6)` | `8`, `0`(可変長引数のビット単位AND。`(logand)` は `-1`) |
| `logior` | `(logior 12 10)`, `(logior 1 2 4 8)` | `14`, `15`(可変長引数のビット単位OR。`(logior)` は `0`) |
| `logxor` | `(logxor 12 10)` | `6`(可変長引数のビット単位XOR。`(logxor)` は `0`) |
| `lognot` | `(lognot 5)` | `-6`(ビット単位NOT、すなわち1の補数) |
| `ash` | `(ash 1 4)`, `(ash 255 -4)` | `16`, `15`(算術シフト。非負のカウントなら左、それ以外は右) |
| `funcall` | `(funcall #'+ 3 4)` | 関数を引数に適用します。関数値(`#'f`、ラムダ)または関数を指すシンボル(`(funcall 'car ...)`)を受け付けます |
| `mapcar` | `(mapcar #'car '((1 2) (3 4)))` | 各要素に関数を適用し、新しいリストを返します |
| `map` | `(map 'list #'+ '(1 2 3) '(10 20 30))` | `(11 22 33)`(シーケンス(リスト/文字列)を最短のものまでマッピングし、`'list`/`'string` の結果を構築、または副作用のため nil を返す) |
| `mapc` | `(mapc #'print '(1 2 3))` | 副作用のために各要素に関数を適用し、元のリストを返します |
| `mapcan` | `(mapcan (lambda (x) (list x x)) '(1 2))` | `(1 1 2 2)`(関数を適用し結果リストを連結します。非破壊的な `append` を使用) |
| `apply` | `(apply #'+ 1 2 '(3 4))` | `10`(先頭の引数と展開された最終リストに関数を適用します) |
| `values` | `(values 1 2 3)`, `(multiple-value-list (values 1 2 3))` | `1`, `(1 2 3)` -- 通常の文脈では主値だけが残ります。`multiple-value-bind`/`-list`/`-call`/`nth-value` はリテラルの `(values ...)` 呼び出し、多値の組み込み関数（`floor` ファミリ、`gethash`、`parse-integer`、`values-list`）、`(values ...)` を返すユーザ関数の全ての値を受け取ります |
| `reduce` | `(reduce #'+ '(1 2 3) :initial-value 0)` | 左畳み込み: `(f (f (f init a) b) c)`。素の形式 `(reduce f list)` は最初の要素を初期値に使います。`:initial-value` キーワード(リテラル)は明示的な初期値を与えます |
| `every` | `(every #'evenp '(2 4 6))` | すべての要素で述語が非nilなら `t`、そうでなければ `nil`(単一リスト形式) |
| `some` | `(some #'oddp '(2 4 5))` | 最初の非nilな述語結果、すべての要素が失敗すれば `nil`(単一リスト形式) |
| `notany` | `(notany #'evenp '(1 3 5))` | すべての要素で述語がnilなら `t`、そうでなければ `nil`(`some` の補) |
| `notevery` | `(notevery #'evenp '(2 4 5))` | いずれかの要素で述語がnilなら `t`、そうでなければ `nil`(`every` の補) |
| `symbol-function` | `(symbol-function 'car)` | シンボルが指す関数を返します(コンパイラ: 引数は引用されたシンボルリテラルでなければなりません) |
| `identity` | `(identity 42)` | `42`(引数をそのまま返します) |
| `make-hash-table` | `(make-hash-table)`, `(make-hash-table :test 'equal)` | 空のハッシュテーブルを作成します。`:test` は受け付けられますが情報的なものです(下記の注記を参照)。`:size` などの他のキーワードは無視されます |
| `gethash` | `(gethash key table)`, `(gethash key table default)` | `key` に格納された値、なければ `default`(省略時はnil)を返します |
| `(setf (gethash key table) v)` | `(setf (gethash "a" h) 1)` | `key` の下に `v` を格納します。placeに対する `incf`/`decf`/`push` と組み合わせて使えます |
| `remhash` | `(remhash key table)` | `key` のエントリを削除します。削除されたら `t`、そうでなければ `nil` を返します |
| `clrhash` | `(clrhash table)` | すべてのエントリを削除します。テーブルを返します |
| `hash-table-count` | `(hash-table-count table)` | エントリ数 |
| `hash-table-p` | `(hash-table-p x)` | `x` がハッシュテーブルなら `t`、そうでなければ `nil` |
| `maphash` | `(maphash (lambda (k v) ...) table)` | 副作用のために各キー/値ペアに関数を呼びます。nilを返します |
| `make-array` | `(make-array 5 :initial-element 0)`, `(make-array (list 2 3))` | 任意の階数の配列を作成します。`:initial-element` はすべてのセルを設定します(省略時はnil) |
| `aref` | `(aref a i)`, `(aref a i j)` | 指定した添字の要素を返します |
| `(setf (aref a i j) v)` | `(setf (aref a 0 0) 1)` | 添字の位置に `v` を格納します。placeに対する `incf`/`decf`/`push` と組み合わせて使えます |
| `vector` | `(vector 1 2 3)` | `#(1 2 3)`(引数からなる新しい階数1の配列) |
| `svref` | `(svref (vector 10 20 30) 1)` | `20`(ベクタの要素アクセス。`setf` のplaceとしても使えます) |
| `array-dimensions` | `(array-dimensions (make-array (list 2 3)))` | `(2 3)`(各次元のサイズのリスト) |
| `array-dimension` | `(array-dimension (make-array (list 2 3)) 1)` | `3`(指定した軸のサイズ。0始まり) |
| `array-rank` | `(array-rank (vector 1 2))` | `1`(階数2の配列では `2`、以降も同様) |
| `array-total-size` | `(array-total-size (make-array (list 2 3)))` | `6`(要素の総数) |
| `row-major-aref` | `(row-major-aref (make-array (list 2 3)) 4)` | フラットな行優先インデックスの要素。階数に依存せず、`setf` の場所としても使えます |
| `array-row-major-index` | `(array-row-major-index (make-array (list 2 3)) 1 1)` | `4`(添字のフラットな行優先インデックス) |
| `coerce` | `(coerce '(1 2 3) 'vector)`, `(coerce "ab" 'list)` | `#(1 2 3)`、`(#\a #\b)`(結果型はリテラルの `'list`/`'vector`/`'string` のみ) |
| `fill-pointer` | `(fill-pointer v)` | `:fill-pointer` ベクタのフィルポインタ(実効長)。`setf` 可能な場所でもある |
| `array-has-fill-pointer-p` | `(array-has-fill-pointer-p a)` | 配列がフィルポインタを持てば `t`、そうでなければ `nil` |
| `adjustable-array-p` | `(adjustable-array-p a)` | 配列が `:adjustable` で作成されていれば `t`、そうでなければ `nil` |
| `array-element-type` | `(array-element-type a)` | 常に `t`(要素型は追跡されない) |
| `vector-push` | `(vector-push x v)` | フィルポインタの位置に `x` を格納しインデックスを返す。満杯なら `nil` |
| `vector-pop` | `(vector-pop v)` | フィルポインタをデクリメントし、通過した要素を返す |
| `vector-push-extend` | `(vector-push-extend x v &optional ext)` | `vector-push` と同様だが満杯時にベクタを拡張する |

## rontolisp パッケージの関数

`rontolisp` パッケージは **Common Lispの一部ではない**
実装固有の関数を提供します。`rontolisp:` 修飾子で参照する(または `(in-package rontolisp)`
の後に修飾なしで)使用してください。パッケージシステムについては
[パッケージ](packages.md) を参照してください。以下の各名前はそれぞれのページにリンクしています。

| Function | Example | Result |
|----------|---------|--------|
| `rontolisp:version` | `(rontolisp:version)` | ビルド情報のプロパティリスト(`:version`, `:build-timestamp`, `:git-commit`, `:git-branch`) |
| `rontolisp:list-functions` | `(rontolisp:list-functions :cl)` | パッケージの関数シンボルをソートしたもの(デフォルトは `:cl`) |
| `rontolisp:list-macros` | `(rontolisp:list-macros)` | パッケージのマクロシンボルをソートしたもの |
| `rontolisp:list-special-forms` | `(rontolisp:list-special-forms)` | パッケージの特殊形式シンボルをソートしたもの |
| `rontolisp:fetch` | `(rontolisp:fetch "http://example.com/")` | HTTPリクエストを非同期に開始します。プロミスを返します |
| `rontolisp:await` | `(rontolisp:await p)` | プロミスを解決します (ブロッキング)。プロミス以外はそのまま返します |
| `rontolisp:then` | `(rontolisp:then p (lambda (r) (getf r :status)))` | 確定値にコールバックを適用する新しいプロミスを導出します |
| `rontolisp:promisep` | `(rontolisp:promisep p)` | 値がプロミスなら `t` |
| `rontolisp:http-handler` | `(rontolisp:http-handler 'handle 8080)` | ハンドラ関数でHTTPリクエストを処理します（ブロッキングサーバ。`--component` では `wasi:http` コンポーネント） |
| `rontolisp:json-parse` | `(rontolisp:json-parse "{\"n\": 1}")` | JSON文字列をパースします: オブジェクトはキーワードのplist（`:hash-table` 指定でハッシュテーブル）になります |
| `rontolisp:json-stringify` | `(rontolisp:json-stringify (list :n 1))` | 値（plistとハッシュテーブルはオブジェクト）をJSON文字列にシリアライズします |
| `rontolisp:tcp-connect` | `(rontolisp:tcp-connect "127.0.0.1" 7777)` | ブロッキングTCP接続を開きます。双方向ストリームハンドルを返します |
| `rontolisp:tcp-listen` | `(rontolisp:tcp-listen 7777)`, `(rontolisp:tcp-listen 0 "127.0.0.1")` | リスニングTCPソケットをバインドしてリスナーハンドルを返します。ポート `0` は空きエフェメラルポートを選びます |
| `rontolisp:tcp-accept` | `(rontolisp:tcp-accept listener)` | クライアント接続を待ちます (ブロッキング)。双方向ストリームハンドルを返します |
| `rontolisp:tcp-local-port` | `(rontolisp:tcp-local-port listener)` | リスナーまたはソケットが実際にバインドされているローカルポート |
| `rontolisp:tls-connect` | `(rontolisp:tls-connect "example.com" 443)` | 暗号化（TLS）クライアント接続を開きます。`tcp-connect` と同じ種類のストリームハンドルを返します |
| `rontolisp:tls-listen` | `(rontolisp:tls-listen "server.p12" "changeit" 8443)` | PKCS12キーストアから暗号化リスニングソケットをバインドします。`tcp-accept` で受け付けます |
| `rontolisp:tls-listen-pem` | `(rontolisp:tls-listen-pem "cert.pem" "key.pem" 8443)` | PEMの証明書／鍵ファイルから暗号化リスニングソケットをバインドします |
| `rontolisp:wasm-export` | `(rontolisp:wasm-export 'fact :params '(:int) :returns :int)` | WASMコアモジュールへのコンパイル時に `defun` をホストから呼び出し可能にします |
| `rontolisp:wasm-import` | `(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)` | WASMコアモジュールへのコンパイル時に、ホスト関数をLispから呼び出し可能として宣言します |

イントロスペクション関数(`list-functions` / `list-macros` /
`list-special-forms`)については
[パッケージのイントロスペクション](packages.md#package-introspection)
で詳しく説明しています。`rontolisp:fetch`
は外向きのHTTPリクエストを開始してプロミスを返し、汎用のプロミス操作 `rontolisp:await` / `rontolisp:then` / `rontolisp:promisep` がそれを解決します。全体像は
[HTTPリクエストガイド](../guides/http-fetch.md)を、オプション、結果plist、バックエンドのサポート、制限については
[fetch](functions/rontolisp-fetch.md)、
[await](functions/rontolisp-await.md)、
[then](functions/rontolisp-then.md)、
[promisep](functions/rontolisp-promisep.md) のリファレンスページを参照してください。`rontolisp:http-handler` は `fetch` の受信側で、同じリクエスト／レスポンスのプロパティリストを使ってハンドラ関数でHTTPリクエストを処理します。各バックエンドでの実例は
[HTTPサーバガイド](../guides/http-handler.md)を、バックエンドのサポートと制限は
[http-handler](functions/rontolisp-http-handler.md) のリファレンスページを参照してください。`rontolisp:json-parse` と `rontolisp:json-stringify` はJSONドキュメントとLispの値を相互変換します（JavaScriptの `JSON.parse`/`JSON.stringify` 相当。fetchレスポンスボディのパースなどに使えます）。値の対応と制限については
[json-parse](functions/rontolisp-json-parse.md) と
[json-stringify](functions/rontolisp-json-stringify.md) のリファレンスページを参照してください。tcp関数（`rontolisp:tcp-connect` / `tcp-listen` / `tcp-accept` / `tcp-local-port`）は素のTCPソケットを開き、そのハンドルには標準のストリーム関数（`read-line` / `write-line` / `read-byte` / `write-byte` / `close`）がそのまま使えます。echoサーバーの実例は
[TCPソケットガイド](../guides/tcp-sockets.md)を、バックエンドのサポートと制限は
[tcp-connect](functions/rontolisp-tcp-connect.md)、
[tcp-listen](functions/rontolisp-tcp-listen.md)、
[tcp-accept](functions/rontolisp-tcp-accept.md)、
[tcp-local-port](functions/rontolisp-tcp-local-port.md) のリファレンスページを参照してください。TLS版（`rontolisp:tls-connect` / `tls-listen` / `tls-listen-pem`）は同じストリームハンドルをTLSで包みます。
[tls-connect](functions/rontolisp-tls-connect.md)、
[tls-listen](functions/rontolisp-tls-listen.md)、
[tls-listen-pem](functions/rontolisp-tls-listen-pem.md) のリファレンスページを参照してください。`rontolisp:wasm-export` と `rontolisp:wasm-import`
はWASMバックエンド向けのコンパイル時ディレクティブです。
[wasm-export](functions/rontolisp-wasm-export.md) と
[wasm-import](functions/rontolisp-wasm-import.md) のリファレンスページ、および
[WebAssemblyへのコンパイル](../compiling/wasm.md) ガイドを参照してください。

## linalg パッケージの関数

`linalg` パッケージは、組み込みの配列に対する numpy
スタイルのベクトル・行列演算を提供します(要素ごとの演算とリダクションは任意の階数で動作します)。**Common Lispの一部ではありません**。
関数は `linalg:` 修飾子で参照してください(このパッケージは `cl` を使用しないため、
通常は `cl-user` に留まり修飾名で呼び出します)。パッケージはLispソースで一度だけ
実装されており、すべてのバックエンドで同一に動作します。算術は整数・有理数入力に
対して厳密で、整数行列の `det`・`inv`・`solve` は厳密な有理数を返します。
以下の各名前はそれぞれのページにリンクしています。概要と実例は
[ベクトルと行列ガイド](../guides/linear-algebra.md)を参照してください。

| Function | Example | Result |
|----------|---------|--------|
| `linalg:zeros` | `(linalg:zeros 3)`, `(linalg:zeros '(2 2))` | `#(0 0 0)`、`#2A((0 0) (0 0))`(shapeは整数または `(rows cols)` のリスト) |
| `linalg:ones` | `(linalg:ones '(2 2))` | `#2A((1 1) (1 1))` |
| `linalg:full` | `(linalg:full '(2 2) 7)` | `#2A((7 7) (7 7))` |
| `linalg:eye` | `(linalg:eye 2)` | `#2A((1 0) (0 1))`(単位行列) |
| `linalg:arange` | `(linalg:arange 5)`, `(linalg:arange 2 10 2)` | `#(0 1 2 3 4)`、`#(2 4 6 8)`(stopは含まない。stepは負も可) |
| `linalg:linspace` | `(linalg:linspace 0 1 5)` | `#(0 1/4 1/2 3/4 1)`(両端を含むn等分の値) |
| `linalg:from-list` | `(linalg:from-list '((1 2) (3 4)))` | `#2A((1 2) (3 4))`(フラットなリストからはベクタ) |
| `linalg:to-list` | `(linalg:to-list (linalg:eye 2))` | `((1 0) (0 1))` |
| `linalg:shape` | `(linalg:shape #2A((1 2 3) (4 5 6)))` | `(2 3)` |
| `linalg:size` | `(linalg:size (linalg:eye 3))` | `9`(要素の総数) |
| `linalg:reshape` | `(linalg:reshape (linalg:arange 6) '(2 3))` | `#2A((0 1 2) (3 4 5))`(行優先。サイズは一致が必要) |
| `linalg:flatten` | `(linalg:flatten (linalg:eye 2))` | `#(1 0 0 1)` |
| `linalg:transpose` | `(linalg:transpose #2A((1 2 3) (4 5 6)))` | `#2A((1 4) (2 5) (3 6))`(ベクタはそのまま返します) |
| `linalg:add` | `(linalg:add #(1 2 3) 10)` | `#(11 12 13)`(要素ごと。スカラーのオペランドはブロードキャスト) |
| `linalg:sub` | `(linalg:sub #(5 5) 1)` | `#(4 4)` |
| `linalg:mul` | `(linalg:mul m1 m2)` | アダマール積(要素ごとの積)。行列積ではありません |
| `linalg:div` | `(linalg:div #(1 2 3) 2)` | `#(1/2 1 3/2)`(整数の除算は厳密な有理数) |
| `linalg:emap` | `(linalg:emap (lambda (x) (* x x)) (linalg:arange 4))` | `#(0 1 4 9)`(全要素に関数を適用) |
| `linalg:dot` | `(linalg:dot v1 v2)` | numpyスタイルのディスパッチ: ベクタ.ベクタはスカラー、行列.ベクタ / ベクタ.行列はベクタ、行列.行列は行列積 |
| `linalg:matmul` | `(linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8)))` | `#2A((19 22) (43 50))`(行列積) |
| `linalg:outer` | `(linalg:outer #(1 2) #(3 4 5))` | `#2A((3 4 5) (6 8 10))`(外積) |
| `linalg:sum` | `(linalg:sum #2A((1 2) (3 4)))` | `10` |
| `linalg:mean` | `(linalg:mean #(1 2 3 4))` | `5/2`(整数入力に対しては厳密) |
| `linalg:amax` | `(linalg:amax #2A((1 9) (3 4)))` | `9`(最大の要素) |
| `linalg:amin` | `(linalg:amin #(5 2 8))` | `2`(最小の要素) |
| `linalg:argmax` | `(linalg:argmax #(1 9 3))` | `1`(ベクタのみ。同値の場合は最初のインデックス) |
| `linalg:argmin` | `(linalg:argmin #(5 2 8))` | `1` |
| `linalg:norm` | `(linalg:norm #(3 4))` | `5.0`(ユークリッド / フロベニウスノルム) |
| `linalg:trace` | `(linalg:trace #2A((1 2) (3 4)))` | `5`(正方行列のみ) |
| `linalg:det` | `(linalg:det #2A((1 2) (3 4)))` | `-2`(整数・有理数入力に対しては厳密) |
| `linalg:inv` | `(linalg:inv #2A((1 2) (3 4)))` | `#2A((-2 1) (3/2 -1/2))`(特異行列ではエラーを通知します) |
| `linalg:solve` | `(linalg:solve a b)` | `a . x = b` の解(`b` はベクタまたは行列) |
| `linalg:array-equal` | `(linalg:array-equal (linalg:eye 2) #2A((1 0) (0 1)))` | `t`(同じ形状かつ数値的に等しい要素。配列自体は `eq` でしか比較できません) |

## java パッケージの関数

`java` パッケージはリフレクションで任意の Java API を操作します。**JVM 専用**であり、インタプリタ (`java -jar rontolisp.jar`) と JVM コンパイル済みクラス (コンパイラがリフレクションブリッジを生成 `.class` に埋め込みます) で動作します (WASM バックエンドでは動作せず、GraalVM ネイティブバイナリはリフレクションメタデータを持たないためインタプリタ実行もできません)。また **Common Lisp の一部ではありません**。関数は `java:` 修飾子付きで参照します。各名前は個別のページにリンクしています。マーシャリング、オーバーロード解決、制限については [Java 連携ガイド](../guides/java-interop.md)を参照してください。

| 関数 | 例 | 結果 |
|----------|---------|--------|
| `java:new` | `(java:new "java.lang.StringBuilder" "ab")` | ホストオブジェクト (`#<java ...>`) |
| `java:call` | `(java:call obj "size")` | マーシャリングされたインスタンスメソッドの結果 |
| `java:static` | `(java:static "java.lang.Math" "max" 3 7)` | マーシャリングされた静的メソッドの結果 |
| `java:field` | `(java:field "java.lang.Integer" "MAX_VALUE")` | マーシャリングされたフィールド値 |
| `java:proxy` | `(java:proxy "java.lang.Runnable" (lambda (m) ...))` | callable を背後に持つインターフェースのインスタンス |

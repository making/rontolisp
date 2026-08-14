# uiop/utility

`uiop/utility` は uiop の他のすべてがその上に書かれている層です。文字列、リスト、
プロパティリスト、ハッシュテーブル、タイムスタンプ、コンディションのヘルパで、
オペレーティングシステムを一切必要としません。**68 個のエクスポートすべてが実装済み**
であり、ファイルシステムもサブプロセスもネットワークも触らないため、そのすべてが
4 つのバックエンド — インタプリタ、JVM、2 つの WASM 出力 — で動作します。

どの名前もどちらの綴りからでも到達できます。`uiop:strcat` と
`uiop/utility:strcat` は同じ関数です
([uiop パッケージ](../uiop.md#sub-packages))。

## 文字列

| 関数 | 内容 |
|----------|--------------|
| `uiop:strcat` | 文字列指示子を連結します。`nil` は空文字列、文字は長さ 1 の文字列として扱われます |
| `uiop:reduce/strcat` | リストに対する `strcat`。`:key`、`:start`、`:end` は `reduce` と同じ意味です |
| `uiop:string-prefix-p` | 文字列がその接頭辞で始まるか |
| `uiop:string-suffix-p` | 文字列がその接尾辞で終わるか |
| `uiop:string-enclosed-p` | 両方を同時に |
| `uiop:stripln` | 末尾の CR・LF・CRLF を取り除きます。取り除いた後の文字列と取り除いた終端の 2 値を返します |
| `uiop:frob-substrings` | 複数の部分文字列を左から順に置換 (または削除) します。先に一致した範囲の内側は対象になりません |
| `uiop:first-char` / `uiop:last-char` | 空でない文字列の最初 / 最後の文字。そうでなければ `nil` |
| `uiop:split-string` | 区切り文字列のいずれかの文字で分割します |
| `uiop:emptyp` | `nil` および長さ 0 のベクタ・文字列に対して真 |
| `uiop:+cr+` / `uiop:+lf+` / `uiop:+crlf+` | 3 種類の改行を文字列として |
| `uiop:standard-case-symbol-name` | 名前指示子を文字列として。文字列なら大文字化します |
| `uiop:find-standard-case-symbol` | その名前をパッケージから引きます |

`strcat` の寛容さがこの関数の要点です。省略可能な断片を、条件分岐で包まずに
そのまま連結できます。

```lisp
(print (uiop:strcat "a" nil #\b "c"))
(print (uiop:reduce/strcat (list "aa" "bb" "cc") :start 1))
(print (list (uiop:string-prefix-p "ab" "abc")
             (uiop:string-suffix-p "abc" "bc")
             (uiop:string-enclosed-p "a" "abc" "c")))
(print (uiop:frob-substrings "hello world" (list "o") "0"))
```

```
"abc"
"bbcc"
(T T T)
"hell0 w0rld"
```

`stripln` は取り除いたものを第 2 値として返すので、両者を `strcat` すれば元の行が
復元できます。

```lisp
(multiple-value-bind (line ending) (uiop:stripln (uiop:strcat "hi" uiop:+crlf+))
  (print (list line (length ending)))
  (print (string= (uiop:strcat line ending) (uiop:strcat "hi" uiop:+crlf+))))
```

```
("hi" 2)
T
```

## リスト・プロパティリスト・ハッシュテーブル

| 関数 | 内容 |
|----------|--------------|
| `uiop:ensure-list` | リストでないものを 1 要素のリストで包みます |
| `uiop:length=n-p` | リストの長さがちょうど `n` か。`n` を超えて辿りません |
| `uiop:appendf` | `(appendf place list...)`、すなわち `(setf place (append place list...))` |
| `uiop:remove-plist-key` / `uiop:remove-plist-keys` | 指定したキーを除いたプロパティリスト — キーワード引数の整理用です |
| `uiop:ensure-gethash` | エントリを返し、無ければデフォルトを計算して格納します。第 2 値が既に存在したかを示します |
| `uiop:list-to-hash-set` | リストを `equal` のハッシュ集合に |
| `uiop:lexicographic<` / `uiop:lexicographic<=` | 与えた `element<` で 2 つのリストを要素ごとに比較します |

```lisp
(print (uiop:remove-plist-keys (list :b :c) (list :a 1 :b 2 :c 3)))
(print (let ((l (list 1))) (uiop:appendf l (list 2 3)) l))
(print (let ((h (make-hash-table :test 'equal)))
         (list (multiple-value-list (uiop:ensure-gethash "k" h (constantly 5)))
               (multiple-value-list (uiop:ensure-gethash "k" h (constantly 6))))))
```

```
(:A 1)
(1 2 3)
((5 NIL) (5 T))
```

## タイムスタンプ

タイムスタンプは実数または真偽値で、`t` がマイナス無限大、`nil` がプラス無限大です。
つまり存在しないファイルは「無限に古く」、不明なファイルは「無限に新しい」— ASDF が
ビルド順序を決めるときの考え方です。

| 関数 | 内容 |
|----------|--------------|
| `uiop:timestamp<` / `uiop:timestamp<=` | 2 つのタイムスタンプを比較します |
| `uiop:timestamps<` / `uiop:timestamp*<` | リスト (または引数列) が狭義単調増加か |
| `uiop:earlier-timestamp` / `uiop:later-timestamp` | 2 つのうち小さい方 / 大きい方 |
| `uiop:timestamps-earliest` / `uiop:timestamps-latest` | リストに対して |
| `uiop:earliest-timestamp` / `uiop:latest-timestamp` | 引数列に対して |
| `uiop:latest-timestamp-f` | `(latest-timestamp-f place timestamp...)`。場所に累積します |

```lisp
(print (list (uiop:timestamp< 1 2) (uiop:timestamp< t 3) (uiop:timestamp< 3 nil)))
(print (list (uiop:earliest-timestamp 3 1 2) (uiop:latest-timestamp 3 1 2)))
(print (let ((newest 1)) (uiop:latest-timestamp-f newest 5 3) newest))
```

```
(T T T)
(1 3)
5
```

`timestamps<` は `nil` = プラス無限大から比較を始めるため、空でないリストが
「増加している」ことは決してありません — 本家と同じ答えで、直さずにそのままにしてあります。

## 関数指示子

`uiop:ensure-function` は*指示子*を関数に変換します。関数はそれ自身、定数 (真偽値・
キーワード・文字・数値・パス名) は `(constantly それ)`、ハッシュテーブルはその参照関数、
シンボルはその `fdefinition`、コンスは部分適用された呼び出し (または評価される
`lambda` 形式)、文字列は読み込まれて関数名として評価されます。

| 関数 | 内容 |
|----------|--------------|
| `uiop:ensure-function` | 上記の変換 |
| `uiop:call-function` | `(apply (ensure-function spec) args)` |
| `uiop:call-functions` | リストに対して順に `call-function` |
| `uiop:access-at` | アクセサの連鎖を適用します。整数は `elt`、キーワードは `getf`、`nil` は恒等、シンボルや関数は呼び出し、コンスは `ensure-function` です |
| `uiop:access-at-count` | `access-at` の指定が読む部分オブジェクトの個数 |
| `uiop:register-hook-function` | フックを変数に push します — [未実装のもの](#what-is-missing)を参照 |

```lisp
(print (funcall (uiop:ensure-function 'car) (list 9 8)))
(print (uiop:call-function (list '+ 1) 2))
(print (uiop:access-at (list :a (list 10 20)) (list :a 1)))
```

```
9
3
20
```

## コンディション

| 名前 | 内容 |
|------|--------------|
| `uiop:not-implemented-error` | この実装が持たない操作を名指しするコンディションと、それをシグナルする関数 |
| `uiop:parameter-error` | 操作は存在するが、そのパラメータの組み合わせは受け付けない |
| `uiop:simple-style-warning` | uiop 自身のスタイル警告 — 本物の `style-warning` なので標準の型に対するハンドラで捕捉できます |
| `uiop:style-warn` | フォーマット文字列・コンディション型・コンディションからスタイル警告をシグナルします |
| `uiop:match-condition-p` | コンディションがパターンに一致するか (型名、`#(name package)` ベクタ、述語、`simple-condition` のフォーマット文字列) |
| `uiop:match-any-condition-p` | 複数のパターンのいずれかに |
| `uiop:call-with-muffled-conditions` | 一致するコンディションを抑止してサンクを実行します |
| `uiop:with-muffled-conditions` | そのマクロ版 |
| `uiop:boolean-to-feature-expression` | `(:and)` または `(:or)` — 常に真 / 常に偽の `#+` テスト |
| `uiop:symbol-test-to-feature-expression` | 「このパッケージはこの名前をエクスポートしているか」から同じものを |

```lisp
(print (uiop:with-muffled-conditions ('(warning)) (warn "not shown") :muffled))
(print (handler-bind ((style-warning (lambda (c) (muffle-warning c))))
         (uiop:style-warn "deprecated: ~A" 'old-name)
         :warned))
(print (list (uiop:boolean-to-feature-expression t)
             (uiop:symbol-test-to-feature-expression "CAR" :cl)))
```

```
:MUFFLED
:WARNED
((:AND) (:AND))
```

知っておくべき差異が 1 つあります。`match-condition-p` の文字列パターンは
`simple-condition-format-control` と比較されますが、rontolisp ではそこに既に整形済みの
メッセージが入っています。したがってフォーマット指示子を含むパターンは一致しません。
指示子を含まないパターンはこれまでどおり一致します。

## マクロ

| マクロ | 内容 |
|-------|--------------|
| [`uiop:if-let`](../macros/uiop-if-let.md) | 束縛したうえで、すべての変数が非 nil のときだけ `then` 側を選びます |
| `uiop:nest` | 各フォームを直前のフォームの末尾に入れ子にします — インデント対策です |
| `uiop:while-collecting` | 名前ごとに収集**関数**を束縛します。フォームは各リストを順に多値で返します |
| `uiop:with-upgradability` | 本家は全定義をこれで包みます。ここでは `progn` です — 下記参照 |
| `uiop:with-muffled-conditions` | `call-with-muffled-conditions` の略記 |
| `uiop:appendf` / `uiop:latest-timestamp-f` | 上記の 2 つの `define-modify-macro` |
| `uiop:compatfmt` | 貧弱な `format` が読めないプリティプリンタ指示子を除去します。rontolisp はすべて読めるので文字列はそのまま返ります |
| `uiop:uiop-debug` | 開発者個人のデバッグ用ファイルを読み込みます — [未実装のもの](#what-is-missing)を参照 |
| `uiop:parse-body` | (マクロではなく関数) 本体をフォーム・宣言・ドキュメント文字列に分解します。マクロを書くライブラリが呼ぶものです |

```lisp
(print (uiop:nest (list 1) (list 2) (list 3)))
(print (multiple-value-list
        (uiop:while-collecting (names numbers)
          (dolist (row (list (list 'a 1) (list 'b 2)))
            (names (first row))
            (numbers (second row))))))
(print (multiple-value-list (uiop:parse-body '("doc" (declare (ignore x)) (+ 1 2))
                                             :documentation t)))
```

```
(1 (2 (3)))
((A B) (1 2))
(((+ 1 2)) ((DECLARE (IGNORE X))) "doc")
```

**`uiop:with-upgradability` は `progn` に展開されます。** 本家が自身の定義をすべて
これで包むのは、動作中のイメージ内で ASDF が自分自身を再定義できるようにするためです。
本体はコンパイル時・ロード時・実行時に評価され、各関数は `notinline` と宣言されます。
rontolisp には更新すべきイメージがありません — プログラムは一度コンパイルされて
実行されるだけです — ので、ここではこれが `progn` の意味そのものです。これは欠落では
なく意図的な選択です。定義は書かれたとおりに確立され、コンパイルバックエンドでも
トップレベル定義のままです。

```lisp
(uiop:with-upgradability ()
  (defun double-it (x) (* x 2))
  (defvar *scale* 5))
(print (list (double-it 3) *scale*))
```

```
(6 5)
```

## 文字型は 1 つだけ

本家の文字型の一群が存在するのは、処理系によっては `base-char` と `character` が
別の型であり、文字列の要素型を調べる必要があるからです。rontolisp の文字型は
**1 つ**だけで — `(subtypep 'character 'base-char)` は真です — 本家自身の導出を
この型格子の上で走らせると、要素は 1 つ、添字は 0、`+non-base-chars-exist-p+` は偽に
なります。残りはそこから決まります。どの文字列も base 文字列であり、任意の文字列群の
共通要素型は `character` です。

```lisp
(print (list uiop:+max-character-type-index+
             (uiop:character-type-index #\a)
             uiop:+non-base-chars-exist-p+))
(print (list (uiop:base-string-p "abc")
             (uiop:strings-common-element-type (list "a" #\b))))
```

```
(0 0 NIL)
(T CHARACTER)
```

## 未実装のもの

2 つのメンバは、あるふりをするのではなく rontolisp に無いものを名指しします。
どちらも理由とともに `uiop:not-implemented-error` をシグナルします。

- **`uiop:register-hook-function`** は実行時に名前で指定された変数に push しますが、
  それには `(setf (symbol-value var) ...)` が必要で、どのバックエンドでもこれは
  場所 (place) ではありません。
- **`uiop:load-uiop-debug-utility`** (およびそれを呼ぶ `uiop:uiop-debug`) は
  実行時に計算されたパス名を `load` しますが、`load` はどのバックエンドでも
  コンパイル時の展開です。`uiop:*uiop-debug-utility*` には本家の既定のフォームが
  入ったままです。

```console
$ rontolisp -e '(uiop:register-hook-function (quote *h*) (lambda () 1))'
Unhandled condition: Not (currently) implemented on rontolisp: UIOP/UTILITY:REGISTER-HOOK-FUNCTION pushing onto a hook needs (setf (symbol-value ...)), which is not a place on any backend
```

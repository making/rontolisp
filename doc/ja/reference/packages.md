# パッケージ

rontolispには、4つの組み込みパッケージを持つ小さな名前空間(パッケージ)システムがあります:

- **`cl`** — 標準パッケージ。すべての組み込み関数、マクロ、特殊形式、および `*package*` 変数がここに属します。
- **`cl-user`** — デフォルトの作業パッケージ。`cl` を *使用* するため、標準シンボルを修飾なしで利用できます。プログラム開始時のカレントパッケージです。ユーザ定義はここに置かれます。
- **`rontolisp`** — 実装固有のシンボルのためのパッケージ。`cl` を **使用しません**。`version`、`list-functions`、`list-macros`、`list-special-forms` の各関数を所有します。
- **`java`** — リフレクションによる Java 連携。JVM インタプリタ (`java -jar rontolisp.jar`) でのみ使え、コンパイラやネイティブバイナリでは使えません。`cl` を **使用しません**。`new`、`call`、`static`、`field`、`proxy` を所有します。[Java 連携ガイド](../guides/java-interop.md)を参照してください。

シンボルはパッケージ修飾子 `package:symbol`(例: `cl:car`、`rontolisp:version`)で参照できます。`*package*`
はカレントパッケージの名前に評価され、`(in-package name)`
はそれを切り替えます(名前はキーワード、シンボル、または文字列です: `:rontolisp`、`rontolisp`、`"rontolisp"`)。

```lisp
(print *package*)              ; => cl-user
(print (rontolisp:version))    ; => (:version "0.1.0-SNAPSHOT" :build-timestamp "..." :git-commit "..." :git-branch "...")
```

`rontolisp:version` は `rontolisp --version` と同じ情報をプロパティリストとして返します。

`rontolisp` パッケージは `cl` を使用しないため、その中では標準シンボルを `cl:` で修飾する必要がありますが、(所有している)`version` は修飾なしで利用できます:

```console
(in-package rontolisp)
(cl:print (version))           ; the rontolisp package owns version
(cl:print (cl:car '(1 2)))     ; standard symbols need the cl: prefix here
;; (car '(1 2)) would be an error: Undefined symbol: car (use cl:car)
```

デフォルトパッケージ `cl-user` は空で `cl` を使用するため、通常のプログラムでは修飾子は不要です。

## パッケージのイントロスペクション

`rontolisp:list-functions`、`rontolisp:list-macros`、`rontolisp:list-special-forms`
はパッケージのシンボルをカテゴリ別に、アルファベット順にソートして返します。省略可能な引数はパッケージ指定子(キーワード、裸のシンボル、引用されたシンボル、または文字列:
`:cl`、`cl`、`'cl`、`"cl"`)で、デフォルトは `:cl` です。未知のパッケージはエラーです(`No such package: foo`)。

```lisp
(print (rontolisp:list-macros))
; => (and case ccase cond decf do do* dolist dotimes ecase error etypecase format incf let* loop or pop prog1 prog2 psetq push remf setf time typecase unless when with-open-file)
(print (rontolisp:list-special-forms))
; => (defconstant defmacro defparameter defun defvar function if in-package lambda let progn quote return setq while)
(print (length (rontolisp:list-functions)))
; => 190
(defun square (x) (* x x))
(print (rontolisp:list-functions :cl-user))
; => (square)
(print (rontolisp:list-functions :rontolisp))
; => (fetch list-functions list-macros list-special-forms version)
(print (rontolisp:list-functions :java))
; => (call field new proxy static)
```

この分類は関数名前空間に従います。ある名前が関数として列挙されるのは、`#'name`
を通じて関数値として使用できる場合に限られます(そのため `first`、`length`、`1+`
... はインライン展開でコンパイルされるにもかかわらず関数です)。一方
`list-macros`/`list-special-forms` は関数値を持たない演算子を列挙します。注記:

- `cl-user` の `list-functions` はユーザ定義関数(`defun`)を列挙します。パッケージ修飾された名前、`%`
  プレフィックスの内部用、または `cl` シンボルをシャドウする名前は除外されます。コンパイル出力では、これはプログラムの
  `defun` の **コンパイル時スナップショット** です。`load`/`eval` を通じて実行時に定義された関数(`--dynamic`
  を使っても)は含まれず、`(in-package :rontolisp)`
  が有効な間に定義された関数はどのパッケージにも列挙されません。
- car/cdrの合成(`cadr`、`caddr` ...)はパターンで認識され列挙されないため、`list-functions`
  には現れません。
- パッケージ指定子はリテラルでなければなりません。計算された指定子は読み込み/コンパイル時に拒否されます(インタプリタはさらに
  `funcall` を通じて計算された指定子を受け付けます)。
- `version` と同様に、これらの関数はコンパイルされたランタイムの `eval`/`load` 内ではサポートされません。

パッケージは読み込み/コンパイル時に(ソース順で)解決されるため、`in-package`
はトップレベルのディレクティブであり、`*package*`
は可変のランタイム変数ではなくカレントパッケージを反映します。コンパイル出力では、実行時に読み込まれたファイルのパッケージディレクティブは処理されません。`rontolisp`
パッケージの関数(`version`、`list-functions` ...)は第一級の値として利用できません(`mapcar`/`funcall`
に渡せません)。また `cl` を使用しないパッケージ内では、`cl`
シンボル名をローカル変数としてシャドウしてはいけません。

## rontolisp パッケージの拡張

`rontolisp` パッケージが所有するシンボルは **実装固有であり、Common Lispの一部ではありません**。`rontolisp:`
修飾子で参照する(または `(in-package rontolisp)`
の後に修飾なしで使用する)必要があります。上記のイントロスペクションヘルパー(`version`、`list-functions`、`list-macros`、`list-special-forms`)に加え、このパッケージは
`rontolisp:fetch` を通じて外向きのHTTPを提供します。これらはすべて
[関数](functions.md#rontolisp-package-functions)
リファレンスに独自のページを持ち、完全な
[`rontolisp:fetch`](functions/rontolisp-fetch.md) ドキュメントも含まれます。

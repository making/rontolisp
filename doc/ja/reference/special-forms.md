# 特殊形式

**表中の各形式名はそれぞれのページにリンクしています**。各ページには、より詳しい説明と、ブラウザで評価できる実行可能な例があります。

| 形式 | 構文 | 説明 |
|------|--------|-------------|
| `quote` | `(quote expr)` or `'expr` | 式を評価せずに返します |
| `if` | `(if cond then else?)` | 条件分岐。`nil` は偽、それ以外はすべて真です |
| `let` | `(let ((x 1) (y 2)) body...)` | ローカル変数の束縛(並列)。スペシャル宣言された名前(`defvar`/`declaim`)はレキシカルではなくダイナミックに束縛されます |
| `progv` | `(progv symbols values body...)` | 実行時に計算した `symbols` のリストを `values` に本体の間ダイナミック束縛し、脱出時に復元します(インタプリタのみ) |
| `lambda` | `(lambda (params...) body...)` | 無名関数 |
| `progn` | `(progn expr1 expr2...)` | 式を順に評価し、最後の値を返します |
| `setq` | `(setq name value ...)` | 変数に値を代入します。複数の `name value` ペアを受け付け、左から右へ代入し、最後の値を返します |
| `define-symbol-macro` | `(define-symbol-macro name expansion)` | グローバルなシンボルマクロを定義します。`name` への参照は `expansion` を評価し、`name` への `setq`/`setf` は `expansion` を place として書き込みます。トップレベル専用で、`symbol-macrolet` のグローバル版です |
| `while` | `(while test body...)` | testが非nilの間、bodyを繰り返し評価します。nilを返します |
| `return` | `(return value?)` | 最も内側を囲むループ(`do`/`dolist`/`dotimes`/`loop`)からの非局所脱出。そのループは `value`(またはnil)に評価されます |
| `unwind-protect` | `(unwind-protect protected cleanup...)` | `protected` を評価し、そこからのあらゆる脱出時(通常復帰・`error` 巻き戻し・`return`/`return-from`)に `cleanup` フォームを実行(`--no-gc` ではコンパイルエラー) |
| `defun` | `(defun name (params...) body...)` | 関数名前空間に関数を定義します。関数名を返します |
| `defmacro` | `(defmacro name (params...) body...)` | ユーザーマクロを定義します。呼び出しは展開され(本体は未評価の引数フォームを束縛して実行)、展開形が評価されます。`&rest`/`&body` をサポートします。名前を返します |
| `defclass` | `(defclass name (super?) ((slot options...)...))` | クラスを定義します(静的 CLOS サブセット: 単一継承、`:initarg`/`:initform`/`:reader`/`:accessor` スロットオプション)。名前を返します |
| `defgeneric` | `(defgeneric name (param...))` | 第 1 引数でディスパッチする総称関数を定義します。名前を返します |
| `defmethod` | `(defmethod name (param...) body...)` | 総称関数にメソッドを追加します。第 1 引数に `(var (eql literal))`・クラス・組み込み型の specializer を付けられます。名前を返します |
| `defvar` | `(defvar name value?)` | グローバル変数を定義してスペシャル宣言します。`name` がまだ束縛されていない場合のみ `value` を束縛します(冪等)。`value` がなければ未束縛のままにします。名前を返します |
| `defparameter` | `(defparameter name value)` | グローバル変数を定義してスペシャル宣言します。`name` がすでに束縛されていても **常に** `value` を(再)束縛します。名前を返します |
| `defconstant` | `(defconstant name value)` | `defparameter` と同様(rontolispは定数性を強制しません)。名前を返します |
| `function` | `(function name)` or `#'name` | 関数名前空間から関数を検索し、値として返します |
| `defpackage` | `(defpackage name (:use ...) (:export ...))` | 新しいパッケージを定義します(トップレベルの read/コンパイル時ディレクティブ。clause は `:use` と `:export` のみ)。名前を返します |
| `rontolisp:async` | `(rontolisp:async (defun ...))` or `(rontolisp:async (lambda ...))` | ラップした `defun`/`lambda` を非同期版 (`async-defun`/`async-lambda`) に変えます — JavaScript 風の記法です |
| `rontolisp:async-defun` | `(rontolisp:async-defun name (params...) body...)` | 非同期関数を定義します: 呼び出すと本体が eager に開始され、本体の値 (またはエラー) で確定する future を返します |
| `rontolisp:async-lambda` | `(rontolisp:async-lambda (params...) body...)` | 無名の非同期関数。呼び出しごとに future を返します |
| `rontolisp:await` | `(rontolisp:await value)` | future が確定するまで現在の非同期関数をサスペンドし、確定値を返します。future 以外はそのまま通過します。`async-defun`/`async-lambda` の本体内とトップレベルでのみ使えます |
| `tagbody` | `(tagbody tag-or-form...)` | go タグ付きの本体フォーム: `go` がタグへ(前方・後方を問わず)ジャンプし、末尾到達で nil を返します |
| `go` | `(go tag)` | 囲んでいる `tagbody` のタグへ制御を移します(コンパイルされた `go` は字句的ですが、`lambda` を跨ぐものは非局所脱出として `tagbody` に再入します) |
| `catch` | `(catch tag body...)` | `tag`(`eq` で比較されるランタイム値)で名前を付けた動的な脱出点を確立します。フォームの値は本体の値、またはその動的エクステント内で発生した一致する `throw` の値です(`--no-gc` ではコンパイルエラー) |
| `throw` | `(throw tag result)` | タグが `eq` であるもっとも内側のアクティブな `catch` へ制御(と `result`)を移します。途中の `unwind-protect` の cleanup はすべて実行され、間にある `handler-case` はこれを捕捉しません |

rontolispはCommon Lispのような **Lisp-2** です。関数と変数は別々の名前空間に存在します。裸のシンボルは変数として評価され(`car`
単独は未束縛変数エラー)、呼び出し位置のシンボルは関数名前空間のみで解決され(`car`
という名前の変数が関数 `car` をシャドウしません)、関数は `#'name`、`(function name)`、`(symbol-function 'name)`
で値として取得されます。[関数名前空間と第一級関数](function-namespace.md) を参照してください。

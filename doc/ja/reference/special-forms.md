# 特殊形式

**表中の各形式名はそれぞれのページにリンクしています**。各ページには、より詳しい説明と、ブラウザで評価できる実行可能な例があります。

| 形式 | 構文 | 説明 |
|------|--------|-------------|
| `quote` | `(quote expr)` or `'expr` | 式を評価せずに返します |
| `if` | `(if cond then else?)` | 条件分岐。`nil` は偽、それ以外はすべて真です |
| `let` | `(let ((x 1) (y 2)) body...)` | ローカル変数の束縛 |
| `lambda` | `(lambda (params...) body...)` | 無名関数 |
| `progn` | `(progn expr1 expr2...)` | 式を順に評価し、最後の値を返します |
| `setq` | `(setq name value ...)` | 変数に値を代入します。複数の `name value` ペアを受け付け、左から右へ代入し、最後の値を返します |
| `while` | `(while test body...)` | testが非nilの間、bodyを繰り返し評価します。nilを返します |
| `return` | `(return value?)` | 最も内側を囲むループ(`do`/`dolist`/`dotimes`)からの非局所脱出。そのループは `value`(またはnil)に評価されます |
| `defun` | `(defun name (params...) body...)` | 関数名前空間に関数を定義します。関数名を返します |
| `defvar` | `(defvar name value?)` | グローバル変数を定義します。`name` がまだ束縛されていない場合のみ `value` を束縛します(冪等)。`value` がなければ未束縛のままにします。名前を返します |
| `defparameter` | `(defparameter name value)` | グローバル変数を定義します。`name` がすでに束縛されていても **常に** `value` を(再)束縛します。名前を返します |
| `defconstant` | `(defconstant name value)` | `defparameter` と同様(rontolispは定数性を強制しません)。名前を返します |
| `function` | `(function name)` or `#'name` | 関数名前空間から関数を検索し、値として返します |

rontolispはCommon Lispのような **Lisp-2** です。関数と変数は別々の名前空間に存在します。裸のシンボルは変数として評価され(`car`
単独は未束縛変数エラー)、呼び出し位置のシンボルは関数名前空間のみで解決され(`car`
という名前の変数が関数 `car` をシャドウしません)、関数は `#'name`、`(function name)`、`(symbol-function 'name)`
で値として取得されます。[関数名前空間と第一級関数](function-namespace.md) を参照してください。

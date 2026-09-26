# コンパイルされた eval の制限

`eval` は 3 つのバックエンドすべてで動作します。インタプリタでは完全なツリーウォーク評価器そのものです。WASM コンパイラと JVM コンパイラは、フォームを実行時に実行する小さなツリーウォークインタプリタ（`_eval`/`_apply`/`_store` とヘルパー `_envLookup`/`_lookup`）を出力に埋め込みます。そのため、別個の評価器やパーサーは不要です。

コンパイルされた `eval`（WASM および JVM）は、レキシカル環境と永続的なグローバル環境を実装し、インタプリタとの一致を目指しています。自己評価アトム、変数参照、クロージャ、特殊フォームと高階関数（`let`、`lambda`、`cond`、`while`、`dotimes`、`setq`、`setf`、`push`、`pop`、`funcall`、`apply`、`mapcar`、`mapc`、`reduce`、ネストした `eval` など）、および任意の関数や解釈されたクロージャの適用はすべて、インタプリタと同じように動作します。すべてを列挙するのではなく、相違点を以下に挙げます。

## コンパイルされた `eval` の制限

コンパイルされた `eval`（WASM および JVM）がインタプリタと異なるのは、以下の場合だけです。

- **`let` の束縛リストは `((name value) ...)` の形式を使う必要があります**（裸の `(let (x) ...)` はサポートされません）。
- **実行時に構築された `lambda` はラムダリストキーワードを解釈しません。** コンパイルされた `defun`/`lambda` フォームは `&optional`/`&rest`/`&key` をサポートし（コンパイル時に脱糖されます）、そのようなコンパイル済み可変長関数を `eval` から呼び出すことはできます。しかし eval されるフォームの中にのみ存在する lambda、例えば `(eval '(funcall (lambda (&rest r) r) 1 2))` はパラメータを位置的に束縛し — `&rest` は通常のパラメータ名として扱われます — 引数の個数も検査しません。ラムダリストキーワードを持たない実行時 lambda は、インタプリタと同様に引数の個数の誤りを `program-error` として通知します。
- **未束縛の変数はシンボル自身に評価されます。** インタプリタは `The variable x is unbound` を通知しますが、実行時 `eval` にはエラー通知の手段がなく、代わりにシンボルを返します。呼び出し位置にある未定義の関数は `nil` を返します。
- **トップレベルのグローバル変数はコンパイルされたコードからライトスルーで共有されます。** トップレベルの `setq`/`defvar`/`defparameter`/`defconstant` は、その値を実行時 `eval` のグローバル環境にミラーします。そのため、eval された式は、コンパイルされたプログラムが定義したグローバルを読むことができます（例: `(setq add10 (make-adder 10))` の後に `(eval '(funcall add10 100))` は `110` を返します）。このミラーは一方向です。後で `eval` がそのような変数を再代入しても、コンパイルされたコードは自身のコピーを読み続けます。トップレベルの `(set ...)`（または `(setf (symbol-value ...) ...)`）は両方に書き込むため、コンパイルされた読み取りにも `eval` にも見えます。ミラーされるのはグローバル変数だけです。トップレベルフォームの*レキシカル*変数 — `let`/`loop`/`do` の変数 — はミラーされません。これは `eval` が空のレキシカル環境で評価するという Common Lisp の規定に一致します（`(let ((x 5)) (eval 'x))` はどのバックエンドでもグローバルの `x` を、それが無ければシンボル自身を読みます）。
- **`let*`、`do`、`do*`、`dolist`、`return`、`defvar`、`defparameter`、`defconstant`、`incf`、`decf`、`format`、`error`、`ecase`、`etypecase`、`ccase`、`concatenate`、`with-open-file` およびファイルストリーム関数（`open`、`close`、`write-line`）はサポートされません。** これらのフォームはコンパイル時にのみ展開・処理されます。実行時 `eval` インタプリタはこれらを認識しません。シーケンス関数（`length`、`reverse`、`member`、`member-if`、`find`、`find-if`、`position`、`count`、`assoc`、`assoc-if`、`getf`、`last`、`butlast`、`remove`、`remove-if`、`remove-if-not`、`remove-duplicates`、`delete`、`delete-if`、`delete-if-not`、`substitute`、`nsubstitute`、`nconc`、`copy-list`、`nreverse`、`make-list`、`union`、`intersection`、`set-difference`、`adjoin`、`identity`、`mapcan`、`sort`、`every`、`some`）と `princ-to-string`/`prin1-to-string` は、コンパイルされた関数レジストリを通じて解決されるため動作します。
- **`defmacro` とバッククォートはコンパイル時のみです。** コンパイルパスでは、ユーザーマクロはコンパイラの実行前に完全展開され（定義も取り除かれ）、バッククォートのテンプレートはリーダーで展開されます。コンパイル済みプログラムの実行時 `eval`/`read` は `defmacro` もバッククォート文字も認識しません。 `macroexpand`/`macroexpand-1` も同様です: リテラルのクォートされた引数を持つ呼び出しはコンパイル時に展開結果へ畳み込まれ、実行時 `eval` はこれらの関数を認識しません（対照的に `gensym` はファーストクラスのラッパーを持つため動作します）。
- **`defstruct` はコンパイル時のみです。** トップレベルの `defstruct` はコンパイル前に生成関数へ展開されるため、コンストラクタ/アクセサ/述語を `eval` から呼び出すことはできますが、eval されるフォームの中で新しい構造体を定義したり、アクセサを `setf` の place として使うことはできません。`#S(...)` リテラルも同様にコンパイル前に解決されるため、`eval` の中では認識されません。
- **CLOS サブセットはコンパイル時のみです。** `defstruct` と同様に、トップレベルの `defclass`/`defgeneric`/`defmethod` はコンパイル前に展開されます。総称関数・reader/accessor・コンストラクタを `eval` から呼び出すことはできますが、eval されるフォームの中でクラスやメソッドを定義することはできず、`make-instance`/`slot-value` は `eval` の中では認識されません（コンパイル時のクラスレジストリを通じて解決されるためです）。
- **`rontolisp` パッケージの関数はサポートされません。** `rontolisp:version`、`rontolisp:fetch`、`rontolisp:http-handler`、`rontolisp:await`、`rontolisp:futurep`、`rontolisp:json-parse`、`rontolisp:json-stringify` は直接コンパイルされます（定数、インライン呼び出し、または組み込まれるライブラリ関数）。実行時 `eval`/`load` はこれらを認識しません。

これらの相違は設計に由来します。実行時 `eval` は、実際に出力にコンパイルされた関数のコンパイル時レジストリに対して名前で演算子を解決し、組み込み関数はコンパイルされたコードと共有されます。

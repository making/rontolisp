# コンパイルされた eval の制限

`eval` は 3 つのバックエンドすべてで動作します。インタプリタでは完全なツリーウォーク評価器そのものです。WASM コンパイラと JVM コンパイラは、フォームを実行時に実行する小さなツリーウォークインタプリタ（`_eval`/`_apply`/`_store` とヘルパー `_envLookup`/`_lookup`）を出力に埋め込みます。そのため、別個の評価器やパーサーは不要です。

コンパイルされた `eval`（WASM および JVM）は、レキシカル環境と永続的なグローバル環境を実装し、インタプリタとの一致を目指しています。自己評価アトム、変数参照、クロージャ、特殊フォームと高階関数（`let`、`lambda`、`cond`、`while`、`dotimes`、`setq`、`setf`、`push`、`pop`、`funcall`、`mapcar`、`mapc`、`reduce`、ネストした `eval` など）、および任意の関数や解釈されたクロージャの適用はすべて、インタプリタと同じように動作します。すべてを列挙するのではなく、相違点を以下に挙げます。

## コンパイルされた `eval` の制限

コンパイルされた `eval`（WASM および JVM）がインタプリタと異なるのは、以下の場合だけです。

- **`let` の束縛リストは `((name value) ...)` の形式を使う必要があります**（裸の `(let (x) ...)` はサポートされません）。
- **比較演算子は `eval` の中では二項演算です。** コンパイルされたトップレベルコードは可変長の `=`、`<`、`>`、`<=`、`>=` と可変長の `min`/`max`/`gcd`/`lcm` をサポートします（コンパイル時にネストした二項演算に脱糖されます）。しかしその脱糖は `eval` によって実行時に解釈されるフォームには及ばず、そこではこれらの演算子は引数を 2 つ取り、それ以上の引数は無視されます（そのため `(eval '(= 1 1 2))` は `(= 1 1)` を評価して真を返します）。`+ - * / list` はどこでも完全に可変長です。8 個以上のパラメータを持つユーザー関数は `nil` を返します。
- **失敗するエッジケース。** 引数なしの `(+)`/`(-)`/`(*)`/`(/)` は実行時に失敗します（WASM ではトラップ、JVM では例外）。単項の `(- x)` と `(/ x)` はインタプリタと同様に符号反転／逆数化を行います。
- **実行時に構築された `lambda` はラムダリストキーワードを解釈しません。** コンパイルされた `defun`/`lambda` フォームは `&optional`/`&rest`/`&key` をサポートし（コンパイル時に脱糖されます）、そのようなコンパイル済み可変長関数を `eval` から呼び出すことはできます。しかし eval されるフォームの中にのみ存在する lambda、例えば `(eval '(funcall (lambda (&rest r) r) 1 2))` はパラメータを位置的に束縛します — `&rest` は通常のパラメータ名として扱われます。
- **多倍長整数への昇格はありません。** 実行時 `eval` インタプリタ内の算術演算は固定幅整数を使い、オーバーフロー時にラップします。コンパイルされたコード自体が多倍長整数に昇格する JVM 上でも同様です。
- **未束縛の変数はシンボル自身に評価されます。** インタプリタは `The variable x is unbound` を通知しますが、実行時 `eval` にはエラー通知の手段がなく、代わりにシンボルを返します。呼び出し位置にある未定義の関数は `nil` を返します。
- **トップレベルのグローバル変数はコンパイルされたコードからライトスルーで共有されます。** トップレベルの `setq`/`defvar`/`defparameter`/`defconstant` は、その値を実行時 `eval` のグローバル環境にミラーします。そのため、eval された式は、コンパイルされたプログラムが定義したグローバルを読むことができます（例: `(setq add10 (make-adder 10))` の後に `(eval '(funcall add10 100))` は `110` を返します）。このミラーは一方向です。後で `eval` がそのような変数を再代入しても、コンパイルされたコードは自身のコピーを読み続けます。
- **`let*`、`do`、`do*`、`dolist`、`return`、`defvar`、`defparameter`、`defconstant`、`incf`、`decf`、`format`、`error`、`ecase`、`etypecase`、`ccase`、`concatenate`、`with-open-file` およびファイルストリーム関数（`open`、`close`、`write-line`）はサポートされません。** これらのフォームはコンパイル時にのみ展開・処理されます。実行時 `eval` インタプリタはこれらを認識しません。シーケンス関数（`length`、`reverse`、`member`、`member-if`、`find`、`find-if`、`position`、`count`、`assoc`、`assoc-if`、`getf`、`last`、`butlast`、`remove`、`remove-if`、`remove-if-not`、`remove-duplicates`、`delete`、`delete-if`、`delete-if-not`、`substitute`、`nsubstitute`、`nconc`、`copy-list`、`nreverse`、`make-list`、`union`、`intersection`、`set-difference`、`adjoin`、`identity`、`mapcan`、`sort`、`every`、`some`）と `princ-to-string`/`prin1-to-string` は、コンパイルされた関数レジストリを通じて解決されるため動作します。
- **`defmacro` とバッククォートはコンパイル時のみです。** コンパイルパスでは、ユーザーマクロはコンパイラの実行前に完全展開され（定義も取り除かれ）、バッククォートのテンプレートはリーダーで展開されます。コンパイル済みプログラムの実行時 `eval`/`read` は `defmacro` もバッククォート文字も認識しません。 `macroexpand`/`macroexpand-1` も同様です: リテラルのクォートされた引数を持つ呼び出しはコンパイル時に展開結果へ畳み込まれ、実行時 `eval` はこれらの関数を認識しません（対照的に `gensym` はファーストクラスのラッパーを持つため動作します）。
- **`defstruct` はコンパイル時のみです。** トップレベルの `defstruct` はコンパイル前に生成関数へ展開されるため、コンストラクタ/アクセサ/述語を `eval` から呼び出すことはできますが、eval されるフォームの中で新しい構造体を定義したり、アクセサを `setf` の place として使うことはできません。
- **`rontolisp` パッケージの関数はサポートされません。** `rontolisp:version`、`rontolisp:list-functions`、`rontolisp:list-macros`、`rontolisp:list-special-forms`、`rontolisp:fetch`、`rontolisp:await`、`rontolisp:then`、`rontolisp:promisep`、`rontolisp:json-parse`、`rontolisp:json-stringify` は直接コンパイルされます（定数、インライン呼び出し、または組み込まれるライブラリ関数）。実行時 `eval`/`load` はこれらを認識しません。

これらの相違は設計に由来します。実行時 `eval` は、実際に出力にコンパイルされた関数のコンパイル時レジストリに対して名前で演算子を解決し、組み込み関数はコンパイルされたコードと共有されます。

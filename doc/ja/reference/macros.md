# マクロ

**表中の各マクロ名はそれぞれのページにリンクしています**。各ページには、より詳しい説明と、ブラウザで評価できる実行可能な例があります。

| マクロ | 構文 | 説明 |
|-------|--------|-------------|
| `cond` | `(cond (test1 body1...) ...)` | 複数の節を持つ条件分岐。最初に真となったtestのbodyを返します |
| `case` | `(case key (k1 body1...) ((k2 k3) body2...) (otherwise body...))` | `eql` で比較したkeyによるディスパッチ。キーは評価されません。リストキーはいずれかの要素にマッチします。`t`/`otherwise` がデフォルトです。何もマッチしなければnilを返します |
| `ecase` | `(ecase key (k1 body1...) ((k2 k3) body2...))` | 網羅的な `case`。デフォルト節はなく(`t`/`otherwise` は通常のキーです)、マッチしないキーは `error` を通知します |
| `ccase` | `(ccase key (k1 body1...) ...)` | `ecase` と同様。マッチしないキーは `error` を通知します。リスタートシステムがないため、これは `ecase` と同一です(訂正不可) |
| `and` | `(and expr1 expr2...)` | 短絡評価のAND。最初のnilまたは最後の値を返します。`(and)` は `t` を返します |
| `or` | `(or expr1 expr2...)` | 短絡評価のOR。最初の非nil値またはnilを返します。`(or)` は `nil` を返します |
| `when` | `(when condition body...)` | conditionが真のときbodyを評価し、それ以外はnilを返します |
| `unless` | `(unless condition body...)` | conditionがnilのときbodyを評価し、それ以外はnilを返します |
| `dotimes` | `(dotimes (var count result?) body...)` | `var` を `0`..`count-1` に束縛してbodyを評価します。`result`(またはnil)を返します |
| `do` | `(do ((var init step?)...) (end-test result...) body...)` | 並列にステップする変数で反復します。`end-test` が真になったとき `result` フォームを返します |
| `do*` | `(do* ((var init step?)...) (end-test result...) body...)` | `do` と同様ですが、束縛とステップが逐次的(`let*` 形式)です。各init/stepフォームは今回の反復ですでに更新された変数を参照します |
| `loop` | `(loop for i from 1 to n collect (f i))` | ANSI `loop` の限定サブセット。数値/リストのステップ(`for`)、集約(`collect`/`sum`/`count`/...)、単純な制御節(`while`/`repeat`/`when`/`finally`/`return`)に対応します。完全な文法と制限事項はページを参照してください |
| `prog1` | `(prog1 first body...)` | すべてのフォームを順に評価し、`first` の値を返します |
| `prog2` | `(prog2 first second body...)` | すべてのフォームを順に評価し、`second` の値を返します |
| `time` | `(time form)` | `form` を評価し、経過実時間を標準出力に印字し(`; Elapsed real time: N ms`)、formの値を返します。`N` はインタプリタ/JVMではミリ秒の整数、WASMではミリ秒の浮動小数点です |
| `psetq` | `(psetq v1 e1 v2 e2 ...)` | 並列代入。いずれかの変数に代入する前にすべての右辺が評価されます。nilを返します |
| `typecase` | `(typecase x (integer body...) (string body...) (t default...))` | `x` の型によるディスパッチ。サポートされる型名: `integer`, `float`, `number`, `rational`, `string`, `symbol`, `keyword`, `cons`, `list`, `null`, `atom`, `character`, `hash-table`, `boolean`(および `t`/`otherwise`)と、複合指定子 `(or ...)`/`(and ...)`/`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)` および `(integer 0 9)` のような範囲付き数値型。何もマッチしなければnilを返します |
| `etypecase` | `(etypecase x (integer body...) (string body...))` | 網羅的な `typecase`。デフォルト節はなく、どの節にも型がマッチしないオブジェクトは `error` を通知します |
| `error` | `(error "bad value: ~a" x)` | エラーを通知し、実行を中止します。第1引数はリテラルの制御文字列でなければなりません(`format` と同じディレクティブ: `~a`, `~s`, `~%`)。インタプリタとJVMはメッセージを保持する例外をスローし、WASMはトラップします。`format` と同様に関数値を持たないマクロです(`#'error` はサポートされません) |
| `setf` | `(setf place value)` | 一般化代入。placeとして `car`, `cdr`, `nth`, `first`..`fourth`, `rest`, `caXXXr` をサポートします |
| `push` | `(push item place)` | placeにあるリストの先頭にitemを追加します。新しいリストを返します |
| `pop` | `(pop place)` | placeにあるリストの先頭要素を取り除いて返します |
| `remf` | `(remf place indicator)` | placeにあるプロパティリストからキーと値のペアを取り除きます。見つかれば `t`、見つからなければ `nil` を返します |
| `let*` | `(let* ((x 1) (y x)) body...)` | 逐次的な束縛。各initフォームは直前の束縛を参照します。ネストした `let` に展開されます |
| `dolist` | `(dolist (var list result?) body...)` | `var` を各要素に束縛してbodyを評価します。`var` をnilに束縛して `result`(またはnil)を返します |
| `incf` | `(incf place delta?)` | `(setf place (+ place delta))` に展開されます。`delta` のデフォルトは1です。新しい値を返します |
| `decf` | `(decf place delta?)` | `(setf place (- place delta))` に展開されます。`delta` のデフォルトは1です。新しい値を返します |
| `format` | `(format t "Hello ~a, ~d!~%" 'world 42)`, `(format nil "~a" x)` | 標準出力(`t`、nilを返す)または文字列(`nil`)への整形出力 |
| `with-open-file` | `(with-open-file (s "f.txt" :direction :output) (write-line "hi" s))` | ファイルを開き、ストリームを `s` に束縛し、bodyを評価し、ファイルを閉じます。bodyの値を返します。サポートされるのは `:direction` オプション(`:input` がデフォルト、`:output`)と `:element-type` オプション(`'character` がデフォルト、バイナリストリームには `'(unsigned-byte 8)`)で、どちらもリテラルでなければなりません |
| `check-type` | `(check-type place typespec [string])` | `place` の値が指定された型でなければエラーをシグナルし、型に合致していれば nil を返します。ライト版: リスタートがないため place への再格納はありません |
| `assert` | `(assert test-form [(place...) [datum args...]])` | `test-form` が偽ならエラーをシグナルし、真なら nil を返します。place のリストは受理されますが無視されます（リスタートなし） |
| `declare` | `(declare declaration...)` | 解析されるだけの no-op: nil に評価され、引数は評価も検証もされません |
| `declaim` | `(declaim declaration...)` | `declare` と同様の no-op（ファイルレベルの宣言用） |
| `proclaim` | `(proclaim declaration)` | `declaim` と同様の no-op（CL からの逸脱: マクロとして分類され、引数は評価されません） |
| `the` | `(the type form)` | `form` の値をそのまま返します。型はチェックされません |
| `eval-when` | `(eval-when (situation...) body...)` | 本体を `progn` として評価します。すべての状況指定は「今評価する」として扱われます。トップレベルの本体はスプライスされ、ネストした `defun`/`defmacro` 定義も収集されます |
| `flet` | `(flet ((name lambda-list body...)...) body...)` | 局所的な非再帰の関数束縛（Lisp-2: 呼び出し位置と `#'name`）。定義本体は同名の外側の関数を参照し、兄弟定義は見えません。ラムダリストは `defun` の拡張をサポートします |
| `labels` | `(labels ((name lambda-list body...)...) body...)` | `flet` と同様ですが定義同士が互いに見えます（再帰と相互再帰） |
| `multiple-value-bind` | `(multiple-value-bind (var...) values-form body...)` | 変数をプロデューサフォームの値に束縛します。リテラルの `(values ...)` 呼び出し、多値の組み込み関数（`floor` ファミリ、`gethash`、`parse-integer`）、`(values ...)` を返すユーザ関数は全ての値を供給します。余った変数は nil に束縛されます |
| `multiple-value-list` | `(multiple-value-list values-form)` | プロデューサの値をリストに集めます（`multiple-value-bind` と同様に認識） |
| `multiple-value-call` | `(multiple-value-call function values-form...)` | 全てのプロデューサの全ての値を引数として関数を呼び出します。ユーザ関数の値も実行時に展開されて渡ります（CL からの逸脱: 特殊オペレータではなくマクロに分類） |
| `nth-value` | `(nth-value n values-form)` | プロデューサの n 番目（0 始まり）の値、なければ nil。`multiple-value-list` の上の `nth` に展開されます |

マクロは関数値を持ちません。`#'cond` や `(funcall 'setf ...)`
はエラーです。呼び出し位置でインライン展開される便利なアクセサや述語(`first`, `rest`, `nth`,
`second`..`fourth`, `1+`, `1-`, `zerop`, `plusp`, `minusp`, `evenp`, `oddp`)は、関数値としても使用できる(`#'first`)ため
[関数](functions.md) に列挙されています。

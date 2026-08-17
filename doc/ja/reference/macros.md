# マクロ

**表中の各マクロ名はそれぞれのページにリンクしています**。各ページには、より詳しい説明と、ブラウザで評価できる実行可能な例があります。

| マクロ | 構文 | 説明 |
|-------|--------|-------------|
| `cond` | `(cond (test1 body1...) ...)` | 複数の節を持つ条件分岐。最初に真となったtestのbodyを返します |
| `case` | `(case key (k1 body1...) ((k2 k3) body2...) (otherwise body...))` | `eql` で比較したkeyによるディスパッチ。キーは評価されません。リストキーはいずれかの要素にマッチします。`t`/`otherwise` がデフォルトです。何もマッチしなければnilを返します |
| `ecase` | `(ecase key (k1 body1...) ((k2 k3) body2...))` | 網羅的な `case`。デフォルト節はなく(`t`/`otherwise` は通常のキーです)、マッチしないキーは `error` を通知します |
| `ccase` | `(ccase key (k1 body1...) ...)` | `ecase` と同様。マッチしないキーは `error` を通知します。rontolisp はその周囲に `store-value` リスタートを確立しないため、これは `ecase` と同一です(訂正不可) |
| `and` | `(and expr1 expr2...)` | 短絡評価のAND。最初のnilまたは最後の値を返します。`(and)` は `t` を返します |
| `or` | `(or expr1 expr2...)` | 短絡評価のOR。最初の非nil値またはnilを返します。`(or)` は `nil` を返します |
| `when` | `(when condition body...)` | conditionが真のときbodyを評価し、それ以外はnilを返します |
| `unless` | `(unless condition body...)` | conditionがnilのときbodyを評価し、それ以外はnilを返します |
| `dotimes` | `(dotimes (var count result?) body...)` | `var` を `0`..`count-1` に束縛してbodyを評価します。`result`(またはnil)を返します |
| `do` | `(do ((var init step?)...) (end-test result...) body...)` | 並列にステップする変数で反復します。`end-test` が真になったとき `result` フォームを返します |
| `do*` | `(do* ((var init step?)...) (end-test result...) body...)` | `do` と同様ですが、束縛とステップが逐次的(`let*` 形式)です。各init/stepフォームは今回の反復ですでに更新された変数を参照します |
| `loop` | `(loop for i from 1 to n collect (f i))` | ANSI `loop` の限定サブセット。数値/リストのステップ(`for`)、集約(`collect`/`sum`/`count`/...)、単純な制御節(`while`/`repeat`/`when`/`finally`/`return`)に対応します。完全な文法と制限事項はページを参照してください |
| `prog1` | `(prog1 first body...)` | すべてのフォームを順に評価し、`first` の値を返します |
| `multiple-value-prog1` | `(multiple-value-prog1 (floor 17 5) (cleanup))` | `prog1` と同様だが最初のフォームの全多値を返す |
| `prog2` | `(prog2 first second body...)` | すべてのフォームを順に評価し、`second` の値を返します |
| `time` | `(time form)` | `form` を評価し、経過実時間を標準出力に印字し(`; Elapsed real time: N ms`)、formの値を返します。`N` はインタプリタ/JVMではミリ秒の整数、WASMではミリ秒の浮動小数点です |
| `psetq` | `(psetq v1 e1 v2 e2 ...)` | 並列代入。いずれかの変数に代入する前にすべての右辺が評価されます。nilを返します |
| `psetf` | `(psetf place1 e1 place2 e2 ...)` | `psetq` を `setf` プレースへ一般化したもの。プレースの部分式と値はすべて代入前に評価されます。nilを返します |
| `block` | `(block name body...)` | 名前付きブロック。最後のフォームの値、またはマッチする `(return-from name v)` の値を返します。マッチはすべてのバックエンドでレキシカルなので、クロージャ内の `return-from` はソース上でそれを囲むブロックを抜けます |
| `typecase` | `(typecase x (integer body...) (string body...) (t default...))` | `x` の型によるディスパッチ。サポートされる型名: `integer`, `float`, `number`, `rational`, `string`, `symbol`, `keyword`, `cons`, `list`, `null`, `atom`, `character`, `hash-table`, `boolean`(および `t`/`otherwise`)と、複合指定子 `(or ...)`/`(and ...)`/`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)` および `(integer 0 9)` のような範囲付き数値型。何もマッチしなければnilを返します |
| `etypecase` | `(etypecase x (integer body...) (string body...))` | 網羅的な `typecase`。デフォルト節はなく、どの節にも型がマッチしないオブジェクトは `error` を通知します |
| `ctypecase` | `(ctypecase x (integer body...) (string body...))` | `etypecase` と同様。どの節にも型がマッチしないオブジェクトは `error` を通知します。rontolisp はその周囲に `store-value` リスタートを確立しないため、これは `etypecase` と同一です(訂正不可) |
| `error` | `(error "bad value: ~a" x)`, `(error 'my-error :v x)`, `(error obj)` | エラーを通知し、[`handler-case`](macros/handler-case.md) に捕捉されなければ実行を中止します。designator: リテラルの制御文字列(`format` と同じディレクティブ)、initarg 付きのクォートされたコンディション型シンボル(型付きコンディションを構築。`define-condition` の `:report` がメッセージになります)、またはコンディションオブジェクト。インタプリタとJVMはメッセージとコンディションを保持する例外をスローし、wasm-GC は捕捉フォームを含むプログラムでは WebAssembly 例外をスローし、含まなければトラップします。`format` と同様に関数値を持たないマクロです(`#'error` はサポートされません) |
| `cerror` | `(cerror continue-format datum args...)` | **継続可能な**エラーを通知します: シグナルの周囲に `continue` リスタートが確立されるため、`handler-bind` ハンドラが [`continue`](functions/continue.md) を呼べば nil を返して先へ再開できます。誰も起動しなければ `error` と同じ動作です |
| `signal` | `(signal 'my-condition :v x)` | **非致命的**なコンディションを通知します(designator は `error` と同じ): 確立済みの `handler-case` に送出され、なければ nil を返して継続します(`--no-gc` では常に nil) |
| `handler-case` | `(handler-case expr (type (var) body...)... (:no-error (v) body...))` | `expr` を評価し、通知されたエラーをコンディション型がマッチする最初の節にディスパッチします(マッチしなければ再送出)。`:no-error` は正常終了時に実行。wasm-GC では `wasmtime -W exceptions=y` が必要。`--no-gc` ではコンパイルエラー |
| `ignore-errors` | `(ignore-errors form...)` | フォームの値、エラー通知時は nil。`handler-case` の糖衣。wasm-GC では `wasmtime -W exceptions=y` が必要。`--no-gc` ではコンパイルエラー |
| `handler-bind` | `(handler-bind ((type handler)...) body...)` | **シグナル点で、巻き戻しの前に**実行されるハンドラを確立します。ハンドラは [`restart-case`](macros/restart-case.md) のリスタートを起動できます。リターンしたハンドラは辞退します。`--no-gc` ではコンパイルエラー |
| `restart-case` | `(restart-case form (name (args...) body...)...)` | 名前付きリスタートを確立して `form` を評価します。リスタートが起動されるとここまで巻き戻り、節本体がインラインで実行されます(節から外側の `tagbody` へ `go` できます)。`--no-gc` は主フォームのみの簡易展開を保ちます |
| `restart-bind` | `(restart-bind ((name fn)...) body...)` | 関数が**起動点で**実行されるリスタートを確立します(巻き戻しなし) |
| `with-simple-restart` | `(with-simple-restart (name fmt args...) body...)` | `restart-case` の糖衣: リスタートが起動されるとフォームから `(values nil t)` が返ります |
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
| `with-open-stream` | `(with-open-stream (s (make-string-input-stream "hi")) (read-line s))` | すでに開いているストリームを束縛して本体を評価し、閉じる。`open` を伴わない `with-open-file` |
| `check-type` | `(check-type place typespec [string])` | `place` の値が指定された型でなければエラーをシグナルし、型に合致していれば nil を返します。ライト版: リスタートがないため place への再格納はありません |
| `assert` | `(assert test-form [(place...) [datum args...]])` | `test-form` が偽ならエラーをシグナルし、真なら nil を返します。place のリストは受理されますが無視されます（リスタートなし） |
| `declare` | `(declare declaration...)` | 解析されるだけの no-op: nil に評価され、引数は評価も検証もされません |
| `declaim` | `(declaim declaration...)` | `declare` と同様の no-op（ファイルレベルの宣言用） |
| `proclaim` | `(proclaim declaration)` | `declaim` と同様の no-op（CL からの逸脱: マクロとして分類され、引数は評価されません） |
| `the` | `(the type form)` | `form` の値をそのまま返します。型はチェックされません |
| `eval-when` | `(eval-when (situation...) body...)` | 本体を `progn` として評価します。すべての状況指定は「今評価する」として扱われます。トップレベルの本体はスプライスされ、ネストした `defun`/`defmacro` 定義も収集されます |
| `locally` | `(locally declaration... form...)` | 本体を `progn` として評価します。先頭の `declare` フォームは取り除かれます(宣言はパースされるだけの no-op) |
| `with-standard-io-syntax` | `(with-standard-io-syntax form...)` | `*package*` を `cl-user` に束縛し、本体を `progn` として評価します。Common Lisp が再束縛を求めるその他のリーダー/プリンター制御変数は、rontolisp では情報提供用(`*read-default-float-format*`)か、どのリーダー/プリンターからも読まれないかのいずれかです |
| `write-char` | `(write-char char [stream])` | 1 文字を書き出してその文字を返します。1 文字の文字列の `write-string` に展開されるため、ファイル/文字列ストリームでも動きます |
| `flet` | `(flet ((name lambda-list body...)...) body...)` | 局所的な非再帰の関数束縛（Lisp-2: 呼び出し位置と `#'name`）。定義本体は同名の外側の関数を参照し、兄弟定義は見えません。ラムダリストは `defun` の拡張をサポートします |
| `labels` | `(labels ((name lambda-list body...)...) body...)` | `flet` と同様ですが定義同士が互いに見えます（再帰と相互再帰） |
| `symbol-macrolet` | `(symbol-macrolet ((name expansion)...) body...)` | 局所シンボルマクロ。`name` への自由参照はその位置で `expansion` を評価し、`name` への `setq`/`setf` は展開形の place へ代入します。同名の内側の束縛はシャドウします |
| `multiple-value-bind` | `(multiple-value-bind (var...) values-form body...)` | 変数をプロデューサフォームの値に束縛します。リテラルの `(values ...)` 呼び出し、多値の組み込み関数（`floor` ファミリ、`gethash`、`parse-integer`）、`(values ...)` を返すユーザ関数は全ての値を供給します。余った変数は nil に束縛されます |
| `multiple-value-list` | `(multiple-value-list values-form)` | プロデューサの値をリストに集めます（`multiple-value-bind` と同様に認識） |
| `multiple-value-call` | `(multiple-value-call function values-form...)` | 全てのプロデューサの全ての値を引数として関数を呼び出します。ユーザ関数の値も実行時に展開されて渡ります（CL からの逸脱: 特殊オペレータではなくマクロに分類） |
| `nth-value` | `(nth-value n values-form)` | プロデューサの n 番目（0 始まり）の値、なければ nil。`multiple-value-list` の上の `nth` に展開されます |
| `make-instance` | `(make-instance 'class-name :initarg value ...)` | [`defclass`](special-forms/defclass.md) クラスのインスタンスを生成します(静的 CLOS サブセット)。クラス名はリテラルでも計算されたものでもよく、クラス集合はコンパイル時に固定されます |
| `slot-value` | `(slot-value object 'slot-name)` | [`defclass`](special-forms/defclass.md) インスタンスのスロットを読み取ります。`setf` 可能な place です。スロット名はリテラルのクォートされたシンボルでなければなりません |
| `with-slots` | `(with-slots (x (v y)) instance body...)` | スロット名を本体のシンボルマクロ的な場所として束縛します。読み取りはスロットを参照し、束縛名への `setf`/`push`/`incf` はスロットへ書き戻されます。`defstruct` のスロットも解決します |
| `with-accessors` | `(with-accessors ((x pt-x)) instance body...)` | 変数を、インスタンスに対するアクセサ呼び出しを表すシンボルマクロ的な場所として束縛します |
| `change-class` | `(change-class obj 'class :initarg v)` | インスタンスのクラスをその場で変更して返します(同一性と共通スロットは保たれ、新しいスロットは `:initform` で埋まります) |
| `rontolisp:with-arena` | `(rontolisp:with-arena () body...)` | ボディを実行してその値を返し、非 GC WASM バックエンド(`--no-gc`)のメモリ再利用境界を名付けます。内部で確保されたものは終端でポップされ、ボディの値だけが残ります。他のバックエンドでは実際の GC が回収するため、単なる `progn` です |
| `rontolisp:with-mutex` | `(rontolisp:with-mutex (mutex-form) body...)` | mutex を獲得し、ボディを実行し、あらゆる脱出時(シグナルされたエラーを含む)に解放します。サーブされるハンドラがリクエストごとに 1 つの仮想スレッドで動くインタプリタと JVM バックエンドでは実際の相互排他になり、単一スレッドの WASM バックエンドでは no-op です |
| `uiop:if-let` | `(uiop:if-let ((a x) (b y)) then else)` | `let` と同じく変数を並列に束縛し、**すべての**変数が非 nil のときだけ `then` 側を取ります。入れ子でない単一束縛 (`(uiop:if-let (x form) ...)`) も受け付けます |
| `uiop:when-let` | `(uiop:when-let ((a x)) body...)` | 暗黙の `progn` ボディを持ち else 分岐のない `uiop:if-let`: すべての変数が非 nil のときだけボディを評価し、そうでなければ nil です |
| `uiop:when-let*` | `(uiop:when-let* ((a x) (b (f a))) body...)` | 逐次版の `uiop:when-let`: 各フォームは先行する束縛を参照でき、最初に nil になった時点で残りを評価せず nil になります |
| `uiop:with-deprecation` | `(uiop:with-deprecation (:style-warning) (defun old-f (x) x))` | 包んだ定義をそのまま確立します。lite: rontolisp には非推奨警告のチャネルがないため level フォームは無視され、警告は一切出ません |
| `prog` | `(prog ((v init)...) tag-or-form...)` | ブロック内の `let` + `tagbody`: `go` が本体のタグ間をジャンプし、`(return x)` が `x` を返して抜けます |
| `prog*` | `(prog* ((v init)...) tag-or-form...)` | `prog` と同様で束縛が逐次的(`let*` 方式) |
| `shiftf` | `(shiftf a b 9)` | 場所の値を左へシフトし、最後の場所に新しい値を格納し、最初の場所の古い値を返します |
| `load-time-value` | `(load-time-value form)` | `form` をソース中の出現ごとに一度だけ評価します(初回使用時に遅延評価)。使用のたびではありません |
| `define-compiler-macro` | `(define-compiler-macro name (params...) body...)` | `name` の呼び出しをコンパイル時に書き換えます。`&whole` フォームを返すと辞退します。ヒントであり、本体がシグナルした場合・`name` が標準演算子の場合・`apply`/`funcall` 経由の場合は無視されます |
| `typep` | `(typep x '(unsigned-byte 8))` | `typecase` の指定子集合に対する型判定。指定子はリテラル(クオートされた)型に限られます |
| `slot-boundp` | `(slot-boundp obj 'slot)` | スロットが値を保持しているか: 未知のスロット、`:initform` なしで書かれ値も与えられていないスロット、`slot-makunbound` で空にしたスロットは `nil` |
| `slot-makunbound` | `(slot-makunbound obj 'slot)` | スロットを未束縛にしてインスタンスを返します。以後の読み取りは `unbound-slot` をシグナルします |
| `slot-exists-p` | `(slot-exists-p obj 'slot)` | インスタンスのクラスがそのスロットを宣言しているか(束縛の有無は無関係)。インスタンスでない値には `nil` |
| `print-unreadable-object` | `(print-unreadable-object (obj stream :type t) body...)` | 本体出力を `#<[type ]...>` で囲んで書き、nil を返します(`:identity` は受理のみでアドレスは出力しません) |
| `pprint-logical-block` | `(pprint-logical-block (s obj :prefix "<" :suffix ">") body...)` | プレフィックス、本体の出力、サフィックスを書きます。`obj` がリストでなければ `write` で印字し本体は評価しません。折り返しは起きません(桁位置を持たないため) |
| `with-package-iterator` | `(with-package-iterator (next pkgs :external) body...)` | ライト版: イテレータ名を「もうシンボルはない」と常に返すローカル関数に束縛(intern テーブルなし) |
| `do-external-symbols` | `(do-external-symbols (s :rontolisp) (print s))` | パッケージのエクスポート済みシンボルを反復 (インタプリタ専用。コンパイル済みバックエンドはパッケージレジストリを持たない) |

マクロは関数値を持ちません。`#'cond` や `(funcall 'setf ...)`
はエラーです。呼び出し位置でインライン展開される便利なアクセサや述語(`first`, `rest`, `nth`,
`second`..`fourth`, `1+`, `1-`, `zerop`, `plusp`, `minusp`, `evenp`, `oddp`)は、関数値としても使用できる(`#'first`)ため
[関数](functions.md) に列挙されています。

# 仕様との差異

準拠は設計上部分的です。Clojure オラクル（Clojure CLI 1.12）と動作が異なる箇所は
ここに列挙され、どのバックエッドでも同じです:

- `false` は `nil` とは別のオブジェクトです。両者とも偽なので
  `if`/`when`/`cond`/`and`/`or`/`not` は同じ扱いにしますが、`=` と `nil?` は区別します。
  `false?`/`true?`/`boolean?` がそれに応じて返します。
- `println`/`print` は部分を単一スペースでつなぎ、3 値 `true`/`false`/`nil` をその綴りで
 出力します。`str` は素の連結で `true`/`false`/`""` と綴ります（`str` の中の
 コレクションは、オラクルの `toString` 同様、文字列をクォートした可読の綴りです）。`pr`/`prn`/`pr-str` は
 可読の系列です（文字列はクォート付きで印字、`pr-str` は `pr` と同様スペース区切り）。
 `print-str`/`prn-str`/`println-str` は同じ系列を文字列で答えます。
 print 系はオラクルと同様に `nil` を返します。
- コレクションは Clojure 記法で印字されます（`[1 :a s]`、`{:a 1}`、`#{1}`、
 `(true false nil :k)`）。クオートされたシンボルは `c%` の後ろからマングル解除されます。
 `nil` は `nil` のまま（決して `()` にならない）で、map/set の走査順は未規定
 （`keys`/`vals` と同じ）なので、1 エントリの map と 1 メンバーの set だけが決定的に
 印字されます。循環を閉じる値は Scheme の `write` と同様のデータラベル
 （`#0=(1 . #0#)`）で印字され、循環なしの共有は 2 度印字されます。atom は可読でない
 形（`#<Atom value>`）、関数は `#<procedure>`、例外はその `toString`
 （`clojure.lang.ExceptionInfo: m {}`。オラクルは `#error {...}`）、未束縛の var のルートは `#<Unbound: #'user/x>`（オラクルの
 `#object` はハッシュを含みます）と印字されます。ストリームは種類に対応するホストクラスの
 `#object` として、オラクルの印字から識別ハッシュを除いた形で印字されます
 （`#object[java.io.StringWriter "ab"]`、
 `#object[java.io.OutputStreamWriter "java.io.OutputStreamWriter"]`）。`str` も同じ形の
 `toString` を答えます。文字列入力ストリームは `with-in-str` の
 `clojure.lang.LineNumberingPushbackReader` で、オラクルが `StringReader` の上の
 `java.io.PushbackReader` を答える場合も同じです。
- `*print-meta*` はオラクル同様に値のメタデータを値の前に書きますが、クォートしたリストは
 オラクルのリーダーが付ける `:line`/`:column` メタデータを持ちません。`*print-dup*` は
 プリンタが読まないただの値です。`assert` は展開される時点の `*assert*` を読むため、
 トップレベルでリテラルに `set!` すると以降の `assert` が無効になります。関数の中の
 `set!` や計算した値への `set!` では無効になりません（オラクルでは実行された時点で効きます）。
 Clojure 値に対する `~S`/`~A` は Common Lisp 記法のままです（`format` は CL サーフェス）。
 `print-method`/`pprint` はありません。
- マップ・セット・memoize のキーは、ベクター・リスト・マップ・セットも含めて本家と同じく
 `=` で一致するキーを見つけます。ただし格納されるコレクションのキーは、プログラムが最初に
 格納した同じ種類（ベクター・リスト・遅延 seq）の `=` なキーなので、メタデータと入れ子の
 要素の綴りはその先のオブジェクトに従います。これらのキーは異なる値と種類ごとに1つずつ、
 実行の終わりまで保持されます。セットリテラルの重複要素は綴り単位で拒否されます。
 `=` はベクター、リスト、遅延 seq を本家と同じく要素単位で比較します。`nil` が空リスト
 なので、本家が `false` を返す `(= [] nil)` と `(= (java.util.ArrayList.) nil)` は `true` に
 なります。
 `=` は本家と同じく左辺の Java オブジェクトに `equals` で尋ねますが、渡すのは数値、文字列、
 文字、`true`、`nil`、Java オブジェクトだけです。`false`、キーワード、シンボル、コレクションは、
 同じ種類の Java の `List`、`Map`、`Set` を除き、どの Java オブジェクトとも `=` になりません。
 マップとセットは Java のコレクションのキーを本家のハッシュマップ・セットと同じくそれ自身の
 `equals` で探し、本家の小さな配列マップが使う `=` では探しません。
 `(get {[1 2] :v} (java.util.ArrayList. [1 2]))` はここでは `nil`、本家では `:v` です。
- `seq` とその上の操作、`count`、`empty?`、`get`、`contains?`、`keys`、`vals` は本家と同じく
 Java の `Iterable`、`Map`、`CharSequence` を読み、`find`、`select-keys`、`reduce-kv`、
 `update-vals`、`update-keys`、`conj`、`merge`、`merge-with` は Java の `Map` をマップとして
 読みます。ただし seq は取った時点ですべて読み（本家はイテレータを遅延で辿ります）、`Map` の
 エントリは `[k v]` ベクターです（本家は Java のエントリで、`#object[...]` と表示されます）。
- 最も大きい入力がマップである `clojure.set/union` はシグナルします。本家は他の入力の
 `[k v]` メンバーをそこへ conj します。`clojure.set` の結果はメタデータを持ちません。
- マップエントリは単なる2要素のベクターなので、`map-entry?` はすべての `[k v]` に対して `true` を返し
 （本家はプログラムが作ったものに `false`）、`key`/`val` はそのようなベクターを読みます。
 同じ理由で `(conj {} #{[1 2]})` と `(conj {} (seq [[1 2]]))`・`(merge {} (seq [[1 2]]))` は `{1 2}` を返します（本家は `ClassCastException`。要素は本物のエントリでなければなりません）。
 キーワードはインターンされないため、`find-keyword` は一度も使われていない綴りにもそのキーワードを
 返します（本家は `nil`）。
- 型述語は値の表現に従います。`nil` が空リストなので、`seq?`・`list?`・`coll?`・`sequential?`・
 `counted?` は `()` に `false` を返します。strict な入力に対して操作が返す seq はリストなので、
 `list?`・`counted?`・`realized?` はそれに `true` を返します（本家の lazy seq や chunked seq は
 `false`）。チャンク化された seq はなく（`chunked-seq?` は常に `false`）、`iterate`/`cycle` の seq は
 一度強制されてから `realized?` になります。decimal と `N` のリテラルは通常の有理数なので、
 `decimal?` は常に `false` で、`ratio?`・`integer?`・`int?` はその有理数に対して答えます
 （`(ratio? 1.5M)` と `(int? 2N)` は `true`）。同じ理由で、`1` と `1M` を含むマップ・セットリテラルは
 重複として拒否されます。`identical?` は数値・文字・シンボルを値で比較し
 （`(identical? 1000 1000)` は `true`）、綴りが同じ2つのキーワードを同じオブジェクトとして扱います。

- プログラム自身がトップレベルで定義したコア名（`(defn peek ...)`）は、定義より上の呼び出しも
 含めてファイル全体でコアの関数を隠します（オラクルでは定義より上の呼び出しはコアに届きます）。
 ローカル束縛はオラクル同様にそのスコープで隠します。
- seq 群の空に対する `rest`/`next` は `nil` です（オラクルは `()` を印字）。範囲外の
 `nth` は投げずにデフォルトを返します。map/set seq の順序はテーブルの走査順です。
 文字列の seq は Common Lisp 記法で印字される文字になります。lazy seq は1要素ずつ
 realize します（chunk 化なし）。lazy seq の `str` は要素を綴ります（オラクルは
 `clojure.lang.LazySeq@<hash>`）。`map` は任意個数のコレクションを取ります。
- strict なコレクション上の `for` は strict なリストを答え、`for` の実行時に realize
 されます（オラクルは消費まで待ちます）。要素がなければ `nil` で、オラクルが `()` と
 印字する点と異なります（`rest`/`next`/`take` と同じ empty-as-`nil` の立場）。最初の
 lazy なコレクションから先はオラクル同様に lazy です。
- `cond` は寛容な読みを保ちます:奇数の末尾 arm はデフォルトです（Clojure はシグナル）。
 コレクションリテラル上のスレッディングステップはシグナルします（ここではコレクションは
 関数ではない）。
- 末尾位置で自分を名前で呼ぶ関数（`defn`、名前付き `fn`、`letfn` の項目）は、`recur` と
  同じくどのバックエンドでも定数スタックで動きます。末尾位置で互いを呼び合う関数（`letfn`
  の項目同士、`defn` 同士）も同様です。本家はこの呼び出しごとにフレームを積むため、深い
  呼び出しは本家ではスタックオーバーフローし、ここでは最後まで実行されます。
  そのオーバーフローを確かめるテスト（`(is (thrown? StackOverflowError (tail-fibo 1000000N)))`）
  は失敗します。
- `clojure.test` はテストを定義順に実行します（本家の順序は名前空間のマップの順です）。
  エラー報告は例外の `toString`（実行時エラーはその report）を表示し、
  スタックトレースは表示しません。位置は `is` 式の行で、本家は例外を投げたフレームを示します。
  失敗した `thrown-with-msg?` はコンディションのメッセージを表示し、本家は `#error {...}` を
  表示します。ホストのスタックオーバーフロー（`catch StackOverflowError`、
  `(is (thrown? StackOverflowError ...))`）は、インタプリタではコンディションではないため
  1 行の報告でプログラムが終了し、WASM ではトラップになります。本家と同じく捕捉するのは
  JVM バックエンドだけです。`use-fixtures` は名前を挙げて拒否します。
- `catch`（と `thrown?`）は、ランタイムが Common Lisp のコンディションをシグナルする箇所で
  オラクルが投げるクラスとして実行時エラーを捕捉し、Clojure ランタイム自身の拒否は同じ呼び出しで
  オラクルが投げるクラスとして捕捉します（`(first 5)` は `IllegalArgumentException`、失敗した
  `assert` は `Exception` の catch が捕捉しない `AssertionError`）。クラスを示さないエラー --
  オラクルが受け付ける構文の拒否（正規表現の先読み、`(partition 0 coll)`）-- は、
  `clojure.lang.ExceptionInfo` 以外のどのクラスの catch でも最初のものが捕捉します。
  下位の関数が先に拒否する誤用は、その関数のクラスになります。`(shuffle 5)` は `seq` の
  `IllegalArgumentException` で、オラクルは `java.util.Collection` へのキャストで失敗します。範囲外の添字は `IndexOutOfBoundsException` で、
  そのサブクラスの catch も捕捉します（オラクルの `aget` は `ArrayIndexOutOfBoundsException` を
  投げます）。文字列の範囲を超える `subs`、`.substring`、`.charAt` は
  `StringIndexOutOfBoundsException` を投げます（int の範囲を超える double の境界も同じで、
  オラクルの `subs` は `ArithmeticException` か `IllegalArgumentException` を投げます）。catch が名指すクラスはこの
  ホストで解決できなければなりません（`java.*`、`clojure.lang` の throwable、プログラムの
  Java クラスパス `--java-classpath`・`--java-dep` のクラス）。オラクルの
  クラスパスにしかないクラスは拒否します。
- 例外はクラス、メッセージ、データ、cause を持つコンディションです。実行時エラーは
  ランタイムがシグナルする Common Lisp のコンディションで、そのメッセージは Common Lisp の
  report（失敗した `(inc nil)` の `(.getMessage e)` は `+: The value NIL is not of type
  NUMBER` で、オラクルでは `NullPointerException` の文言）、`str` はオラクルのクラス名の
  接頭辞を持たないその report です。throwable の構築が例外になるのは、メッセージと
  cause 以外に何も持たないクラスだけです。独自のメンバーを持つクラス
  （`java.net.URISyntaxException`）はホストオブジェクトのままです。インタプリタと JVM では、
  Java のメンバが投げた例外と throw されたホストオブジェクトはオラクルと同じくホスト自身の
  ものです。catch はそのクラスで捕捉し、そのオブジェクト自体を束縛します。
  プログラムが作った例外の `class` はクラス名をキーワードで返し（`:java.lang.Exception`。オラクルはホストの
  クラスを返します）、クラスを示さないエラーには `:java.lang.RuntimeException` を返します。`.printStackTrace` は `toString` の行を `*err*` に書き（オラクルはそれとフレーム
  ごとの行を、`*err*` の束縛に関わらずプロセスの標準エラーに書きます）、`.getStackTrace` は
  空のベクターを返します。`.getClass` は `class` と同じ値を返します。インタプリタと JVM では、
  それ以外のメソッドは、例外のクラスのホストの例外に対して呼び出され、Java のメンバに渡した
  例外もそのホストの例外として渡ります。ホストの例外はメッセージと cause から一度だけ作り、
  `ex-info` のものは `RuntimeException` です。そのため `(.getCause (UncheckedIOException. "u" e))`
  が返すのはそのホストの例外で、`e` 自体ではありません（オラクルでは `e` と `identical?` です）。
  実行時エラーとランタイムの拒否にはそのようなホストの例外がないため、`Throwable` を受け取る
  Java のメンバはそれに一致しません。例外でない値の `throw` は、値のレンダリングをメッセージとする
  `ClassCastException` になります（オラクルのメッセージは 2 つのクラス名を挙げます）。
- multimethod のディスパッチ値はマップのキーと同じく（ベクターも含めて `=` で）比較されます。
 階層経由のディスパッチは厳密に最も具体的なメソッドを優先し、その後
 `prefer-method` の選択に従います。ホストクラス上の `defmethod` は `class` が答える
 キーワードの下に格納され、すべての数値綴りは `:number` にまとまります（オラクルは
 `Long` と `Double` を区別します）。真の nil は `(:C%NIL)` マーカーへ写されるので、
 どの表も nil をキーにしません。一方リテラルに `:nil` な値はキーワード行のままです
 （オラクル通り。ディスパッチ関数の中の `class` 呼び出しは nil 引数に nil 自身を答えるので、
  null 判定でそこでもマーカーへ写ります。素のものでも他の関数で包んだものでも、記録した定義から再降低される名前付き `defn`・`def` 済み関数経由でも、インラインなディスパッチ datum の中でそれらを呼び出す場合（呼び出し位置で同じ降低をインライン化）でも同様です）。
 `Object` メソッドは検索の後・デフォルトの先に捕まえます。throwable やストリームのクラスは
 その名前のキーワード（`class` が答えるもの）の下に格納され、検索はオラクルの継承と同じく
 Java のスーパータイプをたどります。インタフェース（`java.io.Serializable`、
 `java.io.Closeable`）と `Object` も含みます。`isa?`・`derive`・`underive`・`parents`・
 `ancestors`・`descendants` はクラス名を同じキーワードとして読むので
 `(isa? (class "a") String)` は `true` で、クラスの `parents`/`ancestors` はオラクルと同じく
 Java のスーパータイプを加えます。コアの種類（`:string`、`:number` など）、record、deftype は
 ここでは 1 つの値にならないホストクラス群を表します。`Object` に `isa?` で、`ancestors` は
 `Object` を加えますが、ホストクラスのそれ以外のスーパータイプはモデル化しません。これらの
 位置にクラス名を書かずホスト相互運用も使わないプログラムは階層だけを読むので、そこでの
 `(ancestors (class e))` は `derive` が記録したものを返します。ここではキーワードがクラス
 そのものなので、`:java.lang.Exception` と綴ったキーワードもそのクラスです。ホストの
 クラスオブジェクト（ホストオブジェクトの `class`、インタプリタと JVM）はその名前の
 キーワードと、単純名を通じてコアの種類のキーワードと同じクラスなので、
 `(isa? (class (java.util.ArrayList.)) java.util.List)` は `true` です。同じく `:list` と
 綴る `clojure.lang.IPersistentList` にも `isa?` で、オラクルは `false` を返します。それ以外の
クラス（`java.io.File`）はディスパッチ値でもクラスオブジェクトで、オラクル通りです。プロトコルの
 ディスパッチは階層を読まず（タグの
 完全一致と `Object` 既定）、`Long`・`Double` を `:number` にまとめます（オラクルは
 区別します）。
- `(methods mt)` と `get-method`・`remove-method`・`prefer-method` は式ではなく multimethod の
  名前（`defmulti` の var。alias や refer 経由も可）を取ります。multimethod を束縛した
  ローカルは降低時に拒否されます。`methods` が返すマップはホストクラスの行を `class` が
  答えるキーワードで引き、オラクルは `Class` 自身で引きます。
- record はオラクル同様リテラルで印字されます（`#user.R{:a 7}`）。ただし `str` も
 そのリテラルを綴ります（オラクルは `user.R@<hash>`）。deftype はラッパーリスト
 （`(:C%TYPE ...)`）、reify は `(:C%REIFY ...)` で印字されます。決定的に印字されるのは
 エントリのマップだけです。
- deftype の `^:volatile-mutable` フィールドは `^:unsynchronized-mutable` と同じ素の
 スロットです（スレッド間の順序保証はありません）。
- `split`/`replace` は seq を返しベクターにはなりません。素の文字列は文字通りのままです（パターン値だけがパターンマッチします）。`index-of` は
 見つからないときオラクル同様に `-1` を返します（`clojure.string/index-of` は `nil`）。
- 本体内の `def` は本体が走るときにグローバルを設定します。本体内の `defn` は文位置の
 みで動きます（複数アリティはトップレベルのみ）。
- 操作は正しいコレクション種別を仮定します。誤用はオラクルの代わりに Common Lisp の
 型エラーをシグナルすることがあります。
- マクロは lower 中に展開されるため、すべてのバックエンドは展開後のコードを実行します。
 マクロ呼び出しに対するインタプリタ自身の `eval` も同様に展開します。マクロ本体は
 プログラムとは別にコンパイル時に実行されます。呼び出し位置より上のトップレベル定義
 （関数、マルチメソッド、プロトコル拡張、そして本体が最初に読むときに作られる `def`
 の値）は見えますが、それ以外のトップレベル文はそこでは実行されず、本体が変えたもの
 （プログラムの atom への `swap!`）はプログラムからは見えません。oracle はコンパイルと
 実行を一つのプロセスで行います。プログラムのマクロが展開した `defmethod` やプロトコル
 拡張はそこでは見えません。定義より上での呼び出しは拒否され、マクロに関数値はありません。special
 form（`if`、`do`、`let*`、`new` など）、リーダーが綴る先頭（`deref`、
 `syntax-quote`、`ns`、`in-ns`）の `defmacro` は名前を挙げて拒否されます。oracle はこれを
 受け付けます（special form なら呼び出し位置では無視します）。
- `#(...)` はソース、クオートの下、`read-string`/`read` のいずれでもオラクルと同じ
  `(fn* [p1__N# ...] (body))` と読まれますが、N はトップレベルのフォーム（読む datum）
  ごとに 1 から数え直します。オラクルのカウンタはプロセス全体で進むため、引数名が異なり、
  同じテキストを 2 回読んだ結果はこちらでは `=` になります。マクロに渡した正規表現
  リテラルは、同じソースからコンパイルし直した新しいパターンとして展開に届きます
  （オラクルの展開は同じ `Pattern` オブジェクトを持ちます）。
- syntax-quote が限定するのは special form 以外のすべてのシンボルであり、oracle と
  同じです。核の名前は `clojure.core/name` と綴り（`(:refer-clojure ...)` で隠され
  たものは自身の名前空間で綴ります）、その他の解決できない綴りは定義側の名前空間、
  エイリアス先頭はその名前空間、クラス先頭は完全限定名で綴ります。各 `x#` は展開ごとに 1 つの gensym を束縛します。オラクルはコンパイル
 ごとに 1 つを解決するため、2 つの展開が suffix を共有するところ、こちらは異なります
 （より新鮮であり、capture されません）。`macroexpand-1`/`macroexpand` は mangle された
 データそのものを答えるため、quote したフォームとの `=` が成り立ち、表示は oracle と
 同じ小文字綴りになります。ネストした syntax-quote は 1 回の展開の中で
 各レベルを評価します。
- var のメタデータは `#'` の位置より上で lowering された定義から来ます。再定義より上で
  lowering された本体は古い docstring を見ます（オラクルの var は最新を見ます。呼び出しと
  同じ分かれ方です）。`:ns` は名前空間のシンボルです（オラクルは Namespace オブジェクト）。
  入口ファイルの `:file` は与えたままのパスです（オラクルは絶対パスにします）。
  `def`/`defn`/`defn-`/`defmacro` 以外（`defmulti`、`deftest`、レコードのファクトリなど）で
  定義された var は `:name` と `:ns` だけを持ちます。マクロの var の deref はシグナルを
  上げます（オラクルは展開関数を答えます）。`clojure.core` の var のメタデータは `:name`、
  `:ns` とマクロの `:macro` だけです（オラクルは `:arglists`、`:doc`、`:added` と位置も
  持ちます）。ここで値を持たない core の var（`#'all-ns`）は拒否されます。
- 名前空間は oracle の `#object[clojure.lang.Namespace "user"]` から identity hash を除いた
  形で印字され、その `class` は `:clojure.lang.Namespace` を答えます。`the-ns` と `find-ns` が
  知っているのは、呼び出しより上でプログラムが作った名前空間、require したライブラリ、
  `clj -M` が先にロードする 4 つ（`clojure.core`、`clojure.edn`、`clojure.java.io`、
  `clojure.string`）です。`in-ns` は `nil` を答えます（oracle は名前空間を答えます）。
  `*ns*` への `set!` や `binding` は `*ns*` が読む値を変えますが、以降のフォームが解決される
  名前空間は変えません。それはリテラルの名前をとる `ns` と `in-ns` が決めます。コンパイル
  したプログラムの `*file*` は、コンパイル時のエントリファイルのパスです。
- `class` は種類名のキーワードで答えます（`:string`・`:number`・`:keyword` 等）。オラクルは
  ホストクラスを返しますが、wasm バックエンドにはありません。record/deftype は
  タグのキーワードで、ホストオブジェクト（インタプリタと JVM）はホストクラスで答えます。
- コレクション・キーワード・シンボル・比・atom・fn へのインスタンス呼び出しは core 関数を通して
  答えるため、その逸脱も引き継ぎます（`.getClass` は `class` と同じ値を返します）。対応づけて
  いないメソッドは `Method m taking N args is not supported for class C` として拒否し、
  オラクルが答える場合（`.hashCode`）もあります。map のクラス名は件数だけで決め、8 件までは
  array map、それを超えると hash map とします。ここでは `nil` が空リストなので、コレクションの
  メソッドは `nil` にも答えます（`(.count nil)` は `0`）が、オラクルは
  `NullPointerException` を投げます。`nil` へのそれ以外のメソッドは `NullPointerException` です。
  record・deftype・reify でも、プログラムが定義したどの record・deftype のプロトコルメソッドでも
  フィールドでもない名前は同じように拒否します（オラクルは `No matching field found`）。
  後の REPL 入力が record・deftype を定義しても、それより前に lower された呼び出し箇所は
  そのメソッドもフィールドも見ません。
- `instance?` は値の種類ごとのオラクルのクラスで答えます。リストと正格な seq は
  `clojure.lang.PersistentList` なので、`(map inc [1])` について `IPersistentList` と `Counted` は
  `true`、`LazySeq` は `false` です（オラクルでは `LazySeq`）。2要素のベクタはすべて
  `java.util.Map$Entry` です（`map-entry?`）。整数は `(int 1)` も含めて `Long` で、`Integer` には
  なりません。どの種類の値もインスタンスにならない `clojure.lang` のクラス
  （`clojure.lang.PersistentQueue`）は未知の名前になります（オラクルは `false`）。プロトコルの
  インタフェース（`user.P`）が知るのはその箇所を lower した時点で定義済みの record と deftype
  なので、後の REPL 入力で定義したものはそこではインスタンスになりません。プロトコル自身の名前
  （var である `P`）は未知の名前になります（オラクルは `ClassCastException`）。
- `unchecked-` の算術は整数を64ビット（`-int` 系は32ビット）に折り返し、型変換 `short`・`byte`・
  `char`・`float` と合わせてオラクルと同じです。ただし64ビットを超える整数もここでは通常の整数なので、
  オラクルでは折り返されない bigint のオペランド（`(unchecked-add 9223372036854775807N 1)`）も
  折り返します。`int` と `long` はオラクルの範囲検査をせずに切り捨てます（`(long 1e19)` は
  `10000000000000000000` で、オラクルは `IllegalArgumentException` を投げます。`(long ##NaN)` は
  シグナルし、オラクルは `0` を返します）。比の `double` は最も近い double で（`(double 2/3)` は
  `0.6666666666666666`）、オラクルは先に有効数字 16 桁に丸めます（`0.6666666666666667`）。`inc`・
  `dec` と検査付きの演算は桁あふれしません（整数は bignum です）。
- `bigint` と `biginteger` は通常の整数、`bigdec` は通常の有理数を返します（`(bigdec "1.5")` は
  `3/2` と表示され、オラクルは `1.5M`）。`N`・`M` リテラルと同じです。10進展開が無限になる比の
  `bigdec` はオラクル同様シグナルします。
- `format` は `%s`・`%d`・`%x`・`%X`・`%o`・`%c`・`%b`・`%f`・`%%`・`%n` を描画します
  （幅・浮動小数点精度付き）。`%e`・`%g`・フラグ・非リテラルは名前付きで拒否されます。
  `%s` の `nil` はオラクル同様 `"null"` です。
- `line-seq` はパスか開かれたリーダー（`clojure.java.io/reader` など。閉じるのは
  `with-open`）を取って、どちらも strict に答えます（オラクルはリーダーを取って遅延です）。
  `spit`・`slurp`・`line-seq`・`reader` はインタプリタと JVM、wasm ではパスを含む `--dir` プリオープン付きで動きます。
  リーダーの上の `java.io.InputStreamReader` はどのバックエンドでもそのリーダー自身です。Ring のリクエスト
  `:body` が、オラクルでは `InputStream` であるのに対し、ここではリーダーだからです。
- Ring アダプター（`ring.adapter.rontolisp/run-server`）のリクエストマップには
  `:content-type` と `:content-length` が入りますが、`:character-encoding` と
  `:ssl-client-cert` は入りません。ボディのないリクエストの `:body` は `nil` です（Jetty の
  アダプターは空のストリームを渡します）。非同期ハンドラと `java.io.File` のボディは拒否し、
  2 つ目の同時サーバーは最初のものを置き換えます（[アダプター](reference/ring.md)を参照）。
- 組み込みの [Ring ユーティリティ](reference/ring-util.md)は、文字セットを文字列で指定します
  （UTF-8、ISO-8859-1、US-ASCII とそれらの JDK の別名。ほかは拒否します）。
  `ring.util.request/body-string` は拡張できるマルチメソッドではなく関数で、`content-length`
  は ASCII の数字だけを読みます。`java.io.File`、URL、バイト配列を使うものは含みません。
- ソート済みのマップとセットは、順序付け・表示・キーの検索がオラクルと同じですが、どの操作も
  コピーを作ります（関連付けにはハッシュマップと同じくコレクションの大きさ分のコストが
  かかります）。`class` は `:map`/`:set` を返します。先頭から走査して何も残らない `subseq`/`rsubseq` は `nil` を返します（オラクルは
  `()`）。値として `subseq` に渡したテストは `(1 0)`・`(0 0)`・`(-1 0)` への答え方で判別します
  （オラクルはコアの関数との同一性で判別します）。`compare` は文字列をコードポイントで比べます
  （オラクルは UTF-16 の単位で比べるので、U+FFFF を超える文字で答えが変わります）。
- `float` は倍精度の値を返すので、`(float 1/3)` は `0.3333333333333333` です（オラクルの Float は
  `0.33333334` と表示します）。float の範囲を超える値は同様にシグナルします。`int` と `long` は
  切り捨てるだけで、範囲外の値を拒否しません（オラクルは `integer overflow`、
  `Value out of range for long: ...`）。
- 被除数が NaN や無限大の `mod` と `rem` は `ArithmeticException` を投げます（オラクルは
  `NumberFormatException`）。
- `vector-of` は通常のベクターを返します。あとの `conj` や `assoc` は値をそのまま格納し
  （オラクルは型変換を続けます）、`:float` も倍精度で保持します。
- リスト・遅延シーケンス・シーケンスの `empty` は `nil` を返し（オラクルは `()`。`rest` と同じ、空を
  `nil` とする扱い）、メタデータも持ちません。マップエントリの `empty` は `[]` です（オラクルは `nil`）。
- `partition` に pad はありません。非正のサイズや step の `partition-all` はシグナルします
  （オラクルは `()` の無限 seq を返します）。`pmap` は `map` で、呼び出し元のスレッドで順に
  走ります。`take-nth` は step が 0 だとシグナルし、seq 形は負の step を絶対値で進みます。
  オラクルの seq 形は先頭要素を無限に繰り返します。
- トランスデューサーはオラクルと同じく畳み込み関数に対する関数ですが、`eduction` は入力を
  それへ通した `sequence` で、一度だけ計算します（strict な入力には strict に、lazy な入力
  には lazy に）。オラクルは reduce のたびに変換をやり直します。`println` はその seq を
  表示し、オラクルはオブジェクトを表示します。`reduced` 値はラッパーのリストとして表示
  されます。
- トランザクションは単一スレッドのエクステントです。`dosync` はリトライせず、`commute`
  は関数を1回だけ走らせ（オラクルは2回走らせうる）、validator は書き込み時に走って
  失敗時は古い値を残します。`dosync` の外側での `alter` 等はシグナルします。
- agent は同期アトムです。`send`/`send-off` は即時に適用して agent を答えにし
  （印字は unreadable な `#<Atom ...>` で、オラクルのオブジェクトではありません）、
  `await` と `shutdown-agents` は `nil` を答えます。`*agent*` は送信実行中にだけ
  束縛され（外側は `nil`。オラクルでは unbound です）。
- `binding` が再束縛できるのは `^:dynamic` な var だけです（それ以外はオラクルの
  非 dynamic エラー同様に拒否）。`^:dynamic` な `defn` も再束縛できます（定義は
  直接のまま、呼び出しは var 経由になります）。それ以外の名前やローカルについた
  リーダーメタデータは解析して捨てられ、ディスパッチに影響しません。
- REPL では、後の入力が `^:dynamic` として定義する var と同じ名前のローカルは、
  その定義以後、エクステントの間その var を束縛します。そこで呼ばれた関数は
  ローカルの値を読みます。オラクルではローカルはレキシカルに束縛され、関数は var を
  読みます。ファイルではこのローカルはレキシカルです。
- `with-redefs` が置き換えるルートは var の値セルなので、`^:dynamic` な var の
  `binding` の内側では、その var の束縛を変更します（オラクルではルートが変わり、
  束縛はそのままです）。`clojure.core` の var は名前を挙げて拒否します。REPL では、
  以前の入力が `^:redef` なしで定義した `defn` を拒否します。
- `locking` のロックは値ごとに保持するミューテックスで、ロック表は渡された値を
  プログラムの実行中ずっと保持します。`nil` をロックしたときの
  `NullPointerException` のメッセージはローカル名 `locklocal` を示します（オラクルは
  生成した名前を示します）。
- `with-meta` はメタデータを持つコピーを返します。そこから導いた値（`assoc`、`conj`
  など）はメタデータなしで始まり（オラクルは引き継ぎます）、シンボルはメタデータを
  持ちません（`with-meta` はシンボルを返します）。コレクションリテラルのリーダー
  メタデータの `:tag` は書いたままのシンボル（`String`）で、オラクルはクラス
  （`java.lang.String`）に解決します。
- `with-open` は `close` メソッド越しに閉じるため、バックエンドの届く closeable
  だけが動きます（Java の closeable は JVM が要ります）。`time` は値を答えますが、
  ミリ秒数は固定されません。数えるのは整数ミリ秒（`42.0`）で、オラクルの値には
  ナノ秒の桁が付きます。
- `read-string`/`read` はクオートと同じ答えを返します。`@x` は `(deref x)` と読まれ、
  構文クォートは展開されません（オラクルは `(clojure.core/deref x)` と読み、展開します）。
  レコードリテラルは
  プログラムが定義するどのクラスでも読め、後の `require` が読み込むクラスも読めます
  （オラクルは先にクラスが読み込まれている必要があります）。deftype のリテラルは
  拒否されます。`read` はストリームを取り、素の `clojure.java.io/reader` も受け付けます
  （オラクルは `PushbackReader` を要求します）。ホストのリーダは拒否します。
- `*out*`/`*in*`/`*err*` は `*standard-output*`/`*standard-input*`/`*error-output*`
  です（再束縛は標準ストリームの再束縛になります）。ルートで読んだ `*out*` と `*in*` は
  プロセスの標準ストリームを指すストリーム値です。
  `defonce` はリロードでルートを保ちます（`def` はリセットします）。
- ホストオブジェクトの boolean は、lowering 時に receiver のクラスがわかり
  （構築リテラル、それを束縛した `let`/`if-let`/`when-let` ローカル、または
  `..` ステップの宣言戻り値型）、その引数個数の
  オーバーロードがすべてプリミティブ boolean を答える場合と、receiver が文字列・数値・
  文字で、そのクラスのその引数個数のオーバーロードがすべてプリミティブ boolean を答える
  場合（`(.matches "abc" "x")`）と、receiver がインタフェース 1 つかクラス 1 つだけの
  `proxy` の場合だけ `false` を答えます。
  それ以外のホスト boolean は共有の `java:` unmarshal のままとなり、`false` は
  `nil` と表示されます。ホストコレクションから読み戻した `Boolean.FALSE` も同じです
  （`false` を入れたリストの `(vec l)` は `[nil]` を答えます）。
- Java のインタフェースが期待される位置に渡した fn は、どのインタフェースでもその抽象
  メソッドすべてを実装し、それぞれメソッドの引数で呼ばれます。オラクルが fn を変換する
  のは `@FunctionalInterface` 注釈付きのインタフェースだけです（`PropertyChangeListener`
  はオラクルでは `ClassCastException` になります）。fn の値は引数と同じ規則で Java へ
  戻ります。Java に渡した `Comparator` の fn は数値を答えます（オラクルは `true`/`false`
  も受け付けます。fn 自身への `(.compare f a b)` はここでも受け付けます）。
- Java に渡したマップは、そのエントリを持つ新しい `java.util.LinkedHashMap` になり、
  各キーと値は引数と同じく変換されます（ベクタは `List`、マップは `Map`）。オラクルは
  マップそのものを渡すため、コピーの `str` は入れ子のコレクションを Java の形で綴ります
  （`{a=[1, 2]}`。オラクルは `{a=[1 2]}`）。セット・キーワード・record には Java の値が
  ないため、それを取るメンバ（キーワードをキーにしたマップも）は一致しません。
- 整数の receiver は `Integer` に収まれば `Integer`、収まらなければ `Long` として
  呼ばれます（オラクルでは常に `Long` です）。`(.getClass 1)` は
  `java.lang.Integer` を答えます。
- パラメータタグの `_` はその引数を `java:` サーフェスのコスト規則に任せるため、
  `(^[_] Math/abs -2)` は `2` を答えます。オラクルは複数のオーバーロードが残るタグを
  拒否します。
- 読むのはソースルート以下の `.clj` と `.cljc` のファイルだけです。クラスを含む依存の jar は
  Java クラスパスにも加わりますが、ディレクトリのクラスは加わりません（準備済みライブラリの
  `target/classes` は `--java-classpath` で与えます）。プロジェクトの `deps.edn` は、oracle が
  作業ディレクトリのものを読むのに対し、エントリファイルの位置から上へたどって最初に見つかる
  ものです。コマンドライン以外（組み込み側、ブラウザのプレイグラウンド）では Maven 座標と git
  座標を取得しません。それにしかありえない名前空間はそれを挙げて拒否し、プログラムの残りは
  動きます。組み込みのライブラリは、その依存も含めて何も取得しません。組み込みの Ring 名前空間は
  `ring/ring-core` の座標がなくてもロードでき（oracle では座標が必要です）、同梱より古い ring-core
  の代わりにもなります。`pom.xml` のプロジェクトは読まず、ライブラリの `data_readers.clj` も
  読みません。そのタグはほかの未知のタグと同じく拒否します。`settings.xml` の認証情報で応じるのは
  Basic 認証だけで（oracle は Digest と NTLM にも応じます）、ダウンロードは `maven-metadata.xml` も含めて
  常に `.sha1` と照合します（oracle の既定は警告だけです）。どのリポジトリにもなかったファイルは
  翌日まで問い合わせ直しません（oracle は実行のたびに問い合わせます）。更新ポリシーはすべての
  リポジトリで 1 つ、daily です（リポジトリの `:update` は読みません）。git のタグはローカルの
  クローンで確認し、タグがないか別のコミットを指すときだけ取得します（oracle は解決のたびに
  取得します）。チェックアウトは `~/.gitlibs` ではなく `~/.rontolisp/gitlibs` に置きます。1 つの
  ライブラリの 2 つのコミットのどちらも他方の子孫でないときは、両方を挙げて拒否します（oracle は
  メッセージのない例外を投げます）。依存ライブラリの `:deps/prep-lib` は確認するだけで実行しません。
  トップレベルの依存はファイルの順に展開します。oracle は 9 個以上の依存を持つマップをハッシュの
  順に展開するため、どちらを先に見たかで選択が決まる場合（同じバージョンの `1.0` と `1.0.0` の
  ような 2 通りの書き方）に限って結果が異なります。
- `-M -m` と `-X` が指す名前空間や関数がないときは、ほかのプログラムと同じく
  `Could not locate my/app.clj ...` や `No such var: my.app/-main` になります。oracle は
  `Namespace could not be found on classpath` や `loaded but function not found` を返し、
  `-main` がなければ `NullPointerException` を投げます。`clojure.main` の `-e`、`-i`、
  `--report`、標準入力から読む `-X` の引数（`-`）、`-T` のツールは拒否します。`:jvm-opts` は
  無視し、値がマップでないエイリアスを選んでも何も加えません。ファイルのない実行（`-M -m`、
  `-X`、`-e`、REPL）は最初に作業ディレクトリを探しますが、oracle のクラスパスには含まれません。
  `rontolisp test` は `clj` のコマンドではなく、cognitect test-runner の既定（`test` 以下の、
  名前が `-test` で終わる名前空間）に従います。
- リーダ条件は `:rontolisp` も選びます。フォームが `:clj` より先に挙げていればそちらが先です。
  `{:read-cond :preserve}` で読んだリーダ条件やタグ付きリテラルの `class` は、ここでのほかの
  クラスと同じくキーワード（`:clojure.lang.ReaderConditional`・`:clojure.lang.TaggedLiteral`）
  を返します。`str` が綴るハッシュは `=` に従うこの実装のもので、オラクルの `hashCode` では
  ありません。`(reader-conditional nil false)` は `#?()` から読んだものと同じく `#?()` と
  印字されます。選ばれない分岐の中では、
  未知のエイリアスの `::alias/kw` も読めます（オラクルは拒否します）。実行時のリーダは
  `#?@(:clj nil)` を何も展開しないものとして読み（オラクルは拒否します）、`:features` は
  ハッシュセットだけを受け取ります。
- 2 つの名前空間で同じ単純名を持つ record と deftype はディスパッチタグを共有し、
  `class` の答え、プロトコルディスパッチ、`=` の比較はこのタグに基づきます。
- 同じ名前を 2 つの名前空間から refer すると後の refer が有効になります（oracle は拒否
  します）。refer した名前を定義すると、その定義が oracle の警告なしに refer を置き換えます。

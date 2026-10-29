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
 `nil` は `nil` のまま（決して `()` にならない）です。map/set はどのバックエンドでも
 エントリを挿入順に印字し、`seq`・`keys`・`vals` も挿入順にたどります -- 8 エントリ以下の
 map ではオラクル（その array map）と同じ順で、8 を超える map（その hash map）と
 set（その hash set）では異なります。循環を閉じる値は Scheme の `write` と同様のデータラベル
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
 Common Lisp の `format` で Clojure 値に `~S`/`~A` を使うと Common Lisp 記法のままです（CL
 サーフェスのため。`clojure.pprint/cl-format` は Clojure の記法で書きます）。
 [print-method](reference/print-method.md) はプログラム自身の record・deftype・`:type`
 キーワードと、インスタントか UUID 以外のコアの値が属さないホストクラスが対象です。ホストクラスの
 メソッドはホストオブジェクトだけを書きます（ここで作ったインスタントや UUID は `#inst`/`#uuid`
 のままです）。ほかのクラスのメソッドは名前で拒否し、その `methods` と `prefer-method` も
 同様です。メソッドのない型の `get-method` は `print-method` 自身を
 返します（オラクルは既定のメソッドを返します）。
- 引数の個数の誤りはオラクルと同じ `Wrong number of args (N) passed to: 名前` ですが、
 ローカルの `fn` の名前はオラクルのクラス名から生成部分を除いたものです。囲む関数の名前
 （`defn` なら `my.app/f`、外にない場合は名前空間）に、`fn` 自身の名前か `fn` を続けます
 （`my.app/f/fn`。オラクルは `my.app/f/fn--177`、トップレベルでは
 `my.app/eval176/fn--177/named--178` のように `evalN` と `try` を包む関数も入ります）。
 var の関数（`defn` と、`def` の値そのものである `fn`）の名前はオラクルと同じです。
- マップ・セット・memoize のキーは、ベクター・リスト・マップ・セットも含めて本家と同じく
 `=` で一致するキーを見つけます。ただし格納されるコレクションのキーは、プログラムが最初に
 格納した同じ種類（ベクター・リスト・遅延 seq）の `=` なキーなので、メタデータと入れ子の
 要素の綴りはその先のオブジェクトに従います。これらのキーは異なる値と種類ごとに1つずつ、
 実行の終わりまで保持されます。deftype のキーで、先のオブジェクトが後の `=` なキーの代わりに
 格納されるのは、両者が同じ型で同じフィールドの値を持つ場合だけです（`reify` と変更可能な
 フィールドを持つ型では代わりに格納されません）。そのため `=` が読まないフィールドは、格納した
 オブジェクト自身のものです。
 `=` はベクター、リスト、遅延 seq を本家と同じく要素単位で比較します。`nil` が空リスト
 なので、本家が `false` を返す `(= [] nil)` と `(= (java.util.ArrayList.) nil)` は `true` に
 なります。キーとしては本家と同じく `[]` と `nil` を区別するので、空のリストや seq は空ベクターの
 キーに一致しません。`(get {[] 1} ())` は `nil` です（本家は `1`）。
 `=` は本家と同じく左辺の Java オブジェクトに `equals` で尋ねますが、渡すのは数値、文字列、
 文字、`true`、`nil`、Java オブジェクトだけです。`false`、キーワード、シンボル、コレクションは、
 同じ種類の Java の `List`、`Map`、`Set` を除き、どの Java オブジェクトとも `=` になりません。
 マップとセットは Java のコレクションのキーを本家のハッシュマップ・セットと同じくそれ自身の
 `equals` で探し、本家の小さな配列マップが使う `=` では探しません。
 `(get {[1 2] :v} (java.util.ArrayList. [1 2]))` はここでは `nil`、本家では `:v` です。
- `hash`、`hash-ordered-coll`、`hash-unordered-coll`、`mix-collection-hash`、`hash-combine`、
 `.hashCode` は本家と同じ数を返します。ただし同一性でハッシュする値（関数、atom、var、例外、
 `hasheq` も `hashCode` も実装しない deftype や reify）の数は本家と異なり、バックエンドや実行
 ごとにも変わります（本家でも実行ごとに変わります）。ここで `nil` である `()` と空の `rest` は
 `0` になります（本家は空の seq としてハッシュします）。`Calendar` はそのミリ秒の `Date` として
 ハッシュします。`hash-ordered-coll` はマップ・セット・
 レコードの要素をこの実装の順で辿ります（順序によらないハッシュは一致します）。拒否の
 `ClassCastException` のメッセージには本家のモジュールとローダの説明が付きません。
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
 一度強制されてから `realized?` になります。`identical?` は数値・文字・シンボルを値で比較し
 （`(identical? 1000 1000)` は `true`）、綴りが同じ2つのキーワードを同じオブジェクトとして扱います。
- long の範囲内の整数は、何が作ったものでも通常の整数です。`N` リテラル（`2N`）、`bigint`、
 `biginteger`、`BigInt` の和や積（`(+ 1N 2)`）、整数になる比の演算結果（`(* 1/2 2)`、
 `(rationalize 1.0)`）はオラクルの `N` なしで印字され（`3N` ではなく `3`）、`int?` は `true`、
 `class` は `:java.lang.Long` を返し、オラクルが `BigInt` を拒否するビット演算も受け付けます。
 long の範囲を超える整数はオラクルの `BigInt` で、`pr` と `print` では `N` 付きで印字されます
 （`str` はオラクルの toString と同じく付けません）。範囲を超える `biginteger` も `N` 付きで
 印字されます（オラクルの `BigInteger` は付けません）。整数は `BigInteger` の `.negate` と
 `.bitLength` に答えます。`Long` でも答えます（オラクルは拒否します）。
- 10 進数（`1.50M`、`bigdec`、その読み取り、`(BigDecimal. "1.5")`、`BigDecimal/valueOf`）は、
 スケール・綴り・演算・比較・ハッシュ・`with-precision` の丸め・Java のメソッドがオラクルの
 `BigDecimal` と同じで、Java には `java.math.BigDecimal` として渡ります。よく使うもの以外の
 `BigDecimal` のメソッド（`.sqrt`・`.divideAndRemainder`）はホストのある所でだけ動きます。
 Java が返す `BigDecimal` は、メンバーの宣言が `BigDecimal` なら 10 進数になります。宣言が
 `Object` や `Number`（JDBC の `getObject`）なら、プログラム自身が 10 進数を作る場合だけ
 10 進数になり、それ以外ではホストオブジェクトのままです。リテラルを受け手とするメソッドに
 誤った引数を渡すと（`(.add 1.5M 2)`）リフレクタの拒否になります（オラクルはコンパイラが
 キャストします）。`MathContext` と `RoundingMode` は識別ハッシュなしで印字されます。
 添字や個数を読む所（`nth`・`take`・`drop`・`repeat`・`subvec`・`char`・`dotimes`）では 10 進数を
 拒否します（オラクルは int に変換します）。`subs` と `range` は受け付けます。

- プログラム自身がトップレベルで定義したコア名（`(defn peek ...)`）は、定義より上の呼び出しも
 含めてファイル全体でコアの関数を隠します（オラクルでは定義より上の呼び出しはコアに届きます）。
 ローカル束縛はオラクル同様にそのスコープで隠します。
- seq 群の空に対する `rest`/`next` は `nil` です（オラクルは `()` を印字）。範囲外の
 `nth` は投げずにデフォルトを返します。
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
  JVM バックエンドだけです。`use-fixtures` という名前を書いてもコンパイルでき、呼び出し時にだけ拒否します。
  `report` は `:pass`/`:fail`/`:error`/`:summary` 以外の型では黙ります（本家はマップを表示し、
  カスタムの `:type` メソッドを実行します）。`:fail`/`:error` マップの `:file`/`:line` は優先
  されますが、コンディションは移植可能なスタックトレースを持たないため、test.check の
  file-and-line は本家がテストファイルを示すところでホストのフレームを読みます。
  test.check の大きな値のジェネレーターはハングします。`int-rose-tree` が縮小木全体を
  ここでは即時に構築するためです（strict な入力に対する `map` は strict で、本家は常に
  lazy です）。2^k 付近の描画値に対する木は約 k^k ノードになります（5 で 20、10 で 254、
  20 で 37,954、26 で 631,314、30 で 330万、42 で 26億）。小さな値のジェネレーターは
  すべてのバックエンドで答えます（`choose` は 0..25 程度まで、`:max-size 10` のシード付き
  `quick-check`）。`(gen/generate (gen/choose 0 100) 5 99)` のような大きな値の draw は
  JVM 以外では 1 分以上かかります。
- `catch`（と `thrown?`）は、ランタイムが Common Lisp のコンディションをシグナルする箇所で
  オラクルが投げるクラスとして実行時エラーを捕捉し、Clojure ランタイム自身の拒否は同じ呼び出しで
  オラクルが投げるクラスとして捕捉します（`(first 5)` は `IllegalArgumentException`、失敗した
  `assert` は `Exception` の catch が捕捉しない `AssertionError`）。クラスを示さないエラー --
  オラクルが受け付ける構文の拒否（正規表現の `\X`、`(partition 0 coll)`）、ホストのない WASM での Java 呼び出し -- は、
  `class` が答えるとおりの `java.lang.RuntimeException` です。`RuntimeException`、`Exception`、
  `Throwable` の catch は捕捉し、サブクラス（`NumberFormatException`）や `Error` の catch は
  捕捉しません。
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
  NUMBER` で、オラクルでは `NullPointerException` の文言。ゼロ除算のメッセージはオラクルと
  同じ `Divide by zero`）、`str` はオラクルのクラス名の
  接頭辞を持たないその report です。throwable の構築が例外になるのは、メッセージと
  cause 以外に何も持たないクラスだけです。独自のメンバーを持つクラス
  （`java.net.URISyntaxException`）はホストオブジェクトのままです。インタプリタと JVM では、
  Java のメンバが投げた例外と throw されたホストオブジェクトはオラクルと同じくホスト自身の
  ものです。catch はそのクラスで捕捉し、そのオブジェクト自体を束縛します。
  プログラムが作った例外の `class` はクラス名をキーワードで返し（`:java.lang.Exception`。オラクルはホストの
  クラスを返します）、クラスを示さないエラーには `:java.lang.RuntimeException` を返します。`.printStackTrace` は `toString` の行を `*err*` に書き（オラクルはそれとフレーム
  ごとの行を、`*err*` の束縛に関わらずプロセスの標準エラーに書きます）、`.getStackTrace` は
  空のベクターを返します（そのため `Throwable->map` の `:trace` は `[]` で、`:via` の
  マップに `:at` はありません）。`.getClass` は `class` と同じ値を返します。インタプリタと JVM では、
  それ以外のメソッドは、例外のクラスのホストの例外に対して呼び出され、Java のメンバに渡した
  例外もそのホストの例外として渡ります。ホストの例外はメッセージと cause から一度だけ作り、
  `ex-info` のものは `RuntimeException` です。そのため `(.getCause (UncheckedIOException. "u" e))`
  が返すのはそのホストの例外で、`e` 自体ではありません（オラクルでは `e` と `identical?` です）。
  実行時エラーとランタイムの拒否にはそのようなホストの例外がないため、`Throwable` を受け取る
  Java のメンバはそれに一致しません。例外でない値の `throw` は、値のレンダリングをメッセージとする
  `ClassCastException` になります（オラクルのメッセージは 2 つのクラス名を挙げます）。
- 非推奨または内部用の 6 つの `clojure.core` 名は、オラクル通りにではなく名前ごとに解決します。
  `EMPTY-NODE` は空のベクターです（[EMPTY-NODE](reference/empty-node.md)）：`(= EMPTY-NODE [])` と
  `(identical? EMPTY-NODE EMPTY-NODE)` は `true` を返し、呼び出しは `[]` の呼び出しと同様です
  （キーと省略可能な既定値、それ以外の個数は拒否）。オラクルの persistent-vector ノードはどのベクター
  とも `=` にならず、`count`・`nth` は例外を投げ、呼び出しは `ClassCastException` になります。
  `StackTraceElement->vec` はホストのスタックトレース要素に対するオラクル通りの `[class method file line]`
  ベクターを返します（[StackTraceElement->vec](reference/stack-trace-element-vec.md)）：クラスと
  メソッドはシンボル、`nil` はオラクルの `NullPointerException`、それ以外は `ClassCastException`
  です。要素はインタプリタと JVM にのみ存在します（`.getStackTrace` は `[]` を返します）。
  `add-classpath`、`Inst`、`PrintWriter-on`、`primitives-classnames` は拒否のままです：ここには
  クラスローダが存在しないためです（依存関係は `deps.edn`、`--java-dep`、`--java-classpath` から取得します。
  オラクルは 1.1 で `add-classpath` を非推奨にしました）。`Inst` は `inst-ms*` が拡張するホストの
  プロトコルオブジェクトであり、どのバックエンドにも存在しません（述語と読み取りは `inst?` と `inst-ms`
  が担います）。`PrintWriter-on` はプロキシした writer 上にホストの `PrintWriter` を構築します
  （代わりに `with-out-str` で文字列に印字するか `*out*` を再束縛してください）。
  `primitives-classnames` はプリミティブ `Class` オブジェクト用の print-dup ヘルパーマップです
  （ここではクラスは名前で印字します）。ホストのクラスローダに手を伸ばすライブラリ
  （`clojure.lang.RT/baseLoader`、例えば integrant の `load-hierarchy`）も同様に呼び出し時に
  拒否します：ここにはクラスローダが存在しません。
- multimethod のディスパッチ値はマップのキーと同じく（ベクターも含めて `=` で）比較されます。
 階層経由のディスパッチは厳密に最も具体的なメソッドを優先し、その後
 `prefer-method` の選択に従います。ホストクラス上の `defmethod` は `class` が答える
 キーワードの下に格納されます。真の nil は `(:C%NIL)` マーカーへ写されるので、
 どの表も nil をキーにしません。一方リテラルに `:nil` な値はキーワード行のままです
 （オラクル通り。ディスパッチ関数の中の `class` 呼び出しは nil 引数に nil 自身を答えるので、
  null 判定でそこでもマーカーへ写ります。素のものでも他の関数で包んだものでも、記録した定義から再降低される名前付き `defn`・`def` 済み関数経由でも、インラインなディスパッチ datum の中でそれらを呼び出す場合（呼び出し位置で同じ降低をインライン化）でも同様です）。
 `Object` メソッドは検索の後・デフォルトの先に捕まえます。throwable、ストリーム、数値のクラスは
 その名前のキーワード（`class` が答えるもの。`:java.lang.Long`・`:java.lang.Double`・
 `:clojure.lang.Ratio`・`:clojure.lang.BigInt`・`:java.math.BigDecimal`・`:java.lang.Number`）の下に格納され、検索はオラクルの継承と同じく
 Java のスーパータイプをたどります。インタフェース（`java.io.Serializable`、
 `java.io.Closeable`）と `Object` も含みます。`isa?`・`derive`・`underive`・`parents`・
 `ancestors`・`descendants` はクラス名を同じキーワードとして読むので
 `(isa? (class "a") String)` は `true` で、クラスの `parents`/`ancestors` はオラクルと同じく
 Java のスーパータイプを加えます。コアの種類（`:string`、`:map` など）はそれが表す値の
 スーパータイプを加えます。関数は `clojure.lang.IFn` に `isa?` で、その `ancestors` は
 それを持ちます。インタフェースと `Object` を含め、オラクルの継承通りです。値の位置の
 クラス名はそのキーワードとして読みます（`(= clojure.lang.IFn (class (fn [])))` は
 `false` のままです）。オラクルの `clojure.lang` のほかのクラスも同じで、どの値も答えない
 キーワードになります（`clojure.lang.PersistentVector$ChunkedSeq` の `defmethod` は一致しません）。
 数値のクラスも同じで、`(= Long (class 1))` は `true`、`Long` は
 `:java.lang.Long` と印字します。record や deftype は
 ここでは 1 つの値にならないホストクラス群を表します。`Object` に `isa?` で、`ancestors` は
 `Object` を加えますが、ホストクラスのそれ以外のスーパータイプはモデル化しません。ここではキーワードがクラス
 そのものなので、`:java.lang.Exception` と綴ったキーワードもそのクラスです。ホストの
 クラスオブジェクト（ホストオブジェクトの `class`、インタプリタと JVM）はその名前の
 キーワードと、単純名を通じてコアの種類のキーワードと同じクラスなので、
 `(isa? (class (java.util.ArrayList.)) java.util.List)` は `true` です。同じく `:list` と
 綴る `clojure.lang.IPersistentList` にも `isa?` で、オラクルは `false` を返します。それ以外の
クラス（`java.util.AbstractList`）はディスパッチ値でもクラスオブジェクトで、オラクル通りです。プロトコルの
 ディスパッチは階層（`derive`）を読まず、タグの完全一致の次はプロトコルを extend した
 クラスだけを試し、それから `Object` 既定です。1つの値が実装する2つの `clojure.lang` インタフェースは
 それぞれが受ける値の種類で順序づけます（`IRef` が `IDeref` より先）。`clojure.lang` は
 このクラスパスにないためです。
- `(methods mt)` と `get-method`・`remove-method`・`prefer-method` は multimethod の名前
  （`defmulti` の var。alias や refer 経由も可）を降低時に読むため、`defmulti` が作っていない
  名前はそこで拒否します。それ以外の引数（ローカル、var の deref）は値と同じく下の実行時
  レジストリを読みます。`methods` が返すマップはホストクラスの行を `class` が
  答えるキーワードで引き、オラクルは `Class` 自身で引きます。`prefers` は各ディスパッチ値と
  優先される値の集合の対応を答え、`remove-all-methods` はメソッドと優先指定を消して
  multimethod を返します。どちらも名前の取り方は同じです。値としては（`methods`・
  `get-method`・`remove-method`・`prefer-method`・`prefers`・`remove-all-methods`）
  呼び出しと同じ文言で引数の個数を確認してから、`defmulti` がディスパッチ関数と
  共に登録する実行時レジストリを読み、呼び出しと等価に動作します。
  `get-method`・`remove-method` は呼び出しと同じキーの読み取りを行い、`Object` クラスの
  `remove-method` と `remove-all-methods` はスロットをクリアして呼び出し同様
  multimethod を返します。登録されていない関数値は従来どおり拒否されます。
  `get-method` と multimethod の `.getMethod`・`.dispatchFn` はオラクルの `MultiFn` と同じ
  答えを返しますが、multimethod の `Object` メソッドはキーワードのディスパッチ値にも答えます
  （ここでの `class` は値のクラスをキーワードで答えます）。
- `satisfies?` はプロトコル名と値を、`extend` は型名の後にプロトコルとメソッドマップを
  任意の回数取ります。プロトコルや型を束縛したローカルは降低時に拒否されます。
  `extends?` は2つの名前なら降低時に表を読み、計算されたプロトコルや型（`(class x)`
  のような式、またはローカル）なら値と同じく下記のレジストリを読みます。
  値としてはオラクルと同じ文言で引数の個数を
  確認してから、`defprotocol` がメソッド表と共に登録する実行時レジストリを読み、
  呼び出しと等価に動作します。`satisfies?` はタグの行を読み、`:extend-via-metadata`
  の場合はインラインテーブルを先に、歩行はレジストリのフラグやガードに従い、なければ
  `Object` の既定値を読みます。`extends?` はキーの行を読み、インラインテーブルも
  併せて、なければ既定値を読みます。`extend` は各ペアを順に格納し（末尾の奇数番目は
  捨てます）、nil を答えます。登録のないプロトコル値はオラクルの
  `NullPointerException`、クラスでない型は `ClassCastException`（JVM のモジュール
  接尾辞なし）、プロトコルでないペアは `IllegalArgumentException` になります。
  `nil` が `Object` の既定値を参照することは決してなく、オラクル同様です（`nil`
  への明示的な行は充足します）。`extend` はオラクルの `assoc-in`
  同様に対象の行全体を置換します（空マップは空行で置換し、`nil` は除去します）。
  マップでないメソッドマップはそのまま格納され、充足と拡張はしますがディスパッチ
  しません（オラクル同様です。計算されたマップや値としての `extend` は、メソッド名
  でない項目を読み飛ばします。オラクルは格納します。リテラルマップの非メソッド
  キーは降低時に拒否されます）。本体がそのプロトコルを既に実装している
  record や deftype への拡張はオラクル同様に拒否されます（呼び出し形式は降低時に、
  値は実行時に、`class X already directly implements interface I for
  protocol:#'ns/P`）。インターフェース歩行の第二キーはリテラルの `extend`
  が必要です。
- record はオラクル同様リテラルで印字されます（`#user.R{:a 7}`）。ただし `str` も
 そのリテラルを綴ります（オラクルは `user.R@<hash>`）。deftype はラッパーリスト
 （`(:C%TYPE ...)`）、reify は `(:C%REIFY ...)` で印字されます。決定的に印字されるのは
 エントリのマップだけです。本体が `toString` を上書きしたものは、オラクルの
 `#object[user.T "text"]` から同一性ハッシュを除いた形で印字され、reify のクラスは
 オラクルの番号を除いた `user$reify` と綴ります。
- deftype の `^:volatile-mutable` フィールドは `^:unsynchronized-mutable` と同じ素の
 スロットです（スレッド間の順序保証はありません）。
- `split`/`replace` は素の文字列を文字通りのままにします（パターン値だけがパターンマッチします）。`index-of` は
 見つからないときオラクル同様に `-1` を返します（`clojure.string/index-of` は `nil`）。
- 本体内の `def` は本体が走るときにグローバルを設定します。本体内の `defn` は文位置の
  みで動きます。`definline` は `defn` と同じです。inline 本体は文書化された no-op です
  （呼び出し側を inline 展開するものはここにはありません）ので、定義は関数そのものです。
- 操作は正しいコレクション種別を仮定します。誤用はオラクルの代わりに Common Lisp の
 型エラーをシグナルすることがあります。
- マクロは lower 中に展開されるため、すべてのバックエンドは展開後のコードを実行します。
 マクロ呼び出しに対するインタプリタ自身の `eval` も同様に展開します。マクロ本体は
 プログラムとは別にコンパイル時に実行されます。呼び出し位置より上のトップレベル定義
 （関数、マルチメソッド、プロトコル拡張、そして本体が最初に読むときに作られる `def`
 の値）は見えますが、それ以外のトップレベル文はそこでは実行されず、本体が変えたもの
 （プログラムの atom への `swap!`）はプログラムからは見えません。oracle はコンパイルと
 実行を一つのプロセスで行います。プログラムのマクロが展開した `defmethod` やプロトコル
 拡張はそこでは見えません。定義より上での呼び出しは拒否され、プログラム定義のマクロのむき出しの名前は oracle と同じく値を持ちません。special
 form（`if`、`do`、`let*`、`new` など）、リーダーが綴る先頭（`deref`、
 `syntax-quote`、`ns`、`in-ns`）の `defmacro` は名前を挙げて拒否されます。oracle はこれを
 受け付けます（special form なら呼び出し位置では無視します）。core マクロのむき出しの
 名前は値としてその var のルートを答えます（`(def x when)`）。oracle はむき出しの
 マクロ名を値位置でコンパイル時に拒否します。`bound-fn*` はその関数を答え、値の
 呼び出し時に束縛を取り込みます。
- `macroexpand-1`、`macroexpand`、`clojure.walk/macroexpand-all` と core マクロの関数は、
 `let`、`loop`、`fn`、`letfn`、`when`、`when-not`、`if-not`、`cond`、`condp`、`and`、
 `or`、`case`、`if-let`、`when-let`、`if-some`、`when-some`、`when-first`、`->`、`->>`、
 `as->`、`cond->`、`cond->>`、`some->`、`some->>`、`doto`、`..`、`dotimes`、`doseq`、
 `for`、`while`、`comment`、`declare`、`defn`、`defn-`、`binding`、`assert`、`locking`、
 `with-open`、`future` を、gensym の番号を除いて oracle と同じフォームに展開します。
 それ以外の core マクロ（`lazy-seq`、`delay`、`defmacro`、`ns` など）は呼び出しそのものを
 答えます。oracle はその展開を答えます。誤った形の呼び出しの拒否は展開関数が投げた
 まま伝わります。oracle はそれを `Compiler$CompilerException`（`Syntax error
 macroexpanding ...`）に包みます。テストが 2 つ以上のハッシュバケットで衝突する `case` は、
 スキップするハッシュを挿入順に並べます（セットの順序の差異）。実行中にこれらを呼ぶ
 プログラムは展開関数を持ち運び、マクロ本体の中でだけ展開するプログラムは何も持ち
 運びません。
- `eval`、計算で得たシンボルの `resolve`・`find-var`・`ns-resolve`・`requiring-resolve`、
 計算した引数の `require`・`use`・`refer`・`alias`・`in-ns`・`load`・`load-file`・
 `load-string`、そして `load-reader` が実行中に lower されるのはインタプリタと JVM の
 クラスです。インタプリタはプログラムの lower を実行中も保持します（REPL はセッションの
 ものを）。JVM のクラスはプログラムとコンパイラを持ち、最初の呼び出しでプログラムを lower
 し直し、各呼び出しが lower したものを実行中のクラスにリンクしたクラスへコンパイルするため、
 呼び出しのたびにクラスを 1 つロードします（出力はコンパイラを持ちます。`.class` なら隣に、
 `.jar` なら内部に）。wasm モジュールは lower の仕組みを持たないため、2 つの wasm バックエンド
 では実行時に `UnsupportedOperationException` を投げ、oracle は評価・解決します。
 プログラムが宣言だけした名前をそうした呼び出しが定義すると、oracle と同じく以後はプログラム
 自身のフォームからも呼べます。そうした呼び出しは REPL の 1 入力と同じく `*ns*` の名前空間で
 lower されます。それが
 定義したものは以後の呼び出しと入力から見えますが、それより前に lower されたフォームは
 lower したときのものを使い続けます。再定義した `defn` が届くのはその後に lower された
 フォームだけで、それだけが定義する名前をプログラム自身のフォームは知りません（クォート
 した名前の `intern` は別で、その場所で var を定義します）。そうした呼び出しを含む
 プログラムはセッションと同じく開いた形で lower されるため、プロトコルはクラスをたどって
 ディスパッチし、宣言だけの名前は開いたままです。リテラルで綴れない値（関数、atom）は
 その値自身として `eval` するフォームに入ります（oracle は関数は埋め込みますが atom は
 `Can't embed object in code` で拒否します）。lower できないフォームは lower の文言の
 `Compiler$CompilerException` を投げます。マクロ本体の中の `eval` と計算したシンボルの
 `resolve` はどのバックエンドでもプログラムの lower 中に動き、そこで `eval` したフォームが
 作る定義は lower 中にだけ存在します。`resolve` は、このフロントエンドにない `clojure.core` の
 var（oracle は var）、レコードや型の名前（oracle はクラス）、lower に組み込まれた
 名前空間（`clojure.string`）の var に対して `nil` を返します。quote したシンボルは
 呼び出しが lower される名前空間で解決され（oracle は呼び出しの実行時の `*ns*` を
 読みます）、呼び出しより下の定義にも解決されます。
- `#(...)` はソース、クオートの下、`read-string`/`read` のいずれでもオラクルと同じ
  `(fn* [p1__N# ...] (body))` と読まれますが、N はトップレベルのフォーム（読む datum）
  ごとに 1 から数え直します。オラクルのカウンタはプロセス全体で進むため、引数名が異なり、
  同じテキストを 2 回読んだ結果はこちらでは `=` になります。マクロに渡した正規表現
  リテラルは、同じソースからコンパイルし直した新しいパターンとして展開に届きます
  （オラクルの展開は同じ `Pattern` オブジェクトを持ちます）。
- `#=` はプログラムの lowering 中に評価されるため、すべてのバックエンドが答えを
  リテラルとして運びます。オラクルの読み取りと同じく、先頭は未評価の残りに
  関数として適用され、`*read-eval*` が読み取りを守り、クォートはこれを遮りません。
  ただし答えはリテラルの綴りを持たねばならず、クラス（`#=java.lang.String`）、
  コンストラクタや static 呼び出し、その他綴りのない値（アトム、関数、UUID・Date
  以外のホストオブジェクト）は拒否されます（未知の名前は `Can't resolve`、
  オラクルがオブジェクトを埋め込むところは `Can't embed object in code`）。
  マクロの先頭は引数の個数にかかわらずオラクルの引数個数拒否になりますが、
  2 個以上の引数ではオラクルはマクロ本体をずらした引数で実行します。
  破棄の `#_` はフォームを評価せずに捨てますが、オラクルは内側の `#=` を先に
  評価します。マップ・セットのリテラルは読み取ったフォーム同士で重複キーを
  拒否するため、評価して初めて等しくなる 2 つの `#=` の答えはこちらでは 2 つの
  要素のまま残り、オラクルでは 2 つ目を拒否します。非限定の `#=(var x)` は
  現在の名前空間で解決されますが、オラクルでは例外になります。
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
  lowering された本体は、再定義より下で lowering された `#'` が実行されるまで古い docstring を
  見ます（オラクルの var はすぐに最新を見ます）。`:ns` は名前空間のシンボルです（オラクルは Namespace オブジェクト）。
  入口ファイルの `:file` は与えたままのパスです（オラクルは絶対パスにします）。
  `def`/`defn`/`defn-`/`defmacro` 以外（`defmulti`、`deftest`、レコードのファクトリなど）で
  定義された var は `:name` と `:ns` だけを持ちます。マクロの var の deref は、oracle と
  同じく、呼び出し・環境・呼び出しの引数をとるその関数を答えます。`clojure.core` の var の
  メタデータは `:name`、`:ns` とマクロの `:macro` だけです（オラクルは `:arglists`、
  `:doc`、`:added` と位置も持ちます）。ここで値を持たない core の var
  （`#'add-classpath`）は拒否されます。
- 名前空間は oracle の `#object[clojure.lang.Namespace "user"]` から identity hash を除いた
  形で印字され、その `class` は `:clojure.lang.Namespace` を答えます。`the-ns` と `find-ns` が
  知っているのは、呼び出しより上でプログラムが作った名前空間、require したライブラリ、
  `clj -M` が先にロードする 4 つ（`clojure.core`、`clojure.edn`、`clojure.java.io`、
  `clojure.string`）です。`in-ns` は `nil` を答えます（oracle は名前空間を答えます）。
  `*ns*` への `set!` や `binding` は `*ns*` が読む値を変えますが、以降のフォームが解決される
  名前空間は変えません。それはリテラルの名前をとる `ns` と `in-ns` が決めます。コンパイル
  したプログラムの `*file*` は、コンパイル時のエントリファイルのパスです。名前空間の対応
  （`ns-publics`、`ns-map` など）は呼び出しを下ろすときに分かることだけを答えます。サブセットにない
  `clojure.core` の var は載らず、import はクラスのシンボルに対応し、求まる形でないものはインタプリタと JVM のクラスでは呼び出しの実行時に答え、wasm モジュールでは拒否されます。
  `alias`、`refer`、`ns-unmap`、`ns-unalias` はプログラムを下ろしながら結線するため、本体の中でも
  ファイル全体に届きます（oracle は本体の実行時に走ります）。単独の `(refer-clojure ...)` も
  同じ `(:refer-clojure ...)` フィルタ（`:only`・`:exclude`・`:rename`、`nil` を返す）を
  同じように結線します。値としての `require`、`use`、`in-ns`（`(apply require nses)`）は、
  インタプリタと JVM のクラスでは呼び出しの実行時にロードし、切り替えます。wasm モジュールは
  ライブラリのロードと名前空間の切り替えをプログラムを下ろすときに行うため、実行時のどの適用も
  `UnsupportedOperationException` で拒否します（oracle はロードし、切り替えます。spec を 1 つも
  渡さない `require` と `use` は、どこでも oracle と同じ `Nothing specified to load` を投げます）。リテラルの `load-string` はファイルと
  同じく先に宣言するため、同じ形が定義したものをもう名乗れます（oracle は後の形が必要です）。
  lazy-or-strict 動詞が strict に実現した seq の `bounded-count` は全部数えます（oracle は lazy のまま
  上限で止まります）。レコードやキューの `print-dup` は `pr` と同じ書き方になります（oracle は
  `toString`）。
- `class` は種類名のキーワードで答えます（`:string`・`:keyword`・`:map` 等）。オラクルは
  ホストクラスを返しますが、wasm バックエンドにはありません。数値はそのクラス名の
  キーワードで答えます（`:java.lang.Long`・`:java.lang.Double`・`:clojure.lang.Ratio`、long を
  超える整数は `:clojure.lang.BigInt`、10 進数は `:java.math.BigDecimal`）。`1N` はふつうの整数として
  読むため、ここでは `Long` です。record/deftype は
  タグのキーワードで、ホストオブジェクト（インタプリタと JVM）はホストクラスで答えます。
- [バイト配列](reference/byte-array.md)は、オラクルの `#object["[B" ...]` から識別ハッシュを
  除いた形で印字し、`str` は `[B` を返します（オラクルは `[B@1b6d3586`）。`aset` は -128 から
  127 の整数ならどれでも格納し、オラクルのリフレクションは `Byte` しか受け取りません
  （オラクルでは `(aset bs 0 5)` は拒否され、`(aset bs 0 (byte 5))` は拒否されません）。
  バイト配列の seq は、seq を取った時点の要素を保持し、オラクルの seq はたどる時点の配列を
  読みます。
- 縮小変換（`byte`・`short`・`int`）は普通の整数を返します（g02、clj 1.12.6 で 2026-10-13
  に測定）。オラクルは `Byte`・`Short`・`Integer` を返します。印字・`=`・ハッシュ・
  `compare`・`sort`・`case`・map/set のキー・算術演算（オラクルでも普通の整数を返します）・
  変換・真偽値はすべてオラクルの値と同じで、バイト配列の要素もオラクルの `Byte` に対して
  普通の整数で読めます。ボックス型が必要な箇所だけが異なります。`byte` を取るメンバーは
  オラクルでは `Byte` しか受け取らないため、`(.put bb (byte 7))` は `(.put bb 7)` 同様に
  拒否されます。1バイトだけ書く場合は1要素のバイト配列を渡してください
  （`(.put bb (byte-array [7]))` はどちらでも `[7 0]` になります）。候補が1つしかない
  `short`・`int`・`long` メンバーは、オラクルが数を変換するのと同様に fixnum 引数を変換します
  （どちらも、引数の個数に合う候補が1つだけなら、その候補が引数をどう変換しても受け取ります）。`instance?` の `Byte`・`Short`・`Integer` はキャスト結果に偽で
  （`Long` には真）、`class` は `:java.lang.Long` を答え、`.compareTo` はキャスト結果を `Long`
  として読みます（オラクルは `Long` を `Byte.compareTo` に渡すと例外を投げます）。
  キャストをボックス化すると、その1系統のオーバーロードのために全バックエンドの値モデル
  全体（`+`・`=`・印字・すべての `unmarshal`）が動くため、キャストは普通の整数のままです。
- [型付き配列](reference/int-array.md)（`object-array`・`to-array`・`to-array-2d`・`into-array`・`int-array`・
  `long-array`・`double-array`・`float-array`・`short-array`・`char-array`・`boolean-array`）はベクターです（`make-array` の作るものと同じ）。`class` は `:vector` を答え、`str` と `pr` は
  ベクターを印字し、`=` は要素を比べ、`coll?`・`vector?`・`sequential?`・`counted?` は真で、
  `aset` はどんな値も格納し、`alength` と `aclone` はベクターを受けます。オラクルは配列のクラスを答え、
  識別ハッシュ付きの `#object` を印字し、同一性で比べ、コレクション述語は偽で、型違いの `aset` を拒否し、
  `alength`・`aclone` にベクターを拒否します。`to-array-2d` はベクタのベクタを答え、入れ子の [aget](reference/aget.md) で読みます（1 つの配列に複数の添字は、入れ子のベクター一般と同様にランク違反です）。`aset-int`・`aset-long`・`aset-double`・`aset-float`・`aset-short`・`aset-boolean`・`aset-char` は、プリミティブへのキャスト値をどのベクターにも格納し、値自体を答えます。オラクルは要素型を検査します。複数の添字は入れ子のベクターに格納します。オラクルの行は `Object` 配列で、リフレクションが拒否します。`ints`・`longs`・`doubles`・`floats`・`shorts`・`chars`・`booleans` はどのベクターも狭めます（広げます）。オラクルはプリミティブ配列でないものを拒否します。`float` は `float` と同じく倍精度を持つので、
  `(first (float-array [1/3]))` は長く印字されます（オラクルは `0.33333334`）。2 引数の `short` の初期値は
  short 範囲の整数ならどれでも詰めます。オラクルは `Short` にだけ詰めます（`Short` はここでは整数です）。
  小さな `N` リテラルは `Long` なので、オラクルの `BigInt` が拒否するところで `Long` の要素型を取ります。
  計算で求めた `Class` オブジェクトは `Class` でないとして拒否されます。
- `java.nio.charset.StandardCharsets` のフィールドと、文字列リテラルの `Charset/forName`
  （[文字セット](reference/byte-array.md)）は、オラクルの `#object` から識別ハッシュを除いた形で
  印字し、`class` はそのクラスのキーワードで答えます。符号化と復号ができるのは UTF-8、
  ISO-8859-1、US-ASCII だけで、それ以外（たとえば UTF-16）を `.getBytes` や `String.` に渡すと、
  その名前のオラクルの `java.io.UnsupportedEncodingException` になります。リテラル以外の
  `Charset/forName` と、Java のメンバが返した `Charset` はホストオブジェクトのままで（インタプリタと
  JVM）、プログラムで名前を挙げた文字セットとは同じオブジェクトであるときだけ `=` になります。
- [トランジェント](reference/transient.md)は、オラクルの `#object` から識別ハッシュを除いた形で
  印字し、`str` はクラス名を返します。`!` 付きの操作は渡されたトランジェントを返し、オラクルは
  別のオブジェクトを返すことがあります（8エントリーを超えたアレイマップの `assoc!`）。そのため
  返り値を無視するプログラムは、ここではすべての編集を保ち、オラクルでは一部を失います。
  トランジェントのベクターの末尾を過ぎた `nth` は、ベクターと同じく `nil` を返します。
- [永続キュー](reference/persistent-queue.md)は、オラクルの `#object` から識別ハッシュを除いた形で
  印字し、`class` は型のキーワード `:PersistentQueue` を返します。
- `clojure.lang.LispReader$StringReader` と `$RegexReader`（[read](reference/read.md)）は関数で、
  `fn?` は true を返します（オラクルは false）。
- [delay](reference/delay.md) は、オラクルの `#object` から識別ハッシュを除いた形で印字します。
  失敗した delay の `:val` は例外のレポートで、オラクルはその `#error` マップを印字します。`str` は
  `clojure.lang.Delay` を返します（オラクルは `clojure.lang.Delay@hash`）。
- コレクション・キーワード・シンボル・比・atom・fn へのインスタンス呼び出しは core 関数を通して
  答えるため、その逸脱も引き継ぎます（`.getClass` は `class` と同じ値を返します。ただし fn では
  オラクルがそれをコンパイルするクラスで、その `.getName` は core 関数なら `clojure.core$name`、
  `defn` と fn リテラルを値とする `def` なら `ns$name`、それ以外の fn なら無名 fn の
  `clojure.lang.AFunction$fn` です。オラクルはこの最後の名前に外側の関数と名前付き `fn` の名前を
  含めます。別のフォームの中の `defn` もここでは `ns$name` で、オラクルは
  `ns$eval123$name__124` です）。オラクルの
  クラスが実装する JDK インターフェースのメソッド（`.toArray`）は wasm バックエンドで
  `Method m taking N args is not supported for class C` として拒否し、オラクルのクラスが
  持ちうるがここでは答えないメソッド（`.reduce`・`.meta`・atom の `.compareAndSet`）は
  全バックエンドで同じように拒否します。値のクラスのどれかが持つメソッドはどの receiver でも
  この形で拒否し、オラクルは別のクラスにはそのメソッドがないと答えます。インタープリターと
  JVM でのこうした呼び出しは値が Java へ渡るときの読み取り専用の Java オブジェクトに届き、
  その変更メソッドは何かが変わるときだけ例外を投げます（存在しないキーの `.remove`、空の
  コレクションの `.clear` は返ります）。オラクルの変更メソッドは常に投げます。map のクラス名は
  件数だけで決め、8 件までは array map、それを超えると hash map とします。ここでは `nil` が空リストなので、コレクションの
  メソッドは `nil` にも答えます（`(.count nil)` は `0`）が、オラクルは
  `NullPointerException` を投げます。`nil` へのそれ以外のメソッドは `NullPointerException` です。
  record・deftype・reify でも、プログラムが定義したどの record・deftype のプロトコルメソッドでも
  フィールドでもない名前はコレクションと同じように扱います。
  後の REPL 入力が record・deftype を定義しても、それより前に lower された呼び出し箇所は
  そのメソッドもフィールドも見ません。
- 引数なしの `(StringBuilder.)`（`(new StringBuilder)`・`(StringBuffer.)` も）は全バックエンドで
  文字列出力ストリームであり、`(new java.io.StringWriter)` と同じオブジェクトです。`class` は
  `:java.io.StringWriter` を返し、`StringWriter` の `#object` として印字します。`(.length w)`
  は `StringWriter` についても答えます（オラクルにそのメソッドはありません）。1引数の `.append`
  はその値の `str` を追加します（nil は `"null"` を追加します）。オラクルはオーバーロードを
  解決するので、あいまいな引数（`(.append sb nil)`）はあちらでは拒否されます。1引数の構築は
  そのテキストでストリームを満たして開始し、整数の引数は容量です（無視します）。それ以外の
  引数は `No matching ctor found for class ...` として拒否します。nil もその中に含まれますが、
  オラクルは `NullPointerException` を投げます。1引数の `java.io.StringWriter` は代わりに
  空のそのようなストリームです。数値は容量であり（無視します）、それ以外の引数はオラクルの
  `Number` への `ClassCastException`（nil はその `NullPointerException`）で、そのメッセージに
  モジュール名の尾部は付きません。`class` と `instance?` はビルダーをその実体で
  ある `StringWriter` として見るので（`(instance? CharSequence (StringBuilder. "a"))` は
  `false` です）。文字列の `.intern`
  はその文字列自身なので、インターニングの有無がわかるのは `identical?` だけです。
  `java.util.Locale/US`（`ROOT`・`ENGLISH` も）はその `toString` のテキスト（`"en_US"`・`""`・
  `"en"`）です。`str` と `.toString` はオラクルと一致しますが、`pr`・`class`（`:string`）・
  `string?` は文字列として読みます。1引数の `.toUpperCase`/`.toLowerCase` は引数が何であっても
  引数なしと同じ写像になりますが、オラクルは引数を `Locale` にキャストします。
- 引数なしの `(java.util.HashMap.)`（`(new java.util.HashMap)` も）は空のマップであり、
  マップからの1引数の構築はそのマップ自身です（wasm バックエンド。可変のマップは存在
  しないため）。`.get` などの読み取りはそれを読みます。`.put` はオラクルのメッセージなしの
  `UnsupportedOperationException` を投げます（インタープリターと JVM では、値が Java へ
  渡るときの読み取り専用マップが投げるものと同じです）。2つの数値からの2引数の構築
  （`(java.util.HashMap. 1024 0.25)`）も空のマップであり、マップからの `.putAll` はその
  エントリーをマップに格納して nil を返します。レコードやソート済みマップへの `.putAll`
  は同じ `UnsupportedOperationException` を投げます。マップのリテラルへの `.putAll` は
  そのリテラル自身のテーブルに格納しますが、オラクルは例外を投げます。nil からの構築はオラクルの
  `NullPointerException` です。`str` と `class` は Clojure のマップとして読みます（`{:a 1}`、
  array-・hash-map のキーワード）。オラクルは `HashMap` として綴ります。容量の引数はその
  引数自身を返しますが、オラクルは空の可変マップを構築します。ホストがある場合
  （インタープリターと JVM）は、その可変オブジェクトのまま残ります。
- 引数なしの `(java.util.LinkedList.)` は wasm バックエンドではこのフロントエンド独自の
  可変リストになります（ホストのオブジェクトは存在しないため）。`class`、
  `java.util.LinkedList` への `instance?`、`print` はそのリストとして読みます（`print` は
  `pr` と同じく要素を書きますが、オラクルはホストのオブジェクトを書きます）。整数を渡した
  `.remove` は常にその添字の要素の削除です（long に対してオラクルのリフレクターが選ぶ
  オーバーロードです）。ホストがある場合（インタープリターと JVM）は、ホストの
  `LinkedList` のまま残ります。
- 引数なしの `(Object.)` は wasm バックエンドでは新しい uninterned シンボルになるため、
  ロード時のロックが構築できます。2 つの構築はホストのオブジェクト同様 `=` にも
  `identical?` にもなりませんが、表示は gensym になります（オラクルはオブジェクトの
  識別子を表示します）。ホストがある場合はホストのオブジェクトのまま残ります。
- `(java.util.Random. seed)`（`(new java.util.Random seed)` も）の構築は wasm
  バックエンドでは JDK の 48 ビット線形合同法ジェネレーターになり、オラクルの描画と
  ビット単位で一致します（`.nextInt`、`.nextLong`、`.nextFloat`、`.nextDouble`、
  `.nextBoolean`、`.nextGaussian`、`.setSeed`）。引数なしの構築はホストのエントロピー
  からシードします。シードと上限のキャストはオラクル通りに拒否します。ホストがある
  場合（インタープリターと JVM）は、ホストのオブジェクトのまま残ります。1引数の
  `(Math/floor x)`、`(Math/ceil x)`、`(Math/log x)` も wasm
  バックエンドではオラクルの double を返し、キャストはオラクル通りに拒否します。1引数の
  `(Math/abs x)` も wasm バックエンドでは long の範囲の整数または double
  の絶対値を返し（`Long/MIN_VALUE` は自身、`-0.0` は `0.0` を返します）、それ以外は
  オラクルの no-matching-method 通りに拒否します。1引数の `(Long/bitCount x)` と
  `(Long/numberOfLeadingZeros x)` は数値の `longCast` に対するポピュレーションカウントと
  先行ゼロカウントを返し、キャストはオラクル通りに拒否します。`Double/MAX_VALUE`
  （`MIN_VALUE`、無限大、`NaN` も）は wasm バックエンドでは自身です。
  `(proxy [ThreadLocal] [] (initialValue [] ...))` は wasm
  バックエンドではシングルスレッドのセルになります（実行スレッドは1つです）。`.get`
  は格納値を返し、未格納なら初期値を一度だけ計算して格納します。`.set`
  は格納、`.remove` は消去します。ホストがある場合はプロキシはホストのサブクラスの
  まま残ります。
- リテラルなキーからの `(System/getProperty k)` は nil、`(System/getProperty k dflt)`
  は既定値を返します（wasm バックエンド。プロパティは存在しないため。プロパティがない
  ホストと同じで、ライブラリのロードが読むものです）。nil はオラクルの
  `NullPointerException` です。計算されたキーはホストの呼び出しのまま残り、wasm
  バックエンドはそれを拒否します。
- `Long/MAX_VALUE`（および `MIN_VALUE`、`Integer`/`Short`/`Byte` の組）は wasm
  バックエンドではその値自身になるため、ロード時の定数が読めます。それ以外の
  メンバーはホストのフィールドのまま残ります。
- 裸のクラス名は wasm バックエンドでは `(:C%CLASS name)` 指示子になるため、クラスを
  キーとするマップはロード時に構築できます。同じクラスの 2 つのリテラルは 2 つの
  `forName` の答えと同様に等しくなりますが、表示はリストになり（オラクルは名前を
  表示します）、`class?` と `str` は指示子を読みます。
- `DateTimeFormatterBuilder` の連鎖は wasm バックエンドでは評価されます（各ビルダー
  呼び出しはマーカーを受け渡し、`toFormatter` はフォーマッター、`ofPattern` と
  `ZoneId/of` はそれぞれのマーカーになります）。そのためロード時のフォーマッターが
  構築できますが、それでの構文解析や書式化はオラクルの言葉で拒否されます。
- `instance?` は値の種類ごとのオラクルのクラスで答えます。リストと正格な seq は
  `clojure.lang.PersistentList` なので、`(map inc [1])` について `IPersistentList` と `Counted` は
  `true`、`LazySeq` は `false` です（オラクルでは `LazySeq`）。2要素のベクタはすべて
  `java.util.Map$Entry` です（`map-entry?`）。整数は `(int 1)` も含めて `Long` で、`Integer` には
  なりません。どの種類の値もインスタンスにならない `clojure.lang` のクラス
  （`clojure.lang.PersistentQueue`）は未知の名前になります（オラクルは `false`）。プロトコルの
  インタフェース（`user.P`）が知るのはその箇所を lower した時点で定義済みの record と deftype
  なので、後の REPL 入力で定義したものはそこではインスタンスになりません。プロトコル自身の名前
  （var である `P`）は未知の名前になります（オラクルは `ClassCastException`）。
  `class` が返したクラスの値は `class` が区別するものだけを区別するので、
  `(instance? (class 1) 1.5)` と `(instance? (class {}) (sorted-map))` は `true` です。サブクラスを
  たどるのは例外と実行時エラーだけなので、`java.sql.Timestamp` の `ts` について
  `(instance? (class #inst "2020") ts)` は `false` です。キーワードは `class` が返すクラスの
  キーワードとして扱うので、var 経由の `(instance? :a x)` は `false` になります（オラクルは
  `ClassCastException`）。その例外のメッセージにモジュールの末尾は付きません。
- `reduce` と `reduce-kv`（とその上に作られた動詞）が `clojure.core.protocols/CollReduce` と
  `IKVReduce` を参照するのは record・deftype・`reify` に対してだけです。どちらかを `nil`、
  `Object`、コアの種類へ拡張したものには `coll-reduce` や `kv-reduce` を直接呼んで届きます。
  オラクルの `reduce` は、自分では畳み込まないコレクション（文字列、マップ）についてもその拡張を
  使います。インライン本体が実装していない引数の数でプロトコルメソッドを呼ぶと
  `ArityException` をシグナルします（オラクルは `AbstractMethodError`）。
- `reify`・`deftype`・`defrecord` の本体が実装できるのは、コア関数が参照する `clojure.lang`
  のインタフェース（`IReduceInit`、`IReduce`、`IKVReduce`、`Seqable`、`Counted`、`Indexed`、
  `ILookup`、`IFn`（`Callable` と `Runnable` を含む）、`IDeref`、`IMeta`、`IObj`）、`CharSequence`、
  コレクションのインタフェース（`IPersistentMap`、`ISeq`、`Sequential`、`Iterable`、`java.util.List` など）、
  `Object` のメソッドの上書きです（[reify](reference/reify.md#host-interfaces)）。それ以外の
  `clojure.lang` のインタフェース（`IChunkedSeq` など）は名前を挙げて拒否され、それ以外のホストの
  インタフェース（`Closeable`、`Lock` など）は `instance?` とそのメソッドの呼び出しに答えます。本体が
  書かなかった default メソッドを Clojure から呼ぶと拒否されます（オラクルはインタフェース側の実装を
  実行します）。`ISeq` 型への
  `first`・`next`・`rest` はその `seq` を通して読み、関数がメソッドを呼ぶ回数はオラクルと異なる
  ことがあり、コレクションの型の `str` は中身を綴り、`re-matcher` と `re-find` は `CharSequence`
  の型を先頭の 16、32、64 ... 文字と範囲を広げながら読み（オラクルのマッチャが止まる位置より先まで
  読みます）、ほかの正規表現の関数と `clojure.string` は呼び出しごとに `length` と `charAt` で
  全体を一度読みます（[コレクションのインタフェース](reference/reify.md#collection-interfaces)）。型自身がハッシュと
  等価性を定める値はマップのキーや集合の要素として `=` で照合されますが、どのマップもオラクルの
  ハッシュマップと同じ方法でキーを比べます。オラクルの配列マップ（8 エントリまで）はハッシュを
  使わずに比べます。マップ・集合・シーケンシャルの型の値は `hasheq` ではなく中身で振り分けられます
  （[マップのキー](reference/reify.md#map-keys-and-set-members)）。`Seqable` だけを実装した型について、
  `sort` と `distinct` はその seq を通して答えます。オラクルはどちらも拒否します。
- プログラムが定義したインタフェース（`definterface`）は、同じ行に自前のバイナリ名で
  加わります。`instance?` はその名前を調べ、`.method` 呼び出しはその行のメソッドに
  ディスパッチします（[reify](reference/reify.md#host-interfaces)）。相違点: 名前の値は
  バイナリ名の文字列です（オラクルはクラスオブジェクト）。そのため `resolve` は var を
  答え、`str` は名前を bare に綴ります。インタフェースを再定義すると置き換わり、
  再定義より前のインスタンスもそれを満たします（オラクルでは古いクラスのインスタンス
  だけです）。`reify` への個数の違う呼び出しは `java.lang.Object` を名指します
  （reify のクラスはここのホストクラスではありません）。本体が書かなかったメソッドの
  abstract 拒否はメソッドとインタフェースを名指しますが、レシーバーのクラスは名指しません。
- `clojure.core.reducers` は呼び出したスレッドの上で部分を順に1つずつ fold します。空でない
  2つのコレクションの `cat` は両方を持つ1つのアキュムレーター（ベクター）を返します。オラクルは
  `Cat` の木を返し、その fold は半分ずつの fold を結合します。
- `unchecked-` の算術は整数を64ビット（`-int` 系は32ビット）に折り返し、型変換 `short`・`byte`・
  `char`・`float` と合わせてオラクルと同じです。ただし64ビットを超える整数もここでは通常の整数なので、
  オラクルでは折り返されない bigint のオペランド（`(unchecked-add 9223372036854775807N 1)`）も
  折り返します。`int` と `long` は範囲検査とメッセージを含めてオラクルの型変換と同じです。
  リテラルの引数はその型の型変換を、それ以外はオブジェクトの型変換を通ります。オラクルの
  コンパイラがプリミティブの double と型付けする値（double リテラルを束縛した `let` の
  ローカル、`(* 2.0 x)`）は、オラクルでは `Value out of range for int: 2.0E10`、ここでは
  `integer overflow` で拒否されます。比の `double` は最も近い double で（`(double 2/3)` は
  `0.6666666666666666`）、オラクルは先に有効数字 16 桁に丸めます（`0.6666666666666667`）。`inc'`・
  `dec'`・`+'`・`-'`・`*'` は `inc`・`dec`・`+`・`-`・`*` と同じです。整数は bignum なので
  桁あふれせず、数値以外はオラクルと同じクラスで拒否されます。`cast` はクラスのインスタンスなら
  値を（名前を書いたクラスは `instance?` と同じテスト、計算で求めたものはプロトコルタグで判定）、
  `nil` には `nil` を答え、それ以外はオラクルの拒否です。値がここに存在しないホストクラスは
  常に拒否されます（ホストクラスは wasm バックエンドに存在しません）。
- `*unchecked-math*` が真値（`true` または `:warn-on-boxed`）の間、`+`・`-`・`*`・`inc`・
  `dec` は 64 ビット整数の和・差・積を 2^64 で折り返します。それ以外（double・比・範囲外の
  整数の被演算子、フラグが偽の場合）は通常の動詞と同じです。フラグはコンパイル時ではなく
  呼び出しの実行時に読まれます。呼び出しを囲む `binding` は、オラクル（フラグをコンパイル時に
  呼び出しへ焼き付けるため例外を投げる）とは異なり折り返します。トップレベルの `set!` より前に
  定義した関数も、その後の呼び出しでは折り返します。boxed-math 警告は一切出ないため、
  `:warn-on-boxed` は `true` とまったく同じく折り返します。値としての動詞（`+`・
  `(apply + ...)`・`(map inc ...)` など）はフラグによらず通常の桁あふれする動作のままです
  （オラクルの関数値は桁あふれで例外を投げます）。
- `format` は `java.util.Formatter` と同じ描画・拒否をします（計算した書式文字列は呼び出し時に
  解析します）。違いは次のとおりです。`%h`・`%a`・`%t` は（呼び出し時に）拒否されます。long の範囲を超える整数は
  `BigInteger` として描画します（オラクルは `BigInt` を `%d`・`%x`・`%o` で拒否し、`biginteger` の
  値は描画します）。
  `int` も long なので `(format "%x" (int -1))` は 64 ビット分の `f` になり（オラクルは 32 ビット）、
  `%c` は整数を拒否します（オラクルは `Integer` のコードポイントを受け付けます）。
- `upper-case`・`lower-case`・`capitalize`・`.toUpperCase`・`.toLowerCase`、`format` の大文字の変換、
  `cl-format` の大文字小文字の節は、トルコ語・アゼルバイジャン語・リトアニア語以外のロケールでの
  オラクルの `String` のメソッドと同じに変換します（`ß` は `SS`、語末の大文字シグマは `ς`）。
  例外は BMP の外の文字を含む語で、JDK はそこにない語の切れ目を見ることがあります。
  `(lower-case "Α𝐀Σ")` はここでは `"α𝐀ς"`、オラクルでは `"α𝐀σ"` です。
- `line-seq` は開かれたリーダー（`clojure.java.io/reader` など。閉じるのは `with-open`）か、
  開いて読むパス、File、URL、バイトストリームを取って、HTTP の応答を遅延で読むほかはどれも
  strict に答えます（オラクルはリーダーだけを取って遅延です）。`spit`・`slurp`・`line-seq`・`reader` はすべてのバックエンドで、
  wasm ではファイルを含む `--dir` プリオープン付きで動きます。
- Ring アダプター（`ring.adapter.rontolisp/run-server`）のリクエストマップには
  `:content-type` と `:content-length` が入りますが、`:character-encoding` と
  `:ssl-client-cert` は入りません。非同期ハンドラは拒否し、2 つ目の同時サーバーは
  最初のものを置き換えます。ファイルを指さない `java.io.File` のボディはエラーを通知し
  （500）、Jetty は空の 200 を返します（[アダプター](reference/ring.md)を参照）。
- 組み込みの [Ring ユーティリティ](reference/ring-util.md)は、文字セットを文字列で指定します
  （UTF-8、ISO-8859-1、US-ASCII とそれらの JDK の別名。ほかは拒否します）。
  `ring.util.request/body-string` は拡張できるマルチメソッドではなく関数で、`content-length`
  は ASCII の数字だけを読みます。
- ソート済みのマップとセットは、順序付け・表示・キーの検索がオラクルと同じですが、どの操作も
  コピーを作ります（関連付けにはハッシュマップと同じくコレクションの大きさ分のコストが
  かかります）。`class` は `:map`/`:set` を返します。先頭から走査して何も残らない `subseq`/`rsubseq` は `nil` を返します（オラクルは
  `()`）。値として `subseq` に渡したテストは `(1 0)`・`(0 0)`・`(-1 0)` への答え方で判別します
  （オラクルはコアの関数との同一性で判別します）。`compare` は文字列をコードポイントで比べます
  （オラクルは UTF-16 の単位で比べるので、U+FFFF を超える文字で答えが変わります）。
- 文字列のインデックスと長さは、すべてのバックエンドで Unicode のコードポイント単位です（BMP の
  外も含みます）。`count`・`subs`・`.substring`/`.subSequence`・`.charAt`・`nth`/`get`・
  `clojure.string/index-of`/`last-index-of`・`.indexOf`/`.lastIndexOf`・`compare` はいずれも
  この単位で読みます（`int` を取る `.indexOf` はその文字を探します）。オラクルは UTF-16 単位で
  数えるため、サロゲートペアを含む答えはすべて異なります（`(count "a🌍b")` はここでは 3、
  オラクルでは 4）。Java・JavaScript・LSP・ほかのエディタの UTF-16 オフセットは境界で変換して
  渡します。これらの呼び出し同士を組み立てたインデックス計算はそのまま動きますが、外部の
  オフセットは先に変換しなければなりません。`lower-case`（および `.toLowerCase`）も同じ
  コードポイント単位の語走査で畳み込むため、BMP の外の文字を含む語の末尾も異なったままです
  （`(lower-case "Α𝐀Σ")` はここでは `"α𝐀ς"`、オラクルでは `"α𝐀σ"`）。
- `float` は倍精度の値を返すので、`(float 1/3)` は `0.3333333333333333` です（オラクルの Float は
  `0.33333334` と表示します）。float の範囲を超える値は同様にシグナルします。
- 被除数が NaN や無限大の `mod` と `rem` は `ArithmeticException` を投げます（オラクルは
  `NumberFormatException`）。
- `vector-of` は通常のベクターを返します。あとの `conj` や `assoc` は値をそのまま格納し
  （オラクルは型変換を続けます）、`:float` も倍精度で保持します。
- リスト・遅延シーケンス・シーケンスの `empty` は `nil` を返し（オラクルは `()`。`rest` と同じ、空を
  `nil` とする扱い）、メタデータも持ちません。マップエントリの `empty` は `[]` です（オラクルは `nil`）。
- 非正のサイズや step の `partition-all` はシグナルします
  （オラクルは `()` の無限 seq を返します）。`pmap` はインタプリタと JVM では要素ごとに
  一つの future を走らせ、オラクルの先読みで順に deref するので印字される seq は逐次版と
  同じです（単一スレッドの WASM 層では逐次 `map`）。`pvalues` は式ごとに一つの future を
  走らせて順に deref します（空のときは `nil`。オラクルの空の lazy seq は `()` と印字され
  ます。WASM 層では呼び出しスレッドで順に評価）。どちらも失敗は `deref` の順で表面化し、
  オラクルの `ExecutionException` で包まれます（逐次層では素の条件を投げます）。`take-nth` は step が 0 だとシグナルし、seq 形は負の step を絶対値で進みます。
  オラクルの seq 形は先頭要素を無限に繰り返します。
- トランスデューサーはオラクルと同じく畳み込み関数に対する関数ですが、`eduction` は入力を
  それへ通した `sequence` で、一度だけ計算します（strict な入力には strict に、lazy な入力
  には lazy に）。オラクルは reduce のたびに変換をやり直します。`println` はその seq を
  表示し、オラクルはオブジェクトを表示します。自前の `CollReduce` の行を通して畳み込まれる
  入力は `eduction` の時点で畳み込むため、オラクルでは拒否されるその `seq` も答えを返します。
  `reduced` 値はラッパーのリストとして表示されます。
- トランザクションはバージョンで競合を検出します。`dosync` はロックを保持せずに
  本体を走らせ、deref・`alter` の読み・`ensure` を読み集合に記録し、コミット時点で
  別のトランザクションが読んだ ref に書き込んでいれば本体をリトライします。そのため
  本体は複数回走ることがあり、副作用も繰り返すことがあります（オラクル同様）。
  書き込みはコミットまでログに留まるため、並行する本体が未コミットの書き込みを
  読むことはありません。`commute` はコミット時に適用され、競合がなければ関数を
  2回走らせます（オラクル同様）。同じトランザクション内で `commute` の後に同じ
  ref への `alter` や `ref-set` を行うと、オラクルの "Can't set after commute"
  拒否になります。`sync` はフラグを評価せずに捨てる
  同じ実行です（入れ子の `sync` は入れ子の `dosync` と同じく実行中のものに
  合流します）。`io!` はその中でオラクルの `IllegalStateException`
  （`I/O in transaction`）を上げ、`with-loading-context` は本体をそのまま実行します。
  別スレッドのトランザクションを
  待つ本体は終わらず、トランザクションの外側の読み出しは実行中のトランザクションの
  書き込みを見ることがあります（オラクルはコミット済みの値だけを見ます）。
- agent はインタプリタと JVM では最大 4 本のワーカースレッドからなる共有プールでアクションを
  走らせます。ワーカーは待つ agent がなくなると終了します。送信はアクションをキューに入れて agent を答えるだけで戻り、1 つの agent の
  アクションは送った順に1つずつ走ります。`send-off` は同じプールを共有します（オラクルは
  専用の無制限プールを持ちます）。生成時の束縛は新しいスレッドに届きません（引き継ぐのは
  `bound-fn*` だけです）。対照的に WASM レッグ（シングルスレッドのモジュール）では、
  アイドルの agent への送信は `send` が戻る前に送信側のスレッドでアクションを走らせた
  ままです。別のスレッドが走らせている agent への送信はそのスレッドのキューに加わります。
  同様に `restart-agent` は失敗の後ろに残ったキューを自分のスレッドで走らせ、
  エラーハンドラは失敗したアクションを走らせたスレッドで走ります。agent の印字は
  unreadable な `#<Atom ...>` で、オラクルのオブジェクトではありません。
  `shutdown-agents` は `nil` を答え、`*agent*` はアクションの実行中にだけ束縛され
  ます（外側は `nil`。オラクルでは unbound です）。`(await-for 0 a)` はプールと競合し
  `false` を答えることがあり、オラクルと同様です。WASM ではアクションが先に走るため、
  そこでは `true` を答えます。
- `tap>` はインタプリタと JVM ではオラクルと同様に値を tap スレッド向けのキューに
  入れます（1024 個。満杯なら `false` を答えます）。そのためプログラムの終了直前に tap した値は
  tap に届かないことがあります。WASM レッグ（シングルスレッドのモジュール）では、`tap>` は
  戻る前にすべての tap を呼び、常に `true` を答えます。tap は追加した順に呼ばれ、
  `remove-tap` は tap を `identical?` で探します（オラクルの集合は `=` で探します）。
- 値セルを持たない `defn` は新しい関数を定義の束縛の後に検証します。validator が拒否した場合もオラクルと同じ
  `Invalid reference state` を報告しますが、以降の呼び出しは拒否された定義に届きます
  （オラクルは古い定義を保ちます）。拒否された `defmacro` の再定義は新しいエクスパンダがそのまま使われます
  （下にある呼び出しは lowering 時にそれで展開され、テーブルもそれを保持します）。
  オラクルは古いルートを保ちマクロを外します。
- `swap!`、`reset!`、`compare-and-set!`、`swap-vals!`、`reset-vals!` は、atom と
  セルを共有する ref と volatile も受け付けます（オラクルは atom 以外を拒否します）。
- `binding` が再束縛できるのは `^:dynamic` な var だけです（それ以外はオラクルの
  非 dynamic エラー同様に拒否）。`^:dynamic` な `defn` も再束縛できます（定義は
  直接のまま、呼び出しは var 経由になります）。それ以外の名前やローカルについた
  リーダーメタデータは解析して捨てられ、ディスパッチに影響しません。`with-precision` は
  本体の周囲で `*math-context*` を評価された精度と丸めモードの math context に束縛し、10 進数の
  演算はオラクルと同じくそれに従って丸めます。この context は識別ハッシュを除いたオラクルの
  `#object` として印字されます。`bound-fn` は `fn` に対する
  `bound-fn*` です。取り込んだ生成時の束縛は呼び出し元の束縛の上に積まれるため、
  別スレッドでの呼び出しも取り込んだ値を見ます。生成時にどこにも束縛されていない
  var はマップに入らず、動的に入った `with-local-vars` の範囲下で作られた
  `bound-fn*` はそのセルを見ません。`with-local-vars` は各 name を新しい var
  セルに束縛します。根は unbound で、範囲を出ると復元されます（`unwind-protect`
  が全セルを復元するため、throw しても復元されます）。セルは `#'ns/name` と
  印字され（オラクルのセルは無名です）、セルへの `alter-var-root` は拒否され、
  body をまたぐ `recur` はオラクルの `try` 境界同様拒否されます。
- REPL では、後の入力が `^:dynamic` として定義する var と同じ名前のローカルは、
  その定義以後、エクステントの間その var を束縛します。そこで呼ばれた関数は
  ローカルの値を読みます。オラクルではローカルはレキシカルに束縛され、関数は var を
  読みます。ファイルではこのローカルはレキシカルです。
- `with-redefs` と `alter-var-root` が置き換えるルートは var の値セルなので、
  `^:dynamic` な var の `binding` の内側では、その var の束縛を変更します（オラクルでは
  ルートが変わり、束縛はそのままです）。`clojure.core` の var は名前を挙げて拒否し、
  `alter-var-root` が届いたマルチメソッド、プロトコルメソッド、レコードのコンストラクタ、
  テストも拒否します。REPL では、以前の入力が `^:redef` なしで定義した `defn` を拒否します。
- `locking` のロックは値ごとに保持するミューテックスで、ロック表は渡された値を
  プログラムの実行中ずっと保持します。`nil` をロックしたときの
  `NullPointerException` のメッセージはローカル名 `locklocal` を示します（オラクルは
  生成した名前を示します）。
- `with-meta` はメタデータを持つコピーを返します。`assoc`（およびそれを経由する
  `update` と `assoc-in`）はオラクル同様に由来元のメタデータを引き継ぎますが、
  それ以外の導いた値（`conj`、`dissoc` など）はメタデータなしで始まり（オラクルは引き継ぎます）、
  マップのキーやセットの要素になったシンボルはメタデータを持ちません
  （`(meta (first (keys {(with-meta 'x {:a 1}) 1})))` は `nil`、オラクルは `{:a 1}`）。
  定義の `:arglists` は引数の型ヒントを含みません（オラクルは各引数のシンボルに保ちます）。
  コレクションリテラルのリーダー
  メタデータの `:tag` は書いたままのシンボル（`String`）で、オラクルはクラス
  （`java.lang.String`）に解決します。レコードや deftype を名指す `def` の
  `{:tag Type}` も同じ理由（ここでは型はクラスではありません）でクォートされた
  シンボルのままになり、オラクルはクラスを格納します。
- `with-open` は `close` メソッド越しに閉じるため、バックエンドの届く closeable
  だけが動きます（`clojure.java.io` のバイトストリームはどのバックエンドでも、Java の
  closeable はインタプリタと JVM で）。`time` は値を答えますが、
  ミリ秒数は固定されません。数えるのは整数ミリ秒（`42.0`）で、オラクルの値には
  ナノ秒の桁が付きます。
- `read-string`/`read` はクオートと同じ答えを返します。`@x` は `(deref x)` と読まれ、
  構文クォートは展開されません（オラクルは `(clojure.core/deref x)` と読み、展開します）。
  レコードリテラルは
  プログラムが定義するどのクラスでも読め、後の `require` が読み込むクラスも読めます
  （オラクルは先にクラスが読み込まれている必要があります）。deftype のリテラルは
  拒否されます。`read` はストリームを取り、素の `clojure.java.io/reader` も受け付けます
  （オラクルは `PushbackReader` を要求します）。ホストのリーダは拒否します。
- `#inst` と `#uuid` は、すべてのバックエンドでオラクルの `java.util.Date` と
  `java.util.UUID` として読まれます。`java.util.Date`、`java.sql.Timestamp`、`java.util.UUID`
  のコンストラクタと静的メンバーも同じ値を作ります。わずかな違い（インスタントの `str` は UTC で
  答える、Java のメンバーが返したホストの Date や UUID はここで作った値と `=` になるが、
  ホストオブジェクトとして印字され、マップでは別のキーになる）は
  [インスタントと UUID](reference/instants.md) にあります。
- `clojure.java.io` の `java.io.File`、`java.net.URL`、`java.net.URI`、バイトストリームは、
  すべてのバックエンドでこのフロントエンド自身の値です。違い（文字セットは 3 つ、`http:` URL
  を読むのはそのために `rontolisp:fetch` を使うプログラムだけ、リソースはクラスパスではなく
  ソースパスで見つける、WASM のディレクトリ、`input-stream` がバイト配列のストリームを包まずに
  返す）は
  [clojure.java.io](reference/clojure-java-io.md) にあります。
- データリーダはプログラムのコンパイル時に動くので、ソースでの答えはメタデータを失い、ここで
  綴れる値でなければなりません。関数、deftype のインスタンス、UUID・Date 以外のホストの
  オブジェクトは `Can't embed object in code` です（オラクルは `print-dup` で印字できるものを
  コンパイルします）。ここでは `()` が `nil` なので、空リストの答えは `No dispatch macro` です。
  `*data-readers*` や `*default-data-reader-fn*` の `set!` は `read-string` と `read` の読み方を
  変えますが、プログラムのソースの読み方は変えません（オラクルのロードはファイルの後続の
  フォームを、REPL は後続の入力をそれで読みます）。エントリファイル自身のルートの
  `data_readers` のファイルも数えます（オラクルはクラスパスのものだけを読みます）。
- `*out*`/`*in*`/`*err*` は `*standard-output*`/`*standard-input*`/`*error-output*`
  です（再束縛は標準ストリームの再束縛になります）。ルートで読んだ `*out*` と `*in*` は
  プロセスの標準ストリームを指すストリーム値です。
  `defonce` はリロードでルートを保ちます（`def` はリセットします）。
- Java のインタフェースが期待される位置に渡した fn は、どのインタフェースでもその抽象
  メソッドすべてを実装し、それぞれメソッドの引数で呼ばれます。オラクルが fn を変換する
  のは `@FunctionalInterface` 注釈付きのインタフェースだけです（`PropertyChangeListener`
  はオラクルでは `ClassCastException` になります）。オラクルと同じく、Java が
  `Runnable`・`Callable`・`Comparator`・`Object` として受け取った fn は、コレクションの中の
  fn も含めて Java から fn そのものとして返り（`(.comparator (java.util.TreeSet. f))` は
  `f` で、`(.invokeAll pool [f g])` は両方を実行します）、オブジェクトを返すメソッドでは fn
  の答えは値自身のオブジェクトになります。fn の Java オブジェクトは
  `clojure.lang.AFunction@1b6d3586` と綴られます（オラクルでは fn 自身のクラス名です）。
  プリミティブを返すメソッドでは、答えはオラクルのアダプタと同じくキャストされます。
  `boolean` は、呼び出しが実行前に解決されるならコンパイラの `(Boolean)` キャスト、
  そうでなければリフレクションの真偽判定です。そのため、オラクルが型ヒント（ここでは
  捨てられます）やコレクションリテラルのクラス（`(.filter (.stream [1 2]) pred)`）に
  よってだけ解決する呼び出しでは、述語の `nil` はここでは `false` になり、オラクルは
  例外を投げます。`proxy` の本体が返すプリミティブは、オラクルの proxy クラスと同じく
  アンボックスされます。
- Java に渡した値は、このフロントエンドのクラスのオブジェクトで、Java からはオラクル自身の
  オブジェクトと同じように読めます。ベクタ・リスト・遅延シーケンス・セット・マップ・record・
  ソート済みコレクションは、Clojure の印字どおりに綴られる読み取り専用の `java.util` の
  `List`・`Set`・`Map` です（ベクタは `RandomAccess` かつ `Comparable` でもあります）。
  キーワード・シンボル・分数はオラクルと同じくハッシュし、比較し、印字されるオブジェクトで
  （分数は `Number` です）、本体が Java のインタフェースを実装するか `Object` のメソッドを
  上書きする deftype や reify、および本体が Java のインタフェースを実装する record は、
  そのインタフェースを実装し、型のメソッドを呼ぶオブジェクトです（record は `Map` でも
  あります）。fn は fn そのものであるオブジェクト（上の項目）です。アトム・それ以外の
  deftype や reify などそれ以外の値は、自分自身とだけ等しく、オラクルの `Object.toString`
  と同じく綴られるオブジェクトです
  （`clojure.lang.Atom@1b6d3586`）。Java はどれも元の値として返します。オラクルとの違いは
  次のとおりです。Java から見えるクラスが違います（`getClass` と、そのクラスを名指す JDK の
  `ClassCastException` のメッセージ。このフロントエンド自身のキャスト失敗はオラクルのクラス名を
  挙げますが、モジュールの説明は付きません。reify のクラスは名前空間ごとの `ns$reify` で、
  オラクルは一つずつ番号を振ります）。要素の変換は値が渡るときに一度だけで、遅延
  シーケンスは最後まで実現されます。`toString` はここでの `str` の答えです（遅延シーケンスと
  record は中身を綴ります）。オラクルのクラスが `Comparable` でない値もここでは
  `Comparable` で、その `compareTo` はオラクルと同じ `ClassCastException` を投げます。
  Java から deftype のフィールドは見えません。メンバが返した Java の配列は
  ここではリストになり、配列が期待され、かつリストのまま受け取る引数がない箇所では配列に
  戻ります。そのため `(java.util.Arrays/asList [1 2])` はベクタを一つ持つリストになります
  （オラクルは例外を投げます）。`make-array` が作る配列もここではベクタで、同じく配列に
  戻ります。バイト配列はそのバイトの `byte[]` として渡ります。インタプリタでも JVM でも
  バイト配列自身の配列が渡るので、Java のオブジェクトが保持した配列（`ByteBuffer/wrap` の
  配列、`ByteBuffer` の `.array`）はバイト配列そのものです。戻ってきた `byte[]` は同じバイトを
  持つ新しいバイト配列で、渡したものと `identical?` にはなりません。Java が `proxy`、deftype や
  reify のメソッド、fn に渡す `byte[]` も同じです。
- 整数の receiver は `Integer` に収まれば `Integer`、収まらなければ `Long` として
  呼ばれます（オラクルでは常に `Long` です）。`(.getClass 1)` は
  `java.lang.Integer` を答えます。
- パラメータタグの `_` はその引数を `java:` サーフェスのコスト規則に任せるため、
  `(^[_] Math/abs -2)` は `2` を答えます。オラクルは複数のオーバーロードが残るタグを
  拒否します。
- 引数の `:tag`（引数自身のもの、またはヒント付きの `let` ローカルやパラメータのもの）は、
  呼び出しのクラスが実行前にわかる場合、オラクルのコンパイラと同じくオーバーロードを選びます。
  receiver のヒントは捨てるため、`(defn f [^ExecutorService e ^Runnable r] (.submit e r))`
  はヒントを読まないリフレクションで呼びます。`Callable` と別のインターフェースの両方が
  受け取れる位置の fn は `Callable` です。`(.submit e (fn [] 1))` は `1` の future を答えます。
  オラクルは型のわかる receiver に対するヒントなしの呼び出しを拒否し
  （`More than one matching method found: submit`）、`ScheduledThreadPoolExecutor` に対する
  リフレクションでは `submit(Runnable)` を選びます（その `getMethods()` の順序。`nil` の future）。
- `(.thenComparing c (fn [a b] ...))` は fn 引数を `Comparator` として受け取り、それで比較します。
  オラクルはその位置の fn リテラルやローカルをコンパイル時に拒否します
  （`More than one matching method found: thenComparing`。`Function` のオーバーロードも受け取れるため）。
- `clojure.lang` に対してコンパイルされた依存の Java クラス（その上の Clojure
  ライブラリ自身のクラス、たとえば `reitit.Trie`）はここではリンクできず、そのクラスへの
  すべての呼び出しは不足クラスを名指しする `Cannot link class C: missing class M`
  で拒否されます。インタプリタでも JVM でも `(catch Exception e ...)` で捕捉できます
  （wasm ではあらゆる `java:` 呼び出しが実行時エラーです）。不足クラスを静的
  イニシャライザが必要とするクラス（その型の静的フィールドを持つもの、たとえば
  `sci.impl.FISupport`）も同じ拒否になります。その `ExceptionInInitializerError`
  が不足クラスを運ぶため、構築・呼び出し・静的フィールド読みのいずれも同じ拒否を
  答え、本当に壊れたイニシャライザは従来どおり伝播します。`clojure.lang`
  のブリッジはなく、予定もありません。ここの値は `IPersistentMap`
  ではないため、それを受け取る依存のコードはどちらにせよ読めないからです。そのような
  クラスを必要とするライブラリはポータブルな経路も提供しており（reitit の
  `clojure-trie-compiler` は oracle と同じ行を出力します）、リーダーは先に名前があれば
  ライブラリの `:rontolisp` ブランチを取ります。
- 読むのはソースルート以下の `.clj` と `.cljc` のファイルだけです。クラスを含む依存の jar は
  Java クラスパスにも加わりますが、ディレクトリのクラスは加わりません（準備済みライブラリの
  `target/classes` は `--java-classpath` で与えます）。プロジェクトの `deps.edn` は、oracle が
  作業ディレクトリのものを読むのに対し、エントリファイルの位置から上へたどって最初に見つかる
  ものです。コマンドライン以外（組み込み側、ブラウザのプレイグラウンド）では Maven 座標と git
  座標を取得しません。それにしかありえない名前空間はそれを挙げて拒否し、プログラムの残りは
  動きます。組み込みのライブラリは、その依存も含めて何も取得しません。組み込みの Ring 名前空間は
  `ring/ring-core` の座標がなくてもロードでき（oracle では座標が必要です）、同梱より古い ring-core
  の代わりにもなります。コマンドライン以外では `pom.xml` のプロジェクトも読みません。取得しないライブラリはデータリーダも与えません。
  その `data_readers.clj` だけが対応づけるタグにはリーダ関数がなく、そのライブラリを挙げて拒否します。
  `settings.xml` の認証情報で応じるのは
  Basic 認証だけで（oracle は Digest と NTLM にも応じます）、ダウンロードは `maven-metadata.xml` も含めて
  常に `.sha1` と照合します（oracle の既定は警告だけです）。どのリポジトリにもなかったファイルは
  そのリポジトリの更新ポリシーが許すまで問い合わせ直しません。既定は `:daily` で、`:update` で
  変えられます（oracle は実行のたびに問い合わせます）。git のタグはローカルの
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
  を返します。`(reader-conditional nil false)` は `#?()` から読んだものと同じく `#?()` と
  印字されます。選ばれない分岐の中では、
  未知のエイリアスの `::alias/kw` も読めます（オラクルは拒否します）。実行時のリーダは
  `#?@(:clj nil)` を何も展開しないものとして読み（オラクルは拒否します）、`:features` は
  ハッシュセットだけを受け取ります。
- 2 つの名前空間で同じ単純名を持つ record と deftype はディスパッチタグを共有し、
  `class` の答え、プロトコルディスパッチ、`=` の比較はこのタグに基づきます。
- 同じ名前を 2 つの名前空間から refer すると後の refer が有効になります（oracle は拒否
  します）。refer した名前を定義すると、その定義が oracle の警告なしに refer を置き換えます。
- `clojure.spec.alpha` は自身の jar からロードしますが、`explain` は素の述語が名前付き `fn`
  （`(fn pos [x] ...)`）か、値が fn に展開されるマクロ呼び出しである `def` のとき
  `:clojure.spec.alpha/unknown` と呼びます。オラクルはこれにも名前を付けます（`defn`、fn リテラルを
  値とする `def`、core 関数、spec のマクロが受け取る述語は名前を保ちます）。`clojure.spec.test.alpha` の `instrument`
  （`defn` の呼び出しは var を経由しません）と `check` も拒否します。実行時に計算したシンボルの
  resolve や名前空間の require を行うもの、つまりシーケンスでない値に対する正規表現 spec の
  `explain`、`nilable` の `form`、ジェネレータ（`gen`、`exercise`。`deps.edn` の test.check を
  使います）は、インタプリタでは動き、コンパイルしたプログラムでは拒否します。`check-asserts` が設定する
  `clojure.lang.RT/checkSpecAsserts` は false で始まり、システムプロパティ
  `clojure.spec.check-asserts` は読みません。

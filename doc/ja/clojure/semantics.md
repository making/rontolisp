# セマンティクス

すべての Clojure フォームは、ファイル読込時に、rontolisp の他の部分が既に消費する
Common Lisp の核フォームへ低下します。どのバックエッドも Clojure の名前を知りません。
このページは低下の対応表と、名前で拒否されるフォームの一覧です。

## 名前と呼び出し

識別子は `c%` 接頭辞の後ろのシンボルへ低下するので、`Foo` と `foo` は別物のまま and
どの名前も核フォームの case ラベルには届きません。`defn` は直接呼び出しされる `defun`
で、パラメータや `let`/`loop` 束縛、`def` された変数といった値束縛のうち実関数を持つものの
head 位置呼び出しは値セルの `funcall` になります -- だから `(defn call-it [f x] (f x))`
が動きます。コレクションを持つかもしれない変数は代わりに prelude ディスパッチャ経由です
（`rontolisp::%clojure-call`。関数は `apply` で、セット・マップ・ベクター・キーワードは
それぞれの読み取りで、`IFn` 同様です）。
`declare` されただけで定義されていない名前は直接呼び出しエラーのままです。`def` は
トップレベル `setq` で -- 本体内にあっても本体が走るときにグローバルを設定します。

複数アリティの `defn` はアリティごとの `defun` 1 つと、引数数で選ぶディスパッチ `defun`
です（可変長節が 1 つだけなら固定パラメータを超える任意の個数を受けます）。それ以外の個数は
シグナルします。複数アリティの `fn` は同じ方法でディスパッチする 1 つの `lambda` で、名前付き
`fn` は自己呼び出しのために自分自身を束縛します。`#(...)` は引数が 1 つの rest リストとして
渡される `lambda` です（`%`..`%9`、`%&`）。本体フォームは 1 呼び出しに包まれます。
`declare` は後で定義される名前を前もって宣言します。

## 束縛

`let` は `let*` です（Clojure の `let` は逐次）。`loop`/`recur` は `labels` の自己呼び出しで、
インタプリタでは定数スタック、初期値は逐次です。パラメータと束縛は分配を受けます:ベクター
パターンは seq ビュー経由で位置的に束縛し（`&` は残りを seq として、そのパターンも分配可。
`:as` は全体）、マップパターンはテーブル対応の読み出し経由で束縛します（`:keys`/`:syms`/
`:strs`、明示ローカル、`:as`、`:or` デフォルト）-- 対象は `let`、`loop`、`fn`/`defn`
パラメータのいずれでも同じで、ネストしたパターンは再帰します。不正な形は名前付き拒否です。

## マクロ

`defmacro` はコンパイル時 expander を定義し、lower の脇に格納されます。各呼び出し位置
は lower 中に datum から datum へ展開されます。引数フォームは quote されて 1 回の
expander 適用へ渡り、答えは datum に decode されて他のフォームと同様に lower されます。
そのため、すべてのバックエンドも、マクロ呼び出しに対するインタプリタ自身の `eval`
も、展開後のコードを実行します。パラメータには未評価フォームが束縛され（`&` rest、
destructuring、複数アリティは `defn` と同様。docstring と attr map は読み飛ばし）、
`&form`/`&env` は拒否されます。本体が見えるのは核の built-in と `clojure.lisp`
ライブラリであり、プログラム自身の定義は見えません。定義は同じ expander を実行時
テーブルにも登録し、`nil` を返し、セッションをまたいで有効です。定義より上での
呼び出しはエラーとなり、マクロに関数値はなく、後からの同名 `def`/`defn` が呼び出し
位置を取り戻します。同名の核関数は呼び出し位置では `defmacro` が覆い隠します
（special form は先に横取りするため覆い隠せません）。

`` `form `` は mangle 済み名前空間上のデータとしてフォームを組み立てます。すべての
シンボルは `c%` の背後で限定され、`~` はそのフォームの値を埋め込み、`~@` は外側の
リスト・ベクター・マップ・セットの中に列を継ぎ足します。各 `x#` は syntax-quote
ごとに 1 つの `(gensym "x")` を束縛します。1 展開につき 1 シンボルであり、同じ展開
の中では出現箇所によらず同じものになります。syntax-quote の外の unquote、列の外の
splice はエラーです。`macroexpand-1` は 1 回、`macroexpand` は fixpoint まで展開し、
いずれも展開結果を表示用に demangle・大文字化したデータとして返します。`gensym` は
評価ごとに新しい uninterned シンボルを返します。`var`/`#'` は拒否されたままです。
本体ではシンボルを quote してください。

```clojure
(defmacro sem-unless [c t] (list 'if c nil t))
(println (sem-unless false 42))
(println (macroexpand-1 '(sem-unless true 1)))
```

## スレッディングと制御フロー

`->`/`->>` は値を 2 番目/最後に挿入し、裸の名前やキーワードは値を渡して呼び出す/参照する
ステップになります。`as->` は名前を段階的に再束縛します（ネストした `let` なのでシャドウ
イングはオラクル一致）。`doto` は（変更されない）ターゲットをそのまま返します。
`cond->`/`cond->>` は真のテストだけをスレッドし、`some->`/`some->>` は `nil` で止まります
が `false` では止まりません。`if`/`when`/`cond`/`do`/`and`/`or` は核フォームそのもの:
すべてのテストは `nil` と false オブジェクトを偽として扱い、`cond` は寛容な読みを保ちます
（奇数の末尾 arm はデフォルト）。`list*` は seq ビュー上の `cons` 折り畳みです。

## 反復

`doseq` は副作用のために seq ビューを反復し `nil` を答えます:束縛ペアごとに `dolist`
が 1 つ、左から右へ入れ子になり、本体は暗黙の `do` です。`dotimes` はカウント未満の
`0` から同じく束縛し `nil` を答えます。カウントは先に `truncate` を通るため、`2.5`
は `0 1` を数え、非数はそこでシグナルします（オラクルの `intCast` と同様）。`for` は
本体を全組合せに適用した strict なリストを答え、逆順に蓄積します。空の結果は `nil`
で、オラクルが `()` と印字する点と異なります。各ペアは seq ビューが取る任意のコレク
ションを取れ、パターンは `let` と同様に分配束縛します。`:when`/`:while`/`:let` 修飾子は
束縛の後に順に続きます:`:when` は要素を飛ばし、`:while` はそのレベルのループを終え
（外側のレベルのものは全体を終える）、`:let` は逐次に束縛します。それ以外のキーワードは
拒否されます。`dorun` は効果のためにコレクションを実現して `nil` を答え、`doall` は
コレクション自身を答えます。seq は既に strict なので、実現とは評価することです。

## コレクション

ベクターリテラルは `vector` 呼び出し、マップリテラルは `equal` ハッシュテーブル --
決してその場では変更されず、すべての操作が新しいテーブルを作るので、永続性は観測可能な
形で保たれます。セットリテラルは各要素を自分自身の下に格納した同じテーブルで、操作がセットを
マップと区別できるよう包まれます。キーワードは綴りを `(:C%KEYWORD name)` と包んだもの:
`equal` で比較されるデータで、呼び出し位置（`(:k m)`、省略可能なデフォルト付き）や関数値として
はマップ参照です。

seq 群はすべてのコレクションのリストビュー上で動きます:リストはそのまま通り抜け、
ベクターと文字列は変換され、マップはエントリごとに 2 要素ベクターを、セットは要素ごとに
1 メンバーを供給します（ともにテーブルの走査順で、未規定）。`nil` と `false` は空です。
それ以外はオラクルのようにシグナルします。strict なコレクションは先に強制されますが、
lazy seq（`lazy-seq`、`lazy-cat`、`repeat`、`cycle`、`iterate`、`repeatedly`）は同じ
ビューを通じて1要素ずつ realize します。`take` は辿って進み無限 seq でも終了します。
`drop`/`first`/`rest`/`next`/`seq` はそれを通じて realize し、`cons`/`concat`/`map`/
`filter` は入力が lazy なら再び lazy を答えます（そうでなければ strict なリスト）。
`lazy-seq` の本体は seq オブジェクトごとに最大1回だけ実行されます。表示は `take` 越し
の prefix だけにしてください -- 素の lazy seq はハングせず `#<LazySeq>` と表示され、
lazy な tail は `...` で打ち切られます。chunk 化はありません。`count`/`empty?`/`=`
はマップとセットにも届き（`=` は構造的で深い）、`get` は省略可能な
デフォルトを取り、マップ・セット・ベクター・文字列・nil を読みます。

## 状態と動的スコープ

メタデータ（`^:private`、`^:dynamic`、`^{...}` attr マップ、型ヒント、`with-meta`）
はどこにあっても解析して捨てられます。ディスパッチに影響しません。その一欠片を読むのは
`binding` だけです。`defn-` は慣習上のプライベート `defn` です。`def` は `defn`
同様に docstring と attr マップを取ります。`defonce` は束縛済みでない場合の `def`
であり、リロードでルートを保ちます。

`ref` はトランザクション規律つきのアトムセルです。`dosync` がエクステントを開き
（単一スレッドのためリトライも分離もなし）、`alter`/`commute` は `:validator`
を通して適用し（失敗はシグナルを上げて書き込まない）、`ref-set` はそれを通して
置き換え、`ensure` は ref 自身を答えます。いずれの動詞もエクステントが必要です。
`agent` は `send`/`send-off` で更新される同じセルです。送信は即時に適用され
（スレッドプールがないため非同期の順序付けは対象外）、agent を答えます。送信の
実行中 `*agent*` が束縛されます。`await` の待ち合わせと `shutdown-agents` は
`nil` を答えます。`future`/`delay`/`force`/`promise`/`deliver` は名前で拒否された
ままで、`proxy-super` も同様です（proxy メソッドに super ハンドルはありません）。

`binding` は `^:dynamic` な var（と、もとから special な `*out*`。
`*out*` は `*standard-output*` です）を動的エクステントで再束縛します。それ以外は
拒否されます。`defstruct` はキーベクターを名前の裏に保持します。
`struct`/`struct-map` はその上に新しいマップを組み立てます。`with-out-str` は
`*standard-output*` を文字列ストリームに束縛し（リテラルの
`with-output-to-string` は使いません）、印字内容を答えます。`time` は
`Elapsed time: N msecs` を報告して値を答えます。`with-open` は束縛して
`unwind-protect` 越しに逆順で閉じ、`close` メソッドを呼びます（Java の
closeable は他の interop 同様 JVM が要ります）。`(. stream write x)` は
`princ` 越しに印字され、どのバックエンドでも動きます。
他の seq 動詞（`doseq`/`for`/`reduce` や `keep` 群）への lazy 入力は seq ビューを1レベル
だけ消費します。先に `take` した prefix を渡してください。

## プロトコル、レコード、型

`defprotocol` はメソッドを宣言します。各メソッドはターゲットのタグ上のディスパッチャ
（階層探索なしの multimethod 形:タグの完全一致、それから `Object` 行）に lower され
ます。`extend-protocol`/`extend-type`/`extend` はターゲットのタグの下に行を足し、
`satisfies?` は所属を調べます。extend 対象は `class` が答える種類（`String`、
`Number`、`Boolean`、`Keyword`、`Symbol`、`Character`、`Map`、`Vector`、`Set`、
`List`/`Seq`、それに外れ既定としての `nil` と `Object`）と既知の record/deftype 名
で、それ以外は名前付きで拒否されます。`Object` 行なしの外れはオラクル同様シグナル
を上げます。各メソッドは1つのパラメータベクターを取ります（複数アリティは拒否のまま）。

`defrecord` 値は型タグ付きのマップです。すべてのマップが使うエントリ表を
`(:C%RECORD tag fields table)` で包むため、マップ動詞はそれを通して読みます
（`get`/`contains?`/`keys`/`vals`/`count`/`seq`/`select-keys` はエントリを読み、
`assoc`/`update`/`conj`/`merge` は表を組み直してタグを保ち、`dissoc` は宣言
フィールドが全部残る間はレコードを保ち、そうでなければオラクル同様プレーンな
マップに落ちます）。`=` は2つのレコードをタグとエントリで比べ、プレーンなマップ
と等しくなることは決してありません（オラクル同様）。`deftype` は不透明タグで
同じ形を共有します。読みは外れ、書きと `seq`/`count`/`empty?` はシグナルを上げ、
`=` は同一性です（オラクル同様）。`reify` は評価ごとに新しいタグを答え、各
プロトコルの表に行を持ちます。コンストラクタはマングルされた関数です。位置指定の
`->Type`、マップからの `map->Type`（record のみ -- オラクルは deftype に `map->`
を定義しません）。`(Type. ...)`/`(new Type ...)` は `->Type` に書き換わります。
`instance?` の record/deftype 名はタグを調べ、`(.-field x)` はフィールド表を読み
ます（欠けたフィールドはオラクル同様シグナル）。インラインのメソッド本体には
フィールドがローカルとして見えます（明示パラメータは同名フィールドを隠します。
オラクル同様）。型ヒント（`^String`、`^H`）はパースして捨てられ、ディスパッチに
影響しません。

## 未対応

各拒否は `unknown name` ではなく欠けた設計を名指します:

| 拒否されるもの | メッセージ形 | 理由 |
|---|---|---|
| end なし `range` | `infinite range is not supported: range needs an end` | 無限 seq は strict には綴れない -- `iterate` を使う |
| `transient`、`persistent!`、`assoc!`、`dissoc!`、`conj!`、`disj!` | `transients are not supported yet: ...` | テーブルの裏にトランジェント実装がない |
| 正規表現リテラル `#"..."` | `regex literals are not supported yet` | どのバックエッドにも正規表現実装がない |
| `definterface`、`gen-class`、`gen-interface` | `protocols are not supported yet: ...` | どのバックエンドにもインターフェース生成がない |
| 複数アリティのプロトコルメソッド | `multi-arity protocol methods are not supported yet: ...` | メソッドごとにパラメータベクターは1つ |
| `:extend-via-metadata` | `extend-via-metadata is not supported yet: ...` | メタデータはディスパッチに影響しない |
| `set!` | 名前で | フィールド書き込みプリミティブがない |
| `var`/`#'` | 名前で | var 機構がない。マクロ本体ではシンボルを quote する |
| `future`、`delay`/`force`、`promise`/`deliver` | 名前で | どのバックエンドにもスレッドプール・遅延メモセル・ブロッキング待ち合わせがない |
| `proxy-super` | 名前で | proxy メソッドは Java 引数だけで super ハンドルなし |
| `defmacro` パラメータの `&form`/`&env` | 名前で | マクロはコンパイル環境を受け取らない |
| `::` 自動解決キーワード | 名前で | 解決先の名前空間がない |
| `--no-gc` ビルド | 名前で | そのバックエッドにはペアもシンボルもクロージャもない |
| 3引数 `into`（トランスデューサー） | `transducers are not supported yet: into` | トランスデューサー実装なし。2引数は conj |
| `file-seq`、`clojure.java.io`（`reader` 以外） | `file-seq` / `unknown name: clojure.java.io/...` | ディレクトリ走査なし。解決するのは `reader` のみで、ファイルストリームのリーダーを開く |

## エラーと位置

低下エラーは最も内側のフォームの位置を名指し（ファイルが判れば `file:line:column`）、
リーダのエラーも同じ方法で接頭辞付きです。

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
（`rontolisp::%clojure-call`。関数は `apply` で、セット・マップ・ベクター・キーワード・シンボルは
それぞれの読み取りで、`IFn` 同様です。キーワードとシンボルの引数は 1 個か 2 個で、それ以外はアリティエラーです）。
`declare` されただけで定義されていない名前の呼び出しはオラクルと同じ `Attempting to call unbound fn` をシグナルします（REPL では後の入力が定義しうるので直接呼び出しのままです）。`def` は
トップレベル `setq` で -- 本体内にあっても本体が走るときにグローバルを設定します。

複数アリティの `defn` はアリティごとの `defun` 1 つと、引数数で選ぶディスパッチ `defun`
です（可変長節が 1 つだけなら固定パラメータを超える任意の個数を受けます）。それ以外の個数は
シグナルします。複数アリティの `fn` は同じ方法でディスパッチする 1 つの `lambda` で、名前付き
`fn` は自己呼び出しのために自分自身を束縛します。`#(...)` はオラクルと同じ `(fn* [p1__N# ...]
(body))` と読まれ、使われた最大の `%N` までの引数と `%&` の rest 引数をとる `lambda` に
なります。本体フォームは 1 呼び出しに包まれます。
`declare` は後で定義される名前を前もって宣言します。

## 名前空間とファイル

名前空間はそれぞれ自分の var を持ちます。定義は現在の名前空間（`ns` か `in-ns` が切り替える
までは `user`）に属します。名前はローカル、現在の名前空間自身の var、refer された var の順に
解決され、`alias/name` や `full.name/name` は別の名前空間の var を指します。その var が
private（`defn-`、`^:private`）なら、oracle のコンパイラと同じく拒否します。`user` の var は
`c%name` に、それ以外の名前空間の var は `c%ns/name` に lower され、プログラムを `load` した
Common Lisp ファイルはこの名前で呼び出します。

プログラムが宣言していない名前空間を `require`・`use`・`ns` 節が指すと、そのファイルを
ロードします。`my-app.core` は `my_app/core.clj` で、それを含む最初のソースルートから
読みます。どのルートにもなければ `my_app/core.cljc` を同じ順で探します（オラクルと同じく、どのルートの `.clj` も `.cljc` より優先します）。ルートは、エントリファイル自身の名前空間が示すディレクトリ（`demo.main` を宣言
する `src/demo/main.clj` なら `src`、`ns` のないファイルならそのファイルのディレクトリ）、
次にプロジェクトの `:paths` と依存ライブラリのルートの順です（[プロジェクト](#projects-depsedn)）。
ファイルはプログラムにつき 1 度だけ lower されます。定義は require したフォームより前に
残り、それ以外のトップレベルフォームは `require` の実行時に走ります。関数本体の中の
`require` も、本体の実行時にロードします。2 度目の `require` は何もロードしません。
`:reload` はファイルを再実行し（`def` はリセット、`defonce` はルートを保持）、
`:reload-all` は依存先を先に再実行します。いずれも oracle と同じです。`ns` フォームのないファイルは、require した側の
名前空間に定義を追加します。`(load "path")` と ns の `(:load "path" ...)` 節は、現在の名前空間のファイルがあるディレクトリからの相対パス（先頭が `/` ならソースルートから）でファイル（`path.clj`、なければ `path.cljc`）を読み、現在の名前空間で実行します。呼ぶたびに再実行し、`nil` を返し、ファイル内の `in-ns` は終了後に残りません。パスは文字列リテラルで、ファイルは lower の間に読まれます。`use` と `:refer :all` は public な var をすべて refer します。
どのルートにもないファイル、require の循環、存在しない var や private な var の refer は、
oracle と同じ文言のエラーになります。[組み込みの名前空間](reference/namespaces.md#built-in-namespaces)
はファイルを必要としません。

`*ns*` は現在の名前空間を値として持ちます。`ns` と `in-ns` が実行された時点で切り替わり、
読むコードの実行時に読まれるため、関数は呼び出し側の名前空間を答えます。require された
ファイルの実行中は `*ns*` が再束縛され（ファイルの `ns` が切り替え、終われば require した側の
名前空間に戻ります）、`*file*` はルートからのファイルのパス（`my_app/core.clj`）、
`*source-path*` はファイル名を持ちます。エントリファイルの `*file*` はその絶対パスです。
[the-ns](reference/the-ns.md)、[find-ns](reference/find-ns.md)、
[ns-name](reference/ns-name.md) は名前から名前空間を得ます。

```bash
# deps.edn は {:paths ["src"]}、demo.main と demo.main-test が demo.lib を require する
rontolisp src/demo/main.clj          # ルート: src（自身の ns）、src（deps.edn）
rontolisp test/demo/main_test.clj    # ルート: test（自身の ns）、src（deps.edn）
```

## プロジェクト: deps.edn

プログラムのプロジェクトは、エントリファイルの位置から上へたどって最初に見つかる `deps.edn`
（REPL では作業ディレクトリのもの）です。oracle の `clj` と同じくファイル全体を読み、組み込みの
ルートマップ（`:paths ["src"]` と `org.clojure/clojure`）、ユーザーレベルの `deps.edn`
（`$CLJ_CONFIG`、なければ `$XDG_CONFIG_HOME/clojure`、なければ `~/.clojure`）、プロジェクトの
`deps.edn` の順にマージします。`deps.edn` がなければ作業ディレクトリがプロジェクトです。oracle が使わないキーは
oracle と同じく無視します。oracle の spec が拒否する値、どの種類にも当たらない座標、oracle が
解決できない依存は、oracle と同じ文言のエラーになります。

- `:paths` はプロジェクトのソースルートを、その `deps.edn` からの相対パスで指定します。並びの
  中のエイリアスのキーワードは、そのエイリアスが並べるパスを表します。
- `:deps` は依存ライブラリを指定します。`{:local/root "../lib"}` はディレクトリか jar です。
  ディレクトリなら、それ自身の `deps.edn` が `:paths`（そのディレクトリからの相対パスで、既定は
  `["src"]`）と `:deps` を与えます。jar なら、中の `.clj` と `.cljc` のファイルを展開せずに読みます。
  ライブラリは oracle と同じ規則で選びます。トップレベルの依存が優先し、それ以外はツリー全体で
  最も新しいバージョンを選びます。`:exclusions` はそれを書いた座標より下からそのライブラリを除き、
  循環は選択済みのライブラリで止まります。
- ソースパスは、エントリファイル自身のルート、プロジェクトの `:paths`、選ばれた各ライブラリの
  ルート（oracle のクラスパスと同じく、ツリーの上から）、最後に組み込みの名前空間の順です。
- `org.clojure/clojure`、`org.clojure/spec.alpha`、`org.clojure/core.specs.alpha` は、バージョンに
  かかわらずこのフロントエンド自身です。`ring/ring-core` と `ring/ring-codec` は、同梱のバージョン
  （ring-core 1.15.5、ring-codec 1.3.0）以下の Maven バージョンなら組み込みの Ring 名前空間です。
  それより新しいバージョンは、Ring 名前空間をロードする時点で拒否します。
- Clojure 本体に属さない `clojure.*` 名前空間（`org.clojure` の contrib ライブラリなど）は、
  ほかの名前空間と同じくソースパスからロードします。
- Maven 座標（`:mvn/version`）は `:mvn/repos`（Maven Central、次に Clojars、次にマップが加える
  リポジトリ。`nil` で除けます）から `:mvn/local-repo`、なければ `~/.m2/repository` に取得します。
  POM の compile と runtime の依存（optional は除く）がツリーに加わり、jar は展開せずに読みます。
  `"[1.0]"` は 1.0 です。バージョン範囲は、リポジトリの `maven-metadata.xml` がその範囲に挙げる
  最も新しいバージョンです。`RELEASE`、`LATEST`、`SNAPSHOT` もそのメタデータで解決します。
  メタデータはローカルリポジトリに保存し、問い合わせ直すのは 1 日に 1 回です。`http:` の
  リポジトリは拒否します。`settings.xml`（`$MAVEN_HOME/conf/settings.xml` に
  `~/.m2/settings.xml` を重ねたもの）は oracle と同じく効きます。mirror、proxy、server の
  認証情報と `httpHeaders`（`mvn --encrypt-password` で暗号化したパスワードも含む）に従い、
  `localRepository`、`offline`、プロファイルの `<repositories>` は読みません。
- git 座標（`:git/url`、または `io.github.user/repo` という名前が示す URL と、`:git/sha`、
  `:git/tag`、`:deps/root`）は `git` コマンドでそのコミットを `~/.rontolisp/gitlibs`
  （`$RONTOLISP_DIST_HOME/gitlibs`）にチェックアウトします。タグはそのコミットを指す必要があり、
  短縮した sha にはタグが必要です。1 つのライブラリの 2 つのコミットでは、子孫のほうが新しい
  バージョンです。その `deps.edn` は `:local/root` のディレクトリと同じく読みます。
- `:local/root` の jar 自身の `pom.xml` が、その jar の依存を与えます。`pom.xml` のプロジェクト
  （`deps.edn` がなく `pom.xml` を持つディレクトリやコミット）は、Maven がモデルを組み立てる
  とおりに読みます。親はリポジトリより先に `<relativePath>`（既定は `../pom.xml`）で探します。
  compile と runtime の依存（optional を含む）を与え、ソースルートとして build のソース
  ディレクトリ（既定は `src/main/java`）、`src/main/clojure`、リソースディレクトリ（既定は
  `src/main/resources`）、`build-helper-maven-plugin` の `add-source` / `add-resource` の
  ディレクトリを加えます。最後のものは oracle と同じく先頭のプラグインから読みます。
- クラスを含む依存の jar は、プログラムの Java クラスパスにも加わります。インタプリタと JVM は
  そのクラスを呼べ、`-o app.jar` はその jar を出力の横にコピーします。WebAssembly は呼び出し時に
  Java を拒否するままです。
- プロジェクトはプログラムの実行前に解決するため、取得できない依存があるとプログラムは止まります。
  2 回目の実行はローカルリポジトリと git のキャッシュだけを読み、ネットワークを使いません。
  取得するのはコマンドラインです。組み込み側（`JvmSourceCompiler`）とブラウザのプレイグラウンドは
  何も取得せず、Maven 座標や git 座標にしかありえない名前空間は、その座標を挙げて拒否します。
  `:aliases` は実行で選んだときに適用します（次節）。

```console
$ cat deps.edn
{:paths ["src"]
 :deps {my/util {:local/root "../util"}
        org.clojure/data.json {:mvn/version "2.5.1"}
        io.github.me/lib {:git/tag "v1.0" :git/sha "1a2b3c4"}}}
$ rontolisp src/app/main.clj   # roots: src, the lib checkout's src, ../util/src, the data.json jar
```

## プロジェクトの実行: -A、-M、-X、test

フラグは `clj` のもので、作業ディレクトリの `deps.edn` に対して `clj` と同じように働きます。
`-A:dev:test` はエイリアスを選び、実行で読む Clojure のファイルはすべてそのエイリアスの下で
読みます。`-M[:aliases]` と `-X[:aliases]` もエイリアスを選び、rontolisp のオプションをそこで
終えます。後ろはすべて実行への引数なので、`-o` などのオプションは前に書きます。

- 選んだエイリアスは oracle と同じく適用します。`:extra-paths` は `:paths` の前に並び、
  `:extra-deps` は `:deps` に加わります。`:override-deps` はライブラリがどこに現れてもその座標を
  置き換え、`:default-deps` は `nil` と書かれたライブラリに座標を与えます。`:replace-paths` と
  `:replace-deps` はプロジェクト自身のものを置き換えます（ルートマップの `org.clojure/clojure`
  は残ります）。`:classpath-overrides` はライブラリを別のディレクトリや jar に向け、`""` なら
  外します。複数のエイリアスは oracle の規則でマージします。マップはマージし、パスの並びは
  重複なしで連結し、`:main-opts`、`:exec-fn`、`:ns-default` は最後のエイリアスのものを使います。
  2 つの `deps.edn` が定義する同名のエイリアスは 1 つのマップです。どのファイルも定義しない
  エイリアスは警告します。`:jvm-opts` は無視します。
- `-M` はエイリアスの `:main-opts` に引数を続けて `clojure.main` として実行します。
  `-m my.app a b` は `my.app/-main` を `"a" "b"` で呼び、`*command-line-args*` も同じ値を持ちます。
  パスならそのファイルを実行し、何もなければ REPL を開きます。
- `-X` は 1 つの関数を 1 つのマップで呼びます。引数は `[fn] [key value]... [map]` で、それぞれを
  EDN として読みます。関数は引数か `:exec-fn` が指すもので、`:ns-default` と `:ns-aliases` で
  修飾します。マップは `:exec-args` に各値をそのキー（ベクタのキーはパス）で設定し、末尾の
  マップを重ねたものです。
- `-o` を付けると、ほかのプログラムと同じくコンパイルします。コマンドラインの引数は成果物に
  固定し、起動時の引数はその後ろに続きます。
- `System/exit` はどのバックエンドでもその終了ステータスでプログラムを終えます。
- `deps.edn` のあるディレクトリでの `rontolisp test` は、プロジェクトのテストを実行します。
  `:test` エイリアスの `:extra-paths`（ルートマップから `test`）以下で、名前が `-test` で終わる
  名前空間をすべて `clojure.test/run-tests` にかけます。終了コードは、すべてのテストが通れば 0、
  失敗・エラーがあるか 1 つも実行されなければ 1 です。`-A:...` は `:test` の代わりに別の
  エイリアスを選び、ファイルを渡すとその名前空間だけを実行します。

```console
$ cat deps.edn
{:paths ["src"]
 :aliases {:dev {:extra-paths ["dev"]}
           :run {:main-opts ["-m" "my.app"]}
           :greet {:exec-fn my.app/greet :exec-args {:name "you"}}}}
$ rontolisp -M:dev:run a b            # (my.app/-main "a" "b")
$ rontolisp -X:greet :name '"deps"'   # (my.app/greet {:name "deps"})
$ rontolisp -o app.wasm -M:run a      # wasmtime run app.wasm b: (my.app/-main "a" "b")
$ rontolisp test                      # test/**/*_test.clj を clojure.test で実行
```

## 束縛

`let` は `let*` です（Clojure の `let` は逐次）。`letfn` は事前走査された項目群上の 1 つの
`labels` で、兄弟同士が互いを呼びます。`loop`/`recur` は `labels` の自己呼び出しで、
どのバックエンドでも定数スタック、初期値は逐次です。`recur` は名前付き・無名の `fn`、`defn`
の節、`letfn` の項目、0引数の `lazy-seq` 本体にも届きます（多アリティの各節がそれぞれ対象）。個数の不一致は
名前付き拒否です。パラメータと束縛は分配を受けます:ベクター
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
`&form`/`&env` は拒否されます。本体が見えるのは核の built-in、`clojure.lisp`
ライブラリ、そして呼び出し位置より上にあるプログラムのトップレベル定義（自身のファイル、
require した名前空間、REPL の前の入力のもの）であり、oracle のフォームごとのロードと
同じです。`def` の値は、本体がそれを読むときにだけ展開用に作られます。
定義は同じ expander を実行時
テーブルにも登録し、`nil` を返し、セッションをまたいで有効です。定義より上での
呼び出しはエラーとなり、マクロに関数値はなく、後からの同名 `def`/`defn` が呼び出し
位置を取り戻します。核の名前（`with-out-str`、`when-not`、`inc`、`declare` など）の
`defmacro` は、oracle のフォーム単位のコンパイルと同じく、定義以降でその名前を覆い隠します。
定義より上の呼び出し位置と、定義より上で定義されたマクロの syntax-quote は核の意味を
保ちます。`clojure.core/name` は、プログラムがその名前で何を定義していても常に核の var
を指します。special form と、リーダーが綴る先頭（`@x` の `deref`、`with-meta`）は
マクロの名前にできません。`fn` のマクロは定義でき、`fn` の呼び出し位置だけを捕らえます
（`#(...)` は special form の `fn*` と読まれるので捕らえません）。

`` `form `` は mangle 済み名前空間上のデータとしてフォームを組み立てます。定義側の
名前空間から見える var を指すシンボルは oracle と同じくその var の名前空間で限定され
（special form は素のままです。核の名前は `clojure.core/name`、その他の解決できない
シンボルは定義側の名前空間で綴ります）、`~` はそのフォームの値を埋め込み、`~@` は外側の
リスト・ベクター・マップ・セットの中に列を継ぎ足します。各 `x#` は syntax-quote
ごとに 1 つの `(gensym "x")` を束縛します。1 展開につき 1 シンボルであり、同じ展開
の中では出現箇所によらず同じものになります。syntax-quote の外の unquote、列の外の
splice はエラーです。`macroexpand-1` は 1 回、`macroexpand` は fixpoint まで展開し、
いずれも展開結果を mangle されたデータそのものとして返すため、quote したフォームとの
`=` が成り立ち、表示は oracle と同じ小文字綴りになります。`gensym` は
評価ごとに新しい uninterned シンボルを返します。

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

`doseq` は副作用のために seq ビューを反復し `nil` を答えます:束縛ペアごとにループ
が 1 つ、左から右へ入れ子になり、本体は暗黙の `do` です。各ループは lazy 入力を 1 要素
ずつ進むため、`:while` は無限の入力も止めます。`dotimes` はカウント未満の
`0` から同じく束縛し `nil` を答えます。カウントは先に `truncate` を通るため、`2.5`
は `0 1` を数え、非数はそこでシグナルします（オラクルの `intCast` と同様）。`for` は
本体を全組合せに適用して答えます。辿るコレクションがすべて strict な間は一度に realize
した strict なリストで（空なら `nil` で、オラクルが `()` と印字する点と異なります）、最初の
lazy なコレクションから先は消費されるにつれて realize される lazy seq です。そのため
`first`/`take` は答える分だけ realize し、無限のコレクションもその後ろで終わります。各ペア
は seq ビューが取る任意のコレクションを取れ、パターンは `let` と同様に分配束縛します。
`:when`/`:while`/`:let` 修飾子は束縛の後に順に続きます:`:when` は要素を飛ばし、`:while`
はそのレベルを終え（外側のレベルのものは全体を終える）、`:let` は逐次に束縛します。
それ以外のキーワードは拒否されます。`dorun` は効果のためにコレクションを最後まで辿って
（lazy なものは realize されます）`nil` を答え、`doall` はコレクション自身を答えます。

## コレクション

ベクターリテラルは `vector` 呼び出し、マップリテラルは `equal` ハッシュテーブル --
決してその場では変更されず、すべての操作が新しいテーブルを作るので、永続性は観測可能な
形で保たれます。セットリテラルは各要素を自分自身の下に格納した同じテーブルで、操作がセットを
マップと区別できるよう包まれます。キーは `=` で一致します: ベクター・リスト・マップ・セットの
キーは、プログラムが最初に格納した同じ種類の `=` なキーの下に格納されるので、`equal`
テーブルで見つかります。ベクターも変更されません: `assoc`・`update`・`assoc-in`・`update-in`
はベクター全体をコピーして添字の要素を置き換え、要素数と等しい添字は末尾に追加します。キーワードは綴りを `(:C%KEYWORD name)` と包んだもの:
`equal` で比較されるデータで、呼び出し位置（`(:k m)`、省略可能なデフォルト付き）や関数値として
はマップ参照です。配列は general です。`(make-array Class dim...)` はクラスを無視した
一般配列を作り、`aget` で読み、`aset` で書き、`alength` で測ります（本の
`interop.clj` の形。Clojure の綴りだけが新しく、どのバックエンドでも動きます）。

seq 群はすべてのコレクションのリストビュー上で動きます:リストはそのまま通り抜け、
ベクターと文字列は変換され、マップはエントリごとに 2 要素ベクターを、セットは要素ごとに
1 メンバーを供給します（ともにテーブルの走査順で、未規定）。`nil` と `false` は空です。
それ以外はオラクルのようにシグナルします。strict なコレクションは先に強制されますが、
lazy seq（`lazy-seq`、`lazy-cat`、`repeat`、`cycle`、`iterate`、`repeatedly`）は同じ
ビューを通じて1要素ずつ realize します。`take` は辿って進み無限 seq でも終了します。
`drop`/`first`/`rest`/`next`/`seq` はそれを通じて realize し、`cons`/`concat`/`map`/
`filter`、`remove`、`keep`、`keep-indexed`、`map-indexed`、`distinct`、`interpose`、
`partition`、`interleave` は入力が lazy なら再び lazy を答えます（そうでなければ strict
なリスト）。
`lazy-seq` の本体は seq オブジェクトごとに最大1回だけ実行されます。表示はオラクル同様に
lazy seq を realize します（空のものは `()`、無限のものは終わりなく表示されます）。chunk
化はありません。`count`/`empty?`/`=`
はマップとセットにも届き（`=` は構造的で深い）、`get` は省略可能な
デフォルトを取り、マップ・セット・ベクター・文字列・nil を読みます。

## 状態と動的スコープ

名前やローカルについたリーダーメタデータ（`^:private`、`^:dynamic`、`^{...}` attr
マップ、型ヒント）は解析して捨てられます。ディスパッチに影響しません。ただし
`def`/`defonce`/`defn` の名前についた `^:dynamic` は var を再束縛可能にします。
`^:dynamic` な `defn` は直接の定義を保ちつつ、呼び出しは var 経由になるため、
`binding` が届きます。再束縛を通すのは `binding` だけです。`defn-` は慣習上のプライベート `defn` です。`def` は `defn`
同様に docstring と attr マップを取ります。`defonce` は束縛済みでない場合の `def`
であり、リロードでルートを保ちます。

値のメタデータは実在します。[with-meta](reference/with-meta.md) はマップを持つコピーを
返し、[meta](reference/meta.md) がそれを読み、[vary-meta](reference/vary-meta.md) が
更新します。ベクター・マップ・セットのリテラルについたリーダーメタデータも `with-meta`
と同様に付きます。`=` はメタデータを無視します。逸脱は2つです。コピーから導いた値
（`assoc`、`conj` など）はメタデータなしで始まり（オラクルは引き継ぎます）、シンボルは
メタデータを持ちません（`with-meta` はシンボルをそのまま返します）。

`#'x`（`(var x)`）はプログラムの定義の var を返します。名前ごとに 1 つのオブジェクトで、
`#'ns/x` と表示され、deref と呼び出しはルートを通ります。メタデータは `#'` より上にある
最新の定義が記録したものです。`def`/`defn`/`defn-`/`defmacro` は `:arglists`、
docstring の `:doc`、名前のメタデータと attr マップ（定義の位置で評価されるため
`^{:test (fn [] ...)}` が動きます）、`:line`/`:column`/`:file`、`:name`、`:ns` を
与えます。[test](reference/core-test.md) はその `:test` 関数を呼びます。ローカルは var では
ありません。プログラムの定義が占めていない名前と `clojure.core/` の綴りは core の var
（`#'clojure.core/inc`）です。ルートは core の値で、マクロのルートはシグナルを上げ、
メタデータは `:name`、`:ns` とマクロの `:macro` です。ここで値を持たない core の var
（`#'all-ns`）は拒否されます。

`ref` はトランザクション規律つきのアトムセルです。`dosync` がエクステントを開き
（単一スレッドのためリトライも分離もなし）、`alter`/`commute` は `:validator`
を通して適用し（失敗はシグナルを上げて書き込まない）、`ref-set` はそれを通して
置き換え、`ensure` は ref 自身を答えます。いずれの動詞もエクステントが必要です。
`agent` は `send`/`send-off` で更新される同じセルです。送信は即時に適用され
（スレッドプールがないため非同期の順序付けは対象外）、agent を答えます。送信の
実行中 `*agent*` が束縛されます。`await` の待ち合わせと `shutdown-agents` は
`nil` を答えます。`future`/`delay`/`force`/`promise`/`deliver` は名前で拒否された
ままで、`proxy-super` も同様です（proxy メソッドに super ハンドルはありません）。

`binding` は `^:dynamic` な var と `clojure.core` の特殊変数を動的エクステントで
再束縛します。それ以外は拒否されます。`*out*`/`*in*`/`*err*` は `*standard-output*`/
`*standard-input*`/`*error-output*` です。フラグは `clojure -M` でのオラクルの値を持ち
（`*print-length*` は `nil`、`*assert*` は `true`、`*data-readers*` は `{}`、
`*command-line-args*` はプログラムの引数、`*clojure-version*` は 1.12.6 など）、
プリンタは `*print-length*`、`*print-level*`、`*print-readably*`、`*print-meta*`、
`*print-namespace-maps*` に従い（キーが一つの名前空間を共有するマップは `#:a{:b 1}` と
印字されます）、`assert` は展開される時点の `*assert*` を読みます。それ以外はただの値です。`*ns*`、`*file*`、`*source-path*` はロードに従い（上述）、`*repl*` は
`false`、`*1`/`*2`/`*3`/`*e` は [REPL](repl.md) の外では `nil` です。`with-in-str` は `*in*` を文字列リーダに束縛し、`read-line`、
`read`、`(.read *in*)` はそこから読みます。`defstruct` はキーベクターを名前の裏に保持します。
`struct`/`struct-map` はその上に新しいマップを組み立てます。`with-out-str` は
`*standard-output*` を文字列ストリームに束縛し（リテラルの
`with-output-to-string` は使いません）、印字内容を答えます。`time` は
`Elapsed time: N msecs`（オラクル同様、倍精度の数）を報告して値を答えます。
`with-open` は束縛して
`unwind-protect` 越しに逆順で閉じ、`close` メソッドを呼びます（Java の
closeable は他の interop 同様 JVM が要ります）。`(. stream write x)` は
`princ` 越しに印字され、どのバックエンドでも動きます。`(.readLine stream)` は
`read-line` 越しに読み（末尾越しはオラクル同様 `nil`）、`(.read stream)` は次の
文字のコードを答えます（末尾越しは `-1`）。
lazy 入力はどの seq 動詞にも届きます。コレクション全体を辿る動詞（`count`、`last`、
`sort`、`apply`、`reverse`、`set`、`frequencies`、`reduce`、`into`）は先に
すべて realize します（無限の入力はオラクル同様に答えを返しません）。途中で止まる動詞
（`second`、`nth`、`some`、`every?`、`take-while`、`drop-while`、`zipmap`、
位置による分配束縛、`doseq`/`for`）は1要素ずつ辿るため、無限の入力でも
答えます。

## プロトコル、レコード、型

`defprotocol` はメソッドを宣言します。各メソッドはターゲットのタグ上のディスパッチャ
（階層探索なしの multimethod 形:タグの完全一致、次にプロトコルを extend したクラスの
うちターゲットが継承または実装するもの、それから `Object` 行）に lower されます。
`extend-protocol`/`extend-type`/`extend` はターゲットのタグの下に行を足し、
`satisfies?` は所属を調べます。extend 対象は `class` が答える種類（`String`、
`Number`、`Boolean`、`Keyword`、`Symbol`、`Character`、`Map`、`Vector`、`Set`、
`List`/`Seq`、それに外れ既定としての `nil` と `Object`）、既知の record/deftype 名、
それに値がインスタンスでありうる他のクラス（throwable、`clojure.lang.IRef` のような
インタフェース、`java.util.Date`、インタプリタと JVM ではホストのクラス）です。後者は
オラクル同様、スーパークラス、インタフェースの順に試します。どのクラスでもない名前は
拒否されます。`Object` 行なしの外れはオラクル同様シグナル
を上げます。メソッドはアリティごとに1つのパラメータベクターを宣言します。インライン本体は
メソッド名を書き直して別のアリティを実装し、拡張は `fn` の節で書き、行には呼び出しの引数の数に
一致するアリティを適用する1つのラムダを格納します。`clojure.core.protocols/CollReduce` や
`IKVReduce` の自前の行を持つ record・deftype・`reify` は、`reduce`・`reduce-kv` とその上に
作られた動詞でもその行を通して畳み込まれます（[reduce](reference/reduce.md)）。
`:extend-via-metadata true` と宣言したプロトコルは、ターゲットのメタデータからも
名前空間で修飾したメソッドのシンボルでメソッドを探します。オラクル同様、
`defrecord`/`deftype`/`reify` 本体の実装の後、extend の行の前です
（[defprotocol](reference/defprotocol.md) 参照）。

`defrecord` 値は型タグ付きのマップです。すべてのマップが使うエントリ表を
`(:C%RECORD tag fields table class)` で包むため、マップ動詞はそれを通して読みます
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

`^:unsynchronized-mutable` または `^:volatile-mutable` を付けた deftype フィールドは
代入できます。その型自身のインラインメソッド内の `(set! field value)` が書き込んで
値を返し、以降の読み（同じ呼び出し内でも、別メソッドの書き込み後でも）は新しい値を
見ます。このフィールドはメソッド専用（`.-field` からは見えません）で、メソッド内で
作ったクロージャ（`fn`・`#()`・`letfn`・`reify`・`lazy-seq`・`for`・`dosync`）は作成時に値を
コピーし、`defrecord` はこの指定を拒否します。いずれもオラクル同様です。
ClojureScript の `^:mutable` は指定になりません。ローカル・パラメータ・不変
フィールドへの `set!` はオラクルと同じ `Cannot assign to non-mutable: ...`、
非 dynamic なグローバルへの `set!` は実行時に
`Can't change/establish root binding of: ... with set` をシグナルします。

## 読み取り

`read-string` は文字列の最初のデータを、`read` はリーダからデータを1つ、実行時に
すべてのバックエンドで読みます。答えは同じテキストをクオートしたときの値と同じです。
数・文字列・文字・キーワード（`::kw` は呼び出し元の名前空間で解決）・コレクションを
同じように読み、メタデータは捨て、`#_` は読み飛ばします。レコードリテラルは
プログラムが定義するクラスのレコードを組みます。`#=` の読み取り時評価とタグ付きリテラルは
ソースと同様に拒否され、リーダ条件は `{:read-cond :allow}` のとき `.cljc` ファイルと同様に読まれます。リーダとして渡せるのは
`clojure.java.io/reader`、`*in*`、それらや `java.io.StringReader` の上の
`java.io.PushbackReader`/`BufferedReader`/`InputStreamReader` で、いずれもどのバックエンドでも
ストリームです。`read` はリーダをデータの直後に残します。`str` はオラクル同様、
コレクション内の文字列をクォートするので、`spit` が書いたものは読み戻せます。
`eval` と `load-string` は提供しません。実行時にコンパイラが動かないためです。

## ホスト境界

`rontolisp.wasm/defimport` と `rontolisp.wasm/export` は `rontolisp:wasm-import` と
`rontolisp:wasm-export` に、`rontolisp.wit/import`・`export`・`provide` は 3 つの WIT
ディレクティブに低下するので、どのバックエンドも Clojure プログラムの境界を Common Lisp の
プログラムと同じように結びます（[WASM ホスト関数](reference/wasm.md)、[WIT 契約](reference/wit.md)）。
ホストに見える名前は書いたとおりの名前で、マングルしたシンボルではありません。`false` は
ホストの偽として渡り、`false` として戻ります。`:s-expr` は Clojure のプリンタのテキストとして
渡ります。それより豊かな WIT の値は両方向で Clojure の綴りで渡り（レコードはマップ、enum や
バリアントのケースはキーワードか `[:case payload]`、フラグは集合、タプルやリストはベクタ）、
`result` のエラー側はエラーの値を持つ `ExceptionInfo` として渡ります。WIT の import の var
は、`require` のエイリアスと同じくそのフォームより下で存在し、`defimport` は `defn` と同じく
それより上でも呼べます。エクスポートはファイル全体の低下が終わってから var を解決するので、
var はエクスポートより下で定義してかまいません。

## HTTP クライアント

`rontolisp.http-client` は Clojure で書かれた組み込みの名前空間で、どのリクエストも
`rontolisp:fetch` を呼びます。そのため Clojure のプログラムはターゲットが fetch に与える
トランスポートでリクエストを送り、トランスポートのないターゲットではコンパイルの時点で
プログラムが拒否されます（[HTTP クライアント](reference/http-client.md)）。`:async true` で
返るフューチャーは rontolisp のフューチャーで、`deref` は `rontolisp:await` と同じ仕組みで
それを待ちます。

## 未対応

各拒否は `unknown name` ではなく欠けた設計を名指します:

| 拒否されるもの | メッセージ形 | 理由 |
|---|---|---|
| end なし `range` | `infinite range is not supported: range needs an end` | 無限 seq は strict には綴れない -- `iterate` を使う |
| `transient`、`persistent!`、`assoc!`、`dissoc!`、`conj!`、`disj!` | `transients are not supported yet: ...` | テーブルの裏にトランジェント実装がない |
| `definterface`、`gen-class`、`gen-interface` | `protocols are not supported yet: ...` | どのバックエンドにもインターフェース生成がない |
| コア関数が参照するもの以外のインタフェースを挙げた `reify`/`deftype`/`defrecord` の本体（[reify](reference/reify.md#host-interfaces)） | `... is not supported yet as an interface of ...` | コレクションのインタフェース（`ISeq`、`IPersistentMap` など）とホストのインタフェースを参照する関数がまだない |
| 特殊変数でない core の var（`inc`）やホストフィールドへの `set!` | `set! of a var is not supported yet: ...`、`set! of a host field is not supported yet: ...` | 代入先の var がない。`java:` にフィールド書き込みがない |
| `future`、`delay`/`force`、`promise`/`deliver` | 名前で | どのバックエンドにもスレッドプール・遅延メモセル・ブロッキング待ち合わせがない |
| proxy メソッドの外側の `proxy-super` | `proxy-super outside a proxy method` | `proxy-super` はメソッドの `this` に対するスーパークラスの実装呼び出し |
| 2 つめのクラス・重複メソッド・`final` スーパークラスを伴う `proxy` | `... is a class, not an interface`、`proxy defines method ... twice`、`proxy cannot extend final class ...` | スーパークラスは 1 つのみ、メソッド名ごとに本体は 1 つ、`final` のスーパークラスは不可 |
| インターフェースだけの proxy の `toString`/`equals`/`hashCode` | `proxy cannot override ... yet` | `java:proxy` は `Object` の 3 メソッドを保つので本体は実行されない（クラスの proxy は実行する） |
| 可変長のみの静的メンバー、インスタンスメソッド（`Class/.m`）、コンストラクタ（`Class/new`）の値 | `... is variadic and has no value form` | `java:static`、`java:call`、`java:new` へ届く rest 展開がない |
| `defmacro` パラメータの `&form`/`&env` | 名前で | マクロはコンパイル環境を受け取らない |
| 未知のエイリアスの `::alias/kw` | `Invalid token: ...` | 解決するのは require のエイリアス、ファイル自身の ns、既知の名前空間のみ |
| `--no-gc` ビルド | 名前で | そのバックエッドにはペアもシンボルもクロージャもない |
| `file-seq`、`clojure.java.io`（`reader` 以外） | `file-seq` / `unknown name: clojure.java.io/...` | ディレクトリ走査なし。解決するのは `reader` のみで、ファイルストリームのリーダーを開く |
| `clojure.core` の var、マクロ、マルチメソッド、プロトコルメソッドの `with-redefs`。REPL では、以前の入力が `^:redef` なしで定義した `defn` の `with-redefs` | `with-redefs of ... is not supported...`、`... define it ^:redef to redefine it` | コアの関数は呼び出しごとにインライン展開される。置き換えるルートを持つのは `def`/`defn`/`declare` の var だけで、REPL の入力は直接呼び出しのまま実行済み |
| 非同期の Ring ハンドラ（`:async? true` 付きの `run-server`） | `asynchronous handlers (:async? true) are not supported` | トランスポートに respond/raise の仕組みがない |
| `rontolisp.wasm` の宣言の `:async`、`async func` の WIT メンバーやエクスポート | `:async is not supported yet ...`、`... is an async func ...` | 中断する呼び出しが答える future は Clojure の future ではない |
| stream か future を受け取るか答える WIT メンバー | `... which the Clojure tier does not carry yet (file.wit:N)` | 非同期 canonical ABI のハンドルが答える Clojure の future がまだない |
| `rontolisp.wasm` の宣言の `:bytes` | `:bytes does not cross from Clojure ...` | `(unsigned-byte 8)` のベクタを渡すが、それに当たる Clojure の値がない |

## エラーと位置

低下エラーは最も内側のフォームの位置を名指し（ファイルが判れば `file:line:column`）、
リーダのエラーも同じ方法で接頭辞付きです。

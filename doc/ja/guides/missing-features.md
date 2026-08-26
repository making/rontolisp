# サポートされない Common Lisp の機能

rontolisp は意図的に小さくした Common Lisp のサブセットで、3 つのバックエンド
（インタプリタ、JVM、WASM）で同一に動作します。ランタイムのメタオブジェクト
プロトコルなしで言語をそのままのバイトコードにコンパイルできるよう保つため、
完全な Common Lisp の機能の多くが意図的に省かれています。

このページでは **利用できない、または部分的にしか対応していないもの** だけを
挙げます。**利用できる**ものについては、
[言語リファレンス](../reference/special-forms.md)を参照してください。

| 機能 | 状況 |
| --- | --- |
| リスタート | 利用可。デバッガ統合（`break`、`*debugger-hook*`）とコンディションとの関連付けはありません |
| `define-symbol-macro` | 利用不可（レキシカルな `symbol-macrolet` は利用可能） |
| `&environment` | `defmacro` のラムダリストで受け付けますが、常に `nil` に束縛されます（マクロ展開環境オブジェクトは存在しません）。`&whole` は `defmacro`・`destructuring-bind` の双方で動作します |
| `loop`（拡張版） | 一部対応（後述） |
| CLOS | 一部対応（静的サブセット + 定義時 MOP サブセット） |
| `defstruct` の `:include` | 単一継承のみ。スロットのデフォルトを上書きする `(:include parent (slot default) ...)` は利用可能 |
| `declare` / `declaim` / `proclaim` / `the` | 結果は変えない。WASM では配列の `type` 宣言が要素アクセサのエミットを誘導（モジュールが小さく速くなる）、それ以外では解析されるだけの no-op |
| `typep` / `subtypep` / `coerce` / `concatenate` | リテラル（クオートされた）型指定子のみ。`coerce` の結果型は `'list` / `'vector` / `'string`（または浮動小数点型）、`concatenate` はこの 3 つのシーケンス系統を構築 |
| `make-package` / `rename-package` / `delete-package` / `unintern` / `shadow`（ランタイム） | 利用不可。`export` / `unexport` / `import` / `use-package` は `in-package` と同様の読み込み/コンパイル時ディレクティブとして利用可能。`defpackage` の `:shadow` / `:shadowing-import-from` はエラー |
| `eval-when` | `progn` として扱う（フェーズの区別なし） |
| `#:name` | 普通のシンボルとして読まれ、gensym 的な新規性はない |
| `*modules*` | 利用不可（`require`/`provide` は利用可能） |
| 複素数 | 利用不可 |
| `--no-gc` での `catch` / `throw` / `unwind-protect` / 条件 | コンパイルエラー（他のバックエンドでは利用可能） |

## 多値

[`values`](../reference/functions/values.md) とその消費側は、ユーザ定義関数の
多値も含めて利用できます。Common Lisp からの残る相違点は次のとおりです:

- **末尾以外**の位置で `values` を呼んでから通常の値を返すプロデューサは
  古い余剰値を残すことがあるため、`values` は結果位置で使ってください
  （消費側は受け取った値をクリアするので、余剰値が残るのは何も消費しない
  `values` 呼び出しだけです）。
- `funcall #'values`（ファーストクラス値）はコンパイル済みプログラムでは
  主値のみを返します。
- 組み込みの `#'name` を渡した `multiple-value-call` はラッパーの固定
  アリティのままです — それ以外の引数個数にはユーザ定義関数か `lambda` を
  渡してください。
- CL で副次値を持つ他の組み込み関数（`read-from-string`、
  `subtypep` など）は単一値のままです —
  [`find-symbol`](../reference/functions/find-symbol.md) と
  [`intern`](../reference/functions/intern.md) はアクセス可能性ステータスを、
  [`macroexpand-1`](../reference/functions/macroexpand-1.md) /
  [`macroexpand`](../reference/functions/macroexpand.md) は `expanded-p` を
  返します。

## 非局所脱出

[`catch`](../reference/special-forms/catch.md) /
[`throw`](../reference/special-forms/throw.md)、
[`block`](../reference/macros/block.md) /
[`return-from`](../reference/macros/return-from.md) と
[`tagbody`](../reference/special-forms/tagbody.md) /
[`go`](../reference/special-forms/go.md) は利用できますが、**コンパイル済み**
バックエンドには 2 つの制限があります（インタプリタには影響しません）:

- `flet`/`labels` のローカル関数をまたぐ必要がある `return-from` はまだ
  未対応です（`lambda` をまたぐものは非局所脱出として対応済みです）。
- `go` はレキシカルに囲む `tagbody` のタグのみを対象にできます。インタプリタは
  さらに関数呼び出しの境界を越える動的 `go`、つまり*呼び出し元*が確立したタグへの
  ジャンプもサポートします。ネストした `lambda` の内側から囲む関数のタグへ
  ジャンプする形 — [`handler-bind`](../reference/macros/handler-bind.md) の
  ハンドラが `go` で保護対象のループを再開する形であり、quri の `:lenient` な
  パーセントデコードがまさにこれです — は `lambda` をまたぐ `return-from` と
  同じく下位変換されます: そのタグで `tagbody` に再入して実行を続ける
  非局所脱出になります。

`lambda` をまたぐ `return-from` と `go`、`catch`/`throw`、`unwind-protect`、
条件の捕捉はいずれも例外処理モードでコンパイルされるため、出力される wasm-GC
モジュールの実行には `wasmtime -W exceptions=y`（37+）が必要です。`--no-gc` では
`catch`/`throw`、`unwind-protect` と条件系のフォームはコンパイルエラーに
なります。

## リスタート

コンディションシステムはリスタート層まで揃っています:
[`handler-bind`](../reference/macros/handler-bind.md) のハンドラは巻き戻しの
前にシグナル点で実行され、[`restart-case`](../reference/macros/restart-case.md) /
[`restart-bind`](../reference/macros/restart-bind.md) /
[`with-simple-restart`](../reference/macros/with-simple-restart.md) が
リスタートを確立し、[`find-restart`](../reference/functions/find-restart.md) /
[`invoke-restart`](../reference/functions/invoke-restart.md) /
[`compute-restarts`](../reference/functions/compute-restarts.md) /
[`muffle-warning`](../reference/functions/muffle-warning.md) /
[`abort`](../reference/functions/abort.md) /
[`continue`](../reference/functions/continue.md) がそれらを駆動します。
[`cerror`](../reference/macros/cerror.md) は継続可能です。欠けているのは
**対話的デバッガ**です: `break` と `*debugger-hook*` は存在せず、リスタートの
`:report` は保存されるだけで描画されず、`:interactive` 関数も実行されません。
またリスタートはコンディションと関連付けられません
(`find-restart`/`compute-restarts` の省略可能なコンディション引数は無視されます)。
[`check-type`](../reference/macros/check-type.md) /
[`assert`](../reference/macros/assert.md) /
[`ccase`](../reference/macros/ccase.md) は依然として `store-value` リスタートを
提供せずにエラーを通知します。`--no-gc` ではリスタートフォームは主フォームへ
退化します(そのバックエンドにはコンディションオブジェクトがありません)。
wasm-GC バックエンドで捕捉できるのは**シグナルされた**コンディションのみです —
ランタイムトラップは依然として中断させます。

## `loop` マクロ

拡張版 [`loop`](../reference/macros/loop.md) の限定的なサブセットが利用でき
ます。対応している節はそのページに一覧があり、分配束縛、並行 `and`、アナフォリック
な `it`、`loop-finish`、`thereis`/`always`/`never` も含まれます。対象外は
`named`（およびそれが名付ける `return-from`）です。また、分配束縛のパターンは
ラムダリストキーワードを解釈せず（`&optional` などはエラーにならず通常の変数と
して束縛されます）、`being` はハッシュテーブルを駆動しますが、パッケージ形式
（`being the external-symbols of ...`）はランタイムの intern テーブルが存在
しないため、解析はされるものの空のシーケンスを反復します。

## 構造体とオブジェクト

[`defstruct`](../reference/special-forms/defstruct.md) は `:include` による継承を
単一継承の形でのみサポートします。スロットの上書きは利用可能です:
`(:include parent (slot new-default) ...)` は継承したスロットのデフォルトを
子のレイアウトでのみ差し替え、インデックスは継承したまま保つため、親のアクセサから
そのまま読めます。インスタンスは標準の
`#S(...)` 構文で印字され、`#S(...)`
リテラルはインスタンスとして読み戻されます — ソース中でも、すべてのバックエンド
のランタイム `read` / `read-from-string` を通しても（コンパイルされたリーダーは
フロントエンドと同等で、`#.`、`#+`/`#-`、`#n=`/`#n#` だけはシグナルします）。
`(:print-object fn)` / `(:print-function fn)` オプションを持つ構造体は
代わりにその関数を通して印字されます。どちらのオプションもサポートしています。

CLOS は**静的なサブセット**です
（[`defclass`](../reference/special-forms/defclass.md)、第 1 引数で
ディスパッチする [`defgeneric`](../reference/special-forms/defgeneric.md) /
[`defmethod`](../reference/special-forms/defmethod.md)、リテラルのクォートされた
名前を取る [`make-instance`](../reference/macros/make-instance.md) と
[`slot-value`](../reference/macros/slot-value.md)）。`:initform` なしで書かれた
スロットは CL と同様に未束縛で始まります:
[`slot-boundp`](../reference/macros/slot-boundp.md) がそれを報告し、
[`slot-makunbound`](../reference/macros/slot-makunbound.md) が元に戻し、
読み取りは `unbound-slot` をシグナルします。
[`change-class`](../reference/macros/change-class.md) はインスタンスのクラスを
その場で変更し（対象は実行時のシンボルやクラスメタオブジェクトでも可）、
`reinitialize-instance` / `shared-initialize` はユーザメソッドなしでも呼び出せ
ます — CL と同様、システムデフォルトが指定された initarg を格納します。
**定義時 MOP サブセット**が入っています:
[`find-class`](../reference/functions/find-class.md) と
[`class-of`](../reference/functions/class-of.md) は実物の `standard-class`
メタオブジェクトを返し、
[`allocate-instance`](../reference/functions/allocate-instance.md) が動作し、
クラスオプション `(:metaclass M)` は定義時にクラス定義プロトコルを実行します
（[`defclass`](../reference/special-forms/defclass.md) 参照）— これが postmodern
の DAO 層を無改変でロードする仕組みです。多重継承も動作します
（クラス優先順位リスト、スーパークラス間のスロットマージ）。対象外: 実行時のクラス構築
（計算されたデータからの `ensure-class`、トップレベル以外の `defclass`、
`add-method`、`compute-applicable-methods`、クラス再定義、
`update-instance-for-different-class`）—
コンパイルされたプログラムのクラスとメソッドの集合はコンパイル時に固定されます。

## ユーザー定義パッケージ

[`defpackage`](../reference/special-forms/defpackage.md) は `:use`、`:export`、
`:nicknames`、`:import-from` をサポートする、リテラルなトップレベルの
read/コンパイル時ディレクティブです（`:documentation`/`:size` は受理されるが
無視されます）。`:shadow` と `:shadowing-import-from` はエラーで（シンボルの
シャドウイングはありません）。`use-package`、
[`export`](../reference/functions/export.md)、`unexport`、
[`import`](../reference/functions/import.md) は `in-package` と同じ読み込み/
コンパイル時ディレクティブとして存在します: リテラルなトップレベル呼び出しは
それ以降のフォームに対して全バックエンドで効果を持ち、実行時に計算される
呼び出しはインタープリタのみで動作します。実行時のパッケージ生成・改名は
できません: `make-package`、`rename-package`、`delete-package` は利用不可です。
`unintern`（および実行時の `shadow` / `shadowing-import`）はそもそも実現でき
ません — シンボルは名前そのものであり、そこから取り除くべき intern テーブルが
存在しないからです。
問い合わせ系は本物です:
[`find-package`](../reference/functions/find-package.md)、
[`package-name`](../reference/functions/package-name.md)、
[`list-all-packages`](../reference/functions/list-all-packages.md)、
[`package-use-list`](../reference/functions/package-use-list.md)、
[`package-used-by-list`](../reference/functions/package-used-by-list.md)、
[`package-shadowing-symbols`](../reference/functions/package-shadowing-symbols.md)
（常に `nil`）。コンパイル済みバックエンドはコンパイル時に焼き込まれた
テーブルから答えるため、コンパイル済みプログラムが後から作ったパッケージは
そこからは見えません。
複数の使用先パッケージが同じ名前を export している場合、
コンフリクトをシグナルする代わりに `:use` 順で最初のパッケージが優先されます。

## 動的（special）変数

`let`/`let*` および [`progv`](../reference/special-forms/progv.md) による
動的束縛はサポートされていますが、**コンパイル済み**バックエンドには 1 つの
制限があります（インタプリタには影響しません）。通常の脱出と special な `let`
の境界を**越えて**脱出する `return`/`return-from` は束縛を復元しますが、`let`
の外側のハンドラで捕捉されるエラー（および境界を越える `go`、WASM バックエンドで
`unwind-protect` / `handler-case` も同時に越える `return`）では復元されません。
`progv` は `unwind-protect` がカバーするすべての脱出（上記のケースを含む）で
復元します。

## 数値タワー

rontolisp は整数（任意精度の bignum を含む）、比（`1/3`）、倍精度浮動小数点数を
サポートしますが、**複素数はサポートしません**。負の数の平方根は、複素数の結果
ではなく浮動小数点の `NaN` を返します。

```console
> (sqrt -1)
NaN      ; full Common Lisp would return #C(0.0 1.0)
```

## その他の省略事項

- ラムダリスト: 拡張された `defmacro` のラムダリスト（`&whole`、`&optional`、
  `&key`、`&aux`、入れ子の分配パターン）は `destructuring-bind` を経由します。
  これは意図的に寛容で、引数の不足は `nil`、余剰は無視となりエラーになりません。
  また funcall/apply 経由の呼び出しでは関数の物理パラメータは 10 個までです。
- ユーザーマクロはコンパイル済みプログラムの実行時 `eval` では認識されず、
  その `eval` がランタイムに構築する `lambda` はラムダリストキーワードを
  解釈しません（[コンパイル済み eval の制限](eval-limitations.md)を参照）。
- **プリティプリンタ**は、行が十分に広いものとしてのテキストを生成しますが、**レイアウト**は変えません。rontolisp のストリームは桁位置を持たないため、論理ブロックが折り返すことはなく、条件付き改行（`pprint-newline` の `:linear` / `:fill` / `:miser`、format の `~_` / `~:_` / `~@_` / `~i`）はすべて何もせず、`*print-right-margin*` / `*print-miser-width*` / `*print-lines*` は受理のみで無視されます。行を分けるのは `(pprint-newline :mandatory)` と `~:@_` だけです。その他の `*print-*` 変数はすべて存在し、プリンタが実際に行う動作そのものの値を保持します。既定値以外を束縛しても効果がないというだけです（`*print-escape*` / `*print-readably*` / `*print-pretty*` と `*print-case*` は例外で、実際に効きます）。`*print-case*` はプリンタが綴るシンボルの大小文字を変換しますが、構造体・CLOS インスタンス・ハッシュテーブル・階数が 1 以外の配列に入れ子になったシンボルは格納された綴りのままです（[リーダのケース](reader-case.md)）。通常の印字操作は `*print-pprint-dispatch*` を参照しません。エントリが効くのは、プログラム自身がエントリ関数を呼ぶ箇所です。
- `#.` の read 時評価は `.asd` ファイル内では警告付きでスキップされます。
- 組み込みマクロの名前（`cond`、`case`、`when`、`setf`、`push` など）は
  再定義できません。

この一覧はすべてを網羅したものではありません。rontolisp は完全な標準ではなく、
焦点を絞ったコアを実装しています。

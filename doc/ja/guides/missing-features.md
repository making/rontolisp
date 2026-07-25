# サポートされない Common Lisp の機能

rontolisp は意図的に小さくした Common Lisp のサブセットで、3 つのバックエンド
（インタプリタ、JVM、WASM）で同一に動作します。ランタイムのメタオブジェクト
プロトコルなしで言語をそのままのバイトコードにコンパイルできるよう保つため、
完全な Common Lisp の機能の多くが意図的に省かれています。

このページでは **利用できない、または部分的にしか対応していないもの** だけを
挙げます。**利用できる**ものについては、
[言語リファレンス](../reference/special-forms.md)を参照するか、実行時に
`rontolisp:list-special-forms`、`rontolisp:list-macros`、`rontolisp:list-functions`
で一覧表示してください。

| 機能 | 状況 |
| --- | --- |
| `catch` / `throw` | 利用不可（`block`/`return-from`/`tagbody`/`go` は利用可能） |
| リスタート（`handler-bind`、`restart-case`、`invoke-restart`、`cerror` など） | 利用不可 |
| `symbol-macrolet` | 利用不可（`macrolet` は利用可能） |
| `&whole` / `&environment` | 利用不可。`defmacro` のラムダリストは必須パラメータと末尾の `&rest`/`&body` 1 つのみ |
| `loop`（拡張版） | 一部対応（後述） |
| CLOS | 一部対応（静的サブセット、MOP なし） |
| `defstruct` の `:include` | 利用不可（インスタンスは `#S(...)` 形式で印字・読み取りされます）|
| `declare` / `declaim` / `proclaim` / `the` | 解析されるだけの no-op（コンパイルには影響しない） |
| `typep` / `subtypep` / `coerce` | リテラル（クオートされた）型指定子のみ。`coerce` の結果型は `'list` / `'vector` / `'string` |
| `make-package` / `export` / `import` / `use-package` / `find-package` / `rename-package`（ランタイム） | 利用不可。`defpackage` の `:shadow` / `:shadowing-import-from` はエラー |
| `progv` | インタプリタのみ（JVM/WASM ではコンパイルエラー） |
| `eval-when` | `progn` として扱う（フェーズの区別なし） |
| `#:name` | 普通のシンボルとして読まれ、gensym 的な新規性はない |
| `*modules*` | 利用不可（`require`/`provide` は利用可能） |
| 複素数 | 利用不可 |
| `--no-gc` での `unwind-protect` / 条件 | コンパイルエラー（他のバックエンドでは利用可能） |

## 多値

[`values`](../reference/functions/values.md) とその消費側は、ユーザ定義関数の
多値も含めて利用できます。Common Lisp からの残る相違点は次のとおりです:

- **末尾以外**の位置で `values` を呼んでから通常の値を返すプロデューサは
  古い余剰値を残すことがあるため、`values` は結果位置で使ってください。
- `funcall #'values`（ファーストクラス値）はコンパイル済みプログラムでは
  主値のみを返します。
- 組み込みの `#'name` を渡した `multiple-value-call` はラッパーの固定
  アリティのままです — それ以外の引数個数にはユーザ定義関数か `lambda` を
  渡してください。
- CL で副次値を持つ他の組み込み関数（`read-from-string`、`macroexpand-1`、
  `intern` など）は単一値のままです。

## 非局所脱出

`catch` / `throw` — 動的スコープの脱出はありません。

[`block`](../reference/macros/block.md) /
[`return-from`](../reference/macros/return-from.md) と
[`tagbody`](../reference/special-forms/tagbody.md) /
[`go`](../reference/special-forms/go.md) は利用できますが、**コンパイル済み**
バックエンドには 2 つの制限があります（インタプリタには影響しません）:

- `flet`/`labels` のローカル関数をまたぐ必要がある `return-from` はまだ
  未対応です（`lambda` をまたぐものは非局所脱出として対応済みです）。
- `go` は同一関数内のレキシカルに囲む `tagbody` のタグのみを対象にできます。
  インタプリタはさらに関数境界を越える動的 `go` をサポートします。

`lambda` をまたぐ `return-from`、`unwind-protect`、条件の捕捉はいずれも例外
処理モードでコンパイルされるため、出力される wasm-GC モジュールの実行には
`wasmtime -W exceptions=y`（37+）が必要です。`--no-gc` では `unwind-protect` と
条件系のフォームはコンパイルエラーになります。

## リスタート

条件システムのコア（`define-condition`、`handler-case`、`ignore-errors`、
`signal`、型付きの `error`）は利用できますが、**リスタートシステム**は利用
できません: `handler-bind`、`restart-case`（主フォームだけを残す no-op として
受理）、`restart-bind`、`invoke-restart`、`with-simple-restart`、`cerror`、
`abort`、`continue`、`break` は存在せず、
[`check-type`](../reference/macros/check-type.md) /
[`assert`](../reference/macros/assert.md) は再格納リスタートを提供せずに
エラーを通知します。wasm-GC バックエンドで捕捉できるのは**シグナルされた**
コンディションのみです — ランタイムトラップは依然として中断させます。

## `loop` マクロ

拡張版 [`loop`](../reference/macros/loop.md) の限定的なサブセットが利用でき
ます: 数値/リストのステップ（`for`）、文字列のステップ（`for ... across`）、
よく使う集約（`collect`、`append`、`sum`、`count`、`maximize`、`minimize`
など）、単純な制御節（`while`/`until`、`repeat`、`when`/`unless`、`finally`、
`return`）。対象外は、分配束縛、`for` 節同士の並行 `and`、`being`、アナフォリック
な `it`、`named`/`loop-finish`、`thereis`/`always`/`never` です。

## 構造体とオブジェクト

[`defstruct`](../reference/special-forms/defstruct.md) は `:include` による継承を
サポートしません。インスタンスは標準の `#S(...)` 構文で印字され、ソース中の
`#S(...)` リテラルはインスタンスとして読み取られます。コンパイル済みプログラムの
ランタイム `read` / `read-from-string` は `#S(...)` を認識しません。

CLOS は**静的なサブセット**です
（[`defclass`](../reference/special-forms/defclass.md)、第 1 引数で
ディスパッチする [`defgeneric`](../reference/special-forms/defgeneric.md) /
[`defmethod`](../reference/special-forms/defmethod.md)、リテラルのクォートされた
名前を取る [`make-instance`](../reference/macros/make-instance.md) と
[`slot-value`](../reference/macros/slot-value.md)）。対象外: 多重継承、
第 2 引数以降の specializer、`slot-boundp`、MOP / 実行時クラス操作
（`find-class`、`change-class`、`add-method`、クラス再定義）—
コンパイルされたプログラムのクラスとメソッドの集合はコンパイル時に固定されます。

## ユーザー定義パッケージ

[`defpackage`](../reference/special-forms/defpackage.md) は `:use`、`:export`、
`:nicknames`、`:import-from` をサポートする、リテラルなトップレベルの
read/コンパイル時ディレクティブです（`:documentation`/`:size` は受理されるが
無視されます）。`:shadow` と `:shadowing-import-from` はエラーで（シンボルの
シャドウイングはありません）、**ランタイム**のパッケージ操作はありません:
`make-package`、`export`、`import`、`use-package`、`find-package`、
`rename-package` は利用できないため、パッケージの export シンボルの集合は
定義時に固定されます。複数の使用先パッケージが同じ名前を export している場合、
コンフリクトをシグナルする代わりに `:use` 順で最初のパッケージが優先されます。

## 動的（special）変数

`let`/`let*` による動的束縛はサポートされていますが、**コンパイル済み**
バックエンドには 2 つの制限があります（インタプリタには影響しません）。実行時に
計算されるシンボルのリストを束縛する
[`progv`](../reference/special-forms/progv.md) はコンパイルエラーになり、
special な `let` の境界を**越えて**脱出する `return`/`return-from` は、そこで
グローバルを復元しません（通常の脱出とエラーによる中断は問題ありません）。

## 数値タワー

rontolisp は整数（任意精度の bignum を含む）、比（`1/3`）、倍精度浮動小数点数を
サポートしますが、**複素数はサポートしません**。負の数の平方根は、複素数の結果
ではなく浮動小数点の `NaN` を返します。

```console
> (sqrt -1)
NaN      ; full Common Lisp would return #C(0.0 1.0)
```

## その他の省略事項

- ラムダリスト: `&whole` は利用できず、`defmacro` のラムダリストは必須
  パラメータと末尾の `&rest`/`&body` 1 つのみを取り、funcall/apply 経由の
  呼び出しでは関数の物理パラメータは 7 個までです。
- ユーザーマクロはコンパイル済みプログラムの実行時 `eval` では認識されず、
  その `eval` がランタイムに構築する `lambda` はラムダリストキーワードを
  解釈しません（[コンパイル済み eval の制限](eval-limitations.md)を参照）。
- `#.` の read 時評価は `.asd` ファイル内では警告付きでスキップされます。
- 組み込みマクロの名前（`cond`、`case`、`when`、`setf`、`push` など）は
  再定義できません。一覧は `(rontolisp:list-macros)` で取得できます。

この一覧はすべてを網羅したものではありません。rontolisp は完全な標準ではなく、
焦点を絞ったコアを実装しています。

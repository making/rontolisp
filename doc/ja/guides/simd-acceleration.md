# ベクトルカーネルと SIMD アクセラレーション(vec, linalg)

`vec` パッケージは、移植可能なパックド `f64` ベクトルカーネルを提供します。[パックド浮動小数点配列型](../reference/data-types.md)に対する、コンストラクタ・要素アクセス・要素ごとの算術・リダクションです。double のベクトルに対する密な数値ループのための第一候補のパッケージであり、すべてのバックエンドで任意のハードウェアアクセラレーション(SIMD)層を備えます。パッケージ名は移植可能な抽象を表し、`--simd` フラグはそれをどうアクセラレートするかを表します。

`--simd` は `vec` 専用のフラグではありません。まったく同じ配列の上に構築された [`linalg` パッケージ](linear-algebra.md)も加速します。本ガイドはまず `vec` を、次にフラグそのものを、最後にそれが `linalg` に対して何をするかを説明します。

JSON や `linalg` ライブラリと同様、`vec` は Lisp ソース(`vec.lisp`)で一度だけ実装されています。インタプリタは `vec:` 関数の初回使用時に定義を遅延ロードし、コンパイル経路はプログラムがパッケージを参照するときに定義をスプライスします。このスカラー定義がインタプリタ・JVM コンパイラ・WASM(wasm-GC)バックエンドでの実装であり、かつアクセラレートされた各経路の正しさの基準(オラクル)でもあるため、すべての関数はどこでも同一に振る舞います。

## vec と linalg の使い分け

`vec` と `linalg` は同じものの 2 つの実装ではありません。同じパックド浮動小数点配列の上の 2 つの**契約**です。しかも `--simd` の下では同じアクセラレートされたカーネルに行き着くので、選択が速度の問題になることはありません。選ぶ基準は、境界条件で関数にどう振る舞ってほしいかです:

| | `linalg` | `vec` |
|---|---|---|
| 受け付ける入力 | パックド配列、`#(1 2 3)` のような一般の boxed 配列、素の数値 | パックド浮動小数点配列のみ |
| 幅の混在(`#d` と `#f`) | 許容 — 両方を広げ、第 1 引数の幅が勝つ | ハードエラー |
| ブロードキャスト | numpy の規則 — どちら側のスカラーも、形状の異なる配列同士も末尾の軸から | `vec:scale` のスカラーのみ |
| 形状 | 階数 n の配列と行列、形状エラーは説明的 | 階数 1 のベクトル(および `vec:matvec` の階数 2 行列) |
| アロケーション制御 | 結果は常に新しい配列 | `-into` 系が呼び出し側のバッファに書き込む |
| `--no-gc` | コンパイル不可 | 完全対応(そこで使える唯一のベクトルパッケージ) |

経験則: **デフォルトでは `linalg` に対して書いてください。** より広い numpy 流の API で、混在した入力も許容し、`--simd` を付ければ同じカーネルで加速されます。`vec` に手を伸ばすのは、その 3 つの専売特許が目的そのものであるときです: アロケーションのないホットループ(`-into` カーネル)、`--no-gc` ターゲット、そして幅の間違いを黙って広げる代わりに即座にエラーへ変える fail-fast の厳格さです。

## データ表現

ベクトルは階数 1 の[パックド浮動小数点配列](../reference/data-types.md)です。すなわち `#d(...)` や `(make-array n :element-type 'double-float)` が生成する、`double-float` 型でアンボックスな配列です。組み込みの `aref` / `length` はこれと相互運用でき、別の場所で構築したパックドベクトルも `vec` 関数に渡せます。要素ごとのカーネルは新しいベクトルを返し、リダクションはスカラーの `double` を返します。

カーネルは幅多相です。単精度ベクトル(`#f(...)` / `:element-type 'single-float`。要素を `f32` として格納し、メモリ半分・SIMD レーン 2 倍)も受け付けます。要素ごとのカーネルはすべてのバックエンドで入力の幅を保ち(`#f` を入れると `#f` が出る)、リダクションは常にスカラーの `double` に畳み込みます。

```lisp
(vec:arange 5)                         ; => #d(0.0 1.0 2.0 3.0 4.0)
(vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) ; => #d(5.0 7.0 9.0)
(vec:dot #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) ; => 32.0
(vec:scale #d(1.0 2.0 3.0) 10)         ; => #d(10.0 20.0 30.0)
```

## API

構築: `vec:zeros` / `vec:ones` は長さ *n* の充填ベクトルを作り、`vec:arange` は `[0.0, 1.0, ..., n-1]` を作り、`vec:from-list` / `vec:to-list` はベクトルと Lisp リストを相互変換します(リスト系はインタプリタ・JVM・wasm-GC でのみ動作し、`--no-gc` では動きません)。`vec:zeros` / `vec:ones` / `vec:arange` は `:element-type` キーワードも取ります。`:element-type 'single-float` を渡すとパックド単精度 (`#f`) ベクトルになります(デフォルトは倍精度)。[linalg のコンストラクタ](linear-algebra.md#single-float-precision)と揃っており、JVM と WASM の `--simd` v128 経路を含む全バックエンドで保持されます。

アクセス: `vec:aref` は要素を読み(`vec:aset` を介した `setf` の場所)、`vec:length` は要素数を返します。これらは汎用のパックド配列演算子への薄いラッパーなので、素の `aref` / `length` も使えます。

要素ごと(新しいベクトル): `vec:add`・`vec:sub`・`vec:mul`(アダマール積)・`vec:div`・`vec:scale`(スカラー倍)。

最初の 4 つには CL 演算子スペル `vec:+`・`vec:-`・`vec:*`・`vec:/` もあります。これらは完全な別名で、加速パスも含めて同じコードにコンパイルされます。可変長引数の [`linalg:`](linear-algebra.md) 版と違い**厳密に 2 引数**です: `vec:` のカーネルはどれも固定アリティでアロケーションが明示的であり(下の `-into` ファミリが存在する理由がこれです)、被演算子が増えるたびに中間ベクトルを黙って確保する可変長スペルはこのパッケージの狙いに反します。`(vec:+ (vec:+ a b) c)` と書くか、より良いのは `-into` のループにすることです。

要素ごとの単項、numpy の ufunc 名で(新しいベクトル): `vec:exp`・`vec:log`・`vec:tanh`・`vec:sin`・`vec:cos`・`vec:tan`・`vec:asin`・`vec:acos`・`vec:atan`・`vec:sinh`・`vec:cosh`・`vec:sqrt`・`vec:abs`・`vec:square`・`vec:negative`・`vec:sign`・`vec:reciprocal`(`1 / x`)。それぞれ各バックエンド自身のスカラー演算を要素ごとに適用します。そのため超越関数のメンバー(`vec:exp` / `vec:log` / `vec:tanh` / `vec:sin` / `vec:cos` / `vec:tan` / `vec:asin` / `vec:acos` / `vec:atan` / `vec:sinh` / `vec:cosh`)は WASM バックエンドではソフトウェア近似を使い(下位桁が JVM と異なります)、`vec:abs` / `vec:negative` / `vec:sign` / `vec:tanh` / `vec:sin` / `vec:tan` の `-0.0` の端値は各バックエンド自身のスカラー演算に従います。`--no-gc` でも超越関数のメンバーと `vec:sign` は他の WASM バックエンドと同じソフトウェアの命令列で動くので、17 個すべてがどこでも動きます。

比較セレクト: `vec:maximum` / `vec:minimum`(2 つのベクトルの要素ごとに大きい方 / 小さい方)、`vec:relu`(要素ごとの `max(x, 0.0)`)、`vec:clip`(スカラー境界での要素ごとの `min(max(x, lo), hi)`)。4 つとも厳密比較セレクト `(if (> x y) x y)` とその鏡像で定義され、IEEE の min/max プリミティブは使いません。したがって比較が偽になるときは常に第 2 被演算子(または境界)が選ばれます: `-0.0` と `0.0` のタイは第 2 被演算子を採り、`NaN` も同じ規則に従い(`vec:relu` は `0.0` に、`vec:clip` は `lo` にします)、`--no-gc` を含むすべてのバックエンドが厳密に一致します。

リダクション(スカラー): `vec:sum`・`vec:dot`・`vec:mean`・`vec:norm`(ユークリッドノルム、自己内積の `sqrt`)。

```lisp
(vec:sum (vec:arange 5))              ; => 10.0
(vec:mean #d(2.0 4.0 6.0))             ; => 4.0
(vec:norm #d(3.0 4.0))                 ; => 5.0
(vec:to-list (vec:mul #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))) ; => (4.0 10.0 18.0)
```

行列×ベクトル(新しいベクトル): `vec:matvec` は GEMV です。階数 2 のパックド行列 `W`(形状 *d* x *n*)と長さ *n* の階数 1 ベクトル `x` の積で、*i* 番目の要素が `W` の第 *i* 行と `x` の内積になる長さ *d* のベクトルを返します(転置なし)。ニューラルネットワークの順伝播の主役であり(すべての射影・フィードフォワード・分類層が `vec:matvec` です)、要素ごとではなく行列の行ごとに 1 回実行される唯一のカーネルです。結果は入力の幅に従います。`--no-gc` では `W` を `(make-array (list d n) :element-type ...)` と 2 添字の `aref` への `setf` で構築してください。階数 2 の `#d((...))` リテラルはそこではサポートされず、`x` は `W` と同じ幅でなければなりません(通常の `vec` の厳格さです)。

```lisp
(vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)) ; => #d(17.0 39.0)
```

[`ml/nn-vec.lisp` の例](https://github.com/making/rontolisp/blob/develop/examples/ml/nn-vec.lisp)は、単精度の順伝播を `vec:matvec` で組み立てた小さな XOR ネットワークです。

## メモリ: ベクトルはどこに置かれ、何が回収するのか

パックド浮動小数点配列は、4 つのターゲットのうち 3 つでは普通の GC 対象の値であり、残る 1 つでは WebAssembly の線形メモリ上のブロックです。メモリの増加を意識する必要があるのは最後の 1 つだけです。

| ターゲット | パックド配列の置き場所 | 自動で回収されるか |
|---|---|---|
| インタプリタ(`-o` なし) | JVM ヒープ | はい(JVM の GC) |
| JVM(`-o prog.class`) | JVM ヒープ | はい(JVM の GC) |
| wasm-GC(`-o prog.wasm`) | WebAssembly GC ヒープ | はい(エンジンの GC) |
| `--no-gc`(`-o prog.wasm --no-gc`) | 線形メモリ(bump 割り当て) | **いいえ — 一切解放されないため、メモリ増加を意識する必要があります** |

GC のある 3 つのターゲットでは、中間結果を捨てるループのメモリ使用量は横ばいのままです。wasm-GC(`wasmtime run -W gc`)で 1024 要素のベクトルを 200000 回新規に作っても、ピークは 50000 回のときと同じ約 123 MB です。アロケータを通過した総量は 1.5 GB あるにもかかわらず、です。`linalg` の配列も同じパックド型なので、まったく同じように振る舞います。

`--no-gc` は設計上これと異なります。名前のとおり、コレクタがありません。`__ronto_alloc` は解放を持たない bump アロケータなので、**ベクトルを返すカーネルはすべて恒久的にメモリを消費します**。回収はアリーナ全体をエクスポート呼び出しの境界で捨てる形でのみ起こります。`--no-gc` モジュールには必ずその境界があります(トップレベルに置けるのは `defun` と `rontolisp:wasm-export` 指示子だけで、`_start` は存在しません)。

- 返り値の型がメモリを指さないスカラー(`:int` / `:long` / `:float` / `:bool` / `:void`)のエクスポートは、リターン時に bump ポインタを自動で戻します
- 常駐ホストは、エクスポートされた `__ronto_alloc_mark` / `__ronto_alloc_reset` の対で呼び出しを挟めます
- **1 回のエクスポート呼び出しの中では何も解放されません。** `(setq acc (vec:add acc d))` のループは `memory.grow` が失敗するまで線形メモリを伸ばし続けます

最後の点こそが、次節の書き込み先を渡すカーネルの存在理由です。`--no-gc` の文字列(`concatenate`・`subseq`・`princ-to-string`)も同じように bump 割り当てされます。`linalg` は `--no-gc` ではそもそもコンパイルできないので、影響を受けるのは `vec` だけです。

wasm-GC にも線形メモリはありますが、パックド配列がそこに置かれることはありません。そこにあるのはインターン済みシンボル名と文字列ストリームのバッファだけで、実行時文字列は「線形メモリが伸び続けるのをやめさせるため」にこそ GC ヒープへ移されました。

## 書き込み先を渡すカーネル(確保しないループ)

上記のうちベクトルを返すカーネルはすべて**新しい**ベクトルを返すため、ループで回すと反復ごとに 1 本確保されます。それぞれに、呼び出し側が用意した書き込み先に結果を書いてそれを返す `-into` 版があり、確保をループの外へ追い出せます。書き込み先が第 1 引数に来るのは、Common Lisp 自身の `map-into` に倣っています。

| 確保する版 | 書き込み先を渡す版 |
|---|---|
| `(vec:add a b)` | `(vec:add-into out a b)` |
| `(vec:sub a b)` | `(vec:sub-into out a b)` |
| `(vec:mul a b)` | `(vec:mul-into out a b)` |
| `(vec:div a b)` | `(vec:div-into out a b)` |
| `(vec:scale v s)` | `(vec:scale-into out v s)` |
| `(vec:matvec w x)` | `(vec:matvec-into out w x)` |
| `(vec:exp v)` | `(vec:exp-into out v)` |
| `(vec:log v)` | `(vec:log-into out v)` |
| `(vec:tanh v)` | `(vec:tanh-into out v)` |
| `(vec:sin v)` | `(vec:sin-into out v)` |
| `(vec:cos v)` | `(vec:cos-into out v)` |
| `(vec:tan v)` | `(vec:tan-into out v)` |
| `(vec:asin v)` | `(vec:asin-into out v)` |
| `(vec:acos v)` | `(vec:acos-into out v)` |
| `(vec:atan v)` | `(vec:atan-into out v)` |
| `(vec:sinh v)` | `(vec:sinh-into out v)` |
| `(vec:cosh v)` | `(vec:cosh-into out v)` |
| `(vec:sqrt v)` | `(vec:sqrt-into out v)` |
| `(vec:abs v)` | `(vec:abs-into out v)` |
| `(vec:square v)` | `(vec:square-into out v)` |
| `(vec:negative v)` | `(vec:negative-into out v)` |
| `(vec:sign v)` | `(vec:sign-into out v)` |
| `(vec:reciprocal v)` | `(vec:reciprocal-into out v)` |
| `(vec:maximum a b)` | `(vec:maximum-into out a b)` |
| `(vec:minimum a b)` | `(vec:minimum-into out a b)` |
| `(vec:relu v)` | `(vec:relu-into out v)` |
| `(vec:clip v lo hi)` | `(vec:clip-into out v lo hi)` |

リダクション(`vec:sum`・`vec:dot`・`vec:mean`・`vec:norm`)はスカラーを返すため元々確保せず、対応する `-into` 版はありません。

```lisp
(let ((acc (vec:zeros 3))
      (d #d(1.0 2.0 3.0)))
  (dotimes (i 3) (vec:add-into acc acc d))
  acc)                                   ; => #d(3.0 6.0 9.0)
```

要素ごとのカーネル(2 項も単項も)では、書き込み先が被演算子と**同一であって構いません**。結果の第 *i* 要素は入力の第 *i* 要素にしか依存しないので、上の `(vec:add-into acc acc d)` や `(vec:exp-into v v)` は正しく定義されたインプレース更新です。例外は `vec:matvec-into` で、出力の各要素が `x` の全要素を畳み込むため、`x` に書き込むと後続の行がまだ読む値を壊してしまいます。`out` と `x`(または `w`)に同じ配列を渡した場合は、黙って壊す代わりにエラーを通知します。

すべての被演算子は要素型を共有していなければならず、`out` は入力以上の長さが必要です(長さは検査されません。`vec:add` が被演算子の長さを検査しないのと同じです)。

これこそが `--no-gc` を実用的な数値計算ループで使えるものにします(上のメモリの表を参照)。`-into` を使えばピークメモリは実際に生かしているベクトル分だけになります。`--no-gc --simd` での実測では、65536 要素のベクトルを 12000 回累算したときのピーク RSS は `vec:add-into` で 13.7 MB、`vec:add` では 4.31 GB(その後トラップ)でした。GC のある 3 つのターゲットでは `-into` は正しさに何の影響もありません。そこでは単なる確保量の最適化です。

`--no-gc` では、`vec:matvec-into` のエイリアシングガードは Lisp のエラーではなく WebAssembly のトラップ(`unreachable` 命令)です(このバックエンドにはエラーチャネルがありません)。そして `-into` が最も重要なのもここです。GEMV のデコードループは、さもなければステップごとに新しい出力ベクトルをバンプアロケートし、何一つ解放されないからです。

## ハードウェアアクセラレーション(任意)

スカラーの `vec.lisp` 基準はすべてのバックエンドで正しく動きます。`--simd` は、ベクトル化可能なカーネル(`add` / `sub` / `mul` / `div` / `scale` / `dot` / `sum` / `matvec` と 4 つの演算子別名、単項 ufunc の `exp` / `log` / `tanh` / `sin` / `cos` / `tan` / `asin` / `acos` / `atan` / `sinh` / `cosh` / `sqrt` / `abs` / `negative` / `sign` / `reciprocal`、比較セレクトの `maximum` / `minimum` / `relu` / `clip`、およびそれらすべての `-into` 版、さらに波及的に `mean` / `norm` / `square`)を実際の CPU ベクトル命令または脱ボックス化されたループに追加でロワリングする、バックエンド非依存の唯一のスイッチです。オプトインです。要素ごとのカーネルはスカラー基準とバイト単位で同一のままですが、リダクションは加算順序が異なり、単精度のリダクションはさらに単精度で累算します。したがってリダクションはスカラー基準と食い違うことがあります。後述の精度に関する 2 つの段落を参照してください。同じフラグは `linalg` の一群の関数も加速します。それらは次節に挙げます。

どのメモリモデル向けにコンパイルするか(`.class`・wasm-GC `.wasm`・`--no-gc` `.wasm`)と、`--simd` を渡すかどうかは**直交する**軸です。

| ターゲット | `--simd` なし | `--simd` あり |
|---|---|---|
| インタプリタ(`-o` なし) | スカラー `vec.lisp` | `jdk.incubator.vector`(ネイティブバイナリには組み込み済み。`java -jar` では `--add-modules` が必要) |
| JVM(`-o prog.class`) | スカラー `vec.lisp` | `jdk.incubator.vector` ブリッジ |
| wasm-GC(`-o prog.wasm`) | スカラー `vec.lisp` | ネイティブ v128(`f64x2` / `f32x4`) |
| `--no-gc`(`-o prog.wasm --no-gc`) | スカラーの線形メモリループ | ネイティブ v128(`f64x2` / `f32x4`) |

- **インタプリタ `--simd`**: `rontolisp prog.lisp --simd` は、スカラーの `vec.lisp` 定義の代わりに同じカーネルを `jdk.incubator.vector` 上で実行します。コンパイル手順は不要で、大きな `vec:dot` は数倍速くなります。ネイティブバイナリはインキュベータモジュールを組み込み済みで、実行時フラグは要りません。素の `java -jar` ではモジュールが無いため、フラグはスカラー基準にフォールバックして注意書きを表示します。そこでアクセラレーションを得るには `java --add-modules jdk.incubator.vector -jar rontolisp.jar prog.lisp --simd` で再実行してください。`--simd` なしのインタプリタは常にスカラー基準を実行します(これがバックエンド横断のオラクルです)。このフラグは REPL でも機能します。`rontolisp --simd` で起動すると `vec:` / `linalg:` カーネルが同じように高速化されます。
- **JVM `--simd`**: `rontolisp prog.lisp -o Prog.class --simd` はカーネルを、埋め込みの `jdk.incubator.vector` ブリッジに振り向けます(`#d` には `DoubleVector`、`#f` には `FloatVector`。`vec:matvec` はそのベクトル化された内積を行列の行ごとに 1 回実行します)。そのクラスの実行には JVM にインキュベータモジュールが必要です: `java --add-modules jdk.incubator.vector Prog`。`--simd` なしのクラスは任意の JVM でスカラー基準を実行します。**ブリッジが CPU のベクトル命令になるかどうかは、そのクラスを実行する JVM 次第です。** Vector API は普通のライブラリであり、JVM がそれをベクトル命令へ落とすかどうかは演算ごとに決まります。落とさない箇所ではレーンを 1 つずつエミュレートするため、`--simd` が置き換えたはずのスカラーループよりはるかに遅くなります。したがって JVM バックエンドでは `--simd` が自動的に得になるとは限らず、同じクラスが 2 つの JVM でまったく違う挙動を示しえます。デプロイ先の JVM で、自分のデータで計測してください。

JVM バックエンドが読みにくい理由はもうひとつあり、そちらは SIMD とは無関係です。コンパイルされた Lisp の数値ループは中間値をすべてボックス化します。配列要素の読み出しごと、積ごと、累算ごとに `Double` が 1 個、さらにループカウンタごとに `Long` が 1 個。つまりスカラーの `vec:` カーネルの律速は演算ではなく確保とディスパッチです。そのボックス化をどれだけ消去できるか(エスケープ解析とインライン化による)は JVM によって大きく異なり、まったく同じスカラーループが JVM を替えるだけで数倍速くなることもあります。`--simd` はこの問いを迂回します。カーネルを、そもそもボックス化しないプリミティブな `double[]` / `float[]` のループに置き換えるからです。
- **wasm-GC `--simd` ネイティブ `v128`**: `rontolisp prog.lisp -o prog.wasm --simd` は `vec:` カーネルを WebAssembly 固定幅 SIMD(`f64x2.*`、単精度では `f32x4.*`)にロワリングします。パックド浮動小数点配列はレーングループの `(array (mut v128))` になりますが、これも通常の GC オブジェクトであり、エンジンの GC で回収されます。メモリの挙動はスカラーの wasm-GC とまったく同じです。`vec:` API 全体(`vec:matvec` や `vec:from-list` / `vec:to-list` を含む)はそのまま動き、結果も変わりません。`--component` やすべての `--optimize` レベルとも併用できます。実行はこれまでどおり `wasmtime run -W gc` です(wasmtime は SIMD 提案を既定で有効にしています)。
- **`--no-gc --simd` ネイティブ `v128`**: `rontolisp prog.lisp -o prog.wasm --no-gc --simd` は同じカーネルを、パックドな線形メモリブロック上にロワリングします。**`--simd` なしの `--no-gc` は同じブロック上に素のスカラーループを出力します** — SIMD 提案を持たない WebAssembly ランタイムでも動く v128 非依存の MVP モジュールで、その移植性と引き換えにベクトル化による高速化を手放します。`vec:matvec` / `vec:matvec-into` は階数 2 のパックド行列ブロック(`[rows][cols][data]`、階数 2 の `make-array` が構築)上で動きます。行ごとの内積は `--simd` では `f64x2` / `f32x4` の内積ループ、なしではスカラーループです。`--no-gc` で利用できないまま残るのは `vec:from-list` / `vec:to-list`(Lisp リストが必要)だけです。`vec:exp` / `vec:log` / `vec:tanh` / `vec:sin` / `vec:cos` / `vec:tan` / `vec:asin` / `vec:acos` / `vec:atan` / `vec:sinh` / `vec:cosh` / `vec:sign` にはベクトル命令が存在しないため、どちらのモードでも同じ要素ごとのループで実行されます。`vec:clip` も同様です(境界は完全な double なので、各要素は拡張して比較されます)。`vec:maximum` / `vec:minimum` / `vec:relu` は `--simd` では比較マスクのセレクトとしてベクトル化され、なしではスカラーの比較 + セレクトのループになります。

wasm-GC で速度差が大きいのは、`--simd` が 2 つのものを同時に置き換えるからです。ボックス化の多いスカラー `vec.lisp` の defun と、1 要素ずつ回すループの両方です。8192 要素のベクトルに対する `vec:dot` を 20000 回反復すると、`wasmtime run -W gc` でスカラーは約 10.1 秒、`--simd` では約 0.10 秒です。

GC 配列からレーングループを読むと、線形メモリからの `v128.load` には無い境界検査が 1 回入り、これをループ外に巻き上げるエンジンは今のところありません。そのため同じカーネルループは、`--no-gc --simd` に比べて wasm-GC `--simd` では約 1.9 倍遅くなります。これがベクトルの管理を GC に任せる対価です。数値計算の内側ループがボトルネックで、ガベージコレクタ無しでも構わないなら、`--no-gc --simd` でコンパイルしてください。

リダクションは SIMD 下では異なる順序で加算するため、非正確な入力に対するリダクションは左から右へのスカラー基準と最終 ULP で異なることがあります。テストで典型的な正確な double に対しては結果は完全に一致します。要素ごとのカーネルは常にビット単位で同一です。

単精度のリダクションにはもう 1 つ注意があります。`--simd` のもとでは `#f` のリダクション — `vec:dot` / `vec:sum` / `vec:matvec` — は、どのバックエンドでも 4 レーンの単精度のまま累算し、最後の値だけを拡張します。一方スカラー基準は各要素を double として読み、double で累算します。したがって、単精度の累算器では保持しきれないデータに対しては、`--simd` は `#f` のリダクションを最終 ULP ではなく、おおよそ単精度のイプシロン程度だけ動かしえます。`--simd` のバックエンドはいずれも同じ方法で累算するので互いに一致し、スカラー基準の方が正確なままです。`#d` (`double-float`) のリダクションは影響を受けません。単精度のリダクションにスカラー基準と同じ正確さが必要なら、その計算には `#d` を使うか、`--simd` を外してください。

## linalg のアクセラレーション

[`linalg` パッケージ](linear-algebra.md)は同じパックド浮動小数点配列の上に書かれており、`--simd` はそのうち 34 個の関数を同じカーネルへ振り向けます。

- **直接加速される**: `add`・`sub`・`mul`・`div`・`sum`・`norm`・`amax`・`amin`・`argmax`・`argmin`・`trace`・`transpose`・`reshape`・`dot`・`outer`、単項 ufunc の `exp`・`log`・`tanh`・`sin`・`cos`・`tan`・`asin`・`acos`・`atan`・`sinh`・`cosh`・`sqrt`・`abs`・`negative`・`sign`、比較セレクトの `maximum`・`minimum`、および Deep Learning from Scratch の例を支える 2 つの内部畳み込みヘルパ(`linalg::%la-im2col` と `linalg::%la-col2im`。im2col のウィンドウ展開とその随伴で、レーンカーネルではなくインデックス演算のループですが、両方の幅でビット単位まで同一です)
- **一緒に加速される**: `mean`・`matmul`・`flatten`・`solve`・`square`・`reciprocal`・`clip`・`relu`。いずれも上の関数を使って書かれています
- **決して加速されない**: `emap`(要素ごとに任意の関数を適用するため)・`det`・`inv`・`array-equal`・各コンストラクタ

別のフラグはなく、関数ごとに何かを有効化する必要もありません。`--simd` を付けてコンパイルまたは実行すれば、`vec` とまったく同じように呼び出しが振り向けられます。

```lisp
(linalg:norm (linalg:sub #d(4.0 6.0) #d(1.0 2.0)))  ; => 5.0
(linalg:emap #'sqrt #d(1.0 4.0 9.0))                ; => #d(1.0 2.0 3.0)
```

`vec` が単一の幅のパックド配列だけを受け付けるのに対し、`linalg` が受け付けるものははるかに広く、`#(1 2 3)` のような一般(ボックス化)配列・幅の異なる 2 つの被演算子・素の数値・形状の異なる配列同士(numpy の規則でブロードキャストされます)・どのブロードキャストにも当てはまらない形状(エラーを通知します)を含みます。カーネルが扱うのは、パックドで幅が揃っている場合です。すなわち、等しい形状・どちらの側でもよいスカラー被演算子・形状は異なるがブロードキャスト可能な 2 つの配列(numpy のブロードキャストそのもの)・`transpose` の axes 形(`(linalg:transpose x '(0 3 1 2))`)・`sum` / `amax` / `amin` / `argmax` / `argmin` の `:axis` 形(整数の軸。負の軸も可、`:keepdims` の有無も問いません)です。**それ以外はすべて、移植可能な `linalg.lisp` の定義がまったく同じ引数値の上で実行されます** — 結果もブロードキャストもエラーメッセージも同一で、各引数フォームの評価はやはり 1 回だけです。したがって `--simd` は linalg プログラムが何を受け付け何を拒むかを一切変えません。よくある場合を速くするだけです。

上述の精度の規則はそのまま当てはまりますが、1 点だけ linalg に有利な例外があります。

- **要素ごとの演算は両方の幅でビット単位まで同一**です。相手が配列でもスカラーでも numpy のブロードキャスト越しでも `add` / `sub` / `mul` / `div` は同一であり、単項 ufunc の `exp` / `log` / `tanh` / `sin` / `cos` / `tan` / `asin` / `acos` / `atan` / `sinh` / `cosh` / `sqrt` / `abs` / `square` / `negative` / `sign` / `reciprocal` も、比較セレクトの `maximum` / `minimum` / `clip` / `relu`(セレクトは入力のビットをコピーするだけなので丸めが起きません)も、`transpose`(axes 形を含む)・`reshape`・`outer`・`trace`・`amax`・`amin`・`argmax`・`argmin` も同様です。
- **配列全体のリダクションは `vec` の規則に従います**。`sum`・`mean`・`norm`、および `dot` のベクトル形式と行列×ベクトル形式は加算順序が異なり、単精度配列に対しては単精度で累算します。
- **軸に沿ったリダクションはビット単位まで同一です。** `(linalg:sum a :axis 0)` や `(linalg:amax a :axis 1)` などの axis 形は、移植可能な定義とまったく同じに — double で、同じ順序で、同じタイブレーク規則で — 畳み込むので、配列全体のリダクションと違って食い違いようがありません。
- **完全な行列積は例外です。** 2 つの行列に対する `(linalg:dot A B)` と `linalg:matmul` は両方の幅で double で累算し、移植可能な定義とビット単位まで同一のままです。これらの行の背後にある規則はひとつです。累算器がレーンの要素型まで狭まるのは、レーンが総和を取る軸そのものであるときだけで、行列積ではレーンは出力行を横切って走ります。

`linalg` は `--simd` の有無にかかわらず `--no-gc` ではそもそもコンパイルできません。したがって上のターゲットの表の `--no-gc` の行は `vec` だけに関係します。

## 実行できる例

最小のものは [`examples/ml/simd-dot.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-dot.lisp) です。1024 個の double に対する `vec:dot` を 4000 回、それだけ。ベクトルは `0.0 .. 1023.0` を保持するので答えは厳密な整数であり、レーンをどう並べ替えても変わりません。`--simd` の有無で実行すると、動くのは経過時間だけです(インタプリタ 2.59 秒 -> 2.3 ms、wasm-GC 273 ms -> 2.4 ms)。

[`examples/ml/simd-gemv.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-gemv.lisp) は、アクセラレーションが存在する理由そのものである 2 つのカーネル -- `vec:matvec` と `vec:dot` -- だけを 100 回繰り返します。256x256 の単精度行列にベクトルを通し、二乗平均平方根が 1 になるようスケールし直し、また通す。この組は Transformer の射影と RMSNorm そのものであり、LLM の推論エンジンが時間のほとんどを費やす場所です。2 回実行してみてください:

```bash
rontolisp examples/ml/simd-gemv.lisp
rontolisp examples/ml/simd-gemv.lisp --simd
```

浮動小数点数ではなく整数の `argmax` の添字を表示するので、アクセラレーションの有無で出力は同一になります。変わるのは経過時間だけです。Apple M4 では wasm-GC が 467 ms -> 3.9 ms、インタプリタが 4.6 秒 -> 15 ms でした。

[`examples/ml/simd-gemv-nogc.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-gemv-nogc.lisp) は同じ内側のループを `--no-gc` でコンパイルしたものです。純粋な計算のリアクターモジュールで、ホストがエクスポートされた `fingerprint` 関数を呼び出し、`argmax` の整数を読み取ります。`--simd` の有無で両方ビルドして呼び出してみてください:

```bash
rontolisp examples/ml/simd-gemv-nogc.lisp -o gemv.wasm --no-gc --simd
wasmtime run --invoke fingerprint gemv.wasm 100
```

どちらのビルドも `85` を表示します(他のすべてのバックエンドと同じ支配方向です)。そして `-into` カーネルにより、何ステップ実行しても、解放されることのない `--no-gc` のバンプヒープはちょうど 3 ブロックのままです。20000 ステップではスカラーモジュールが約 600 ms、`--simd` が約 120 ms でした。

エンジン全体は [`examples/llama2/llama2.lisp`](https://github.com/making/rontolisp/blob/develop/examples/llama2/llama2.lisp) です。llama2.c の `run.c` を 1 ファイルに移植したもの -- チェックポイントローダ、トークナイザ、順伝播、サンプラ -- で、本物の TinyStories チェックポイントを読み込み、C プログラムと同じ物語をトークン単位で語ります。1500 万個の重みはパックド単精度配列への `read-sequence` で読み込まれ、デコードは 1 トークンあたり 100 回超の `vec:matvec` です。stories15M では `--simd` により JVM が 23 から 87 トークン/秒に、wasm-GC が 0.4 から 46 に上がります（`run.c -O2`: 65）。[その README](https://github.com/making/rontolisp/blob/develop/examples/llama2/README.md) を参照してください。

インタプリタと JVM のカーネルがベクトル化するには、1 行が 128 要素以上である必要があります。それ未満ではスカラーループを実行します。ベクトルレジスタを埋める方が高くつくからです。2 つの WASM バックエンドにこの閾値はありません。

## パッケージ

`vec` は `cl` を使いません。すべての関数は外部シンボルで、`vec:name` として参照します。エクスポート名を修飾なしで書くには、`(in-package :vec)`(または `(defpackage ... (:use :vec))`)を有効にしてください。関連する [`linalg` パッケージ](linear-algebra.md)は、同じ配列に対してより広い numpy 風の API(形状操作・行列積・厳密な線形代数)を提供します。

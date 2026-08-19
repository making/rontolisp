# ベクトルと行列（linalg）

`linalg` パッケージは、ベクタと行列のための numpy スタイルの API を提供します。コンストラクタ、形状操作、要素ごとの算術演算、積、集約、離散微積分（差分と数値微分）、線形代数（行列式、逆行列、連立一次方程式の求解）が含まれます。

JSON ライブラリと同様に、`linalg` は Lisp ソース（`linalg.lisp`）として一度だけ実装されています。インタプリタは `linalg:` 関数が最初に使われたときに定義を遅延ロードし、コンパイルパスはパッケージが参照されたときに定義をプログラムへ継ぎ足します。バックエンドごとのコードは存在しないため、すべての関数はインタプリタ、JVM コンパイラ、WASM Preview 1、WASM コンポーネントで同一に振る舞います。

## データ表現

linalg のコンストラクタは [packed float 配列](../reference/data-types.md) を作ります。これは `#d(...)` リテラルと同じ、アンボックスな `(array double-float)` です。ベクタはランク 1 の配列で `#d(1.0 2.0 ...)` と印字され、行列はランク 2 の配列でネストした `#d((...) ...)` 形式で印字されます。`#d` はアンボックスな packed 表現を表すため、その印字結果を読み戻すと packed 配列になります。個々の要素は `aref` で読み書きでき、プログラムの他の場所で構築された配列 (packed でも一般のボックス配列でも) も linalg 関数に渡せます。より高いランクの配列も扱えます。要素ごとの演算、リダクション、`reshape`/`flatten`、`array-equal` はフラットな行優先順で要素を走査するため任意のランクを受け付けます。一方 `dot`/`matmul`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` は numpy の専用ルーチンと同様、ベクタと行列 (ランク 2 以下) に対して定義されたままです。[`linalg:from-list`](../reference/functions/linalg-from-list.md) / [`linalg:to-list`](../reference/functions/linalg-to-list.md) は配列とリストを相互に変換します。

linalg は速度を優先して浮動小数点で計算します。すべてのコンストラクタと配列を生成する演算はデフォルトで packed double-float 配列を返し（単精度も利用できます。[単精度浮動小数点](#single-float-precision)を参照）、[`linalg:det`](../reference/functions/linalg-det.md)、[`linalg:inv`](../reference/functions/linalg-inv.md)、[`linalg:solve`](../reference/functions/linalg-solve.md) は (numpy と同様に) 浮動小数点で計算されるため、一般の逆行列には通常の丸めが生じ、ほぼ特異な行列式は厳密な `0` ではなく微小値になることがあります。リダクションは numpy と同様に要素の型に従います。packed または float 配列に対するリダクションは double を、素の整数配列 (`#(1 2 3)` のようなリテラル) に対するリダクションは整数または厳密な比を返します。[`linalg:norm`](../reference/functions/linalg-norm.md) は `sqrt` が浮動小数点数を返すため常に浮動小数点数です。クロスバックエンドの注意点が 1 つあります。WASM バックエンドは非終端の浮動小数点数をインタプリタや JVM より少ない有効桁数で印字するため、丸めのある逆行列や無理数のノルムは、内部の `double` が同一でもバックエンド間で見た目が異なることがあります。

## 実例

```lisp
(linalg:eye 3)                          ; => #d((1.0 0.0 0.0) (0.0 1.0 0.0) (0.0 0.0 1.0))
(linalg:arange 5)                       ; => #d(0.0 1.0 2.0 3.0 4.0)
(linalg:linspace 0 1 5)                 ; => #d(0.0 0.25 0.5 0.75 1.0)
(linalg:matmul #2A((1 2) (3 4))
               #2A((5 6) (7 8)))        ; => #d((19.0 22.0) (43.0 50.0))
(linalg:det #2A((1 2) (3 4)))           ; => -2.0
(linalg:inv #2A((4 0) (2 4)))           ; => #d((0.25 0.0) (-0.125 0.25))
(linalg:solve #2A((4 0) (2 4)) #(8 8))  ; => #d(2.0 1.0)
```

上記の `inv` と `solve` の行列は、float の結果が厳密になりどのバックエンドでも同一に印字されるよう選んでいます。`(linalg:inv #2A((1 2) (3 4)))` のような一般の逆行列は同じ値を計算しますが浮動小数点の丸めを伴います。

`la` は `linalg` の組み込みニックネームです。すべての `linalg:` 呼び出しは、より短い `la:` プレフィックスでも記述できます。

```lisp
(la:arange 5) ; => #d(0.0 1.0 2.0 3.0 4.0)
```

## 要素ごとの算術演算とブロードキャスト

[`linalg:add`](../reference/functions/linalg-add.md)、[`linalg:sub`](../reference/functions/linalg-sub.md)、[`linalg:mul`](../reference/functions/linalg-mul.md)、[`linalg:div`](../reference/functions/linalg-div.md) は要素ごとに演算し、numpy の規則でブロードキャストします: どちらか一方のスカラーのオペランドはもう一方のオペランドの形状にブロードキャストされ、形状の異なる 2 つの配列は末尾の軸から揃えられます -- 揃えた各軸の長さは等しいか、どちらかが 1 でなければならず（先頭側の欠けている軸は 1 と見なされます）、長さ 1 の軸がもう一方の長さに引き伸ばされます。どちらにも当てはまらないペアは shape mismatch エラーを通知します。結果は最初の配列オペランドの要素型を保持します（混合幅の規則と同じ）。`mul` はアダマール積（要素ごとの積）であることに注意してください。行列積は [`linalg:matmul`](../reference/functions/linalg-matmul.md)（またはランクに応じてディスパッチする [`linalg:dot`](../reference/functions/linalg-dot.md)）です。要素ごとの任意の変換には [`linalg:emap`](../reference/functions/linalg-emap.md) を使います。

この 4 つには CL 演算子スペル [`linalg:+`](../reference/functions/linalg-plus.md)・[`linalg:-`](../reference/functions/linalg-minus.md)・[`linalg:*`](../reference/functions/linalg-star.md)・[`linalg:/`](../reference/functions/linalg-slash.md) もあります。これらは `add` / `sub` / `mul` / `div` の可変長引数の左畳み込みなので、`(linalg:+ a b c)` は段階的にブロードキャストし、各段階は加速されたカーネルのままです。引数が退化した場合は CL に従います: 引数なしは単位元(`0` / `1`)、`+` と `*` の引数 1 つはその引数自身、`-` と `/` の引数 1 つは符号反転 / 逆数です。([`vec:`](simd-acceleration.md) パッケージの演算子別名は代わりに厳密に 2 引数です。あちらのカーネルは設計上すべて固定アリティだからです。)

よく使う要素ごとの演算は、numpy の ufunc 名でも用意されています: [`linalg:exp`](../reference/functions/linalg-exp.md)・[`linalg:log`](../reference/functions/linalg-log.md)・[`linalg:tanh`](../reference/functions/linalg-tanh.md)・[`linalg:sin`](../reference/functions/linalg-sin.md)・[`linalg:cos`](../reference/functions/linalg-cos.md)・[`linalg:tan`](../reference/functions/linalg-tan.md)・[`linalg:asin`](../reference/functions/linalg-asin.md)・[`linalg:acos`](../reference/functions/linalg-acos.md)・[`linalg:atan`](../reference/functions/linalg-atan.md)・[`linalg:sinh`](../reference/functions/linalg-sinh.md)・[`linalg:cosh`](../reference/functions/linalg-cosh.md)・[`linalg:sqrt`](../reference/functions/linalg-sqrt.md)・[`linalg:abs`](../reference/functions/linalg-abs.md)・[`linalg:square`](../reference/functions/linalg-square.md)・[`linalg:negative`](../reference/functions/linalg-negative.md)・[`linalg:sign`](../reference/functions/linalg-sign.md)・[`linalg:reciprocal`](../reference/functions/linalg-reciprocal.md)、さらに比較セレクトの [`linalg:maximum`](../reference/functions/linalg-maximum.md)・[`linalg:minimum`](../reference/functions/linalg-minimum.md)・[`linalg:clip`](../reference/functions/linalg-clip.md)・[`linalg:relu`](../reference/functions/linalg-relu.md)(厳密比較 `(if (> x y) x y)` とその鏡像で定義され、比較が偽なら第 2 被演算子または境界が選ばれます — タイや `NaN` を含め、すべてのバックエンドで同一の規則です)。いずれも対応する `emap`(または `mul` / `div` / `maximum` / `minimum` の呼び出し)と等価ですが、名前付き関数なので [`--simd`](simd-acceleration.md#accelerating-linalg) で加速されます。任意のコールバックを取る `emap` は決して加速されません。

```lisp
(linalg:add #(1 2 3) 10)        ; => #d(11.0 12.0 13.0)
(linalg:mul 2 #2A((1 2) (3 4))) ; => #d((2.0 4.0) (6.0 8.0))
(linalg:div #(1 2 3) 2)         ; => #d(0.5 1.0 1.5)
(linalg:sqrt #(4 9 16))         ; => #d(2.0 3.0 4.0)
(linalg:square #2A((1 2) (3 4))) ; => #d((1.0 4.0) (9.0 16.0))
(linalg:mul #2A((1 2) (3 4)) #(10 20))       ; => #d((10.0 40.0) (30.0 80.0))
(linalg:add #2A((1 2) (3 4)) #2A((100) (200))) ; => #d((101.0 102.0) (203.0 204.0))
(linalg:+ #(1 2) #(3 4) #(10 10))            ; => #d(14.0 16.0)
(linalg:- #(5 5))                            ; => #d(-5.0 -5.0)
```

## 軸に沿った還元

還元関数 [`linalg:sum`](../reference/functions/linalg-sum.md)、[`linalg:mean`](../reference/functions/linalg-mean.md)、[`linalg:amax`](../reference/functions/linalg-amax.md)、[`linalg:amin`](../reference/functions/linalg-amin.md) は numpy と同じキーワード引数を取ります。整数の `:axis`(負は numpy 流に末尾から数える)を渡すと配列全体ではなくその軸に沿って還元し、結果からその軸は除去されますが、`:keepdims` が非 nil のときは長さ 1 の軸として保持されます — 入力にそのままブロードキャストで戻せる形状で、バッチ softmax が行ごとの最大値を引くのに使うのはこの形です。[`linalg:argmax`](../reference/functions/linalg-argmax.md) と [`linalg:argmin`](../reference/functions/linalg-argmin.md) も同じ `:axis` キーワードを取り、スライスごとの index を返します(行列に対しては packed double 配列 — linalg 配列に整数幅はありません)。[`linalg:reshape`](../reference/functions/linalg-reshape.md) は 1 つの `-1` extent を受け付け、要素数から推論します。

```lisp
(linalg:sum #2A((1 2 3) (4 5 6)) :axis 0)                  ; => #d(5.0 7.0 9.0)
(linalg:sum #2A((1 2 3) (4 5 6)) :axis 1)                  ; => #d(6.0 15.0)
(linalg:sum #2A((1 2 3) (4 5 6)) :axis -1 :keepdims t)     ; => #d((6.0) (15.0))
(linalg:mean #2A((1 2 3) (4 5 6)) :axis 0)                 ; => #d(2.5 3.5 4.5)
(linalg:argmax #2A((1 9 3) (7 5 6)) :axis 1)               ; => #d(1.0 0.0)
(linalg:shape (linalg:reshape (linalg:arange 12) '(3 -1))) ; => (3 4)
```

## インデックス操作・選択・マスク

[`linalg:take-rows`](../reference/functions/linalg-take-rows.md) は index ベクタで axis-0 スライスを選択し(numpy の `x[mask]`、任意 rank)、axis 0 を残します。一方 [`linalg:row`](../reference/functions/linalg-row.md) は整数で 1 スライスだけ取り出し、axis 0 を落とします(numpy の `x[i]`。バッチから 1 枚の画像を取り出すと、そのままベクタとして順伝播に渡せます)。[`linalg:gather`](../reference/functions/linalg-gather.md) は行ごとに 1 要素を取り出し(`y[np.arange(n), t]`)、[`linalg:one-hot`](../reference/functions/linalg-one-hot.md) はラベル行列を作ります。要素ごとの比較 [`linalg:equal`](../reference/functions/linalg-equal.md)、[`linalg:greater`](../reference/functions/linalg-greater.md)、[`linalg:greater-equal`](../reference/functions/linalg-greater-equal.md)、[`linalg:less`](../reference/functions/linalg-less.md)、[`linalg:less-equal`](../reference/functions/linalg-less-equal.md) は 0.0/1.0 のマスクを返します(スカラー被演算子とブロードキャスト対応)。numpy が boolean index する場面では、マスクを掛け算してください。[`linalg:zeros-like`](../reference/functions/linalg-zeros-like.md) は同じ形状・同じ幅のゼロ配列を確保します。

```lisp
(linalg:take-rows #2A((10 11) (20 21) (30 31)) #(2 0)) ; => #d((30.0 31.0) (10.0 11.0))
(linalg:row #2A((10 11) (20 21) (30 31)) 2)            ; => #d(30.0 31.0)
(linalg:gather #2A((10 11 12) (20 21 22)) #(2 0))      ; => #d(12.0 20.0)
(linalg:one-hot #(1 0) 3)   ; => #d((0.0 1.0 0.0) (1.0 0.0 0.0))
(linalg:greater #(1 5 3) 2) ; => #d(0.0 1.0 1.0)
```

## 乱数

`np.random` に相当する乱数はシード可能で、バックエンド間で決定的です。[`linalg:seed`](../reference/functions/linalg-seed.md) は Wichmann-Hill 生成器をリセットし、その draw は正確な整数演算と IEEE double 演算だけで構成されるため、シード済みの [`linalg:rand`](../reference/functions/linalg-rand.md)、[`linalg:randn`](../reference/functions/linalg-randn.md)、[`linalg:uniform`](../reference/functions/linalg-uniform.md)、[`linalg:choice`](../reference/functions/linalg-choice.md)、[`linalg:permutation`](../reference/functions/linalg-permutation.md) の列は interpreter・JVM・両 WASM ターゲットで bit-identical です — 重み初期化とミニバッチ抽出がどこでも正確に再現されます。`randn` は Box-Muller ではなく Irwin-Hall(一様乱数 12 個の和)を使います(Box-Muller の `log`/`cos` は WASM で発散するため)。そのため裾は 6σ でクリップされます。初期化には十分ですが、分布が `np.random.randn` と厳密に一致するわけではありません。

```lisp
(linalg:seed 42)         ; => 42
(linalg:choice 60000 4)  ; => #d(26833.0 11120.0 29256.0 22347.0)
(linalg:permutation 5)   ; => #d(0.0 4.0 2.0 3.0 1.0)
```

## 離散微積分

[`linalg:diff`](../reference/functions/linalg-diff.md) と [`linalg:gradient`](../reference/functions/linalg-gradient.md) は numpy の離散微積分ペア(`np.diff` / `np.gradient`)です。`diff` は `:axis`(デフォルトは最後の軸)に沿った `:n` 階の離散差分(デフォルト 1)を取ります。1 ステップごとにその軸が 1 つ短くなり、行列はデフォルトでは各行内で、`:axis 0` では各列に沿って差分されます。`gradient` はサンプル値のベクタの微分を、2 次精度の中心差分(両端は 1 次精度の片側差分)で推定するため、結果は入力と同じ長さになります。省略可能な第 2 引数には、一様なサンプル間隔(数値、デフォルト 1)か、非一様なサンプルのための同じ長さの座標ベクタを渡せます。どちらも他の linalg 変換と同様に入力の幅を保持します。算術は通常どおり浮動小数点ですが、厳密に微分できるサンプル値 — 以下の例のような、整数座標で読んだ多項式 — はすべてのバックエンドで同一に印字されます。

```lisp
(linalg:diff #(1 2 4 7 0))          ; => #d(1.0 2.0 3.0 -7.0)
(linalg:diff #(1 2 4 7 0) :n 2)     ; => #d(1.0 1.0 -10.0)
(linalg:diff #2A((1 3 6) (0 5 6)))  ; => #d((2.0 3.0) (5.0 1.0))
(linalg:gradient #(0 1 4 9 16))     ; => #d(1.0 2.0 4.0 6.0 7.0)
(linalg:gradient #(0 1 4 9 16) 2)   ; => #d(0.5 1.0 2.0 3.0 3.5)
(linalg:gradient #(0 1 9) #(0 1 3)) ; => #d(1.0 2.0 4.0)
```

`#(0 1 4 9 16)` の gradient — 放物線 `y = x^2` を `x = 0..4` でサンプリングしたもの — は、内部の点で真の導関数 `2x` を厳密に復元します(中心差分は 2 次関数に対して厳密で、両端は 1 次精度の推定です)。座標ベクタ形式なら、最後の行のような不等間隔のサンプルでも厳密なままです。[`examples/ml/numerical-calculus.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/numerical-calculus.lisp) は、これらの考え方を投射体の運動の例で一通り示します。

## 単精度浮動小数点

linalg はデフォルトで `double-float` で計算しますが、**幅多相 (width-polymorphic)** です。メモリが半分で SIMD レーン数が倍になる packed **単精度浮動小数点** (`#f`) 配列を受け付け、その幅を保持します。すべてのコンストラクタは `:element-type` キーワードを取り（デフォルトは `'double-float`。`#f` の結果が欲しければ `:element-type 'single-float` を渡します）、すべての変換 -- `add`/`sub`/`mul`/`div`/`emap`、`transpose`/`reshape`、`dot`/`matmul`/`outer`、`inv`/`solve` -- は入力の幅を保持します。したがって単精度の値は最後まで単精度のまま流れます。関数的な重み更新 `(linalg:sub W grad)` は `W` の幅を保持し、暗黙に double へ戻す（JVM の [`--simd`](simd-acceleration.md) パスでは、続く `vec:matvec` で幅不一致エラーを強制する）ことはありません。`f32` の速度とメモリが欲しく精度の低下を許容できるときは単精度を使い、`det`/`inv`/`solve` のような精度が重要な処理にはデフォルトの倍精度を使ってください。

```lisp
(linalg:zeros 3 :element-type 'single-float)                   ; => #f(0.0 0.0 0.0)
(linalg:from-list '((1 2) (3 4)) :element-type 'single-float)  ; => #f((1.0 2.0) (3.0 4.0))
(linalg:add (linalg:from-list '(1 2 3) :element-type 'single-float) 10) ; => #f(11.0 12.0 13.0)
(array-element-type
  (linalg:transpose (linalg:eye 2 :element-type 'single-float)))        ; => SINGLE-FLOAT
```

## SIMD アクセラレーション

`linalg` はどこでもフラグなしで正しく動きますが、[`--simd` フラグ](simd-acceleration.md)で加速されます。32 の関数 — `add`・`sub`・`mul`・`div`・`sum`・`norm`・`amax`・`amin`・`argmax`・`argmin`・`trace`・`transpose`・`reshape`・`dot`・`outer`、単項 ufunc の `exp`・`log`・`tanh`・`sin`・`cos`・`tan`・`asin`・`acos`・`atan`・`sinh`・`cosh`・`sqrt`・`abs`・`negative`・`sign`、および比較セレクトの `maximum`・`minimum` — がネイティブなベクトルカーネル(インタプリタと JVM では `jdk.incubator.vector`、wasm-GC では WebAssembly の `v128`)にルーティングされ、それらを使って書かれている `mean`・`matmul`・`flatten`・`solve`・`square`・`reciprocal`・`clip`・`relu` も一緒に加速されます。プログラムが何を受け付け何を拒否するかは一切変わりません。カーネルが扱えない入力(一般の boxed 配列、幅の混在、素の数値)は移植可能な `linalg.lisp` の定義がそのまま実行され、同じ結果と同じエラーメッセージになります。観測可能な違いは[単精度リダクションの精度規則](simd-acceleration.md#accelerating-linalg)だけで、要素ごとの結果と完全な行列積はビット一致のままです。

速度のためにパッケージを乗り換える理由はありません。`--simd` の下では `linalg` と `vec` は同じカーネルに行き着きます。[vec と linalg の使い分け](simd-acceleration.md#choosing-between-vec-and-linalg)を参照してください — 要約すると: デフォルトでは `linalg` に対して書き、`vec` に手を伸ばすのは `-into` の書き込み先渡しループ・`--no-gc` ターゲット(`linalg` はそこではコンパイルできません)・fail-fast な幅の厳格さが必要なときだけです。

## 第一級関数

linalg の関数は通常の `defun` なので、`#'linalg:norm` などは関数が期待されるあらゆる場所で第一級の値として動作します。

```lisp
(mapcar #'linalg:norm (list #(3 4) #(6 8))) ; => (5.0 10.0)
```

配列は同一性 (`eq`) でしか比較されないため、結果の比較には形状と数値の等価性を検査する [`linalg:array-equal`](../reference/functions/linalg-array-equal.md) を使います（`1` と `1.0` は等しいと判定されます）。

## パッケージ

`linalg` はそれ自体が独立した[パッケージ](../reference/packages.md)であり、`cl` を use していません。`(in-package linalg)` の中では標準関数は非修飾の名前では見えず、`cl:` 修飾（`cl:print`、`cl:mapcar` など）が必要になります。したがってほとんどのプログラムは、このページのすべての例がそうしているように、デフォルトの `cl-user` パッケージにとどまり、修飾された `linalg:` 名で呼び出すべきです。

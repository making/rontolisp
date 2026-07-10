# ベクトルと行列（linalg）

`linalg` パッケージは、ベクタと行列のための numpy スタイルの API を提供します。コンストラクタ、形状操作、要素ごとの算術演算、積、集約、線形代数（行列式、逆行列、連立一次方程式の求解）が含まれます。

JSON ライブラリと同様に、`linalg` は Lisp ソース（`linalg.lisp`）として一度だけ実装されています。インタプリタは `linalg:` 関数が最初に使われたときに定義を遅延ロードし、コンパイルパスはパッケージが参照されたときに定義をプログラムへ継ぎ足します。バックエンドごとのコードは存在しないため、すべての関数はインタプリタ、JVM コンパイラ、WASM Preview 1、WASM コンポーネントで同一に振る舞います。

## データ表現

linalg のコンストラクタは [packed float 配列](../reference/data-types.md) を作ります。これは `#d(...)` リテラルと同じ、アンボックスな `(array double-float)` です。ベクタはランク 1 の配列で `#d(1.0 2.0 ...)` と印字され、行列はランク 2 の配列でネストした `#d((...) ...)` 形式で印字されます。`#d` はアンボックスな packed 表現を表すため、その印字結果を読み戻すと packed 配列になります。個々の要素は `aref` で読み書きでき、プログラムの他の場所で構築された配列 (packed でも一般のボックス配列でも) も linalg 関数に渡せます。より高いランクの配列も扱えます。要素ごとの演算、リダクション、`reshape`/`flatten`、`array-equal` はフラットな行優先順で要素を走査するため任意のランクを受け付けます。一方 `dot`/`matmul`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` は numpy の専用ルーチンと同様、ベクタと行列 (ランク 2 以下) に対して定義されたままです。[`linalg:from-list`](../reference/functions/linalg-from-list.md) / [`linalg:to-list`](../reference/functions/linalg-to-list.md) は配列とリストを相互に変換します。

linalg は速度を優先して浮動小数点で計算します。すべてのコンストラクタと配列を生成する演算はデフォルトで packed double-float 配列を返し（単精度も利用できます。[単精度浮動小数点](#単精度浮動小数点)を参照）、[`linalg:det`](../reference/functions/linalg-det.md)、[`linalg:inv`](../reference/functions/linalg-inv.md)、[`linalg:solve`](../reference/functions/linalg-solve.md) は (numpy と同様に) 浮動小数点で計算されるため、一般の逆行列には通常の丸めが生じ、ほぼ特異な行列式は厳密な `0` ではなく微小値になることがあります。リダクションは numpy と同様に要素の型に従います。packed または float 配列に対するリダクションは double を、素の整数配列 (`#(1 2 3)` のようなリテラル) に対するリダクションは整数または厳密な比を返します。[`linalg:norm`](../reference/functions/linalg-norm.md) は `sqrt` が浮動小数点数を返すため常に浮動小数点数です。クロスバックエンドの注意点が 1 つあります。WASM バックエンドは非終端の浮動小数点数をインタプリタや JVM より少ない有効桁数で印字するため、丸めのある逆行列や無理数のノルムは、内部の `double` が同一でもバックエンド間で見た目が異なることがあります。

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

## 要素ごとの算術演算とブロードキャスト

[`linalg:add`](../reference/functions/linalg-add.md)、[`linalg:sub`](../reference/functions/linalg-sub.md)、[`linalg:mul`](../reference/functions/linalg-mul.md)、[`linalg:div`](../reference/functions/linalg-div.md) は要素ごとに演算し、どちらか一方のスカラーのオペランドはもう一方のオペランドの形状にブロードキャストされます。2 つの配列オペランドは同じ形状でなければなりません。`mul` はアダマール積（要素ごとの積）であることに注意してください。行列積は [`linalg:matmul`](../reference/functions/linalg-matmul.md)（またはランクに応じてディスパッチする [`linalg:dot`](../reference/functions/linalg-dot.md)）です。要素ごとの任意の変換には [`linalg:emap`](../reference/functions/linalg-emap.md) を使います。

よく使う要素ごとの演算は、numpy の ufunc 名でも用意されています: [`linalg:exp`](../reference/functions/linalg-exp.md)・[`linalg:log`](../reference/functions/linalg-log.md)・[`linalg:tanh`](../reference/functions/linalg-tanh.md)・[`linalg:sin`](../reference/functions/linalg-sin.md)・[`linalg:cos`](../reference/functions/linalg-cos.md)・[`linalg:tan`](../reference/functions/linalg-tan.md)・[`linalg:sqrt`](../reference/functions/linalg-sqrt.md)・[`linalg:abs`](../reference/functions/linalg-abs.md)・[`linalg:square`](../reference/functions/linalg-square.md)・[`linalg:negative`](../reference/functions/linalg-negative.md)・[`linalg:sign`](../reference/functions/linalg-sign.md)・[`linalg:reciprocal`](../reference/functions/linalg-reciprocal.md)。いずれも対応する `emap`(または `mul` / `div` の呼び出し)と等価ですが、名前付き関数なので [`--simd`](simd-acceleration.md#linalg-のアクセラレーション) で加速されます。任意のコールバックを取る `emap` は決して加速されません。

```lisp
(linalg:add #(1 2 3) 10)        ; => #d(11.0 12.0 13.0)
(linalg:mul 2 #2A((1 2) (3 4))) ; => #d((2.0 4.0) (6.0 8.0))
(linalg:div #(1 2 3) 2)         ; => #d(0.5 1.0 1.5)
(linalg:sqrt #(4 9 16))         ; => #d(2.0 3.0 4.0)
(linalg:square #2A((1 2) (3 4))) ; => #d((1.0 4.0) (9.0 16.0))
```

## 単精度浮動小数点

linalg はデフォルトで `double-float` で計算しますが、**幅多相 (width-polymorphic)** です。メモリが半分で SIMD レーン数が倍になる packed **単精度浮動小数点** (`#f`) 配列を受け付け、その幅を保持します。すべてのコンストラクタは末尾に省略可能な `element-type` を取り（デフォルトは `'double-float`。`#f` の結果が欲しければ `'single-float` を渡します）、すべての変換 -- `add`/`sub`/`mul`/`div`/`emap`、`transpose`/`reshape`、`dot`/`matmul`/`outer`、`inv`/`solve` -- は入力の幅を保持します。したがって単精度の値は最後まで単精度のまま流れます。関数的な重み更新 `(linalg:sub W grad)` は `W` の幅を保持し、暗黙に double へ戻す（JVM の [`--simd`](simd-acceleration.md) パスでは、続く `vec:matvec` で幅不一致エラーを強制する）ことはありません。`f32` の速度とメモリが欲しく精度の低下を許容できるときは単精度を使い、`det`/`inv`/`solve` のような精度が重要な処理にはデフォルトの倍精度を使ってください。

```lisp
(linalg:zeros 3 'single-float)                   ; => #f(0.0 0.0 0.0)
(linalg:from-list '((1 2) (3 4)) 'single-float)  ; => #f((1.0 2.0) (3.0 4.0))
(linalg:add (linalg:from-list '(1 2 3) 'single-float) 10) ; => #f(11.0 12.0 13.0)
(array-element-type
  (linalg:transpose (linalg:eye 2 'single-float)))        ; => single-float
```

## SIMD アクセラレーション

`linalg` はどこでもフラグなしで正しく動きますが、[`--simd` フラグ](simd-acceleration.md)で加速されます。20 の関数 — `add`・`sub`・`mul`・`div`・`sum`・`norm`・`amax`・`amin`・`argmax`・`argmin`・`trace`・`transpose`・`reshape`・`dot`・`outer`、および単項 ufunc の `exp`・`sqrt`・`abs`・`negative`・`sign` — がネイティブなベクトルカーネル(インタプリタと JVM では `jdk.incubator.vector`、wasm-GC では WebAssembly の `v128`)にルーティングされ、それらを使って書かれている `mean`・`matmul`・`flatten`・`solve`・`square`・`reciprocal` も一緒に加速されます。プログラムが何を受け付け何を拒否するかは一切変わりません。カーネルが扱えない入力(一般の boxed 配列、幅の混在、素の数値)は移植可能な `linalg.lisp` の定義がそのまま実行され、同じ結果と同じエラーメッセージになります。観測可能な違いは[単精度リダクションの精度規則](simd-acceleration.md#linalg-のアクセラレーション)だけで、要素ごとの結果と完全な行列積はビット一致のままです。

速度のためにパッケージを乗り換える理由はありません。`--simd` の下では `linalg` と `vec` は同じカーネルに行き着きます。[vec と linalg の使い分け](simd-acceleration.md#vec-と-linalg-の使い分け)を参照してください — 要約すると: デフォルトでは `linalg` に対して書き、`vec` に手を伸ばすのは `-into` の書き込み先渡しループ・`--no-gc` ターゲット(`linalg` はそこではコンパイルできません)・fail-fast な幅の厳格さが必要なときだけです。

## 第一級関数

linalg の関数は通常の `defun` なので、`#'linalg:norm` などは関数が期待されるあらゆる場所で第一級の値として動作します。

```lisp
(mapcar #'linalg:norm (list #(3 4) #(6 8))) ; => (5.0 10.0)
```

配列は同一性 (`eq`) でしか比較されないため、結果の比較には形状と数値の等価性を検査する [`linalg:array-equal`](../reference/functions/linalg-array-equal.md) を使います（`1` と `1.0` は等しいと判定されます）。

## パッケージ

`linalg` はそれ自体が独立した[パッケージ](../reference/packages.md)であり、`cl` を use していません。`(in-package linalg)` の中では標準関数は非修飾の名前では見えず、`cl:` 修飾（`cl:print`、`cl:mapcar` など）が必要になります。したがってほとんどのプログラムは、このページのすべての例がそうしているように、デフォルトの `cl-user` パッケージにとどまり、修飾された `linalg:` 名で呼び出すべきです。

# ベクトルと行列（linalg）

`linalg` パッケージは、ベクタと行列のための numpy スタイルの API を提供します。コンストラクタ、形状操作、要素ごとの算術演算、積、集約、線形代数（行列式、逆行列、連立一次方程式の求解）が含まれます。

JSON ライブラリと同様に、`linalg` は Lisp ソース（`linalg.lisp`）として一度だけ実装されています。インタプリタは `linalg:` 関数が最初に使われたときに定義を遅延ロードし、コンパイルパスはパッケージが参照されたときに定義をプログラムへ継ぎ足します。バックエンドごとのコードは存在しないため、すべての関数はインタプリタ、JVM コンパイラ、WASM Preview 1、WASM コンポーネントで同一に振る舞います。

## データ表現

linalg のコンストラクタは [packed float 配列](../reference/data-types.md) を作ります。これは `#f(...)` リテラルと同じ、アンボックスな `(array double-float)` です。ベクタはランク 1 の配列で `#f(1.0 2.0 ...)` と印字され、行列はランク 2 の配列でネストした `#f((...) ...)` 形式で印字されます。`#f` はアンボックスな packed 表現を表すため、その印字結果を読み戻すと packed 配列になります。個々の要素は `aref` で読み書きでき、プログラムの他の場所で構築された配列 (packed でも一般のボックス配列でも) も linalg 関数に渡せます。より高いランクの配列も扱えます。要素ごとの演算、リダクション、`reshape`/`flatten`、`array-equal` はフラットな行優先順で要素を走査するため任意のランクを受け付けます。一方 `dot`/`matmul`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` は numpy の専用ルーチンと同様、ベクタと行列 (ランク 2 以下) に対して定義されたままです。[`linalg:from-list`](../reference/functions/linalg-from-list.md) / [`linalg:to-list`](../reference/functions/linalg-to-list.md) は配列とリストを相互に変換します。

linalg は速度を優先して浮動小数点で計算します。すべてのコンストラクタと配列を生成する演算は packed double-float 配列を返し、[`linalg:det`](../reference/functions/linalg-det.md)、[`linalg:inv`](../reference/functions/linalg-inv.md)、[`linalg:solve`](../reference/functions/linalg-solve.md) は (numpy と同様に) 浮動小数点で計算されるため、一般の逆行列には通常の丸めが生じ、ほぼ特異な行列式は厳密な `0` ではなく微小値になることがあります。リダクションは numpy と同様に要素の型に従います。packed または float 配列に対するリダクションは double を、素の整数配列 (`#(1 2 3)` のようなリテラル) に対するリダクションは整数または厳密な比を返します。[`linalg:norm`](../reference/functions/linalg-norm.md) は `sqrt` が浮動小数点数を返すため常に浮動小数点数です。クロスバックエンドの注意点が 1 つあります。WASM バックエンドは非終端の浮動小数点数をインタプリタや JVM より少ない有効桁数で印字するため、丸めのある逆行列や無理数のノルムは、内部の `double` が同一でもバックエンド間で見た目が異なることがあります。

## 実例

```lisp
(linalg:eye 3)                          ; => #f((1.0 0.0 0.0) (0.0 1.0 0.0) (0.0 0.0 1.0))
(linalg:arange 5)                       ; => #f(0.0 1.0 2.0 3.0 4.0)
(linalg:linspace 0 1 5)                 ; => #f(0.0 0.25 0.5 0.75 1.0)
(linalg:matmul #2A((1 2) (3 4))
               #2A((5 6) (7 8)))        ; => #f((19.0 22.0) (43.0 50.0))
(linalg:det #2A((1 2) (3 4)))           ; => -2.0
(linalg:inv #2A((4 0) (2 4)))           ; => #f((0.25 0.0) (-0.125 0.25))
(linalg:solve #2A((4 0) (2 4)) #(8 8))  ; => #f(2.0 1.0)
```

上記の `inv` と `solve` の行列は、float の結果が厳密になりどのバックエンドでも同一に印字されるよう選んでいます。`(linalg:inv #2A((1 2) (3 4)))` のような一般の逆行列は同じ値を計算しますが浮動小数点の丸めを伴います。

## 要素ごとの算術演算とブロードキャスト

[`linalg:add`](../reference/functions/linalg-add.md)、[`linalg:sub`](../reference/functions/linalg-sub.md)、[`linalg:mul`](../reference/functions/linalg-mul.md)、[`linalg:div`](../reference/functions/linalg-div.md) は要素ごとに演算し、どちらか一方のスカラーのオペランドはもう一方のオペランドの形状にブロードキャストされます。2 つの配列オペランドは同じ形状でなければなりません。`mul` はアダマール積（要素ごとの積）であることに注意してください。行列積は [`linalg:matmul`](../reference/functions/linalg-matmul.md)（またはランクに応じてディスパッチする [`linalg:dot`](../reference/functions/linalg-dot.md)）です。要素ごとの任意の変換には [`linalg:emap`](../reference/functions/linalg-emap.md) を使います。

```lisp
(linalg:add #(1 2 3) 10)        ; => #f(11.0 12.0 13.0)
(linalg:mul 2 #2A((1 2) (3 4))) ; => #f((2.0 4.0) (6.0 8.0))
(linalg:div #(1 2 3) 2)         ; => #f(0.5 1.0 1.5)
```

## 第一級関数

linalg の関数は通常の `defun` なので、`#'linalg:norm` などは関数が期待されるあらゆる場所で第一級の値として動作します。

```lisp
(mapcar #'linalg:norm (list #(3 4) #(6 8))) ; => (5.0 10.0)
```

配列は同一性 (`eq`) でしか比較されないため、結果の比較には形状と数値の等価性を検査する [`linalg:array-equal`](../reference/functions/linalg-array-equal.md) を使います（`1` と `1.0` は等しいと判定されます）。

## パッケージ

`linalg` はそれ自体が独立した[パッケージ](../reference/packages.md)であり、`cl` を use していません。`(in-package linalg)` の中では標準関数は非修飾の名前では見えず、`cl:` 修飾（`cl:print`、`cl:mapcar` など）が必要になります。したがってほとんどのプログラムは、このページのすべての例がそうしているように、デフォルトの `cl-user` パッケージにとどまり、修飾された `linalg:` 名で呼び出すべきです。

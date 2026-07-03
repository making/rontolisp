# ベクトルと行列（linalg）

`linalg` パッケージは、ベクタと行列のための numpy スタイルの API を提供します。コンストラクタ、形状操作、要素ごとの算術演算、積、集約、厳密な線形代数（行列式、逆行列、連立一次方程式の求解）が含まれます。

JSON ライブラリと同様に、`linalg` は Lisp ソース（`linalg.lisp`）として一度だけ実装されています。インタプリタは `linalg:` 関数が最初に使われたときに定義を遅延ロードし、コンパイルパスはパッケージが参照されたときに定義をプログラムへ継ぎ足します。バックエンドごとのコードは存在しないため、すべての関数はインタプリタ、JVM コンパイラ、WASM Preview 1、WASM コンポーネントで同一に振る舞います。

## データ表現

linalg の配列は `make-array` で作られる組み込みの配列です。ベクタはランク 1 の配列で `#(...)` と印字され、行列はランク 2 の配列で `#2A(...)` と印字されます。個々の要素は `aref` で読み書きでき、プログラムの他の場所で構築された配列も linalg 関数に渡せます。より高いランクの配列も扱えます。要素ごとの演算、リダクション、`reshape`/`flatten`、`array-equal` はフラットな行優先順で要素を走査するため任意のランクを受け付けます。一方 `dot`/`matmul`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` は numpy の専用ルーチンと同様、ベクタと行列 (ランク 2 以下) に対して定義されたままです。[`linalg:from-list`](../reference/functions/linalg-from-list.md) / [`linalg:to-list`](../reference/functions/linalg-to-list.md) は配列とリストを相互に変換します。

算術演算は汎用かつ厳密です。整数の入力は浮動小数点数に落ちることなく整数と比のまま保たれるため、整数行列に対する [`linalg:det`](../reference/functions/linalg-det.md)、[`linalg:inv`](../reference/functions/linalg-inv.md)、[`linalg:solve`](../reference/functions/linalg-solve.md) は厳密です。特異行列の行列式は浮動小数点の微小値ではなく厳密に `0` になります。浮動小数点の入力は浮動小数点数のまま伝播し、[`linalg:norm`](../reference/functions/linalg-norm.md) は `sqrt` が浮動小数点数を返すため浮動小数点数を返します。

## 実例

```lisp
(linalg:eye 3)                                   ; => #2A((1 0 0) (0 1 0) (0 0 1))
(linalg:arange 5)                                ; => #(0 1 2 3 4)
(linalg:linspace 0 1 5)                          ; => #(0 1/4 1/2 3/4 1)
(let ((a (linalg:from-list '((1 2) (3 4))))
      (b (linalg:from-list '((5 6) (7 8)))))
  (linalg:matmul a b))                           ; => #2A((19 22) (43 50))
(linalg:det (linalg:from-list '((1 2) (3 4))))   ; => -2
(linalg:inv (linalg:from-list '((1 2) (3 4))))   ; => #2A((-2 1) (3/2 -1/2))
(linalg:solve (linalg:from-list '((2 1) (1 3)))
              (linalg:from-list '(3 5)))         ; => #(4/5 7/5)
```

## 要素ごとの算術演算とブロードキャスト

[`linalg:add`](../reference/functions/linalg-add.md)、[`linalg:sub`](../reference/functions/linalg-sub.md)、[`linalg:mul`](../reference/functions/linalg-mul.md)、[`linalg:div`](../reference/functions/linalg-div.md) は要素ごとに演算し、どちらか一方のスカラーのオペランドはもう一方のオペランドの形状にブロードキャストされます。2 つの配列オペランドは同じ形状でなければなりません。`mul` はアダマール積（要素ごとの積）であることに注意してください。行列積は [`linalg:matmul`](../reference/functions/linalg-matmul.md)（またはランクに応じてディスパッチする [`linalg:dot`](../reference/functions/linalg-dot.md)）です。要素ごとの任意の変換には [`linalg:emap`](../reference/functions/linalg-emap.md) を使います。

```lisp
(linalg:add (linalg:from-list '(1 2 3)) 10)      ; => #(11 12 13)
(linalg:mul 2 (linalg:from-list '((1 2) (3 4)))) ; => #2A((2 4) (6 8))
(linalg:div (linalg:from-list '(1 2 3)) 2)       ; => #(1/2 1 3/2)
```

## 第一級関数

linalg の関数は通常の `defun` なので、`#'linalg:norm` などは関数が期待されるあらゆる場所で第一級の値として動作します。

```lisp
(mapcar #'linalg:norm
        (list (linalg:from-list '(3 4))
              (linalg:from-list '(6 8)))) ; => (5.0 10.0)
```

配列は同一性 (`eq`) でしか比較されないため、結果の比較には形状と数値の等価性を検査する [`linalg:array-equal`](../reference/functions/linalg-array-equal.md) を使います（`1` と `1.0` は等しいと判定されます）。

## パッケージ

`linalg` はそれ自体が独立した[パッケージ](../reference/packages.md)であり、`cl` を use していません。`(in-package linalg)` の中では標準関数は非修飾の名前では見えず、`cl:` 修飾（`cl:print`、`cl:mapcar` など）が必要になります。したがってほとんどのプログラムは、このページのすべての例がそうしているように、デフォルトの `cl-user` パッケージにとどまり、修飾された `linalg:` 名で呼び出すべきです。

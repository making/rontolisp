# linalg パッケージの関数

`linalg` パッケージは、組み込みの配列に対する numpy
スタイルのベクトル・行列演算を提供します(要素ごとの演算とリダクションは任意の階数で動作します)。**Common Lispの一部ではありません**。
関数は `linalg:` 修飾子で参照してください(このパッケージは `cl` を使用しないため、
通常は `cl-user` に留まり修飾名で呼び出します)。パッケージはLispソースで一度だけ
実装されており、すべてのバックエンドで同一に動作します。コンストラクタは packed
double-float 配列を作るため浮動小数点で計算します(`det`・`inv`・`solve` は numpy と同様です)。
以下の各名前はそれぞれのページにリンクしています。概要と実例は
[ベクトルと行列ガイド](../../guides/linear-algebra.md)を参照してください。

| Function | Example | Result |
|----------|---------|--------|
| `linalg:zeros` | `(linalg:zeros 3)`, `(linalg:zeros '(2 2))` | `#d(0.0 0.0 0.0)`、`#d((0.0 0.0) (0.0 0.0))`(shapeは整数または `(rows cols)` のリスト) |
| `linalg:ones` | `(linalg:ones '(2 2))` | `#d((1.0 1.0) (1.0 1.0))` |
| `linalg:full` | `(linalg:full '(2 2) 7)` | `#d((7.0 7.0) (7.0 7.0))` |
| `linalg:zeros-like` | `(linalg:zeros-like #2A((1 2) (3 4)))` | `#d((0.0 0.0) (0.0 0.0))`(入力と同じ形状・同じ要素幅のゼロ配列) |
| `linalg:eye` | `(linalg:eye 2)` | `#d((1.0 0.0) (0.0 1.0))`(単位行列) |
| `linalg:arange` | `(linalg:arange 5)`, `(linalg:arange 2 10 2)` | `#d(0.0 1.0 2.0 3.0 4.0)`、`#d(2.0 4.0 6.0 8.0)`(stopは含まない。stepは負も可) |
| `linalg:linspace` | `(linalg:linspace 0 1 5)` | `#d(0.0 0.25 0.5 0.75 1.0)`(両端を含むn等分の値) |
| `linalg:from-list` | `(linalg:from-list '((1 2) (3 4)))` | `#d((1.0 2.0) (3.0 4.0))`(フラットなリストからはベクタ) |
| `linalg:to-list` | `(linalg:to-list (linalg:eye 2))` | `((1.0 0.0) (0.0 1.0))` |
| `linalg:shape` | `(linalg:shape #2A((1 2 3) (4 5 6)))` | `(2 3)` |
| `linalg:ndim` | `(linalg:ndim #2A((1 2) (3 4)))` | `2`(次元数。数値なら 0) |
| `linalg:size` | `(linalg:size (linalg:eye 3))` | `9`(要素の総数) |
| `linalg:reshape` | `(linalg:reshape (linalg:arange 6) '(2 3))` | `#d((0.0 1.0 2.0) (3.0 4.0 5.0))`(行優先。extent 1 つに -1 可、要素数から推論) |
| `linalg:flatten` | `(linalg:flatten (linalg:eye 2))` | `#d(1.0 0.0 0.0 1.0)` |
| `linalg:transpose` | `(linalg:transpose #2A((1 2 3) (4 5 6)))` | `#d((1.0 4.0) (2.0 5.0) (3.0 6.0))`(ベクタはそのまま返します) |
| `linalg:pad` | `(linalg:pad #(1 2) 1)` | `#d(0.0 1.0 2.0 0.0)`(定数 0 のパディング。リストで軸ごとの `(before after)` ペアを指定) |
| `linalg:expand-dims` | `(linalg:expand-dims #(1 2 3) 0)` | `#d((1.0 2.0 3.0))` (extent 1 の軸を挿入。numpy の `expand_dims` / torch の `unsqueeze`) |
| `linalg:squeeze` | `(linalg:squeeze #2A((1 2 3)))` | `#d(1.0 2.0 3.0)` (extent 1 の軸を除去。`:axis` で対象を指定) |
| `linalg:concatenate` | `(linalg:concatenate (list #(1 2) #(3)))` | `#d(1.0 2.0 3.0)` (配列の**リスト**を既存の `:axis` に沿って連結) |
| `linalg:stack` | `(linalg:stack (list #(1 2) #(3 4)))` | `#d((1.0 2.0) (3.0 4.0))` (**新しい** `:axis` に沿って連結) |
| `linalg:slice` | `(linalg:slice #(0 1 2 3 4 5) '((nil nil 2)))` | `#d(0.0 2.0 4.0)` (numpy の基本スライシング。軸ごとに `nil` / `(start end [step])`) |
| `linalg:triu` | `(linalg:triu (linalg:ones '(3 3)) :k 1)` | `#d((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0))` (上三角。causal マスク) |
| `linalg:tril` | `(linalg:tril #2A((1 2) (3 4)))` | `#d((1.0 0.0) (3.0 4.0))` (下三角) |
| `linalg:add` | `(linalg:add #(1 2 3) 10)` | `#d(11.0 12.0 13.0)`(要素ごと。スカラーのオペランドはブロードキャスト) |
| `linalg:sub` | `(linalg:sub #(5 5) 1)` | `#d(4.0 4.0)` |
| `linalg:mul` | `(linalg:mul m1 m2)` | アダマール積(要素ごとの積)。行列積ではありません |
| `linalg:div` | `(linalg:div #(1 2 3) 2)` | `#d(0.5 1.0 1.5)`(packed double-float 配列) |
| `linalg:+` | `(linalg:+ #(1 2) #(3 4) #(10 10))` | `#d(14.0 16.0)`(可変長引数の `add`。CL 演算子スペル) |
| `linalg:-` | `(linalg:- #(10 10) 1 2)` | `#d(7.0 7.0)`(可変長引数の `sub`。引数 1 つで符号反転) |
| `linalg:*` | `(linalg:* #(1 2) #(3 4))` | `#d(3.0 8.0)`(可変長引数の `mul`。アダマール積であって行列積ではありません) |
| `linalg:/` | `(linalg:/ #(1 2 3) 2)` | `#d(0.5 1.0 1.5)`(可変長引数の `div`。引数 1 つで逆数) |
| `linalg:emap` | `(linalg:emap (lambda (x) (* x x)) (linalg:arange 4))` | `#d(0.0 1.0 4.0 9.0)`(全要素に関数を適用) |
| `linalg:exp` | `(linalg:exp (linalg:zeros 3))` | `#d(1.0 1.0 1.0)`(要素ごとの `e^x`) |
| `linalg:log` | `(linalg:log #(1 1 1))` | `#d(0.0 0.0 0.0)`(要素ごとの自然対数) |
| `linalg:tanh` | `(linalg:tanh (linalg:zeros 3))` | `#d(0.0 0.0 0.0)`(要素ごとの双曲線正接) |
| `linalg:sin` | `(linalg:sin (linalg:zeros 3))` | `#d(0.0 0.0 0.0)`(要素ごとの正弦) |
| `linalg:cos` | `(linalg:cos (linalg:zeros 3))` | `#d(1.0 1.0 1.0)`(要素ごとの余弦) |
| `linalg:tan` | `(linalg:tan (linalg:zeros 3))` | `#d(0.0 0.0 0.0)`(要素ごとの正接) |
| `linalg:asin` | `(linalg:asin (linalg:zeros 3))` | `#d(0.0 0.0 0.0)`(要素ごとの逆正弦) |
| `linalg:acos` | `(linalg:acos (linalg:ones 3))` | `#d(0.0 0.0 0.0)`(要素ごとの逆余弦) |
| `linalg:atan` | `(linalg:atan (linalg:zeros 3))` | `#d(0.0 0.0 0.0)`(要素ごとの逆正接) |
| `linalg:sinh` | `(linalg:sinh (linalg:zeros 3))` | `#d(0.0 0.0 0.0)`(要素ごとの双曲線正弦) |
| `linalg:cosh` | `(linalg:cosh (linalg:zeros 3))` | `#d(1.0 1.0 1.0)`(要素ごとの双曲線余弦) |
| `linalg:sqrt` | `(linalg:sqrt #(4 9 16))` | `#d(2.0 3.0 4.0)`(要素ごとの平方根) |
| `linalg:abs` | `(linalg:abs #(-3 2 -1))` | `#d(3.0 2.0 1.0)`(要素ごとの絶対値) |
| `linalg:square` | `(linalg:square #(1 2 3))` | `#d(1.0 4.0 9.0)`(要素ごとの `x * x`) |
| `linalg:negative` | `(linalg:negative #(1 -2 3))` | `#d(-1.0 2.0 -3.0)`(要素ごとの符号反転) |
| `linalg:sign` | `(linalg:sign #(-5 0 7))` | `#d(-1.0 0.0 1.0)`(要素ごとの符号) |
| `linalg:reciprocal` | `(linalg:reciprocal #(2 4 8))` | `#d(0.5 0.25 0.125)`(要素ごとの `1 / x`、float で計算) |
| `linalg:power` | `(linalg:power #(1 2 3) 2)` | `#d(1.0 4.0 9.0)` (要素ごとの `a ** b`。どちらのオペランドもスカラー可) |
| `linalg:maximum` | `(linalg:maximum #(1 5 3) #(4 2 3))` | `#d(4.0 5.0 3.0)`(要素ごとに大きい方。どちらかの被演算子はスカラー可) |
| `linalg:minimum` | `(linalg:minimum #(1 5 3) 4)` | `#d(1.0 4.0 3.0)`(要素ごとに小さい方。どちらかの被演算子はスカラー可) |
| `linalg:clip` | `(linalg:clip #(-2 0 3) -1.0 1.0)` | `#d(-1.0 0.0 1.0)`(要素ごとの `min(max(x, lo), hi)`) |
| `linalg:relu` | `(linalg:relu #(-2 0 3))` | `#d(0.0 0.0 3.0)`(要素ごとの `max(x, 0.0)`) |
| `linalg:erf` | `(linalg:erf #(0 1))` | `#d(0.0 0.842700792949715)`(要素ごとのガウス誤差関数) |
| `linalg:softmax` | `(linalg:softmax #(1 1 1 1))` | `#d(0.25 0.25 0.25 0.25)` (最大値を引いた softmax。`:axis` でスライスごとに正規化) |
| `linalg:log-softmax` | `(linalg:log-softmax #(0 0))` | `#d(-0.6931471805599453 -0.6931471805599453)` (`softmax` の安定な対数) |
| `linalg:dot` | `(linalg:dot v1 v2)` | numpyスタイルのディスパッチ: ベクタ.ベクタはスカラー、行列.ベクタ / ベクタ.行列はベクタ、行列.行列は行列積 |
| `linalg:matmul` | `(linalg:matmul #2A((1 2) (3 4)) #2A((5 6) (7 8)))` | `#d((19.0 22.0) (43.0 50.0))`(行列積。rank 3 以上は最後の 2 軸でスタック) |
| `linalg:outer` | `(linalg:outer #(1 2) #(3 4 5))` | `#d((3.0 4.0 5.0) (6.0 8.0 10.0))`(外積) |
| `linalg:cross` | `(linalg:cross #(1 0 0) #(0 1 0))` | `#d(0.0 0.0 1.0)`(3 次元の外積。長さ 2 のベクタの場合は暗黙のスカラー z を返す) |
| `linalg:sum` | `(linalg:sum #2A((1 2) (3 4)))` | `10`(リダクションは要素の型に従う。`:axis` / `:keepdims` キーワードで軸ごとの還元) |
| `linalg:mean` | `(linalg:mean #(1 2 3 4))` | `5/2`(リダクションは要素の型に従う。`:axis` / `:keepdims` キーワード) |
| `linalg:var` | `(linalg:var #(1 2 3 4))` | `1.25` (分散。`:axis` / `:keepdims` / `:ddof` キーワード) |
| `linalg:std` | `(linalg:std #(2 4 4 4 5 5 7 9))` | `2.0` (`linalg:var` の平方根。キーワードは同じ) |
| `linalg:amax` | `(linalg:amax #2A((1 9) (3 4)))` | `9`(最大の要素。`:axis` / `:keepdims` キーワード) |
| `linalg:amin` | `(linalg:amin #(5 2 8))` | `2`(最小の要素。`:axis` / `:keepdims` キーワード) |
| `linalg:argmax` | `(linalg:argmax #(1 9 3))` | `1`(同値の場合は最初のインデックス。`:axis` で軸ごとのインデックス) |
| `linalg:argmin` | `(linalg:argmin #(5 2 8))` | `1`(同値の場合は最初のインデックス。`:axis` で軸ごとのインデックス) |
| `linalg:norm` | `(linalg:norm #(3 4))` | `5.0`(ユークリッド / フロベニウスノルム) |
| `linalg:trace` | `(linalg:trace #2A((1 2) (3 4)))` | `5`(正方行列のみ) |
| `linalg:diff` | `(linalg:diff #(1 2 4 7 0))` | `#d(1.0 2.0 3.0 -7.0)`(`:axis` に沿った `:n` 階の離散差分。デフォルトは 1 と最後の軸) |
| `linalg:gradient` | `(linalg:gradient #(0 1 4 9 16))` | `#d(1.0 2.0 4.0 6.0 7.0)`(中心差分。入力と同じ長さ。省略可能なスカラー間隔または座標ベクタ) |
| `linalg:det` | `(linalg:det #2A((1 2) (3 4)))` | `-2.0`(浮動小数点。特異行列は微小値になることがある) |
| `linalg:inv` | `(linalg:inv #2A((4 0) (2 4)))` | `#d((0.25 0.0) (-0.125 0.25))`(特異行列ではエラーを通知します) |
| `linalg:solve` | `(linalg:solve a b)` | `a . x = b` の解(`b` はベクタまたは行列) |
| `linalg:array-equal` | `(linalg:array-equal (linalg:eye 2) #2A((1 0) (0 1)))` | `t`(同じ形状かつ数値的に等しい要素。配列自体は `eq` でしか比較できません) |
| `linalg:equal` | `(linalg:equal #(1 5 3) #(2 5 1))` | `#d(0.0 1.0 0.0)`(要素ごとの数値等値を 0.0/1.0 マスクで。スカラー可) |
| `linalg:greater` | `(linalg:greater #(1 5 3) 2)` | `#d(0.0 1.0 1.0)`(要素ごとの `a > b` マスク。スカラー可) |
| `linalg:greater-equal` | `(linalg:greater-equal #(1 5 3) #(2 5 1))` | `#d(0.0 1.0 1.0)`(要素ごとの `a >= b` マスク) |
| `linalg:less` | `(linalg:less #(1 5 3) #(2 5 1))` | `#d(1.0 0.0 0.0)`(要素ごとの `a < b` マスク) |
| `linalg:less-equal` | `(linalg:less-equal #(1 5 3) #(2 5 1))` | `#d(1.0 1.0 0.0)`(要素ごとの `a <= b` マスク) |
| `linalg:where` | `(linalg:where #(1 0 1) 10 20)` | `#d(10.0 20.0 10.0)` (非ゼロマスクによる要素ごとの選択。ブロードキャストあり) |
| `linalg:take-rows` | `(linalg:take-rows #2A((10 11 12) (20 21 22) (30 31 32)) #(2 0))` | `#d((30.0 31.0 32.0) (10.0 11.0 12.0))`(インデックスベクタで選んだ axis-0 スライス) |
| `linalg:row` | `(linalg:row #2A((10 11 12) (20 21 22) (30 31 32)) 1)` | `#d(20.0 21.0 22.0)`(axis-0 スライス 1 つ。axis が落ちる。numpy の `x[i]`) |
| `linalg:gather` | `(linalg:gather #2A((10 11 12) (20 21 22)) #(2 0))` | `#d(12.0 20.0)`(行ごとの `a[i, idx[i]]`) |
| `linalg:one-hot` | `(linalg:one-hot #(1 0 2) 3)` | `#d((0.0 1.0 0.0) (1.0 0.0 0.0) (0.0 0.0 1.0))`(one-hot 行列) |
| `linalg:seed` | `(linalg:seed 42)` | `42`(共有乱数生成器を決定的に初期化。シード済み列は全バックエンドで bit-identical) |
| `linalg:rand` | `(linalg:rand 4)` | 一様 [0, 1) の乱数配列(shape は `linalg:zeros` と同じ指定) |
| `linalg:randn` | `(linalg:randn 4)` | 標準正規の乱数配列(Irwin-Hall。裾は ±6σ でクリップ) |
| `linalg:uniform` | `(linalg:uniform -2.0 2.0 4)` | `[lo, hi)` の一様乱数配列 |
| `linalg:choice` | `(linalg:choice 60000 4)` | `[0, n)` の一様インデックスを size 個(復元抽出。ミニバッチ抽出向け) |
| `linalg:permutation` | `(linalg:permutation 10)` | 0..n-1 のシャッフル(Fisher-Yates) |


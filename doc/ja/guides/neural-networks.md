# ニューラルネットワーク（torch）

`torch` パッケージは [linalg](linear-algebra.md) の上に載る PyTorch スタイルのレイヤーです。どう計算されたかを記憶する**テンソル**と、その履歴を逆向きに辿って勾配を書き込む `torch:backward` からなります。手書きの誤差逆伝播が行っていたこと -- どの配列がどこに流れたかの追跡、演算ごとの随伴の導出、ブロードキャスト軸での勾配の合計 -- が、演算ひとつずつ自動で行われます。

パッケージは Lisp ソースで一度だけ実装され、すべてのバックエンドで同一に動作します。各演算は `linalg` カーネルを通じて計算するため、torch プログラムは [`--simd`](simd-acceleration.md) でそのまま加速され、数値結果も linalg の結果そのものです。ランク 3 以上のバッチ行列積も加速対象に入っているため、attention 層がほぼその呼び出しで占められる Transformer も、素の多層パーセプトロンと同じだけ恩恵を受けます。同じ呼び出しは、形状が往復に見合う大きさになれば [`--gpu`](simd-acceleration.md#accelerating-the-matrix-product-on-a-gpu---gpu) が NVIDIA のデバイスへ載せるものでもあります。デバイス上ではスタックされた行列の往復は行列ごとにではなく 1 回で済みます。[linalg の加速](simd-acceleration.md#accelerating-linalg) を参照してください。

## テンソル

`torch:tensor` は数値、リスト、配列、linalg 配列から葉テンソルを作ります。`:requires-grad t` はパラメータ、すなわち backward が勾配を書き込むべきテンソルの印です。テンソルは `#<TENSOR データ>` (パラメータなら ` :REQUIRES-GRAD T` 付き) と印字されます。印字するのはデータだけなので、どのバックエンドでも同じテキストです。値は `torch:data` (配列)、`torch:item` (要素 1 個のテンソルの中の数値)、`torch:shape` で読み戻します:

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:data *w*)             ; => #f(1.0 2.0)
(torch:shape *w*)            ; => (2)
(torch:requires-grad-p *w*)  ; => T
(torch:item (torch:tensor 2.5)) ; => 2.5
```

演算はテンソル、数値、生の配列、リストを区別なく受け取ります。テンソル以外は勾配の流れない定数になります。生の配列はそのまま使われ、要素幅も元のままです。上のテンソルが `#f` なのに下の積が `#d` になるのはそのためです ([要素幅](#element-width-single-float) を参照):

```lisp
(torch:data (torch:add *w* 10))                        ; => #f(11.0 12.0)
(torch:data (torch:matmul #2A((1.0 2.0) (3.0 4.0)) *w*)) ; => #d(5.0 11.0)
```

## 要素幅: single-float

テンソルのデータはパックされた **single-float** (`#f`) です。PyTorch の既定 dtype である `torch.float32` に合わせています。一方 `linalg` 自身のコンストラクタは numpy に合わせて double-float (`#d`) のままです。torch が何もないところから作る値はすべてこの幅になり (`torch:tensor`、`torch:parameter`、および `torch:linear`、`torch:embedding`、`torch:layer-norm` が持つ重み)、テンソルから計算された値は計算元の幅を引き継ぎます。したがって forward と backward のパス全体が single のまま流れます。桁が必要な場面では `torch:tensor` や `torch:parameter` に `:element-type 'double-float` を渡せば広い型になり、`:element-type nil` は元の配列の幅をそのまま保ちます。

**テンソルと出会う linalg 配列は `:element-type 'single-float` で作ってください。** 幅が混ざった組でも答えは正しく出ます (演算は広い方に合わせ、第 1 オペランドの幅を保ちます) が、[`--simd`](simd-acceleration.md) のカーネルはそれをすべて辞退するため、演算はスカラーループに落ち、モデル全体が加速を失います:

```lisp
(defparameter *t* (torch:tensor '(1.0 2.0)))
(torch:data (torch:mul *t* (linalg:ones '(2) :element-type 'single-float))) ; => #f(1.0 2.0)
(array-element-type (torch:data *t*))                                       ; => SINGLE-FLOAT
```

同じことが `torch:set-data` に渡す生の配列にも当てはまります (パラメータのデータをそのまま置き換えるので、渡された幅がそのまま残ります。`torch.nn.init` 風の初期化はこの形です)。activation に後から足す生のバッファ (位置エンコーディングやマスク表) も同様です。狭い幅の数値的な代償については [single-float の精度](linear-algebra.md#single-float-precision) を参照してください。

## 記録と backward パス

自動微分に参加するオペランドを持つ演算は、その演算をテープに記録します。スカラー (要素 1 個の) テンソルに対する `torch:backward` は勾配を `1.0` でシードし、記録された演算を逆位相順に訪れて各入力の勾配を蓄積します。2 回使われたテンソル (残差接続や再利用された埋め込み行) は両方の経路の**合計**を受け取ります。結果は `torch:grad` で読みます:

```lisp
(defparameter *loss* (torch:sum (torch:mul *w* *w*)))
(torch:item *loss*)  ; => 5.0
(torch:backward *loss*)
(torch:grad *w*)     ; => #f(2.0 4.0)
```

勾配は backward の呼び出しをまたいで蓄積 (`+=`) されます。これはミニバッチのループが求める動作で、ステップの間は `torch:zero-grad` でスロットをクリアします:

```lisp
(torch:backward (torch:sum (torch:mul *w* 3.0)))
(torch:grad *w*)                    ; => #f(5.0 7.0)
(torch:grad (torch:zero-grad *w*))  ; => NIL
```

## ブロードキャストと勾配

要素ごとの演算は numpy と同じようにブロードキャストし、backward はブロードキャストされた軸で合計することで勾配を各オペランドの形に縮約します。`(n d)` の活性に足した `(d)` のバイアスは、バッチ軸で合計された `(d)` の勾配を受け取ります:

```lisp
(defparameter *b* (torch:tensor '(0.5 0.5) :requires-grad t))
(defparameter *y* (torch:add (torch:tensor '((1.0 2.0) (3.0 4.0))) *b*))
(torch:backward (torch:sum *y*))
(torch:grad *b*) ; => #f(2.0 2.0)
```

## テープの外に出る

`torch:no-grad` は記録を無効にして本体を実行します。値は計算されますが、何も記憶されません。学習ループのパラメータ更新 (そして推論一般) をテープの外に置く方法です。テンソル単位の綴りが `torch:detach` で、同じデータを共有しつつ履歴から切り離した葉を返します:

```lisp
(torch:no-grad
  (torch:requires-grad-p (torch:mul *w* 2.0))) ; => NIL
(torch:requires-grad-p (torch:detach (torch:mul *w* 2.0))) ; => NIL
(torch:requires-grad-p (torch:mul *w* 2.0))    ; => T
```

## 学習ループ: y = 2x をフィットする

勾配降下法に必要なのは上記だけです。損失を組み立てる順伝播、`torch:backward`、そして `torch:no-grad` の中での更新。平均二乗誤差を最小化して `y = 2x` をフィットします (すべての量が正確な 2 進有理数になる値を選んであるため、印字結果はどのバックエンドでも同一です):

```lisp
(defparameter *wf* (torch:tensor '(0.0) :requires-grad t))
(defparameter *x* (torch:tensor '(1.0 2.0)))
(defparameter *t* (torch:tensor '(2.0 4.0)))
(dotimes (i 10)
  (let* ((diff (torch:sub (torch:mul *x* *wf*) *t*))
         (loss (torch:mean (torch:mul diff diff))))
    (torch:backward loss)
    (torch:no-grad
      (setq *wf* (torch:tensor (linalg:sub (torch:data *wf*)
                                           (linalg:mul 0.125 (torch:grad *wf*)))
                               :requires-grad t)))))
(torch:data *wf*) ; => #f(1.9998901)
```

## モジュール

**モジュール**はパラメータを保持し、合成でき、順伝播を持ちます。`torch:module` は kind キーワード、**フィールド**のプロパティリスト、forward 関数からモジュールを作り、`torch:forward` がそれを実行します。フィールドのプロパティリストがパラメータ登録そのもので (`torch:parameters` がこれを走査します)、レイヤーの forward はクロージャに閉じ込めた変数ではなく `torch:field` でパラメータを読み戻します。存在するパラメータが走査から漏れることはありません:

```lisp
(defun scale-layer (n)
  (torch:module :scale (list :gain (torch:parameter (linalg:ones (list n))))
                (lambda (self x) (torch:mul x (torch:field self :gain)))))
(defparameter *scale* (scale-layer 2))
(torch:data (torch:forward *scale* (torch:tensor '(3.0 4.0)))) ; => #f(3.0 4.0)
(length (torch:parameters *scale*))                            ; => 1
```

走査はサブモジュール**およびそのリスト**へ降りていき、同一性で重複を排除します。2 つのレイヤーで共有された重みはパラメータ 1 個であり、N 段のブロックのリストに `ModuleList` 型は要りません。`requires-grad` を持たないテンソルのフィールドはバッファ扱いで飛ばされます:

```lisp
(defparameter *stack*
  (torch:module :stack (list :blocks (list *scale* (scale-layer 2))
                             :buffer (torch:tensor '(9.0 9.0)))
                (lambda (self x) x)))
(length (torch:parameters *stack*)) ; => 2
```

`torch:train` と `torch:eval` は同じ走査で学習フラグを切り替え、`torch:zero-grad` はモジュールも受け取ってすべてのパラメータの勾配をクリアします。

## 組み込みレイヤー

`torch:linear`、`torch:embedding`、`torch:layer-norm`、`torch:dropout`、`torch:sequential` は `torch:module` の普通の呼び出し元です。パラメータの初期化は PyTorch とまったく同じ方式で、シード可能な `linalg:seed` の生成器から引かれるため、シードを固定した実行はどのバックエンドでも再現します。`torch:set-field` でパラメータを差し替えると、レイヤーを特定の重みに固定できます:

```lisp
(defparameter *lin* (torch:linear 3 2))
(torch:set-field *lin* :weight (torch:parameter '((1.0 0.0) (0.0 1.0) (1.0 1.0))))
(torch:set-field *lin* :bias (torch:parameter '(0.5 -0.5)))
(torch:data (torch:forward *lin* (torch:tensor '((1.0 2.0 3.0))))) ; => #f((4.5 4.5))
```

`torch:sequential` は引数を各要素に順番に通します。要素はモジュールでも**素の関数**でもよく、これが活性化関数専用のモジュール型を持たない理由であり、reshape を連鎖の中に置ける理由でもあります:

```lisp
(defparameter *net*
  (torch:sequential (torch:linear 4 8) (function torch:relu) (torch:linear 8 2)))
(torch:shape (torch:forward *net* (torch:tensor (linalg:zeros '(3 4))))) ; => (3 2)
(length (torch:parameters *net*))                                       ; => 4
```

活性化関数は [`torch:relu`](../reference/functions/torch-relu.md)、[`torch:tanh`](../reference/functions/torch-tanh.md)、[`torch:gelu`](../reference/functions/torch-gelu.md) で、いずれもテンソルを受け取る普通の関数です。`torch:gelu` の既定は厳密形 `x * (1 + erf(x / sqrt(2))) / 2` (`nn.GELU` 自身の既定) で、微分可能な [`torch:erf`](../reference/functions/torch-erf.md) の上に構築されています。`:approximate :tanh` を指定すると GPT/BERT の定式化になります。

[`torch:fields`](../reference/functions/torch-fields.md) はモジュールのフィールド plist 全体を返します。これがツリーをパッケージの外から走査可能にするものです。`nn.Module.apply` と `nn.Module.named_parameters` に対応するものがないのは、走査をこの plist と [`torch:module-kind`](../reference/functions/torch-module-kind.md) — ドット区切りのパラメータ名の部分文字列ではなく、レイヤーが「何であるか」 — で書くためです。

## 損失関数

`torch:mse-loss` と `torch:cross-entropy-loss` はスカラーテンソルを返す普通の関数です。交差エントロピーは生の**ロジット**を取り (softmax の出力ではありません。数値的に安定な形である `-log-softmax` から計算します)、最終軸以外を平坦化するので `(batch seq vocab)` がそのまま使えます。ターゲットは整数のクラスインデックス (`:ignore-index` がパディング位置を総和からも平均の分母からも除きます) か、ロジットと同じ形の確率分布 (PyTorch のソフトラベル形式、`-sum(target * log-softmax(logits))`) のどちらかです:

```lisp
(torch:item (torch:mse-loss (torch:tensor '(1.0 2.0)) '(0.0 0.0))) ; => 2.5
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0))) #(0)))
; => 0.6931471824645996
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0)))
                                      (torch:tensor '((0.5 0.5)))))
; => 0.6931471824645996
```

リストのターゲットは常にクラスインデックスとして読むため、確率で渡すにはテンソルか配列が必要です。one-hot の分布と対応するインデックスは同じ損失になります。

## オプティマイザ

**オプティマイザ**は更新則とその状態を持ちます。`torch:sgd`、`torch:adam`、`torch:adamw` はモデル (またはパラメータのリスト) を受け取り、ハイパーパラメータとバッファをモジュールとまったく同じ fields plist に保持し、`torch:step` ですべてのパラメータに更新則を適用します:

```lisp
(defparameter *p* (torch:parameter '(1.0 2.0)))
(defparameter *opt* (torch:sgd (list *p*) :lr 0.125 :momentum 0.5))
(torch:backward (torch:sum (torch:mul *p* *p*)))
(torch:step *opt*)
(torch:data *p*)         ; => #f(0.75 1.5)
(torch:step-count *opt*) ; => 1
```

更新は各パラメータのデータを**その場で**書き換え、torch の演算を一切使いません。したがってテープには何も記録されず、`torch:set-data` で手書きした更新と違って `torch:no-grad` で囲む必要がありません。状態 (モーメンタムバッファ、Adam の 2 つのモーメント、バイアス補正が割るステップ数) はパラメータではなくオプティマイザが持つので、同じ重みに対する 2 つのオプティマイザは別々の状態を保ちます。

ハイパーパラメータは普通のフィールドで、学習率スケジュールに必要なのはそれだけです。`torch:zero-grad` はモデルだけでなくオプティマイザも受け取ります:

```lisp
(defparameter *adam* (torch:adam (torch:linear 2 2) :lr 0.001))
(torch:field *adam* :lr)                              ; => 0.001
(torch:field (torch:set-field *adam* :lr 0.0005) :lr) ; => 5.0e-4
```

3 つが乗っているコンストラクタが `torch:optimizer` です。種別キーワード、パラメータ、fields plist、ステップ関数からなるので、このパッケージが用意していない更新則も同じレコードの上の素の defun として書けます。

[`torch:adam`](../reference/functions/torch-adam.md) と [`torch:adamw`](../reference/functions/torch-adamw.md) は同じ規則で、減衰の位置だけが違います。Adam の `:weight-decay` は勾配に `wd * param` を加え、AdamW はパラメータを直接縮めるので適応的な分母で再スケールされません。パラメータ**グループ**というオブジェクトはありません。互いに素なパラメータリストに対する 2 つのオプティマイザがここでのグループであり、Transformer が重み行列だけを減衰させ、バイアス・LayerNorm のゲイン・埋め込みテーブルには手を付けないのはこの方法です。

[`torch:clip-grad-norm`](../reference/functions/torch-clip-grad-norm.md) は `torch:backward` と `torch:step` の間に置きます。全勾配の L2 ノルム (測定値なので、そのままログに出せます) を返し、それが上限を超えていればその場でスケールします。

## ネットワークを学習させる

以上を組み合わせると、PyTorch が書くのと同じループになります。順伝播、損失、`torch:zero-grad`、`torch:backward`、`torch:step` です:

```lisp
(linalg:seed 3)
(defparameter *mlp*
  (torch:sequential (torch:linear 2 8) (function torch:relu) (torch:linear 8 1)))
(defparameter *xs* (torch:tensor '((0.0 0.0) (0.0 1.0) (1.0 0.0) (1.0 1.0))))
(defparameter *ys* (torch:tensor '((0.0) (1.0) (1.0) (0.0))))
(defparameter *sgd* (torch:sgd *mlp* :lr 0.2))
(dotimes (i 200)
  (let ((loss (torch:mse-loss (torch:forward *mlp* *xs*) *ys*)))
    (torch:zero-grad *sgd*)
    (torch:backward loss)
    (torch:step *sgd*)))
(< (torch:item (torch:mse-loss (torch:forward *mlp* *xs*) *ys*)) 1.0e-6) ; => T
```

更新を手書きする場合は `torch:no-grad` で囲む必要があります。パラメータに対する `torch:sub` はテープに記録されてしまうからです。`torch:set-data` はレイヤーのフィールドが指しているテンソルそのものに新しい値を書き込むため、モデルは同じテンソルを使い続けます:

```lisp
(defun sgd-step (model lr)
  (torch:no-grad
    (dolist (p (torch:parameters model))
      (torch:set-data p (linalg:sub (torch:data p)
                                    (linalg:mul lr (torch:grad p)))))))
(sgd-step *mlp* 0.2)
(torch:training-p *mlp*) ; => T
```

## バッチ化、パディング、マスク

`Dataset`/`DataLoader` の階層はありません。バッチは普通のリストです。`torch:shuffled-batches` は例のリスト (または整数 `n`。インデックスリスト `0..n-1` を表し、複数の並行した配列を同時にバッチ化するときの書き方です) をミニバッチに切り分けます。順序はシード付き生成器から得られるので、エポックはどのバックエンドでも再現します:

```lisp
(linalg:seed 1)
(torch:shuffled-batches 7 3)                       ; => ((6 0 5) (1 4 3) (2))
(torch:shuffled-batches '(a b c d) 2 :shuffle nil) ; => ((A B) (C D))
```

`torch:pad-sequence` は可変長のインデックス列のバッチを、バッチ先頭のパディング済みランク 2 テンソル 1 つにまとめます。2 つのマスク構築関数は、アテンションが `-infinity` で埋める定数を作ります:

```lisp
(defparameter *tokens* (torch:pad-sequence '((1 2 3) (4 5))))
(torch:data *tokens*)         ; => #f((1.0 2.0 3.0) (4.0 5.0 0.0))
(torch:padding-mask *tokens*) ; => #f(((0.0 0.0 0.0)) ((0.0 0.0 1.0)))
(torch:subsequent-mask 3)     ; => #d(((0.0 1.0 1.0) (0.0 0.0 1.0) (0.0 0.0 0.0)))
```

どちらのマスクも**生の linalg 配列**です (マスクは勾配を運びません)。形は `(batch query-length key-length)` のスコアにブロードキャストするよう決めてあり、パディングマスクは `(batch 1 length)`、因果マスクは `(1 n n)` です。`torch:masked-fill` は 0 でない値をすべてマスク扱いするので、2 つは `linalg:add` で合成できます。ここで選んだパディング値は、そのまま `torch:cross-entropy-loss` の `:ignore-index` に渡す値でもあり、パディング位置は損失に寄与しなくなります。

## マスク付きアテンションスコア

`torch:masked-fill` はマスクが非ゼロの位置に定数を書き込みます。`torch:softmax` の前に `-infinity` で埋めるのがマスク付きアテンションのイディオムで、マスクされた重みは backward パスを含めてちょうど `0.0` になります:

```lisp
(defparameter *sc* (torch:tensor '((1.0 2.0) (3.0 3.0)) :requires-grad t))
(defparameter *att* (torch:softmax
                     (torch:masked-fill *sc* #2A((0 1) (0 0)) (/ -1.0 0.0))
                     :axis 1))
(torch:data *att*) ; => #f((1.0 0.0) (0.5 0.5))
```

## 実例

[`examples/llm-from-scratch/`](https://github.com/making/rontolisp/blob/develop/examples/llm-from-scratch/README.md) は『作ってわかる大規模言語モデルの仕組み』第2章をこのパッケージで書き直したものです。スケール内積注意とマルチヘッド注意、正弦波位置エンコーディング、パディングマスクと因果マスクを備えたエンコーダ・デコーダ Transformer、そして日英の学習ループと貪欲デコードまでが入っています。PyTorch と `torch` の対応表は同ディレクトリの README にあります。

## パッケージ

`torch` は `cl` を使用しないため、プログラムは `cl-user` のまま修飾名で呼び出します。`#'torch:name` も使えます (すべての関数は普通の defun です)。微分可能な演算は対応する `linalg` の関数を鏡写しにしています。全リストは[関数リファレンス](../reference/functions.md#torch-package-functions)に、`torch:no-grad` は[マクロのページ](../reference/macros/torch-no-grad.md)にあります。

# ニューラルネットワーク（torch）

`torch` パッケージは [linalg](linear-algebra.md) の上に載る PyTorch スタイルのレイヤーです。どう計算されたかを記憶する**テンソル**と、その履歴を逆向きに辿って勾配を書き込む `torch:backward` からなります。手書きの誤差逆伝播が行っていたこと -- どの配列がどこに流れたかの追跡、演算ごとの随伴の導出、ブロードキャスト軸での勾配の合計 -- が、演算ひとつずつ自動で行われます。

パッケージは Lisp ソースで一度だけ実装され、すべてのバックエンドで同一に動作します。各演算は `linalg` カーネルを通じて計算するため、torch プログラムは [`--simd`](simd-acceleration.md) でそのまま加速され、数値結果も linalg の結果そのものです。

## テンソル

`torch:tensor` は数値、リスト、配列、linalg 配列から葉テンソルを作ります。`:requires-grad t` はパラメータ、すなわち backward が勾配を書き込むべきテンソルの印です。テンソル自体は生のレコードとして印字されるため、値は `torch:data` (配列)、`torch:item` (要素 1 個のテンソルの中の数値)、`torch:shape` で読み戻します:

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:data *w*)             ; => #d(1.0 2.0)
(torch:shape *w*)            ; => (2)
(torch:requires-grad-p *w*)  ; => T
(torch:item (torch:tensor 2.5)) ; => 2.5
```

演算はテンソル、数値、生の配列、リストを区別なく受け取ります。テンソル以外は勾配の流れない定数になります:

```lisp
(torch:data (torch:add *w* 10))                        ; => #d(11.0 12.0)
(torch:data (torch:matmul #2A((1.0 2.0) (3.0 4.0)) *w*)) ; => #d(5.0 11.0)
```

## 記録と backward パス

自動微分に参加するオペランドを持つ演算は、その演算をテープに記録します。スカラー (要素 1 個の) テンソルに対する `torch:backward` は勾配を `1.0` でシードし、記録された演算を逆位相順に訪れて各入力の勾配を蓄積します。2 回使われたテンソル (残差接続や再利用された埋め込み行) は両方の経路の**合計**を受け取ります。結果は `torch:grad` で読みます:

```lisp
(defparameter *loss* (torch:sum (torch:mul *w* *w*)))
(torch:item *loss*)  ; => 5.0
(torch:backward *loss*)
(torch:grad *w*)     ; => #d(2.0 4.0)
```

勾配は backward の呼び出しをまたいで蓄積 (`+=`) されます。これはミニバッチのループが求める動作で、ステップの間は `torch:zero-grad` でスロットをクリアします:

```lisp
(torch:backward (torch:sum (torch:mul *w* 3.0)))
(torch:grad *w*)                    ; => #d(5.0 7.0)
(torch:grad (torch:zero-grad *w*))  ; => NIL
```

## ブロードキャストと勾配

要素ごとの演算は numpy と同じようにブロードキャストし、backward はブロードキャストされた軸で合計することで勾配を各オペランドの形に縮約します。`(n d)` の活性に足した `(d)` のバイアスは、バッチ軸で合計された `(d)` の勾配を受け取ります:

```lisp
(defparameter *b* (torch:tensor '(0.5 0.5) :requires-grad t))
(defparameter *y* (torch:add (torch:tensor '((1.0 2.0) (3.0 4.0))) *b*))
(torch:backward (torch:sum *y*))
(torch:grad *b*) ; => #d(2.0 2.0)
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
(torch:data *wf*) ; => #d(1.999890012666583)
```

## モジュール

**モジュール**はパラメータを保持し、合成でき、順伝播を持ちます。`torch:module` は kind キーワード、**フィールド**のプロパティリスト、forward 関数からモジュールを作り、`torch:forward` がそれを実行します。フィールドのプロパティリストがパラメータ登録そのもので (`torch:parameters` がこれを走査します)、レイヤーの forward はクロージャに閉じ込めた変数ではなく `torch:field` でパラメータを読み戻します。存在するパラメータが走査から漏れることはありません:

```lisp
(defun scale-layer (n)
  (torch:module :scale (list :gain (torch:parameter (linalg:ones (list n))))
                (lambda (self x) (torch:mul x (torch:field self :gain)))))
(defparameter *scale* (scale-layer 2))
(torch:data (torch:forward *scale* (torch:tensor '(3.0 4.0)))) ; => #d(3.0 4.0)
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
(torch:data (torch:forward *lin* (torch:tensor '((1.0 2.0 3.0))))) ; => #d((4.5 4.5))
```

`torch:sequential` は引数を各要素に順番に通します。要素はモジュールでも**素の関数**でもよく、これが活性化関数専用のモジュール型を持たない理由であり、reshape を連鎖の中に置ける理由でもあります:

```lisp
(defparameter *net*
  (torch:sequential (torch:linear 4 8) (function torch:relu) (torch:linear 8 2)))
(torch:shape (torch:forward *net* (torch:tensor (linalg:zeros '(3 4))))) ; => (3 2)
(length (torch:parameters *net*))                                       ; => 4
```

## 損失関数

`torch:mse-loss` と `torch:cross-entropy-loss` はスカラーテンソルを返す普通の関数です。交差エントロピーは生の**ロジット**と整数のクラスターゲットを取り (one-hot ベクトルではなく、softmax の出力でもありません。数値的に安定な形であるターゲットクラス位置の `-log-softmax` として計算します)、最終軸以外を平坦化するので `(batch seq vocab)` がそのまま使えます。`:ignore-index` はパディング位置を総和からも平均の分母からも除きます:

```lisp
(torch:item (torch:mse-loss (torch:tensor '(1.0 2.0)) '(0.0 0.0))) ; => 2.5
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0))) #(0)))
; => 0.6931471805599453
```

## ネットワークを学習させる

以上を組み合わせると学習ループになります。順伝播、損失、モデルに対する `torch:zero-grad`、`torch:backward`、そして `torch:no-grad` の中で `torch:parameters` を回るパラメータ更新です。`torch:set-data` はレイヤーのフィールドが指しているテンソルそのものに新しい値を書き込むため、モデルは同じテンソルを使い続けます:

```lisp
(defun sgd-step (model lr)
  (torch:no-grad
    (dolist (p (torch:parameters model))
      (torch:set-data p (linalg:sub (torch:data p)
                                    (linalg:mul lr (torch:grad p)))))))
(linalg:seed 3)
(defparameter *mlp*
  (torch:sequential (torch:linear 2 8) (function torch:relu) (torch:linear 8 1)))
(defparameter *xs* (torch:tensor '((0.0 0.0) (0.0 1.0) (1.0 0.0) (1.0 1.0))))
(defparameter *ys* (torch:tensor '((0.0) (1.0) (1.0) (0.0))))
(dotimes (i 200)
  (let ((loss (torch:mse-loss (torch:forward *mlp* *xs*) *ys*)))
    (torch:zero-grad *mlp*)
    (torch:backward loss)
    (sgd-step *mlp* 0.2)))
(< (torch:item (torch:mse-loss (torch:forward *mlp* *xs*) *ys*)) 1.0e-6) ; => T
```

## マスク付きアテンションスコア

`torch:masked-fill` はマスクが非ゼロの位置に定数を書き込みます。`torch:softmax` の前に `-infinity` で埋めるのがマスク付きアテンションのイディオムで、マスクされた重みは backward パスを含めてちょうど `0.0` になります:

```lisp
(defparameter *sc* (torch:tensor '((1.0 2.0) (3.0 3.0)) :requires-grad t))
(defparameter *att* (torch:softmax
                     (torch:masked-fill *sc* #2A((0 1) (0 0)) (/ -1.0 0.0))
                     :axis 1))
(torch:data *att*) ; => #d((1.0 0.0) (0.5 0.5))
```

## パッケージ

`torch` は `cl` を使用しないため、プログラムは `cl-user` のまま修飾名で呼び出します。`#'torch:name` も使えます (すべての関数は普通の defun です)。微分可能な演算は対応する `linalg` の関数を鏡写しにしています。全リストは[関数リファレンス](../reference/functions.md#torch-package-functions)に、`torch:no-grad` は[マクロのページ](../reference/macros/torch-no-grad.md)にあります。

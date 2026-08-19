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

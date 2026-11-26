# torch パッケージの関数

`torch` パッケージは `linalg` の上に載る PyTorch スタイルの微分可能レイヤーです
([ニューラルネットワークガイド](../../guides/neural-networks.md)を参照)。どう計算さ
れたかを記録するテンソルと、その履歴を辿って勾配を書き込む `torch:backward` から
なります。**Common Lisp の一部ではなく**、関数は `torch:` 修飾子付きで参照します
(パッケージは `cl` を使用しません)。すべての演算はテンソル、数値、配列、リストを
オペランドに取り、`linalg` カーネルを通じて計算するため `--simd` は torch プログ
ラムもそのまま加速します。テンソルは `#<TENSOR データ>` と印字されるので、値は
`torch:data` / `torch:item` / `torch:grad` で読み戻してください。表の中ほどは
`nn` スタイルのモジュール層です。モジュールはフィールドのプロパティリストにパラ
メータを持ち、合成でき、`torch:forward` で実行します。最後の部分はモデルを学習
実行に変えるためのもので、`torch:step` が全パラメータをその場で更新するオプティ
マイザと、`Dataset`/`DataLoader` の階層ではなく素の関数として提供されるバッチ
化・パディング・マスクの補助関数です。唯一のマクロ `torch:no-grad` は
[マクロのページ](../macros/torch-no-grad.md)にあります。

| Function | Example | Result |
|----------|---------|--------|
| `torch:tensor` | `(torch:tensor '(1 2) :requires-grad t)` | パックドデータ上の葉テンソル (`:element-type 'single-float` で `#f`) |
| `torch:tensorp` | `(torch:tensorp x)` | テンソルなら `T`、それ以外は `NIL` |
| `torch:data` | `(torch:data tn)` | linalg 配列 (スカラーテンソルなら数値) |
| `torch:grad` | `(torch:grad tn)` | 蓄積された勾配。backward 前は `NIL` |
| `torch:shape` | `(torch:shape tn)` | 次元リスト。スカラーテンソルは `NIL` |
| `torch:item` | `(torch:item tn)` | 要素 1 個のテンソルの中の数値 |
| `torch:detach` | `(torch:detach tn)` | データを共有しテープから切り離した葉 |
| `torch:zero-grad` | `(torch:zero-grad tn)` | 勾配スロットをクリアしてテンソルを返す |
| `torch:requires-grad-p` | `(torch:requires-grad-p tn)` | テンソルが自動微分に参加するか |
| `torch:backward` | `(torch:backward loss)` | スカラーテンソルからの逆方向自動微分 (`torch:grad` に蓄積) |
| `torch:add` | `(torch:add a b)` | ブロードキャスト付きの微分可能な要素ごとの `+` |
| `torch:sub` | `(torch:sub a b)` | 微分可能な要素ごとの `-` |
| `torch:mul` | `(torch:mul a b)` | 微分可能な要素ごとの (アダマール) `*` |
| `torch:div` | `(torch:div a b)` | 微分可能な要素ごとの `/` |
| `torch:neg` | `(torch:neg a)` | 微分可能な符号反転 |
| `torch:power` | `(torch:power a 2)` | 微分可能な要素ごとの `a ** b` |
| `torch:exp` | `(torch:exp a)` | 微分可能な `e^x` |
| `torch:log` | `(torch:log a)` | 微分可能な自然対数 |
| `torch:sqrt` | `(torch:sqrt a)` | 微分可能な平方根 |
| `torch:tanh` | `(torch:tanh a)` | 微分可能な双曲線正接 |
| `torch:relu` | `(torch:relu a)` | 微分可能な `max(x, 0.0)` |
| `torch:erf` | `(torch:erf a)` | 微分可能なガウス誤差関数 |
| `torch:gelu` | `(torch:gelu a)` | 微分可能な GELU (`:approximate :none` / `:tanh`) |
| `torch:matmul` | `(torch:matmul a b)` | 微分可能な行列積 (ランク 3 以上はバッチ積) |
| `torch:sum` | `(torch:sum a :axis 0)` | 微分可能な合計 (全体または軸に沿って) |
| `torch:mean` | `(torch:mean a)` | 微分可能な平均 |
| `torch:var` | `(torch:var a :ddof 1)` | 微分可能な分散 (除数 `(n - ddof)`) |
| `torch:std` | `(torch:std a)` | 微分可能な標準偏差 |
| `torch:amax` | `(torch:amax a :axis 0)` | 微分可能な最大値 (同値には勾配を均等分配) |
| `torch:argmax` | `(torch:argmax a)` | 最大要素のインデックス (微分不可能、生の値) |
| `torch:topk` | `(torch:topk a 5)` | 軸に沿った上位 `k` 個の値を大きい順に (`:indices t` でその位置) |
| `torch:multinomial` | `(torch:multinomial probs)` | シード付き生成器による行ごとのインデックス抽出 (`:num-samples`、`:replacement`) |
| `torch:softmax` | `(torch:softmax a :axis 1)` | 微分可能な最大値差し引き softmax |
| `torch:log-softmax` | `(torch:log-softmax a :axis 1)` | 微分可能な log-softmax (交差エントロピーの半分) |
| `torch:masked-fill` | `(torch:masked-fill a mask v)` | マスクが非ゼロの位置を `v` で埋める微分可能な演算 |
| `torch:gather` | `(torch:gather a idx)` | 行列の微分可能な行ごとの要素選択 |
| `torch:index-select` | `(torch:index-select a idx)` | 微分可能な行選択 (埋め込み参照。重複は蓄積) |
| `torch:reshape` | `(torch:reshape a '(2 3))` | 微分可能な行優先 reshape |
| `torch:view` | `(torch:view a '(2 3))` | PyTorch のもう 1 つの名前での `torch:reshape` |
| `torch:transpose` | `(torch:transpose a '(1 0 2))` | 微分可能な転置 / 軸の並べ替え |
| `torch:unsqueeze` | `(torch:unsqueeze a 0)` | 微分可能な広がり 1 の軸の挿入 |
| `torch:squeeze` | `(torch:squeeze a)` | 微分可能な広がり 1 の軸の除去 |
| `torch:cat` | `(torch:cat (list a b) :axis 1)` | 既存の軸に沿った微分可能な連結 |
| `torch:stack` | `(torch:stack (list a b))` | 新しい軸に沿った微分可能な結合 |
| `torch:slice` | `(torch:slice a '(nil (0 2)))` | 微分可能な numpy 基本スライス |
| `torch:set-data` | `(torch:set-data tn v)` | テンソルのデータを破壊的に置き換える (パラメータ更新) |
| `torch:module` | `(torch:module :k fields fn)` | ユーザーレイヤー。kind、フィールドのプロパティリスト、forward 関数 |
| `torch:modulep` | `(torch:modulep x)` | モジュールなら `T`、そうでなければ `NIL` |
| `torch:module-kind` | `(torch:module-kind m)` | モジュールの kind キーワード |
| `torch:field` | `(torch:field m :weight)` | モジュールの指定フィールドの値 (なければエラー) |
| `torch:fields` | `(torch:fields m)` | フィールド plist 全体を新しいリストで (モジュール走査用) |
| `torch:set-field` | `(torch:set-field m :weight p)` | モジュールの指定フィールドを設定しモジュールを返す |
| `torch:forward` | `(torch:forward m x)` | モジュール (または素の関数) の順伝播を実行 |
| `torch:parameter` | `(torch:parameter '(1.0))` | `requires-grad` を持つ葉テンソル、すなわち学習可能パラメータ |
| `torch:parameters` | `(torch:parameters m)` | モジュールから到達できる全パラメータ (重複排除済み) |
| `torch:train` | `(torch:train m)` | モジュールとサブモジュールを学習モードにする |
| `torch:eval` | `(torch:eval m)` | モジュールとサブモジュールを評価モードにする |
| `torch:training-p` | `(torch:training-p m)` | モジュールが学習モードかどうか |
| `torch:linear` | `(torch:linear 4 8)` | 全結合レイヤー (`:weight`、`:bias`) |
| `torch:embedding` | `(torch:embedding 100 8)` | 埋め込みテーブル (`:weight`)。任意の形のインデックス |
| `torch:sequential` | `(torch:sequential a #'torch:relu b)` | レイヤーおよび素の関数の連鎖 |
| `torch:layer-norm` | `(torch:layer-norm 8)` | 最終軸に対する層正規化 (`ddof` 0) |
| `torch:dropout` | `(torch:dropout 0.1)` | inverted dropout。評価モードでは恒等写像 |
| `torch:mse-loss` | `(torch:mse-loss y target)` | 平均二乗誤差 (`:reduction :mean` / `:sum` / `:none`) |
| `torch:cross-entropy-loss` | `(torch:cross-entropy-loss logits target)` | ロジットに対する交差エントロピー。target はクラスインデックス (`:ignore-index` でパディングを除外) か確率分布 |
| `torch:optimizer` | `(torch:optimizer :k ps fields fn)` | ユーザー定義オプティマイザ。種別、パラメータ、fields plist、ステップ関数 |
| `torch:optimizerp` | `(torch:optimizerp x)` | オプティマイザなら `T`、それ以外は `NIL` |
| `torch:optimizer-kind` | `(torch:optimizer-kind o)` | オプティマイザの種別キーワード |
| `torch:optimizer-params` | `(torch:optimizer-params o)` | オプティマイザが更新するパラメータテンソル |
| `torch:step` | `(torch:step o)` | 更新則を全パラメータに適用 (その場で更新、テープ外) |
| `torch:step-count` | `(torch:step-count o)` | `torch:step` の実行回数 (Adam の `t`) |
| `torch:sgd` | `(torch:sgd model :lr 0.1)` | SGD。`:momentum` / `:weight-decay` も指定可 |
| `torch:adam` | `(torch:adam model :lr 0.001)` | Adam (`:betas`、`:eps`、`:weight-decay`)。初回からバイアス補正済み |
| `torch:adamw` | `(torch:adamw model :lr 0.001)` | AdamW。同じ規則で `:weight-decay` を分離 (既定 `0.01`) |
| `torch:clip-grad-norm` | `(torch:clip-grad-norm model 1.0)` | 勾配全体の L2 ノルムが上限を超えたらその場でスケール。そのノルムを返す |
| `torch:pad-sequence` | `(torch:pad-sequence seqs)` | 可変長シーケンスをバッチ先頭のパディング済みテンソルに |
| `torch:shuffled-batches` | `(torch:shuffled-batches n 32)` | シード付き生成器によるミニバッチ (`:shuffle`、`:drop-last`) |
| `torch:padding-mask` | `(torch:padding-mask tokens)` | パディング位置の `(batch 1 length)` マスク (生の配列) |
| `torch:subsequent-mask` | `(torch:subsequent-mask 8)` | 対角より上が `1.0` の `(1 n n)` 因果マスク (生の配列) |


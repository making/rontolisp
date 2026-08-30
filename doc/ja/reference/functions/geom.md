# geom パッケージの関数

`geom` パッケージはソリッドモデリング（剛体変換、シーングラフ、三角形メッシュを
キャッシュする境界表現の立体）を提供します。`linalg` の上に rontolisp 自身で書か
れ、同じように最初の使用時に読み込まれます。それ以外には何も依存しないので、`objc`
/ `appkit` と違ってすべてのバックエンドとブラウザのプレイグラウンドで動きます。
**Common Lisp の一部ではありません**。名前は `geom:` 修飾子で参照します。
`geom:transform`、`geom:node`、`geom:solid`、`geom:bounds` は CLOS のクラス名でもあ
り、`typep` や `defmethod` の特定化子に使えます。各関数は個別ページにリンクしていま
す。型モデル・巻き方の規約・メッシュのキャッシュについては[ソリッドモデリングガイ
ド](../../guides/solid-modeling.md)を参照してください。

| 関数 | 例 | 結果 |
|------|-----|------|
| `geom:vec3` | `(geom:vec3 1 2 3)` | パッケージの座標型であるパックされた単精度3次元ベクトル |
| `geom:axis-vector` | `(geom:axis-vector :-y)` | 軸指定子が表す単位ベクトル |
| `geom:axis-angle-matrix` | `(geom:axis-angle-matrix 0.5 :z)` | その軸まわりにその角度だけ回す 3x3 行列 |
| `geom:rpy-matrix` | `(geom:rpy-matrix 0.1 0.2 0.3)` | ロール・ピッチ・ヨーを順に合成した 3x3 行列 |
| `geom:make-transform` | `(geom:make-transform :translation v :rpy '(0 0 1.5))` | 剛体運動。親も同一性もキャッシュも持たない値 |
| `geom:translation-of` | `(geom:translation-of tf)` | 変換の並進3次元ベクトル |
| `geom:rotation-of` | `(geom:rotation-of tf)` | 変換の 3x3 回転行列 |
| `geom:compose` | `(geom:compose outer inner)` | `inner` の運動を `outer` の座標系に運ぶ新しい変換 |
| `geom:invert` | `(geom:invert tf)` | 逆の剛体運動 |
| `geom:transform-point` | `(geom:transform-point tf p)` | 変換で運ばれた点 |
| `geom:inverse-transform-point` | `(geom:inverse-transform-point tf p)` | 逆変換を作らずに戻された点 |
| `geom:make-node` | `(geom:make-node :translation v :parent base)` | ローカル変換を「持つ」シーングラフのノード |
| `geom:local-transform` | `(geom:local-transform n)` | 親から見たノード自身の変換 |
| `geom:world-transform` | `(geom:world-transform n)` | 合成されたワールド変換（ノード上にメモ化） |
| `geom:world-translation` | `(geom:world-translation n)` | ワールド座標でのノードの原点 |
| `geom:world-rotation` | `(geom:world-rotation n)` | ワールド座標でのノードの姿勢 |
| `geom:parent-of` | `(geom:parent-of n)` | 接続先の親。ルートでは `nil` |
| `geom:children-of` | `(geom:children-of n)` | 接続されている子ノードのリスト |
| `geom:attach` | `(geom:attach parent child)` | 子。以後は親の座標系で姿勢が解釈される |
| `geom:detach` | `(geom:detach child)` | 親の座標系から外された子 |
| `geom:translate` | `(geom:translate n v :frame :parent)` | ノードを平行移動し、積み重ねる。`:frame` は `:local`（既定）か `:parent` |
| `geom:rotate` | `(geom:rotate n 0.5 :z)` | 現在の姿勢に積み重ねてノードを回転する |
| `geom:place` | `(geom:place n :rpy '(0 0 1.5))` | 姿勢を直接設定する。アニメーションループ向け |
| `geom:reorient` | `(geom:reorient n 0.5 :z)` | 並進を保ったまま回転を設定する |
| `geom:box` | `(geom:box '(100 200 300))` | 原点を中心とする直方体（スカラーなら立方体） |
| `geom:cylinder` | `(geom:cylinder :radius 50 :height 100)` | z = 0 の上に立つ円柱 |
| `geom:cone` | `(geom:cone :radius 50 :height 120)` | z = 0 上の円を底面とする錐。`:apex` で斜錐 |
| `geom:sphere` | `(geom:sphere :radius 50 :sides 32 :stacks 24)` | 原点を中心とする球 |
| `geom:torus` | `(geom:torus :radius 60 :tube 20)` | xy 平面上のトーラス |
| `geom:extrusion` | `(geom:extrusion profile :along 10)` | 閉じた輪郭をベクトルに沿って掃引した一般の角柱 |
| `geom:revolution` | `(geom:revolution profile :sides 64)` | 輪郭を z 軸まわりに回した立体。軸を離れる端に蓋が付く |
| `geom:polyhedron` | `(geom:polyhedron points facets)` | 生の点列とインデックスループ（逃げ道） |
| `geom:read-model` | `(geom:read-model "bunny.obj")` | モデルファイル中のメッシュ。フォーマットはバイト列から判定 |
| `geom:read-obj` | `(geom:read-obj "bunny.obj")` | Wavefront OBJ ファイル中のメッシュ |
| `geom:read-stl` | `(geom:read-stl "part.stl")` | STL ファイル中のメッシュ。両方の方言に対応 |
| `geom:arrow` | `(geom:arrow :length 200 :radius 6)` | 軸と尖った頭を1つのソリッドにした矢印。向きは `:direction` |
| `geom:triad` | `(geom:triad :at (geom:vec3 0 0 0))` | 3本の `geom:arrow`（+x 赤、+y 緑、+z 青）のリスト |
| `geom:vertices-of` | `(geom:vertices-of s)` | モデル座標のランク2 `(n 3)` パック配列 |
| `geom:facets-of` | `(geom:facets-of s)` | インデックスループ。外側から見て反時計回り |
| `geom:color-of` | `(geom:color-of s)` | ソリッドの色（成分 0..1 の3次元ベクトル）。`setf` 可能 |
| `geom:label-of` | `(geom:label-of s)` | `:label` に渡された値。`setf` 可能 |
| `geom:user-data` | `(geom:user-data s)` | 利用側が独自の状態を置くスロット。`setf` 可能 |
| `geom:scale` | `(geom:scale s 2)` | モデル座標を拡大した新しいソリッド。オペランドは変更されない |
| `geom:nscale` | `(geom:nscale s 2)` | その場で拡大し、両方のキャッシュと `geom:user-data` を破棄する |
| `geom:mesh` | `(geom:mesh s)` | モデル空間の三角形。1つ 18 float、1度計算してキャッシュ |
| `geom:wireframe` | `(geom:wireframe s)` | 各辺1回。1線分 6 float、同様にキャッシュ |
| `geom:mesh-triangle-count` | `(geom:mesh-triangle-count s)` | メッシュが持つ三角形の数 |
| `geom:bounds` | `(geom:bounds (list a b))` | ソリッドまたはそのリストのワールド座標バウンディングボックス |
| `geom:lower-of` | `(geom:lower-of b)` | バウンディングボックスの最小側の角 |
| `geom:upper-of` | `(geom:upper-of b)` | バウンディングボックスの最大側の角 |
| `geom:bounds-center` | `(geom:bounds-center b)` | その中点。ビューアがカメラを向ける先 |
| `geom:bounds-extent` | `(geom:bounds-extent b)` | 各軸方向の大きさ |
| `geom:bounds-union` | `(geom:bounds-union a b)` | 両方を含む最小のボックス |
| `geom:volume` | `(geom:volume s)` | 発散定理による体積。巻き方の検査も兼ねる |
| `geom:centroid` | `(geom:centroid s)` | モデル座標での体積中心 |
| `geom:surface-area` | `(geom:surface-area s)` | メッシュ三角形の面積の合計 |
| `geom:union` | `(geom:union a b)` | 両オペランドのいずれかが占める領域を覆う新しい立体 |
| `geom:difference` | `(geom:difference a b)` | `a` から `b` を取り除いた新しい立体 |
| `geom:intersection` | `(geom:intersection a b)` | 両オペランドが共通に占める領域だけの新しい立体 |
| `geom:section` | `(geom:section s :normal :z)` | 平面が立体を切る断面ループ |
| `geom:history` | `(geom:history s)` | その立体を作ったもの: `nil` またはブール演算の `(op a b)` |


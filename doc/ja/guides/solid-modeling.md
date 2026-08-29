# ソリッドモデリング（geom）

`geom` パッケージは、名前で構築し、運動連鎖にぶら下げ、変換で動かし、計測でき
る立体を提供します。実装は rontolisp 自身で書かれ、依存するのは
[`linalg`](linear-algebra.md) のカーネルだけです。外部呼び出しもファイルシステム
も使わないため、インタプリタ、コンパイル済み `.class`、両方の WASM バックエンド、
ブラウザのプレイグラウンドのいずれでも動きます。インストールも require も不要で、
最初の使用時に読み込まれます。

```lisp
(geom:volume (geom:box '(100 200 300)))
; => 6000000.0
```

## 3つの型

`geom` の型はちょうど3つ、それにバウンディングボックスが加わります。

**`transform`** は剛体運動です。並進3次元ベクトルと 3x3 回転から成ります。これは
**値**であり、親も同一性もキャッシュも持たず、破壊的に変更されることもありません。
したがって同じ変換を何個のノードのローカル変換にしても構いません。
`geom:compose`、`geom:invert`、`geom:transform-point`、
`geom:inverse-transform-point` はいずれも新しい値を作ります。

```lisp
(geom:transform-point (geom:make-transform :translation (geom:vec3 0 0 10))
                      (geom:vec3 1 2 3))
; => #f(1.0 2.0 13.0)
```

**`node`** はローカル変換で「ある」のではなく、ローカル変換を「持ち」ます。だから
こそ、ソリッドもカメラ注視点も素の関節フレームも、余分なスロットなしにすべてノー
ドとして表せます。`geom:world-transform` は祖先の変換を合成した結果をメモ化し、姿
勢が変わると部分木全体でそのメモを破棄します。

**`solid`** は境界表現を持つノードです。`geom:vertices-of` はモデル座標を並べたラ
ンク2 `(n 3)` のパック配列、`geom:facets-of` はインデックスループのリストで、各
ループは**外側から見て**反時計回りに巻かれています。点のリストではなく1つの頂点配
列にしていることが、立体全体の変換を1回の `linalg:matmul` にしています。

すべては float32（`:element-type 'single-float`）です。パックされた単精度配列はそ
のまま GPU の頂点バッファのバイト列だからです。`geom` のメッシュは `objc:data` を
通して変換なしで Metal に届き、`linalg` の変換は幅を保ちます。

```lisp
(array-element-type (geom:mesh (geom:box 1)))
; => SINGLE-FLOAT
```

## コンストラクタ

キーワードを取る名詞のコンストラクタです。形状を決める唯一の寸法だけが位置引数に
なり得ます。

| コンストラクタ | 作られるもの |
|---|---|
| `(geom:box '(100 200 300))` | 原点を中心とする直方体（スカラーなら立方体） |
| `(geom:cylinder :radius 50 :height 100)` | z = 0 の上に立つ円柱 |
| `(geom:cone :radius 50 :height 120)` | z = 0 上の円を底面とする錐。`:apex` で斜錐 |
| `(geom:sphere :radius 50 :sides 32 :stacks 24)` | 原点を中心とする球 |
| `(geom:torus :radius 60 :tube 20)` | xy 平面上のトーラス |
| `(geom:extrusion profile :along 10)` | 閉じた輪郭をベクトルに沿って掃引した一般の角柱 |
| `(geom:revolution profile :sides 64)` | 輪郭を z 軸まわりに回転させた立体。軸を離れる端に蓋が付く |
| `(geom:polyhedron points facets)` | 生の点列とインデックスループ（逃げ道） |

`:sides` と `:stacks` は分割数です。分割された基本形状は滑らかな理想形に**内接**す
るので、体積は閉形式に**下から**収束します。

```lisp
(round (geom:volume (geom:cylinder :radius 50 :height 100 :sides 64)))
; => 784137
```

`pi r^2 h = 785398` に対して 0.16% 小さい値です。

## シーングラフ

`geom:attach` はノードを別のノードにぶら下げ、`geom:detach` は外します。変更子は
`geom:move`、`geom:turn`、`geom:place`、`geom:reorient` で、それぞれ位置引数のフラ
グではなく名前付きの `:frame` を取ります。`:local`（ノード自身の軸、既定）か
`:parent`（接続先の軸）です。`:frame :parent` と書かれた呼び出しはマニュアルなしで
読めます。

```lisp
(let* ((base (geom:make-node))
       (joint (geom:make-node :translation (geom:vec3 0 0 100) :parent base))
       (link (geom:cylinder :radius 8 :height 80)))
  (geom:attach joint link)
  (geom:turn joint (/ 3.141592653589793 2) :y)
  (geom:move base (geom:vec3 0 0 500))
  (mapcar (lambda (x) (round x)) (coerce (geom:world-translation link) 'list)))
; => (0 0 600)
```

`geom:move` は積み重ね、`geom:place` は姿勢を直接設定します。`geom:turn` の差分を
繰り返すとドリフトするため、アニメーションループでは `geom:place` を使います。

## メッシュと、それをキャッシュする理由

`geom:mesh` はモデル空間でのソリッドの三角形を返します。パックされた単精度配列で、
1三角形あたり 18 float（3頂点分の位置＋法線）です。各面を扇状に三角形分割し、法線
は Newell 法で求めます。これは1度だけ計算されてソリッド上に保持され、
`geom:wireframe`（1線分あたり 6 float、各辺1回）も同様です。

このキャッシュは最適化ではなく、設計の要です。剛体の三角形は決して変わらず、変わ
るのは姿勢だけだからです。13,800 三角形からなる 60 立体の多関節モデルでは、毎フ
レーム全頂点をワールド空間に変換するレンダラは **1フレーム 380 ms** を費やし、モ
デル空間のメッシュを1度アップロードしてワールド変換をドローごとのユニフォームとし
て渡すレンダラは **9.0 ms** です。したがって `geom:mesh` はレンダラの内部事情では
なく公開 API であり、そこから作った GPU バッファを利用側が置く場所が
`geom:user-data` です。

```lisp
(let ((s (geom:box 1)))
  (list (geom:mesh-triangle-count s) (eq (geom:mesh s) (geom:mesh s))))
; => (12 T)
```

`geom:scale` はパッケージが提供する唯一の頂点変更であり、したがって両方のキャッ
シュと `geom:user-data` を破棄する唯一の箇所です。

## 計測

`geom:bounds` はソリッド、またはソリッドのリストの軸並行ボックスを**ワールド**座標
で返すので、シーングラフに追従します。`geom:bounds-center`、`geom:bounds-extent`、
`geom:bounds-union`、`geom:lower-of`、`geom:upper-of` で読み出します。

`geom:volume` はメッシュ三角形に発散定理を適用して積分するので、**巻き方の検査**も
兼ねます。逆向きに巻かれた面は減算するため、巻き方を間違えた `geom:polyhedron` は
わずかに小さい値ではなく大きく外れた値を返します。`geom:centroid` は同じ符号付き四
面体の総和、`geom:surface-area` は三角形の面積の合計です。

```lisp
(let ((b (geom:box 10)))
  (geom:move b (geom:vec3 100 0 0))
  (coerce (geom:bounds-center (geom:bounds b)) 'list))
; => (100.0 0.0 0.0)
```

## ここにないもの

ブーリアン演算（和・差・積）、メッシュのファイル形式、そして描画に関わるものは含ま
れません。STL ファイル由来の面を持つ立体は、単なる `geom:polyhedron` です。

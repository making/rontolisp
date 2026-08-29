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
| `(geom:arrow :length 200 :radius 6)` | 軸と尖った頭を1つのソリッドにした矢印。向きは `:direction` |
| `(geom:triad :at (geom:vec3 0 0 0))` | それを3本（+x 赤、+y 緑、+z 青）束ねたリスト |

`:sides` と `:stacks` は分割数です。分割された基本形状は滑らかな理想形に**内接**す
るので、体積は閉形式に**下から**収束します。

```lisp
(round (geom:volume (geom:cylinder :radius 50 :height 100 :sides 64)))
; => 784137
```

`pi r^2 h = 785398` に対して 0.16% 小さい値です。

`geom:arrow` だけは幾何の教科書に載る形ではありませんが、ビューア側ではなくここに
あるのには理由があります。3本の線分で描いた原点指示子には太さを与えられず（線の
プリミティブに幅はありません）、先端を尖らせることもできません。ソリッドである矢印
なら両方できますし、バウンディングボックス、体積、運動連鎖への組み込み、CSG 演算、
4つのバックエンドすべて、そしてブラウザ側のレンダラまで、レンダラのコードを1行も
書かずに手に入ります。尾がモデル原点、先端は `:direction` 方向に `:length` の位置
で、指定しなかった寸法はすべて長さに対する比率なので `(geom:arrow :length 200)` の
1呼び出しで済みます。`geom:triad` はそれを慣例の3色で3本返すもので、呼び出し側が
所有するソリッドのリストです。

```lisp
(mapcar #'geom:label-of (geom:triad :at (geom:vec3 0 0 0)))
; => ("x" "y" "z")
```

分割された基本形状と違い、この体積は実際に作られた形状の閉形式（同じ正 n 角形の
角柱と角錐の和）と**厳密に**一致します。これが全バックエンドで巻き方を固定します。

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

## ブーリアン演算

`geom:union`、`geom:difference`、`geom:intersection` が風景を部品に変えます: 4 つ
のボルト穴が開いた板、溝を削り出したブロック、外形から内形を引いた筐体。各演算はオ
ペランドを**ワールド**座標で扱い (`(geom:difference plate hole)` は両方を配置した
後の見た目そのもの)、オペランドを変更せず、頂点がワールド座標の新しいルート立体を
返します。体積が検算になります: 任意の組について、基本形状が元々持つ分割誤差の範囲
内で `vol(A ∪ B) + vol(A ∩ B) = vol(A) + vol(B)` が成り立ちます。

```lisp
(let ((plate (geom:box '(100 100 20)))
      (hole (geom:cylinder :radius 10 :height 20 :sides 24)))
  (geom:move hole (geom:vec3 0 0 -10))
  (round (geom:volume (geom:difference plate hole))))
; => 193788
```

穴の深さは板の厚みとちょうど同じで、完全に貫通します。同一平面上の面、面の上にちょ
うど乗った頂点や辺、ちょうど接する 2 つの立体は、いずれも扱えるケースです。交わら
ない立体の積はエラーではなく**空**の立体 (面なし、体積 `0.0`) になり、結果は自分を
作ったものを記録します -- `(geom:history result)` は変更されていないオペランドを含
む `(op a b)` を返すので、プログラムはモデルを別のパラメータで作り直せます。

パイプラインは BSP クリッピングで、float64 で実行され、結果の頂点配列でのみ float32
に戻ります。分類の許容誤差は `geom:*tolerance*` (既定 `1.0e-5`) で、オペランドを合
わせたバウンディングボックスに対する**相対値**です -- `geom` には長さの単位がないの
で、絶対のイプシロンでは 0.001 スケールと 1000 スケールのモデルの両方を正しく扱えま
せん。1 つの演算だけ緩めたり締めたりするには、呼び出しの周りで再束縛します。

`geom:section` は同じ分類問題の片オペランドが自明な場合です: 平面が立体を切るルー
プを、ワールド座標の点を並べた rank-2 `(n 3)` packed 配列として返します -- 断面図
が 1 回の呼び出しで得られます。

```lisp
(length (geom:section (geom:torus :radius 60 :tube 20 :sides 24 :rings 12)))
; => 2
```

赤道はチューブを 2 回切ります: 境界と穴です。外側のループは法線の正の側から見て反
時計回り、穴は時計回りに巻かれます。

## 見る: `scene` ビューア

`geom` は何も描きません。すべてのバックエンドで動き、その多くには画面がないからで
す。**macOS** では `scene` パッケージがもう半分を担います。Metal サーフェスを載せた
ウィンドウ、軌道回転・パン・ドリーのカメラ、地面グリッド、座標軸の三つ組み。`geom`
と同じくインタプリタに同梱されているので、素の REPL から 3 行で絵になります。

```console
CL-USER> (defvar *v* (scene:viewer :title "arm" :width 900 :height 640))
CL-USER> (scene:add *v* (geom:cylinder :radius 60 :height 140))
CL-USER> (scene:fit *v*)
CL-USER> (scene:refresh *v*)
```

ドラッグで軌道回転、shift+ドラッグでパン、スクロールでドリー、ウィンドウのリサイズ
も可能です。カメラ操作は自分で再描画しますが、ミューテータはしません。60 個のソリッ
ドを追加するループが 60 フレーム描いてはいけないからで、まとめて呼んだ後の手順が
`scene:refresh`、動くシーンなら `scene:animate` です。ビューアはグローバル変数の集
合ではなく CLOS インスタンスなので、1 つのイメージに 2 つのウィンドウが同時に存在
し、それぞれ独立に回せます。

**上のメッシュキャッシュはこのためにあります。** 各ソリッドのモデル空間メッシュは最
初に描画されたときに専用の GPU バッファに入り（そのソリッドの `geom:user-data` に保
持されます）、フレームはソリッドごとに 4x4 のモデル行列と色を 1 つずつ設定して描画
コールを 1 回発行します。フレーム中に Lisp は三角形に一切触れません。それが上で計測
したモデルにおける 9.0 ms と 380 ms の差です。動く関節のコストは行列 1 つなので、
`scene:animate` のフックは毎フレーム連鎖全体の姿勢を付け直しても構いません。

`scene:shading` は `:solid` / `:wireframe` / `:both` を選び、`scene:axes` は
`nil`（既定。何も描かない）/ `:world` / `:bodies`（各ソリッド自身の座標系。運動連鎖
を読み取れるようにするものです）/ `:both` を選びます。これらはビューア自身の備品で、
太さのない線の三つ組みです。ワールドのものはズームしても読めるよう視距離に比例して
拡大されます。置きたい場所に置け、軸に太さがあり先端が尖った**オブジェクト**としての
原点指示子は上の `(geom:triad)` で、他のソリッドと同じように追加します。だからこそ、
頼まれない限りビューアは三つ組みを描きません。`scene:window-of` と `scene:context-of` は
`appkit:` と、その下の `metal` 描画サーフェスへの抜け道です。

`examples/macos/scene-solids.lisp` は全プリミティブを並べたもの、
`examples/macos/scene-robot-arm.lisp` は動く目標に対して逆運動学を解く 4 関節アーム
です。後者は形状を手で組み立てる `examples/macos/metal-robot-arm.lisp` と同じ機械で
あり、2 つを並べて読む価値があります。どちらもディスプレイが必要なので
`examples.yaml` には入っていません。`scene` と `metal` はどちらも macOS 専用で、こ
れらを参照するプログラムの `.wasm` 出力は、`objc:` のプログラムと同様に名前を挙げて
拒否されます。

### ウィンドウを持たないビューア

`scene:offscreen` は、ドローアブルの代わりにテクスチャに描く同じビューアで、
`scene:snapshot` がそのピクセルを返します -- `width * height * 4` バイト、BGRA、行
0 が上です。似て非なる第二の描画関数ではなく同じ描画関数なので、絵そのものを検査で
きます。赤い箱はフレーム中央で赤い、別の立体の背後にあるものは隠れる、巻き方が逆の
面はカリングされる、`scene:fit` はバウンディングボックス全体をフレーム内に収める、
といった具合です。`metal:offscreen` と `metal:pixels` はその一段下で、`geom` を使わ
ない `metal:` プログラム向けです。

```console
CL-USER> (defvar *v* (scene:offscreen :width 320 :height 240))
CL-USER> (scene:add *v* (geom:box 200 :color (geom:vec3 1.0 0.2 0.2)))
CL-USER> (scene:fit *v*)
CL-USER> (length (scene:snapshot *v*))
307200
```

### どこでも見る: ブラウザの双子

`geom` は rontolisp が動く場所ならどこでも動き、そのレンダラも同じです。
`examples/browser/webgl-solids/` は `scene` の設計を WebGL2 に移植したもので、立体ご
とに 1 つの頂点バッファを一度だけアップロードし、描画ごとにモデル行列のユニフォーム
を渡し、立体 1 つにつき 1 回の描画コールを出し、`geom:mesh` と
`geom:world-transform` をそのまま利用します。実質的な違いは射影だけで、OpenGL のク
リップ空間は z を [-1, 1] に、Metal は [0, 1] に置きます。第二のモデリング層は意図的
に置いていません -- それこそが `geom` にブラウザ方言を生やすものだからです。

## ここにないもの

凸包、オフセット、フィレット、メッシュ修復、メッシュのファイル形式、そして描画に関
わるものは含まれません。描画は上に書いた `scene` の担当で、それはこのパッケージの一
部ではなく利用者です。STL ファイル由来の面を持つ立体は、単なる `geom:polyhedron`
です。

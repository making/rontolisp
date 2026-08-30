# scene パッケージの関数

`scene` パッケージは `metal` と `appkit` の上に載る `geom` ソリッドの 3D
ビューアです。軌道回転・パン・ドリーのカメラ、地面グリッド、ワールドとボディの座標軸、
ソリッド/ワイヤフレーム表示、クリックフック、アニメーションフックを備えます。macOS
専用である理由は
`metal` と同じです。**Common Lisp の一部ではありません**。名前は `scene:`
修飾子で参照してください。`scene:viewer-state` は CLOS クラス名でもあります。
ビューアはグローバル変数の集合ではなくインスタンスなので、1 つのイメージに 2
つのウィンドウが同時に存在し独立に回せます。**フレーム中に Lisp
は三角形に一切触りません**。各ソリッドのメッシュは一度だけ GPU に送られ、フレームは
ソリッドごとに 4x4 行列を 1 つ渡すだけです。以下の各名前は個別ページへリンクします。
モデル側は[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

| 関数 | 例 | 結果 |
|------|-----|------|
| `scene:viewer` | `(scene:viewer :title "arm")` | Metal サーフェスを載せたウィンドウと、それを駆動するビューア |
| `scene:offscreen` | `(scene:offscreen :width 320 :height 240)` | ウィンドウを持たないビューア。描画関数は同一で、レンダラをテスト可能にしているもの |
| `scene:snapshot` | `(scene:snapshot v)` | オフスクリーンビューアの 1 フレームをピクセル (BGRA) として |
| `scene:add` | `(scene:add v s1 (geom:triad))` | 最後に追加したソリッド。リスト引数は展開され、メッシュは最初に描画されたとき GPU に届く |
| `scene:drop` | `(scene:drop v s1 (geom:triad))` | ビューアから外し GPU バッファを解放した最後のソリッド |
| `scene:clear` | `(scene:clear v)` | `nil`。すべてのソリッドを除去。グリッドとカメラはそのまま |
| `scene:contents` | `(scene:contents v)` | 描画中のソリッドを追加順に |
| `scene:fit` | `(scene:fit v)` | `nil`。カメラを内容に向け、全体が収まるまで引く |
| `scene:camera` | `(scene:camera v :azimuth 0.85)` | `nil`。方位角/仰角/距離/注視点のうち与えたものだけ設定 |
| `scene:grid` | `(scene:grid v :extent 1200 :spacing 100)` | `nil`。地面グリッドを作り直す。`:extent` が `nil` ならグリッドを消す |
| `scene:grid-color` | `(scene:grid-color v (geom:vec3 0.2 0.5 0.4))` | `nil`。グリッドの色 |
| `scene:background` | `(scene:background v '(0 0 0 1))` | `nil`。フレーム開始時の色 |
| `scene:shading` | `(scene:shading v :wireframe)` | `nil`。`:solid`、`:wireframe`、`:both` (既定) |
| `scene:axes` | `(scene:axes v :both)` | `nil`。`:world`、`:bodies`、`:both`、`nil` (既定 -- 何も描かない) |
| `scene:ray` | `(scene:ray v 450.0 320.0)` | ビュー上の点を通るワールド視線を `(始点 方向)` で |
| `scene:on-click` | `(scene:on-click v #'reach)` | `nil`。クリックが落ちたワールド点を引数に `hook` を呼ぶ。`nil` で解除 |
| `scene:refresh` | `(scene:refresh v)` | `nil`。ちょうど 1 フレーム描画 |
| `scene:animate` | `(scene:animate v hook)` | `nil`。60 fps で描画し、各フレーム前に `hook` を 1 回呼ぶ |
| `scene:wait` | `(scene:wait v)` | ビューアのウィンドウが閉じられたら `nil` |
| `scene:window-of` | `(scene:window-of v)` | `NSWindow`。`appkit:` や生の `objc:send` への抜け道 |
| `scene:context-of` | `(scene:context-of v)` | `metal:context`。描画サーフェスへの抜け道 |


# scene:offscreen

`(scene:offscreen &key width height background)`

ウィンドウを持たないビューアです。パイプラインもカメラも描画関数も [`scene:viewer`](scene-viewer.md) と同一で、描き先だけが [`scene:snapshot`](scene-snapshot.md) で読み戻せるテクスチャになります。`:width` と `:height` はピクセル単位です。入力はありません -- クリックする対象がないため -- ので、カメラは `scene:camera` と `scene:fit` で動かし、フレームは `scene:snapshot` です。これがレンダラをテスト可能にしているものです。ウィンドウを開くテストは書けないため、これがなければカメラ、射影、モデル行列、面の巻き方の規約、深度テストは何にも検査されないまま出荷されることになります。`scene` パッケージの一部で macOS 専用、`.wasm` は不可です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (defvar *v* (scene:offscreen :width 320 :height 240))
CL-USER> (scene:add *v* (geom:box 200 :color (geom:vec3 1.0 0.2 0.2)))
CL-USER> (scene:fit *v*)
CL-USER> (length (scene:snapshot *v*))
307200
```

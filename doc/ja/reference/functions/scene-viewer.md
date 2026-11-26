# scene:viewer

`(scene:viewer &key title width height background)`

Metal サーフェスを載せたウィンドウを開き、ビューアを返します。グローバル変数の集合ではなく CLOS インスタンスなので、1 つのイメージに 2 つのウィンドウが同時に存在し、それぞれ独立に回せます。ドラッグで軌道回転、shift+ドラッグでパン、スクロールでドリー、ウィンドウのリサイズも可能です。カメラ操作は自分で再描画しますが、以下のミューテータはしません (60 個のソリッドを追加するループが 60 フレーム描いてはいけないからで、その後の手順が `scene:refresh` です)。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (defvar *v* (scene:viewer :title "arm" :width 900 :height 640))
CL-USER> (scene:add *v* (geom:cylinder :radius 60 :height 140))
CL-USER> (scene:fit *v*)
CL-USER> (scene:refresh *v*)
```

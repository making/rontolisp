# scene:axes

`(scene:axes v mode)`

描く座標軸の三つ組みを選びます。`:world` (既定) はワールド座標系のみ、`:bodies` は各ソリッド自身の座標系、`:both` は両方、`nil` はなし。ボディ軸は運動連鎖を読み取れるようにするもので、ソリッドのワールド変換の位置に、モデル空間での広がりに応じた大きさで描かれます。文字は描きません。座標系の名前は `geom:label-of` が持ち、位置は三つ組みが示します。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:axes *v* :both)
NIL
```

# scene:on-click

`(scene:on-click v hook)`

ビューがクリックされるたびに、引数 1 つ -- クリックの視線がカメラ正対のオービット注視点平面と交わるワールド座標の点 -- で `hook` を呼びます。`nil` で解除します。クリックとは数ポイント以上動かさずに離された押下のことなので、クリックとオービットは 1 つのジェスチャで、どちらも修飾キーを必要としません。この平面は、ビューアが何も教わらずに選べる唯一の平面であり、どのカメラ角度でも「見えているところをクリックする」が成り立つ理由でもあります。別の平面が欲しいプログラムのために、直線そのものは `scene:ray` が答えます。`scene` パッケージの一部です。`geom` ソリッド用の 3-D ビューアで、rontolisp 自身で `metal` と `appkit` の上に書かれ、初回使用時に読み込まれます。ディスプレイのある macOS 限定 (`java -jar`、`rontolisp` バイナリ、コンパイル済みの `.class` / `.jar`。`.wasm` は不可)。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```console
CL-USER> (scene:on-click *v* (lambda (p) (geom:place *marker* :translation p)))
NIL
```

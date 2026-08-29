# scene:fit

`(scene:fit v)`

カメラを内容に向け、全体が収まるまで引きます。全ソリッドに対する `geom:bounds` を取り、その中心を注視点、広がりを距離にします。空のビューアでは何もしません。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:fit *v*)
NIL
```

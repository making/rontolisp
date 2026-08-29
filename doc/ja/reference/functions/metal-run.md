# metal:run

`(metal:run ctx fn &key fps)`

`fn` をタイマーで描画します。まず 1 フレーム、その後は毎秒 `fps` 回 (既定 60)。時計は `appkit:timer`、メインスレッド上の `NSTimer` で、そこはそもそも Metal がフレームを走らせたい場所です。返り値はタイマーなので `(objc:send timer "invalidate")` でループを止められます。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (metal:run *ctx* (lambda (encoder) (draw encoder)) :fps 30)
#<objc __NSCFTimer>
```

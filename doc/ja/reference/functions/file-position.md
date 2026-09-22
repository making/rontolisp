# file-position

`(file-position stream [position])`

引数が1つの場合、**バイナリ**ファイルストリーム（`:element-type '(unsigned-byte 8)` で開いたもの）、または要素型を問わず**双方向**のもの（`:direction :io` か `:if-exists :overwrite`）の現在のバイト位置を返します。2つの場合は `position` へ位置を移動して `t` を返し、次の読み書きはそこから始まります。`position` には `:start` と `:end` も指定できます。

位置を判定できないものは `nil` を返します。これは Common Lisp がまさにその場合に規定している値です。`:input` か `:output` で開いた文字ファイルストリーム、文字列ストリーム、ソケット、標準ストリーム、そしてすでにクローズされたハンドルが該当します。移植性のある呼び出し側は `ignore-errors` で保護し、`nil` のときは非シークのフォールバック経路を通ります。

**4つのバックエンドすべてが実際の値を返します**。インタプリタとJVMはハンドルごとの位置を保持してバイトプリミティブが進め、位置指定ではそのオフセットでファイルを開き直します。Preview 1 WASM は `fd_seek` でディスクリプタ自体のカーソルを読み書きし、コンポーネントバックエンドにはカーソルがない（WASI 0.3 の読み取りはオフセット指定）ため、アダプタが追跡するディスクリプタごとのバイトオフセットを経由します。

```lisp
(with-input-from-string (s "abc")
  (file-position s)) ; => NIL
```

```console
(with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
  (print (file-position in 5))
  (print (read-byte in))
  (print (file-position in)))
```

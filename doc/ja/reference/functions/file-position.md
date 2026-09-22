# file-position

`(file-position stream [position])`

引数が1つの場合、ファイルストリームの現在位置を返します。**バイナリ**のもの（整数の `:element-type` で開いたもの）はその要素単位で数えます（`'(unsigned-byte 8)` ならバイト単位）。**文字**のものは SBCL と同じくバイト単位で数えます。文字1つはその UTF-8 長だけ位置を進め、`read-line` で読んだ行は終端（CRLF なら2バイトとも）の後で終わり、`peek-char` で覗いた文字や `unread-char` で戻した文字はまだ消費されておらず、`:if-exists :append` で開いたストリームはファイルの末尾から始まります。2つの場合は `position` へ位置を移動して `t` を返し、次の読み書きはそこから始まります。`position` には `:start` と `:end` も指定できます。

位置を判定できないものは `nil` を返します。これは Common Lisp がまさにその場合に規定している値です。文字列ストリーム、ソケット、標準ストリーム、そしてすでにクローズされたハンドルが該当します。移植性のある呼び出し側は `ignore-errors` で保護し、`nil` のときは非シークのフォールバック経路を通ります。

**4つのバックエンドすべてが実際の値を返します**。インタプリタとJVMは、バイナリストリームではバイトプリミティブが動かした量を数え、文字ストリームと双方向ストリームではチャネルのオフセットを読み、位置指定ではファイルを開き直すか位置を移動します。Preview 1 WASM は `fd_seek` でディスクリプタ自体のカーソルを読み書きし、コンポーネントバックエンドにはカーソルがない（WASI 0.3 の読み取りはオフセット指定）ため、アダプタが追跡するディスクリプタごとのバイトオフセットを経由します。

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

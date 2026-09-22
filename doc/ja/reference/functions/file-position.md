# file-position

`(file-position stream [position])`

引数が1つの場合、**バイナリ**ファイルストリーム（整数の `:element-type` で開いたもの。位置はその要素単位で、`'(unsigned-byte 8)` ならバイト単位）、または要素型を問わず**双方向**のもの（`:direction :io` か `:if-exists :overwrite`）の現在のバイト位置を返します。2つの場合は `position` へ位置を移動して `t` を返し、次の読み書きはそこから始まります。`position` には `:start` と `:end` も指定できます。

**文字列**ストリームにも文字単位の位置があります。入力ストリームは自身の先頭から読んだ文字数を数え（文字列の一部を読むストリームは 0 から始まります）、インデックス、`:start`、`:end` へ位置を移動できます。末尾を越えるインデックスは nil を返し、位置は変わりません。`unread-char` で戻した文字はまだ読まれていないものとして数えます。出力ストリームは `get-output-stream-string` が最後に空にしてから書き込んだ文字数を数え、現在の位置以外へは移動できません。

位置を判定できないものは `nil` を返します。これは Common Lisp がまさにその場合に規定している値です。`:input` か `:output` で開いた文字ファイルストリーム、ソケット、標準ストリーム、そしてすでにクローズされたハンドルが該当します。移植性のある呼び出し側は `ignore-errors` で保護し、`nil` のときは非シークのフォールバック経路を通ります。

**4つのバックエンドすべてが実際の値を返します**。インタプリタとJVMはハンドルごとの位置を保持してバイトプリミティブが進め、位置指定ではそのオフセットでファイルを開き直します。Preview 1 WASM は `fd_seek` でディスクリプタ自体のカーソルを読み書きし、コンポーネントバックエンドにはカーソルがない（WASI 0.3 の読み取りはオフセット指定）ため、アダプタが追跡するディスクリプタごとのバイトオフセットを経由します。

```lisp
(with-input-from-string (s "abcdef" :start 1)
  (read-char s)
  (let ((p (file-position s)))
    (list p (read-char s)))) ; => (1 #\c)
```

```console
(with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
  (print (file-position in 5))
  (print (read-byte in))
  (print (file-position in)))
```

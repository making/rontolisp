# rontolisp:json-span

`(rontolisp:json-span document &rest path)` / `(rontolisp:json-span address size &rest path)`

JSON ドキュメントのバイト列の中で、値がどこにあるかを求めます。ドキュメントを
Lisp の値にはしません。ドキュメント全体を
[`rontolisp:json-parse`](rontolisp-json-parse.md) と同じ基準で検査し、パスを
たどり、ドキュメントの先頭バイトからのバイトオフセットを返します。編集は、
そのバイト範囲への挿入・置換・削除になります。

ドキュメントは文字列(その UTF-8 のバイト列)か `(unsigned-byte 8)` の
ベクタです。wasm-GC バックエンドでは、モジュール自身の線形メモリの `address`
から始まる `size` バイトも渡せます。Proxy-Wasm のホストが置いた本文がその例で、
バイト列はその場で読まれ、コピーされません。そのため数メガバイトの本文も、
すでに占めているメモリの中で探索されます。

パスはキーとインデックスの並びです。文字列はオブジェクトのキーを、非負の整数は
配列の要素を指します。パスを省くと、ドキュメント全体を指します。

```lisp
(multiple-value-list (rontolisp:json-span "{\"a\": [1, {\"b\": true}]}" "a" 1 "b"))   ; => (16 20 NIL 11 20)
(multiple-value-list (rontolisp:json-span "{\"a\": [1, {\"b\": true}]}"))   ; => (0 23 1 NIL NIL)
(multiple-value-list (rontolisp:json-span "{\"a\": [1, {\"b\": true}]}" "z"))   ; => (NIL)
```

## 返す値

| 値 | 内容 |
|----|------|
| `start` | 値の先頭バイトのオフセット |
| `end` | 値の末尾バイトの次のオフセット |
| `count` | オブジェクトならメンバー数、配列なら要素数。それ以外の値では `nil` |
| `cut-start`, `cut-end` | そのメンバーを削除するときに取り除くバイト範囲。キーまたは要素と、その隣のカンマ 1 個からなり、コンテナ内の唯一のメンバーならコンテナの内側すべてになります。ドキュメント全体に対しては `nil` |

パスが値に行き着かないときは `nil` を返します。キーやインデックスが存在しない
場合と、パスの途中の値がその種類のコンテナでない場合です。オブジェクト内で
キーが重複していれば、`json-parse` が採用するのと同じ最後の出現を返します。
オフセットは、ホストのバッファと同じく、どのバックエンドでもバイト単位です。
したがって ASCII 以外の文字を含む文字列では文字インデックスと一致しないので、
テキストを切り出すにはオクテットのベクタを使ってください。

```lisp
(multiple-value-list (rontolisp:json-span "{\"é\": 1}" "é"))   ; => (7 8 NIL 1 8)
(multiple-value-list (rontolisp:json-span (rontolisp:string-to-octets "[10, 20]") 1))   ; => (5 7 NIL 3 7)
```

## 編集

値を置換するには `start` から `end` までを書き換えます。コンテナへの挿入は
閉じ括弧の位置 `(- end 1)` に行い、`count` が 0 でなければカンマを書きます。
メンバーの削除は `cut-start` から `cut-end` までを取り除きます。

```lisp
(defun json-replace (doc path text)
  (multiple-value-bind (start end) (apply #'rontolisp:json-span doc path)
    (concatenate 'string (subseq doc 0 start) text (subseq doc end))))
(json-replace "{\"id\": 7, \"tags\": []}" '("id") "8")   ; => "{\"id\": 8, \"tags\": []}"
(let ((doc "{\"id\": 7, \"tags\": [\"a\"]}"))
  (multiple-value-bind (start end count) (rontolisp:json-span doc "tags")
    (declare (ignore start))
    (concatenate 'string (subseq doc 0 (- end 1)) (if (plusp count) ", " "") "\"b\""
                 (subseq doc (- end 1)))))   ; => "{\"id\": 7, \"tags\": [\"a\", \"b\"]}"
(let ((doc "{\"id\": 7, \"tags\": []}"))
  (multiple-value-bind (start end count cut-start cut-end) (rontolisp:json-span doc "id")
    (declare (ignore start end count))
    (concatenate 'string (subseq doc 0 cut-start) (subseq doc cut-end))))   ; => "{\"tags\": []}"
```

Proxy-Wasm のフィルタも同じ方法で、ホストのバッファをその場で編集します。
書き戻しには `set-buffer` の `:start` と `:size` を使い、これらもバイト単位です
([SDK](https://github.com/making/rontolisp/tree/develop/examples/proxy-wasm))。

```console
(proxy-wasm:with-buffer (address size :response-body 0 body-size)
  (multiple-value-bind (start end members)
      (handler-case (rontolisp:json-span address size) (error () nil))
    ;; an object: a field before its closing brace
    (when (and members (eql (proxy-wasm:load8 (+ address start)) 123))
      (proxy-wasm:set-buffer :response-body
                             (concatenate 'string (if (plusp members) "," "")
                                          "\"seen\":true")
                             :start (- end 1) :size 0))))
```

`address size` の代わりに本文をオクテットのベクタで渡すと、同じオフセットが
返ります。インタプリタや JVM でフィルタのロジックをテストするときの渡し方です。
バイト範囲ではなく値そのものを読むには
[`rontolisp:json-value`](rontolisp-json-value.md) を使います。

## エラー

`json-parse` が受け付けないドキュメントには、同じエラーをこの関数の名前で
通知します。そのため、ハンドラで「JSON でない」と「その値がない」(`nil`)を
区別できます。文字列でも非負の整数でもないパスのセグメントと、線形メモリを
持たないインタプリタや JVM に渡されたアドレスもエラーになります。

```console
CL-USER> (rontolisp:json-span "{\"a\": 1,}" "a")
Error: json-span: expected an object key string
```

wasm-GC では、`multiple-value-bind` の中で `ignore-errors` を使わず、`nil` を
返す `handler-case` で捕捉してください。`ignore-errors` は 2 番目の値として
コンディションを返すので、上の例ではそれが `end` に束縛されます。その `end`
に対する算術が残るため、`format` を含むコンディションの表示処理がモジュールに
入り、上のフィルタは 88 KB 大きくなりました。

## バックエンドの対応

すべてのバックエンド、すべての WASM モードで動作します。インタプリタと JVM は、
rontolisp 自身で書かれた走査をドキュメントのオクテット列に対して実行します。
wasm-GC バックエンドは、使われたときにネイティブの走査をプログラムに
コンパイルします。この走査は文字列のバイト列や線形メモリをその場で読みます
(オクテットのベクタは一度コピーします)。wasm-GC での走査のコストは、小さな
メンバーからなるドキュメントで 1 バイトあたり約 18 命令、文字列の内部で
1 バイトあたり 6 命令です。

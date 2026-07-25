# with-open-stream

`(with-open-stream (var stream-form) body...)`

`stream-form` が返すストリームを `var` に束縛し、その束縛のもとで本体フォームを評価した後ストリームを閉じ、最後の本体フォームの値を返します。`open` を伴わない [`with-open-file`](with-open-file.md) であり、対象はすでに手元にあるストリーム (ソケット、文字列ストリーム、移植性のある `open` 呼び出しの結果) です。インタプリタと JVM では本体が [`unwind-protect`](../special-forms/unwind-protect.md) で包まれるため、どの脱出経路でもストリームは閉じられます。WASM バックエンドは本体評価後に閉じる形を保ちます。

```lisp
(with-input-from-string (in "hello")
  (read-line in)) ; => "hello"
```

これは短縮形です。`with-open-stream` は自分で開いたストリームを受け取る一般形なので、静的に示します:

```console
(with-open-stream (s (open "f.txt" :direction :input))
  (read-line s)) ; => "hello"
```

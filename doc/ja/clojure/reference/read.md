# read

`(read)` / `(read reader)` / `(read reader eof-error? eof-value)` / `(read opts reader)`

リーダからデータを1つ読み、オラクルの `PushbackReader` と同じくリーダをそのデータの直後に残します。答えは同じテキストに対する [read-string](read-string.md) の答えと同じです。リーダとして渡せるのは `clojure.java.io/reader`（その上の `java.io.PushbackReader` はそのリーダ自身です）、`(java.io.PushbackReader. (java.io.StringReader. s))`（どのバックエンドでも文字列のリーダになります）、`*in*` です。`(read)` は `*in*` を読みます。ホストのリーダは拒否されます。入力の終わりでは `(read reader)` が `EOF while reading` を通知し、`(read reader false v)` と `(read {:eof v} reader)` は `v` を答えます。データの途中で入力が終わると、いずれの形でも通知します。4番目の引数（recursive?）は受け取って無視します。すべてのバックエンドで動きます（wasm ではファイルに `--dir` プリオープンが必要です）。値としては0から4個の引数を取ります。

```clojure
(let [r (java.io.PushbackReader. (java.io.StringReader. "(1 2) :k"))]
  (println (read r))
  (println (read r))
  (println (read r false :eof)))
```

```
(1 2)
:k
:eof
```

`spit` が書いた内容は、レコードも含めて読み戻せます。

```console
$ cat backup.clj
(defrecord Message [sender text])
(spit "/tmp/backup.clj" (list (->Message "ann" "hi")))
(prn (read (java.io.PushbackReader. (clojure.java.io/reader "/tmp/backup.clj"))))
$ rontolisp backup.clj
(#user.Message{:sender "ann", :text "hi"})
```

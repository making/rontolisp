# read

`(read)` / `(read reader)` / `(read reader eof-error? eof-value)` / `(read opts reader)`

Reads one datum from a reader and leaves the reader right after it, like the oracle's
`PushbackReader`, answering what [read-string](read-string.md) answers for the same
text. The reader is a `clojure.java.io/reader` (a `java.io.PushbackReader` over one is
that reader), `(java.io.PushbackReader. (java.io.StringReader. s))` (a string reader on
every backend) or `*in*`, which `(read)` reads; a host reader is refused. At the end of
input `(read reader)` signals `EOF while reading`, while `(read reader false v)` and
`(read {:eof v} reader)` answer `v`; the options map's `:read-cond`/`:features` decide reader
conditionals as for [read-string](read-string.md); an end inside a datum always signals. A fourth
argument (recursive?) is accepted and ignored. Runs on every backend (a file needs a
`--dir` preopen on wasm); as a value, zero to four arguments.

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

What `spit` wrote reads back, records included:

```console
$ cat backup.clj
(defrecord Message [sender text])
(spit "/tmp/backup.clj" (list (->Message "ann" "hi")))
(prn (read (java.io.PushbackReader. (clojure.java.io/reader "/tmp/backup.clj"))))
$ rontolisp backup.clj
(#user.Message{:sender "ann", :text "hi"})
```

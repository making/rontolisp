# asdf:initialize-source-registry

`(asdf:initialize-source-registry [parameter])`

`NAME.asd` の探索先を設定します ([システム探索パス](../../guides/asdf-systems.md#the-system-search-path))。
本物の ASDF と同じく、設定が指すディレクトリはこの呼び出しの時点で走査します。値は返しません。

```console
(asdf:initialize-source-registry
 '(:source-registry (:tree :here) :inherit-configuration))
(asdf:load-system :my-lib)
```

設定には、表の最後の 2 つのディレクティブのうち、ちょうど 1 つを含めます:

| ディレクティブ | 意味 |
|---|---|
| `(:directory LOCATION)` | ディレクトリ直下の `.asd` ファイル |
| `(:tree LOCATION)` | ディレクトリ以下の `.asd` ファイル。同じ名前が 2 つあれば最も浅いものを、深さが同じならパス名順で先のものを選びます |
| `(:exclude NAME...)`、`(:also-exclude NAME...)` | 後続の `:tree` が入らないディレクトリ名。`.git` や `_darcs` などの既定値を置き換える、または追加します |
| `(:include LOCATION)` | ファイル、またはディレクトリ内の `.conf` ファイルに書いた設定 |
| `:default-registry` | 本物の ASDF の `~/common-lisp/` と XDG データディレクトリ |
| `:inherit-configuration` | 継承する設定 (探索を行うファイルのディレクトリ、`--system-path`、`RONTOLISP_SOURCE_REGISTRY`) を、この位置で探索します |
| `:ignore-inherited-configuration` | 継承する設定を使わず、ここで指定したディレクトリだけを探索します |

LOCATION は絶対パス、または絶対パスと相対要素のリストです (`(:home "lisp/")`、
`(:here "vendor/")`)。`:here` はロード中のファイル、または設定ファイルのディレクトリです。
本物の ASDF と同じく、相対パスは拒否します。

文字列のパラメータは本物の ASDF の短縮形で、ディレクトリを `:` で連結します。末尾の `//` は
`:tree`、空の要素は `:inherit-configuration` を表し、空の要素がない文字列は継承する設定を使いません。
`(` で始まる文字列は設定として読み取ります。nil は継承する設定だけを使います。

本物の ASDF と異なり、`:default-registry` を指定しない限り `CL_SOURCE_REGISTRY`、
`~/.config/common-lisp/`、`~/common-lisp/` は読みません。プログラムの探索結果がマシンに依存する経路は
`--system-path` と `RONTOLISP_SOURCE_REGISTRY` だけです。

`asdf:clear-source-registry` はこの設定を取り消し、次の探索は継承する設定から始まります。
`asdf:compute-source-registry`、`asdf:ensure-source-registry`、`asdf:process-source-registry` は
本物の ASDF のものです。不正な設定は `asdf/source-registry:invalid-source-registry` を通知します。
これは `warning` ですが、`error` で通知します。

## バックエンドサポート

インタプリタは呼び出しをそのまま実行します。コンパイルパスは、`load-system` がリテラルの名前だけを
読むのと同じく、パラメータがリテラルのトップレベル呼び出しをスプライス中に読みます。`:here` は
呼び出しを含むファイルのディレクトリです。計算したパラメータは、コンパイルパスの探索結果に影響しません。
呼び出しはプログラムにも残るので、変数と `sysdef-source-registry-search` は実行時に、そのプログラムが
一覧できる範囲で答えます。

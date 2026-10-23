;;;; rontolisp-intro.lisp -- an introduction to rontolisp for a programmer who has never
;;;; seen it: what it is, how to run it, the language, the backends, the libraries.
;;;;
;;;; Every fact comes from the repository (README.md, doc/ja, .kb, examples/,
;;;; bench-report/). The runnable blocks are small, so they finish at once on the
;;;; hosted browser runtime. Rendered by decks/build.sh.

(deck :title "rontolisp 入門"
      :author "rontolisp"
      :date "2026-10-23"
      :logo (image "logo.svg" :alt "rontolisp")
      :url "https://github.com/making/rontolisp"
      :description "Java で実装した Common Lisp のサブセット rontolisp の入門。インストール、言語、インタプリタ・JVM・WASM・ネイティブの各バックエンド、ライブラリまで"
      :page-url "https://making.github.io/rontolisp/decks/rontolisp-intro/rontolisp-intro.html"
      :page-image "rontolisp-intro.png"
      :page-image-font ("fonts/BIZUDPGothic-Regular.ttf" "fonts/BIZUDPGothic-Bold.ttf")
      :theme (theme :base :light :code :dark)
      :aspect :16x9
      :runtime :hosted
      :kicker :section
      :agenda "話の流れ"
      :progress t
      :page-numbers t)

;;; --- what rontolisp is ------------------------------------------------------------

(section "rontolisp とは")

(slide :title "Java で実装した Common Lisp のサブセット"
  (p "一つのソースを、三つの実行モードで動かす。")
  (ul (li (b "インタプリタ") " REPL とファイル実行。ツリーウォークで評価する")
      (li (b "JVM コンパイラ") " " (tt ".class") " や " (tt ".jar") " を出力する")
      (li (b "WASM コンパイラ") " wasm-GC を使った " (tt ".wasm") " を出力する"))
  (p "コアライブラリは外部依存を持たない。JVM と WASM のバイトコードも、ASM などのライブラリを使わずに出力する。"))

(slide :title "なぜサブセットなのか"
  (ul "インタプリタ、JVM、WASM で同じように動く"
      "ランタイムのメタオブジェクトプロトコルなしでバイトコードへ直接コンパイルできるよう、言語を小さく保つ"
      "そのため、完全な Common Lisp の機能の多くを意図的に省いている")
  (source "doc/ja/guides/missing-features.md"))

(slide :title "まず動かしてみる"
  (p "このスライドのコードはブラウザの中の rontolisp が実行する。クリックで編集し、Run で実行する。")
  (say "Run で 3628800 が出る" :at "(fact 10)" :step t
    (code :lang "lisp" "
      (defun fact (n)
        (if (= n 0) 1 (* n (fact (- n 1)))))
      (print (fact 10))"))
  (notes "Run を押す。続けて (fact 30) に書き換えて実行し、多倍長整数になることを見せる。"))

;;; --- getting started --------------------------------------------------------------

(section "インストールと最初の実行")

(slide :title "インストール"
  (p "リリースページのビルド済みバイナリを " (tt "PATH") " に置く。JVM は要らない。")
  (code :lang "console" "
    $ URL=https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT
    $ wget $URL/rontolisp-darwin-arm64
    $ chmod +x rontolisp-darwin-arm64
    $ sudo mv rontolisp-darwin-arm64 /usr/local/bin/rontolisp
    $ rontolisp --version")
  (ul "Linux の x86-64 と ARM64 向けもある"
      "Java 25 以降があれば、実行可能 JAR でも動く"))

(slide :title "REPL"
  (p "引数なしで起動すると REPL になる。定義は入力をまたいで残る。")
  (code :lang "console" "
    $ rontolisp
    CL-USER> (+ 1 2)
    3
    CL-USER> (defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
    FACT
    CL-USER> (fact 10)
    3628800
    CL-USER> (quit)")
  (p "プロンプトには現在のパッケージ名が出る。括弧が閉じるまで複数行を読む。"))

(slide :title "ファイルを実行する"
  (column
    (code :lang "lisp" :run nil "
      ;; program.lisp
      (defun square (x) (* x x))
      (print (square 5))
      (print (square 12))"))
  (column
    (code :lang "console" "
      $ rontolisp program.lisp

      25
      144
      $ rontolisp -e \"(print (+ 1 2))\"

      3")
    (ul "REPL と違い、値をエコーしない"
        "エラーで終わると、終了コードが 0 以外になる")))

(slide :title "コマンドの一覧"
  (table :header ("コマンド" "動作")
    ((tt "rontolisp") "REPL")
    ((tt "rontolisp app.lisp") "ファイルをインタプリタで実行")
    ((tt "rontolisp -e \"(...)\"") "引数のプログラムを実行")
    ((tt "rontolisp app.lisp -o OUT") (cell "コンパイル。" (tt "OUT") " の拡張子で出力を選ぶ"))
    ((tt "rontolisp format src/") "ソースを正規のレイアウトに再インデント")
    ((tt "rontolisp test tests/main.lisp") "rove のテストを実行し、結果を終了コードにする")))

;;; --- the language -----------------------------------------------------------------

(section "言語")

(slide :title "使える Common Lisp の範囲"
  (table :header ("機能" "状況")
         :caption "詳しくは「未対応の CL 機能」のページにある"
    ("マクロ、バッククォート" "使える")
    ("コンディションとリスタート" "使える。デバッガとの統合はない")
    ("多値" "使える")
    ("CLOS" "一部。静的なサブセットと、定義時の MOP のサブセット")
    ((tt "loop") "一部 (拡張版)")
    ((tt "eval-when") (cell (tt "progn") " として扱う"))))

(slide :title "プログラムもリスト"
  (p "コードは S 式で書く。S 式はリストなので、マクロはリストを組み立ててコードを作る。")
  (cells '(progn (princ "hi ") (princ "hi ")))
  (code :lang "lisp" "
    (defmacro twice (&body body) `(progn ,@body ,@body))
    (print (macroexpand-1 '(twice (princ \"hi \"))))
    (twice (princ \"hi \"))"))

(slide :title "CLOS とコンディション"
  (code :lang "lisp" "
    (defclass circle () ((r :initarg :r :reader r)))
    (defclass square () ((side :initarg :side :reader side)))
    (defgeneric area (shape))
    (defmethod area ((c circle)) (* 3 (r c) (r c)))
    (defmethod area ((s square)) (* (side s) (side s)))
    (print (mapcar #'area (list (make-instance 'circle :r 2)
                                (make-instance 'square :side 3))))
    (handler-case (/ 1 0)
      (division-by-zero () :infinity))"))

(slide :title "Scheme のフロントエンド (実験的)"
  (p (tt ".scm") " のファイルを、R7RS-small の一部として読む。")
  (code :lang "scheme" "
    (import (scheme base) (scheme write))
    (define (count-up n)
      (let loop ((i 0) (acc '()))
        (if (= i n)
            (reverse acc)
            (loop (+ i 1) (cons (* i i) acc)))))
    (display (count-up 5))"))

(slide :title "Clojure のフロントエンド (実験的)"
  (p (tt ".clj") " と " (tt ".cljc") " を、Clojure の小さなサブセットとして読む。")
  (code :lang "clojure" "
    (defn fact [n]
      (if (< n 2) 1 (* n (fact (- n 1)))))
    (println (fact 20))
    (println (->> (range 10) (filter odd?) (map #(* % %)) (reduce +)))
    (println (frequencies \"mississippi\"))"))

(slide :title "言語はファイルごとに決まる"
  (table :header ("拡張子" "言語")
    ((tt ".lisp .asd") "Common Lisp")
    ((tt ".scm") "Scheme")
    ((tt ".clj .cljc") "Clojure")
    ("それ以外" (cell (tt "--source-language") " で指定する")))
  (ul "一つのプログラムに複数の言語のファイルを混ぜられる"
      "どの言語も同じコアフォームに変換され、すべてのバックエンドで動く"
      "Scheme と Clojure は実験的。動かし続けたいものは Common Lisp で書く"))

;;; --- backends ---------------------------------------------------------------------

(section "バックエンド")

(slide :title "一つのパイプラインから三つの実行モードへ"
  (flow "ソース (CL、Scheme、Clojure)" "コアフォームに変換" "マクロ展開"
        ("インタプリタ、" (tt ".class") "、" (tt ".wasm")))
  (ul "言語の違いは最初の変換で吸収する"
      "バックエンドは Scheme や Clojure の名前を知らない"))

(slide :title "出力の拡張子でバックエンドを選ぶ"
  (code :lang "console" "
    $ rontolisp hello.lisp                                # インタプリタ
    $ rontolisp hello.lisp -o Hello.class && java Hello   # JVM
    $ rontolisp hello.lisp -o hello.jar && java -jar hello.jar
    $ rontolisp hello.lisp -o hello.wasm && wasmtime run hello.wasm
    $ rontolisp hello.lisp -o hello-c.wasm --component && wasmtime run hello-c.wasm
    $ rontolisp hello.lisp --native -o hello && ./hello   # ネイティブ")
  (p "どの形でも " (tt "(print (+ 1 2))") " は 3 を出力する。"))

(slide :title "JVM と WASM"
  (column
    (p (b "JVM"))
    (ul (li (tt ".jar") " は " (tt "java -jar") " で動く")
        "Java のライブラリにもできる"))
  (column
    (p (b "WASM"))
    (ul (li "既定は WASI Preview 1")
        (li (tt "--component") " で WASI 0.3")
        (li (tt "--no-wasi") " でインポートなし")
        (li (tt "--no-gc") " で任意のエンジン向け (数値と文字列のみ)"))))

(slide :title "ネイティブ実行ファイル"
  (p (tt "--native") " は、WASM 出力を wasmtime でマシンコードにプリコンパイルし、小さなランナーの後ろに付ける。")
  (ul "実行に wasmtime も JVM も要らない"
      "起動は約 10 ms"
      (li (tt "--native-target") " で Linux の x86_64 と aarch64、macOS の aarch64 向けを作れる"))
  (source "doc/ja/compiling/native.md"))

(slide :title "インタプリタとコンパイル結果の速さ"
  (lead "fib ベンチマークの実行時間 (短いほど速い)")
  (say "ループ中心のコードは、コンパイルで 20〜200 倍速くなる" :at "インタプリタ"
    (bars :unit " ms" ("インタプリタ" "5,442") ("JVM" 60) ("WASM" 218)))
  (source "bench-report/results/benchmarks.md (2026-09-04, Linux x86_64, AMD EPYC 9V74)"))

(slide :title "インタプリタは試行錯誤に使う"
  (ul "即座に起動し、コンパイラと同じ意味で実行する"
      "I/O が中心のスクリプトなら、速さの差は表に出にくい"
      "CPU を使い切る処理は、コンパイルした成果物で動かす")
  (code :lang "console" "
    $ rontolisp program.lisp -o program.jar
    $ java -jar program.jar"))

(slide :title "ブラウザで動かす"
  (ul (li (b "プレイグラウンド") " rontolisp 自体を GraalVM の Web Image で WebAssembly にしたもの。"
          "REPL と、" (tt ".class") " や " (tt ".wasm") " へのコンパイルがブラウザの中で完結する")
      (li (b "ドキュメント") " リファレンスの Lisp の例は Run で実行できる")
      (li (b "このデッキ") " 同じブラウザ版がコードを実行している")
      (li (b "自分のプログラム") " WASM にコンパイルしてページから呼ぶ ("
          (tt "examples/browser/") " のマインスイーパーや WebGL)")))

;;; --- interop and libraries --------------------------------------------------------

(section "連携とライブラリ")

(slide :title "Java 連携"
  (p (tt "java") " パッケージが、リフレクションで任意の Java API を呼ぶ。")
  (code :lang "lisp" :run nil "
    (java:call (java:new \"java.lang.StringBuilder\" \"ab\") \"length\")  ; => 2
    (java:static \"java.lang.Math\" \"max\" 3 7)                        ; => 7
    (java:field \"java.lang.Integer\" \"MAX_VALUE\")                    ; => 2147483647")
  (ul (li "JVM 上のインタプリタと、コンパイルした " (tt ".class") " で動く")
      "WASM はホストのオブジェクトを表せないので、コンパイルを拒否する"))

(slide :title "ASDF で複数ファイルのプロジェクトを読む"
  (column
    (code :lang "lisp" :run nil "
      ;; my-app.asd
      (defsystem :my-app
        :depends-on (:base)
        :serial t
        :components ((:file \"package\")
                     (:file \"main\")))"))
  (column
    (ul (li (tt ".asd") " はプレーンなデータとして解析する")
        "依存するシステムから順に読み込む"
        (li (tt "--system-path") " で探す場所を足す")
        "インタプリタ、JVM、WASM のどれでも同じように動く")))

(slide :title "実在のライブラリをそのまま読む"
  (code :lang "console" "
    $ rontolisp
    CL-USER> (ql:quickload \"split-sequence\")
    (split-sequence)
    CL-USER> (split-sequence:split-sequence #\\, \"a,b,c\")
    (\"a\" \"b\" \"c\")")
  (ul (li (tt "ql:quickload") " は Quicklisp の配布物から取得し、" (tt "~/.rontolisp/quicklisp/") " にキャッシュする")
      "alexandria、cl-ppcre、cl-who、jzon、chipz などを、上流のソースのまま 4 つのバックエンドで動かしている"))

(slide :title "そのほかの組み込みパッケージ"
  (table :header ("パッケージ" "用途")
         :caption "動くバックエンドはパッケージごとに違う。各ガイドを参照"
    ((tt "uiop") "環境変数、ファイル、ディレクトリなど (一部を実装)")
    ((cell (tt "rove") "、" (tt "rontolisp test")) "テスト")
    ((tt "rontolisp:fetch") "HTTP リクエスト")
    ((tt "clack") "Web アプリケーション")
    ((cell (tt "linalg") "、" (tt "torch")) "線形代数とニューラルネットワーク")
    ((tt "cffi") "C ライブラリの呼び出し (JVM)")
    ((cell (tt "objc") "、" (tt "appkit")) "macOS の GUI")))

;;; --- next steps -------------------------------------------------------------------

(section "次の一歩")

(slide :title "examples ディレクトリ"
  (table :header ("ディレクトリ" "内容")
    ((tt "console/") "アルゴリズムとコンソール入出力")
    ((cell (tt "scheme/") " " (tt "clojure/")) "Scheme と Clojure のプログラム")
    ((cell (tt "ml/") " " (tt "llm/")) "数値計算、機械学習、Llama 2 の推論")
    ((cell (tt "net/") " " (tt "db/")) "ソケット、HTTP サーバ、PostgreSQL")
    ((cell (tt "jvm/") " " (tt "macos/")) "Java 連携、Swing、Cocoa、Metal")
    ((tt "browser/") "ページで動かす WASM")
    ((tt "asdf/") "実在のライブラリの読み込み")))

(slide :title "次に読むもの"
  (ul (li (link "https://making.github.io/rontolisp/docs/" "ドキュメント")
          " はじめに、コンパイル、言語リファレンス、ガイド")
      (li (link "https://making.github.io/rontolisp/playground.html" "プレイグラウンド")
          " インストールせずに試す")
      (li (link "https://making.github.io/rontolisp/skill/" "エージェントスキル")
          " AI コーディングエージェント向けに、ドキュメントから生成したもの")
      (li (link "https://github.com/making/rontolisp" "GitHub")
          " ソースと " (tt "examples/"))))

(slide :class "closing" "インタプリタで試し、同じソースを JVM、WASM、ネイティブで動かす。")

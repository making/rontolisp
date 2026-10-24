;;;; intro.lisp -- a talk introducing lispresen, and the golden input of check.sh.
;;;;
;;;; Each feature comes as a pair of slides under one title, the kicker telling them apart:
;;;; 書き方 shows the deck code that writes it and 表示例 the result on the next slide. A
;;;; feature the player provides gets 使い方, its instructions, and then 試す, a slide to try
;;;; it on. The pair's kicker replaces the deck's section kicker.

(deck :title "lispresen: S式で書くプレゼンテーション"
      :author "rontolisp"
      :date "2026-10-23"
      :logo (image "logo.svg" :alt "rontolisp")
      :url "https://github.com/making/rontolisp/tree/develop/lispresen"
      :description "S式で書いたデッキから、文字の大きさと配置を決めた HTML を一つ作るプレゼンテーションツール"
      :page-url "https://making.github.io/rontolisp/lispresen/intro.html"
      :page-image "intro.png"
      :page-image-font ("fonts/BIZUDPGothic-Regular.ttf" "fonts/BIZUDPGothic-Bold.ttf")
      :theme (theme :base :light :code :dark)
      :aspect :16x9
      :runtime :hosted
      :kicker :section
      :agenda "話の流れ"
      :progress t
      :page-numbers t)

;; The deck code an explanation slide shows: highlighted, never run.
(define sexp (text)
  (code :lang "lisp" :run nil text))

;;; --- what lispresen is, and why S-expressions -------------------------------------

;; The opening slide comes before the agenda, which (agenda) places after it.
(slide :title "S式のデッキから HTML を一つ作る"
  (flow (tt "talk.lisp") "lispresen" (tt "talk.html") "ブラウザ")
  (ul "書き手は内容だけを書く"
      "文字の大きさと配置は lispresen が決める"
      "できた HTML ファイル一つで発表できる"))

(agenda)

(section "lispresen とは")

(slide :title "三つの特長"
  (ol (li (b "読めない文字を出さない")
          (ul "収まらないスライドは、文字を縮めずにエラーにする"))
      (li (b "スライドのコードを動かす")
          (ul "Lisp、Scheme、Clojure をその場で編集して実行する"))
      (li (b "どこで描画しても同じ出力")
          (ul "インタプリタ、JVM、WASM、SBCL が同じバイト列を書く"))))

(slide :title "なぜS式で書くのか"
  (ul "差分をコードと同じようにレビューできる"
      (li "構造が明示される"
          (ul "インデントに意味を持たせない"
              "見えないスタイルがない"))
      "プログラムで生成したデッキもただのリスト")
  (notes "Markdown や WYSIWYG のツールと比べて話す。"))

(slide :title "デッキは読むだけで評価しない"
  (p "トップレベルのフォームを一つずつ " (tt "read") " する。")
  (ul (li "マクロも " (tt "#.") " も使えない")
      "どのバックエンドでも同じ言語になる"
      "信頼できないデッキも安全に描画できる")
  (notes "フォームごとに読むので、診断には行番号が付く。"))

;;; --- writing a deck ---------------------------------------------------------------

(section "デッキを書く")

(slide :title "段落と箇条書き" :kicker "書き方"
  (p (tt "slide") " の中に、ブロックを上から順に並べる。")
  (sexp "
    (slide :title \"段落と箇条書き\"
      \"文字列だけでも段落になる。\"
      (ul \"ul は記号付きの箇条書き\"
          (li \"入れ子は\" (ul \"二段まで\")))
      (ol \"ol は番号付き\" \"7 項目からは警告する\")
      (notes \"発表者ウィンドウにだけ出る。\"))"))

(slide :title "段落と箇条書き" :kicker "表示例"
  "文字列だけでも段落になる。"
  (ul "ul は記号付きの箇条書き"
      (li "入れ子は" (ul "二段まで")))
  (ol "ol は番号付き" "7 項目からは警告する")
  (notes "発表者ウィンドウにだけ出る。"))

(slide :title "文字の装飾" :kicker "書き方"
  (p "装飾もフォームで書き、段落の中に並べる。")
  (sexp "
    (lead \"lead は控えめな色の段落\")
    (p (b \"太字\") \"、\" (i \"italic\") \"、\" (tt \"(code)\") \"、\"
       (mark \"マーカー\") \"、\" (accent \"アクセント色\") \"、\"
       (link \"https://github.com/making/rontolisp\" \"リンク\"))"))

(slide :title "文字の装飾" :kicker "表示例"
  (lead "lead は控えめな色の段落")
  (p (b "太字") "、" (i "italic") "、" (tt "(code)") "、"
     (mark "マーカー") "、" (accent "アクセント色") "、"
     (link "https://github.com/making/rontolisp" "リンク")))

(slide :title "表" :kicker "書き方"
  (p "行はセルのリスト。数値だけの列は右に揃う。")
  (sexp "
    (table :header (\"種類\" \"フォーム\" \"数\")
           :row-header t :align (:left :left :right)
           :caption \"デッキの要素は、どれもリストとして書く\"
      (\"図と数式\" (tt \"flow cells bars math\") \"4\")
      ...
      ((cell :colspan 2 \"合計\") \"30\"))"))

(slide :title "表" :kicker "表示例"
  (table :header ("種類" "フォーム" "数")
         :row-header t :align (:left :left :right)
         :caption "デッキの要素は、どれもリストとして書く"
    ("トップレベル" (tt "deck section slide agenda define") "5")
    ("ブロック" (tt "p lead ul ol code quote column image video table") "10")
    ("部品" (tt "li cell") "2")
    ("図と数式" (tt "flow cells bars math") "4")
    ("インライン" (tt "b i tt mark accent link") "6")
    ("注釈" (tt "say notes source") "3")
    ((cell :colspan 2 "合計") "30")))

(slide :title "引用と出典" :kicker "書き方"
  (p (tt "source") " はスライドの下端に出典を一行で置く。")
  (sexp "
    (quote :cite \"Alan J. Perlis\"
      \"A language that doesn't affect the way ...\")
    (source \"Alan J. Perlis, \" (i \"Epigrams on Programming\")
            \", SIGPLAN Notices 17(9), 1982\")"))

(slide :title "引用と出典" :kicker "表示例"
  (quote :cite "Alan J. Perlis"
    "A language that doesn't affect the way you think about programming is not worth knowing.")
  (source "Alan J. Perlis, " (i "Epigrams on Programming")
          ", SIGPLAN Notices 17(9), 1982"))

(slide :title "コードとコンソール" :kicker "書き方"
  (p "文字列の共通の字下げは取り除く。" (tt "console") " はシェルの記録として描く。")
  (sexp "
    (code :lang \"lisp\" :run nil \"
      (defun square (x)
        (* x x))\")
    (code :lang \"console\" \"
      $ lispresen/check.sh
      ok   interpreter\")"))

(slide :title "コードとコンソール" :kicker "表示例"
  (code :lang "lisp" :run nil "
    (defun square (x)
      (* x x))")
  (code :lang "console" "
    $ lispresen/check.sh
    ok   interpreter
    ok   jvm
    skip native: the jar carries no precompiler for this platform (linux-x86_64)"))

(slide :title "ほかの言語のコード" :kicker "書き方"
  (p (tt ":lang") " が " (tt "java") "、" (tt "python") "、" (tt "bash") "、"
     (tt "sql") "、" (tt "json") " などなら、その言語の規則で色を付ける。"
     "動かせるのは Lisp、Scheme、Clojure だけ。")
  (sexp "
    (code :lang \"java\" \"
      long square(long x) {
        return x * x; // ...
      }\")"))

(slide :title "ほかの言語のコード" :kicker "表示例"
  (column
    (code :lang "java" "
      // the same function in Java
      static long square(long x) {
        return x * x;
      }"))
  (column
    (code :lang "python" "
      def square(x):
          \"\"\"x times itself\"\"\"
          return x * x  # or None"))
  (code :lang "bash" "
    for f in talks/*.lisp; do
      lispresen \"$f\" -o \"${f%.lisp}.html\"  # one page each
    done"))

(slide :title "段組み" :kicker "書き方"
  (p "続けて書いた " (tt "column") " が横に並ぶ。前後のブロックは全幅になる。")
  (sexp "
    (lead (b \"問題\") \" ... → \" (b (accent \"解決\")) \" ...\")
    (column
      (code :lang \"lisp\" :run nil \"...\"))
    (column
      (ul \"コードの列は ...\" \"残りを ...\"))"))

;; A lead above a row of columns; the columns' widths come from their content.
(slide :title "段組み" :kicker "表示例"
  (lead (b "問題") " 等幅の列ではコードの行が収まらない → "
        (b (accent "解決")) " 列ごとに必要な幅を測って配分する")
  (column
    (code :lang "lisp" :run nil "
      (row-widths row k c (- 1920 224))"))
  (column
    (ul "コードの列は最長の行が収まる幅を取る"
        "残りを文章の列が使う")))

(slide :title "吹き出し" :kicker "書き方"
  (p "指す先を " (tt "say") " で包む。コードの中は " (tt ":at") " の文字列で指す。")
  (sexp "
    (li \"日本語の改行は \"
        (say \"BudouX のモデルで決める\" (mark \"文節ごと\")))
    (say \"n が 2 未満なら n を返す\" :at \"(< n 2)\"
      (code :lang \"lisp\" :run nil \"...\"))"))

(slide :title "吹き出し" :kicker "表示例"
  (ul "置き場所は lispresen が探す"
      (li "日本語の改行は " (say "BudouX のモデルで決める" (mark "文節ごと"))))
  (say "n が 2 未満なら n を返す" :at "(< n 2)"
    (code :lang "lisp" :run nil "
      (defun fib (n)
        (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))")))

(slide :title "流れ図" :kicker "書き方"
  (p "箱を左から右へ矢印でつなぐ。" (tt ":at") " で箱の一つを指せる。")
  (sexp "
    (lead \"lispresen の処理の流れ\")
    (say \"テンプレートもここで展開する\" :at \"parse\"
      (flow \"read\" \"parse\" \"segment\"
            \"layout\" \"lint\" \"emit\"))"))

(slide :title "流れ図" :kicker "表示例"
  (lead "lispresen の処理の流れ")
  (say "テンプレートもここで展開する" :at "parse"
    (flow "read" "parse" "segment" "layout" "lint" "emit")))

(slide :title "コンスセル" :kicker "書き方"
  (p (tt "cells") " はリストを箱と矢印で描く。ドット対も描ける。")
  (sexp "
    (column
      (say \"car はセルの下に描く\" :at \"slide\"
        (cells '(slide :title \"t\" (p \"x\")))))
    (column (cells '((a . 1) (b . 2))))"))

;; The cdr chain on one row, each car below its cell, a dotted pair's cdr to its right.
(slide :title "コンスセル" :kicker "表示例"
  (column
    (say "car はセルの下に描く" :at "slide"
      (cells '(slide :title "t" (p "x")))))
  (column (cells '((a . 1) (b . 2)))))

(slide :title "棒グラフ" :kicker "書き方"
  (p "値は整数か、文字列で書いた小数。単位は書いたとおりに付く。")
  (sexp "
    (lead \"コンパイルした CLI の大きさ\")
    (say \"JVM なしで動く\" :at \"ネイティブ\"
      (bars :unit \" KB\" (\"JVM (jar)\" 297) (\"WASM\" 788)
            (\"WASM component\" 796) (\"ネイティブ\" \"6,700\")))
    (source \"2026-10-22, macOS arm64 で測定\")"))

;; A bar chart: a value is an integer or a decimal written as a string, the unit written
;; after it; the callout names a bar by its label. The source stands at the bottom.
(slide :title "棒グラフ" :kicker "表示例"
  (lead "コンパイルした CLI の大きさ")
  (say "JVM なしで動く" :at "ネイティブ"
    (bars :unit " KB" ("JVM (jar)" 297) ("WASM" 788) ("WASM component" 796)
          ("ネイティブ" "6,700")))
  (source "2026-10-22, macOS arm64 で測定 ("
          (link "https://github.com/making/rontolisp/blob/develop/lispresen/DESIGN.md"
                "DESIGN.md") ")"))

(slide :title "数式" :kicker "書き方"
  (p "前置記法の木がそのまま数式になる。段落の中でも、ブロックとしても書ける。")
  (sexp "
    (p \"計算量は \" (math (O (* n (log n)))) \" で済む\")
    (math (= (fib n)
             (cases (n (< n 2))
                    ((+ (fib (- n 1)) (fib (- n 2))) \"otherwise\"))))
    (say \"上下に範囲が付く\"
      (math (= (sum (= i 1) n (^ i 2)) (/ (* n (+ n 1) (+ (* 2 n) 1)) 6))))"))

;; A formula is laid out from its S-expression: inline in a paragraph, or as a block
;; centered in the body; a callout points at the whole block.
(slide :title "数式" :kicker "表示例"
  (p "計算量は " (math (O (* n (log n)))) " で済む。分数や添字も "
     (math (/ 1 (sqrt n))) " や " (math (_ x i)) " のように行の中に収まる。")
  (math (= (fib n) (cases (n (< n 2)) ((+ (fib (- n 1)) (fib (- n 2))) "otherwise"))))
  (say "上下に範囲が付く"
    (math (= (sum (= i 1) n (^ i 2)) (/ (* n (+ n 1) (+ (* 2 n) 1)) 6)))))

(slide :title "画像" :kicker "書き方"
  (p "画像は残りの場所に収まる。" (tt "--embed") " で HTML に埋め込む。")
  (sexp "
    (say \"--embed で HTML に埋め込んだ\"
      (image \"pipeline.png\"
             :alt \"read から emit までの六段階\"
             :caption \"PNG はテーマの色に従わない\"))"))

(slide :title "画像" :kicker "表示例"
  (say "--embed で HTML に埋め込んだ"
    (image "pipeline.png"
           :alt "read, parse, segment, layout, lint, emit の六段階"
           :caption "PNG はテーマの色に従わない")))

(slide :title "画面いっぱいの画像" :kicker "書き方"
  (p "題も本文もなく画像だけを置くと、画像で画面を覆う。次のスライドが表示例。")
  (ul (li (tt ":fit :contain") " なら切り取らず、全体を背景の上に収める")
      "写真の上に文字は置かない。ページ番号も出さない")
  (sexp "
    (slide (image \"photo.svg\" :alt \"夕暮れの山並み\"))"))

(slide (image "photo.svg" :alt "夕暮れの山並みの絵"))

(slide :title "画像と文章を並べる" :kicker "書き方"
  (p (tt ":layout :image-left") " か " (tt ":image-right") " で、画像を画面の端まで広げる。")
  (sexp "
    (slide :title \"画像と文章を並べる\" :layout :image-right
      (ul \"画像は上下と横の端まで届く\" ...)
      (image \"photo.svg\" :alt \"夕暮れの山並み\" :share 1/3))"))

(slide :title "画像と文章を並べる" :kicker "表示例" :layout :image-right
  (ul "画像は上下と横の端まで届く"
      (li "幅は " (tt ":share") " の 1/3, 1/2, 2/3 から選ぶ")
      "題と本文は残りの幅に収める")
  (image "photo.svg" :alt "夕暮れの山並みの絵" :share 1/3))

;; demo.mp4 is a REPL session drawn with ffmpeg (drawtext), 480x270, 5 s, no sound;
;; demo-poster.png is its last frame in 16 colors.
(slide :title "録画したデモ" :kicker "書き方"
  (p "動画は画像と同じ場所に収まる。" (tt "--embed") " で HTML に埋め込む。")
  (ul (li (tt ":autoplay t") " なら、スライドに入ると再生し、出ると止める")
      "クリックかタップで再生と一時停止を切り替える"
      (li "印刷と一覧には " (tt ":poster") " の画像が出る"))
  (sexp "
    (video \"demo.mp4\" :alt \"REPL で fib を呼ぶ\"
           :poster \"demo-poster.png\" :autoplay t :muted t :loop t)"))

(slide :title "録画したデモ" :kicker "表示例"
  (video "demo.mp4" :alt "REPL で (fib 30) と (map fib (range 10)) を評価する"
         :poster "demo-poster.png" :autoplay t :muted t :loop t))

(slide :title "テンプレート" :kicker "書き方"
  (p (tt "define") " で名前を付け、使う所を本体で置き換える。評価はしない。")
  (sexp "
    (define side (head &rest points)
      (column (p (b head)) (ul points)))

    (side \"インタプリタ\" \"起動が速い\" \"eval が完全\")
    (side \"コンパイラ\" \"実行が速い\" \"成果物が一つ\")"))

;; A template: a use is replaced by the body, &rest spliced where it appears.
(define side (head &rest points)
  (column (p (b head)) (ul points)))

(slide :title "テンプレート" :kicker "表示例"
  (side "インタプリタ" "起動が速い" "eval が完全")
  (side "コンパイラ" "実行が速い" "成果物が一つ"))

;;; --- layout -------------------------------------------------------------------------

(section "レイアウトはツールが決める")

(slide :title "文字の大きさは自動で決まる"
  (p "スライドごとに、収まる最大の段を選ぶ。")
  (ol (li "どのスライドも 1920 x 1080 (" (tt ":aspect :4x3") " なら 1440 x 1080) で組み、画面に合わせて拡大する")
      "本文は 56px から始め、必要なときだけ 40px まで下げる"
      "40px でも収まらなければ、縮めずにエラーとして報告する"))

(slide :title "大きさの段階"
  (table :header ("役割" "既定" "警告の境" "下限")
         :row-header t
         :caption "単位は px。警告の境を下回ると警告し、下限でも収まらなければエラーにする"
    ("題" "80" "64" "56")
    ("本文" "56" "48" "40")
    ("コード" "44" "32" "28")
    ("表" "48" "40" "36")
    ("吹き出し" "44" "40" "36")
    ("図のラベル" "48" "40" "36")))

;; :layout names the layout the slide's shape already gives, so it changes nothing.
(slide :title "レイアウトはスライドの形で決まる" :layout :table
  (table :header ("スライドの形" "レイアウト")
         :caption ":layout で選ぶこともできる"
    ("題がなく、段落が一つ" (tt "statement"))
    ("題がなく、画像か動画が一つ" (tt "full"))
    ("題と画像か動画が一つ" (tt "image"))
    ("題とコード、表、引用のどれか一つ"
     (cell (tt "code") " " (tt "table") " " (tt "quote")))
    ((cell (tt "column") " を含む") (tt "columns"))
    ("それ以外" (tt "standard"))))

(slide :title "一文だけのスライド" :kicker "書き方"
  (p "題を付けずに段落を一つだけ置くと、大きな文字で中央に置く。次のスライドが表示例。")
  (sexp "
    (slide \"読みやすさは、書き手ではなくツールが守る。\")"))

(slide "読みやすさは、書き手ではなくツールが守る。")

(slide :title "読みにくさを報告する"
  (code :lang "console" "
    $ java -jar lispresen.jar talk.lisp --check
    talk.lisp:42: warning: slide 7 \"比較\": a list with 8 items is hard to follow; keep it to 6")
  (ul (li "診断には行番号が付く。" (tt "--strict") " なら警告でも失敗する")
      "文字色と背景のコントラストも検査する"))

(slide :title "フォントで測り直す" :kicker "使い方"
  (p "発表するマシンのフォントは、lispresen が幅を測ったフォントと違うことがある。")
  (ul "開いたときに、各スライドの高さを測る"
      "はみ出す所だけ、段を一つずつ下げる"
      (li "URL に " (tt "?check") " を付けると、下げた所を一覧する")))

(slide :title "フォントで測り直す" :kicker "試す"
  (ol (li "URL の末尾に " (tt "?check") " を付けて開き直す")
      (li "右上に " (tt "runtime check: no corrections") " と出れば、補正はない")))

;;; --- presenting -------------------------------------------------------------------

(section "発表する")

(slide :title "キー操作" :kicker "使い方"
  (table :header ("キー" "動作")
         :caption "タップ (左 3 分の 1 で前へ) やスワイプでも送れる。印刷で 1 枚 1 ページの PDF"
    ((tt "→ ↓ Space PageDown") "次へ")
    ((tt "← ↑ PageUp") "前へ")
    ((tt "Home End") "最初と最後")
    ((tt "F") "全画面 (ダブルクリックでも)")
    ((tt "O") "全スライドの一覧")
    ((tt "P") "発表者ウィンドウ")
    ((tt "E") "コードの編集")))

(slide :title "キー操作" :kicker "試す"
  (ol "← で一枚戻り、→ で戻ってくる"
      "O で一覧を開き、矢印で選んで Enter で開く"
      "F で全画面にし、もう一度 F で戻る"
      (li "URL の末尾を " (tt "#5") " にして開き直す")))

(slide :title "発表者ウィンドウ" :kicker "使い方"
  (p (tt "P") " で開く。どちらのウィンドウで送っても、もう一方が付いてくる。")
  (sexp "(notes \"このノートは発表者ウィンドウにだけ出る。\")")
  (ul "今のスライドと次のスライド"
      "スライドの話者ノート"
      "タイマー (クリックで 0 に戻る)"
      "Start で、もう一つのディスプレイに全画面で映す"))

(slide :title "発表者ウィンドウ" :kicker "試す"
  (ol "P を押す"
      "このスライドのノートを読む"
      "→ で送り、こちらのウィンドウも進むことを確かめる")
  (notes "このノートは発表者ウィンドウにだけ出る。"))

(slide :title "ページ送りで一つずつ見せる" :kicker "書き方"
  (p "ページ送りのたびに一つずつ見せる。隠れている間も場所を取る。")
  (sexp "
    (ol :step t \"リストの項目\" \"コードの行\"
        (li (say \"項目の後に出る\" :step t \"吹き出し\")))
    (code :steps ((1 2) \"(fib 20)\") ...)"))

(slide :title "ページ送りで一つずつ見せる" :kicker "表示例"
  (ol :step t "リストの項目" "コードの行"
      (li (say "項目の後に出る" :step t "吹き出し")))
  (p "→ で順に出し、← で一つずつ戻す。"
     (tt "(deck :callouts :step)") " ならどの吹き出しも送りで出る。"))

(slide :title "ブロックを丸ごと送りで出す" :kicker "書き方"
  (p "段落、画像、動画、表、引用、図、数式、コード、列も " (tt ":step t")
     " で送りの一つになる。")
  (sexp "
    (bars :step t :unit \" KB\" ...)
    (lead :step t \"結論は最後に出す\")")
  (p "図と表は一度に全体が出る。吹き出しは包んだブロックと一緒に出る。"))

;; The chart and then the conclusion each take a page advance; the formula's callout is
;; no step, so it comes with the formula.
(slide :title "ブロックを丸ごと送りで出す" :kicker "表示例"
  (p "測った値を見せてから、結論を出す。")
  (bars :step t :unit " KB" ("JVM (jar)" 297) ("WASM" 788) ("ネイティブ" "6,700"))
  (say "jar の何倍か" (math :step t (approx (/ 6700 297) 23)))
  (lead :step t "→ 配るなら jar が一番小さい"))

(slide :title "コードを動かす" :kicker "書き方"
  (p "Lisp、Scheme、Clojure のコードはスライドの上で動く。")
  (sexp "
    (say \"Run で 6765 が出る\" :at \"(fib 20)\" :step t
      (code :file \"fib.lisp\" :lines (4 6)
            :steps ((1 2) \"(fib 20)\")))")
  (ul "クリックか E で編集し、Ctrl+Enter か Run で実行する"
      "Reset でデッキに書いたコードに戻す"
      "出力はスライドに重ねて開く。↑ ↓ でスクロールし、× か Esc で閉じる"))

;; Page advances bring the definition forward, then the call, then the callout on it; the
;; code is lines of a file beside the deck, its language from the extension.
(slide :title "コードを動かす" :kicker "表示例"
  (say "Run で 6765 が出る" :at "(fib 20)" :step t
    (code :file "fib.lisp" :lines (4 6) :steps ((1 2) "(fib 20)")))
  (notes "Run を押す。続けて (fib 25) に書き換えて実行し、Reset で戻す。"))

(slide :title "前のスライドの定義を使う"
  (p "同じ言語のコードは定義を共有する。前のスライドで Run してから実行する。")
  (code :lang "lisp" "(mapcar #'fib '(1 2 3 4 5 6 7 8 9 10))"))

(slide :title "書き換えを動きで見せる" :kicker "書き方"
  (p (tt ":morph t") " のスライドに入ると、前のスライドの最初のコードから変わる。")
  (sexp "
    (slide :title \"...\" :morph t
      (code :lang \"lisp\" \"
        ;; ループで書き直す
        (defun fib (n)
          (loop for a = 0 then b
                and b = 1 then (+ a b)
                repeat n
                finally (return a)))\"))")
  (p "共通のトークンは移動し、消えるものは薄れ、増えるものは現れる。"))

(slide :title "書き換えを動きで見せる" :kicker "表示例"
  (code :lang "lisp" "
    ;; 再帰で書く
    (defun fib (n)
      (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
    (fib 20)"))

;; :morph: on a page advance the code changes from the previous slide's, the tokens the
;; two share moving to their new place.
(slide :title "書き換えを動きで見せる" :kicker "表示例" :morph t
  (code :lang "lisp" "
    ;; ループで書き直す
    (defun fib (n)
      (loop for a = 0 then b
            and b = 1 then (+ a b)
            repeat n
            finally (return a)))
    (fib 20)"))

(slide :title "Clojure と Scheme" :kicker "書き方"
  (p (tt ":timeout") " 秒で止める。" (tt ":fresh t") " は新しいインタプリタで、"
     (tt ":stdin") " を標準入力にして動かす。")
  (sexp "
    (code :lang \"clojure\" :timeout 5 \"...\")
    (code :lang \"scheme\" :fresh t :stdin \"3 4\" \"...\")"))

(slide :title "Clojure と Scheme" :kicker "表示例"
  (column
    (code :lang "clojure" :timeout 5 "
      (defn square [x] (* x x)) ; a comment
      (map square (range 10))"))
  (column
    (code :lang "scheme" :fresh t :stdin "3 4" "
      (define (sq x) (* x x))
      (display (+ (sq (read)) (sq (read))))
      (newline)")))

(slide :title "ランタイムの置き場所"
  (lead "ランタイムは rontolisp のブラウザ版で、約 35 MB ある")
  (ul (li (tt ":runtime :hosted") " 開いたときにネットワークから読む (既定)")
      (li (tt ":runtime \"runtime/\"") " デッキの横から読む")
      (li (tt ":runtime :none") " コードを動かさずに表示する"))
  (code :lang "console" "
    $ cp web/dist/rontoplayground.js* talk/runtime/
    $ jwebserver -d \"$PWD/talk\""))

;;; --- looks ---------------------------------------------------------------------------

(section "見た目を選ぶ")

(slide :title "テーマ" :kicker "書き方"
  (p "組み込みは " (tt ":light") "、" (tt ":dark") "、" (tt ":high-contrast") "、"
     (tt ":midnight") "。個々の色は名前で差し替える。")
  (sexp "
    (deck :theme :midnight)
    (deck :theme (theme :base :dark :accent \"#ff9f43\"))
    (deck :theme (theme :base :light :code :dark))")
  (p "文字色はどれも、背景とのコントラスト比 4.5:1 以上を検査する。"))

(slide :title "テーマ" :kicker "表示例"
  (lead "このデッキは :light を基に、コードの色だけ :dark にした")
  (code :lang "lisp" :run nil "
    (list \"string\" :keyword 42 nil) ; a comment")
  (p "本文の " (tt "tt") "、" (mark "マーカー") "、" (accent "アクセント色")
     " も同じテーマの色で描く。")
  (p (tt "--theme midnight") " を付けて描き直すと、このデッキが紺と金になる。"))

(slide :title "道しるべ" :kicker "書き方"
  (p "表紙、目次、題の上の小見出し、進み具合はデッキの冒頭で決める。")
  (sexp "
    (deck :title \"lispresen\" :author \"rontolisp\"
          :logo (image \"logo.svg\" :alt \"rontolisp\")
          :url \"https://github.com/making/rontolisp\"
          :agenda \"話の流れ\" :kicker :section
          :progress t :page-numbers t)
    (slide :kicker \"試す\" ...)
    (slide :kicker nil ...)"))

(slide :title "道しるべ" :kicker "試す"
  (ol (li "Home で表紙に戻り、ロゴと URL の QR コードを見る")
      "節の始まりの目次では、今の節が強調される"
      (li "題の上の小見出しは節の題。" (tt ":kicker") " で変えられる")
      "下端のバーとページ番号が今の位置を示す"))

(slide :title "目次の位置" :kicker "書き方"
  (p "目次は表紙の次に入る。" (tt "(agenda)") " を置くと、その位置に入る。")
  (sexp "
    (deck :title \"lispresen\" :agenda \"話の流れ\")
    (slide :title \"S式のデッキから HTML を一つ作る\" ...)
    (agenda)
    (section \"lispresen とは\")")
  (ul (li (tt "(agenda)") " より前のスライドと節は目次に載らない")
      (li "目次を出すには " (tt ":agenda") " も要る")))

;; The agenda of this deck stands at its own (agenda), so the try slide sends there.
(slide :title "目次の位置" :kicker "試す"
  (ol "Home で表紙に戻る"
      (li "→ で進むと、表紙の次は " (tt "(agenda)") " より前に書いたスライド")
      (li "もう一度 → で目次。最初の節は " (tt "(agenda)") " の後に書いた「lispresen とは」")))

;;; --- outputs and backends ---------------------------------------------------------

(section "出力と始め方")

(slide :title "描画は文字列から文字列への関数"
  (sexp "
    (lispresen:render source &key theme runtime read-file embed page-image)
    ;; => (values html diagnostics image)")
  (ul "入出力をしないので、CLI からもブラウザからも呼べる"
      "整数だけで計算するので、どこでも同じバイト列になる"))

(slide :title "どのバックエンドでも同じ HTML"
  (table :header ("バックエンド" "成果物" "実行")
         :caption "check.sh が六つの出力をバイト単位で比べる"
    ("インタプリタ" "なし" (tt "rontolisp cli.lisp"))
    ("JVM" (tt ".jar") (tt "java -jar"))
    ("WASM" (tt ".wasm") (tt "wasmtime run"))
    ("WASM component" (tt ".wasm") (tt "wasmtime run"))
    ("ネイティブ" "実行ファイル" (tt "./lispresen"))
    ("SBCL" "なし" (tt "sbcl --script cli.lisp"))
    ("ブラウザ" (cell :colspan 2 (tt "web/") " のエディタが入力のたびに描き直す"))))

;; The preview image is an output of the same run, so the try slide links to the
;; published one instead of embedding it.
(slide :title "リンクのプレビュー" :kicker "書き方"
  (sexp "
    (deck :title \"lispresen\"
          :description \"S式で書くプレゼンテーション\"
          :page-url \"https://example.com/talk.html\"
          :page-image \"talk.png\"
          :page-image-font (\"r.ttf\" \"b.ttf\"))")
  (ul (li (tt "head") " に " (tt "og:title") " や " (tt "og:image") " の "
          (tt "meta") " タグを書く")
      (li "表紙を 1200 x 675 の PNG に描き、HTML の隣に書く")
      (li "画像の文字は TrueType フォントで描く")))

(slide :title "リンクのプレビュー" :kicker "試す"
  (ol (li "このデッキの URL を SNS の投稿欄に貼る")
      (li "プレビューに表紙の画像と題、説明が出る")
      (li (link "https://making.github.io/rontolisp/lispresen/intro.png" (tt "intro.png"))
          " を開くと、描いた画像そのものを見られる")))

(slide :title "ネイティブ実行ファイル" :kicker "使い方"
  (p "JVM も rontolisp も要らない実行ファイルを、リリース "
     (link "https://github.com/making/rontolisp/releases/tag/lispresen-0.1.0-SNAPSHOT"
           (tt "lispresen-0.1.0-SNAPSHOT"))
     " で配布している。")
  (table :header ("プラットフォーム" "ファイル")
    ("macOS (Apple Silicon)" (tt "lispresen-darwin-arm64"))
    ("Linux (x86_64)" (tt "lispresen-linux-amd64"))
    ("Linux (arm64)" (tt "lispresen-linux-arm64")))
  (ul (li "実行権を付け、" (tt "PATH") " の通ったディレクトリに " (tt "lispresen") " の名前で置く")
      (li "macOS でブラウザからダウンロードしたファイルは隔離され、起動すると止められる")))

(slide :title "ネイティブ実行ファイル" :kicker "試す"
  (code :lang "console" "
    $ BASE=https://github.com/making/rontolisp/releases/download
    $ curl -fLO $BASE/lispresen-0.1.0-SNAPSHOT/lispresen-darwin-arm64
    $ chmod +x lispresen-darwin-arm64
    $ sudo mv lispresen-darwin-arm64 /usr/local/bin/lispresen
    $ lispresen --version
    {\"version\": \"0.1.0-SNAPSHOT\", \"gitCommit\": \"3b9b214\"}")
  (p (tt "curl") " と " (tt "wget") " で取得したファイルは隔離されない。ブラウザでダウンロードしたファイルは "
     (tt "xattr -d com.apple.quarantine") " で隔離を外す。"))

(slide :title "編集しながら見る" :kicker "使い方"
  (p (tt "lispresen serve") " はデッキを HTTP で配り、保存するたびに描き直す。")
  (ul "ブラウザで開いたページは、見ていたスライドのまま読み込み直す"
      "診断はターミナルと、スライドに重ねたパネルに出る"
      (li "画像と動画はデッキの横から配るので、" (tt "--embed") " は要らない")))

(slide :title "編集しながら見る" :kicker "試す"
  (code :lang "console" "
    $ lispresen serve talk.lisp
    lispresen: built talk.lisp: 0 errors, 0 warnings")
  (ol (li (tt "http://127.0.0.1:8000/") " をブラウザで開く")
      "デッキを書き換えて保存し、ページが追従するのを見る"
      (li "Ctrl-C で止め、" (tt "lispresen talk.lisp --embed --strict") " で HTML を書く")))

(slide :title "コマンドライン"
  (code "
    lispresen DECK [-o OUT] [--theme NAME] [--runtime URL|hosted|none]
                   [--embed] [--check] [--strict]
    lispresen new FILE [--lang ja|en] [--force]
    lispresen serve DECK [--port N] [--host ADDR] [--theme NAME]
                         [--runtime URL|hosted|none]
    lispresen --version")
  (ul (li (tt "-") " で標準入力から読み、標準出力に書く")
      (li (tt "--embed") " で画像を埋め込み、ファイル一つで完結させる")
      (li (tt "--version") " は版とビルドしたコミットを JSON で出す")
      (li "終了コードは 0 が成功、1 がエラー、2 が使い方の誤り")))

;; :kicker nil drops the section title the deck puts above this slide's title.
(slide :title "始め方" :kicker nil
  (code :lang "console" "
    $ lispresen new talk.lisp
    $ lispresen serve talk.lisp
    $ lispresen talk.lisp --embed --strict")
  (ul (li (tt "new") " は、主なフォームにコメントで説明を添えた雛形を書く。"
          (tt "--lang en") " なら英語")
      (li "診断には行番号が付く。その行を直して保存すると、ページが描き直る")
      (li "見本はこのデッキ、" (tt "lispresen/examples/intro.lisp"))))

;; :class adds a class to the slide's <section>; no built-in style uses this one.
(slide :class "closing" "スライドも、コードと同じように書いて、レビューして、動かす。")

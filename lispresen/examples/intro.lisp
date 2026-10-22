;;;; intro.lisp -- a deck presenting lispresen itself, and the golden input of check.sh.

(deck :title "lispresen: S式で書くプレゼンテーション"
      :author "rontolisp"
      :date "2026-10-22"
      :theme :light
      :page-numbers t)

(section "なぜS式で書くのか")

(slide :title "スライドはデータ"
  (ul "差分をコードと同じようにレビューできる"
      (li "構造が明示される"
          (ul "インデントに意味を持たせない"
              "見えないスタイルがない"))
      "生成したデッキもただのリスト")
  (notes "デッキは read するだけで評価しないことに触れる。"))

(slide "読みやすさは、書き手ではなくツールが守る。")

(slide :title "文字の大きさは自動で決まる"
  (p "スライドごとに、収まる最大の段を " (b "段階表") " から選ぶ。")
  (ol "本文は 56px から 40px まで"
      "最小でも収まらなければエラーとして報告する"
      (li "日本語は " (mark "文節") " で改行する")))

(slide :title "全体像"
  (image "pipeline.png"
         :alt "read, parse, segment, layout, lint, emit の六段階"
         :caption "デッキが HTML になるまで"))

(slide :title "デッキの読み方"
  (p "トップレベルのフォームを一つずつ " (tt "read-from-string") " で読む。")
  (code :lang "lisp" :run nil "
    (multiple-value-bind (form end)
        (read-from-string source t nil :start pos)
      (push (list form line) forms)
      (setf pos end))"))

(slide :title "その場で動かす"
  (code :lang "lisp" "
    (defun fib (n)
      (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))
    (fib 20)"))

(slide :title "前のスライドの定義を使う"
  (p "同じ言語のコードは一つの環境を共有する。")
  (code :lang "lisp" "(mapcar #'fib '(1 2 3 4 5 6 7 8 9 10))"))

(slide :title "Clojure も動く"
  (code :lang "clojure" :timeout 5 "
    (defn square [x] (* x x)) ; a comment
    (println :squares)
    (map square (range 10))"))

;; A template: a use is replaced by the body, &rest spliced where it appears.
(define side (head &rest points)
  (column (p (b head)) (ul points)))

(slide :title "インタプリタとコンパイラ"
  (side "インタプリタ" "起動が速い" "eval が完全")
  (side "コンパイラ" "実行が速い" "成果物が一つ" "どのバックエンドでも同じ出力"))

(slide :title "引用"
  (quote :cite "Alan Perlis" "A language that doesn't affect the way you think about programming is not worth knowing."))

(slide :title "バックエンドの比較"
  (table :header ("バックエンド" "起動" "成果物" "コマンド")
         :caption "どれも同じ HTML を出力する"
    ("インタプリタ" "速い" "なし" (tt "rontolisp cli.lisp"))
    ("JVM" "普通" (tt ".jar") (tt "java -jar"))
    ("WASM" "速い" (tt ".wasm") (tt "wasmtime run"))))

(slide :title "表の数値は右に揃う"
  (table :header ("項目" "前年" "今年" "増減")
         :row-header t
    ("スライド数" "24" "31" "+7")
    ("平均文字数" "1,204" "986" "-18.1%")
    ("警告" "12" "--" "-12")))

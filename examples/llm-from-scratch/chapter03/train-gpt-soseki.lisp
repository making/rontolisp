;; chapter03/train-gpt-soseki.lisp -- notebooks/chapter03/train_gpt_soseki.ipynb,
;; ported: a character-level GPT trained on 夏目漱石, then sampled from.
;;
;; The notebook downloads『吾輩は猫である』from 青空文庫 with requests +
;; BeautifulSoup and strips its 注記 and ルビ. Nothing is downloaded here and
;; nothing is vendored: the corpus below is the novel's OPENING, which is public
;; domain, inlined so the program is self-contained and runs on all four
;; backends -- the same choice chapter02/section5.lisp made for its parallel
;; corpus. Everything the notebook does with the full novel it does with this;
;; only the shapes shrink, and every one of them is a defparameter here.
;;
;; The notebook's loss PLOT does not port; the losses it was drawn from are
;; printed instead, which is also what makes this example testable.
;;
;;   rontolisp chapter03/train-gpt-soseki.lisp

(load "../gpt/trainer.lisp")

(linalg:seed 42)

;; --- the corpus --------------------------------------------------------------
;; 夏目漱石『吾輩は猫である』(1905) の冒頭 -- public domain.

(defparameter *soseki-lines*
  '("吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。"
    "何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。吾輩はここで始めて人間というものを見た。"
    "しかもあとで聞くとそれは書生という人間中で一番獰悪な種族であったそうだ。" "この書生というのは時々我々を捕えて煮て食うという話である。"
    "しかしその当時は何という考もなかったから別段恐しいとも思わなかった。"
    "ただ彼の掌に載せられてスーと持ち上げられた時何だかフワフワした感じがあったばかりである。"
    "掌の上で少し落ちついて書生の顔を見たのがいわゆる人間というものの見始であろう。"
    "この時妙なものだと思った感じが今でも残っている。第一毛をもって装飾されべきはずの顔がつるつるしてまるで薬缶だ。"
    "その後猫にもだいぶ逢ったがこんな片輪には一度も出会わした事がない。のみならず顔の真中があまりに突起している。"
    "そうしてその穴の中から時々ぷうぷうと煙を吹く。どうも咽せぽくて実に弱った。" "これが人間の飲む煙草というものである事はようやくこの頃知った。"))

(defparameter *text*
  (let ((out ""))
    (dolist (line *soseki-lines* out)
      (setq out (concatenate 'string out line)))))

;; --- the shapes --------------------------------------------------------------
;; The notebook's own: block-size 256, n-embd 384, 6 layers, 6 heads, 5000 steps
;; on a T4. Raise these back toward it and the program is unchanged.

(defparameter *block-size* 8)

(defparameter *n-embd* 8)

(defparameter *n-layer* 1)

(defparameter *n-head* 2)

(defparameter *dropout* 0.1)

(defparameter *batch-size* 4)

(defparameter *max-steps* 100)

(defparameter *warmup-steps* 8)

(defparameter *learning-rate* 0.04)

(defparameter *new-tokens* 30)

(format t "corpus size: ~a characters~%" (length *text*))

;; --- the tokenizer -----------------------------------------------------------

(defparameter *tokenizer* (simple-tokenizer *text*))

(format t "vocabulary: ~a distinct characters~%"
        (tokenizer-vocab-size *tokenizer*))
(format t "first ten: ~s~%" (subseq (torch:field *tokenizer* :chars) 0 10))

;; --- the data ----------------------------------------------------------------

(defparameter *loaders*
  (create-dataloaders *text* *tokenizer*
                      :block-size *block-size*
                      :batch-size *batch-size*
                      :train-split 0.9))

(defparameter *train-loader* (car *loaders*))

(defparameter *val-loader* (cadr *loaders*))

(format t "train batches: ~a | validation batches: ~a~%"
        (length (data-loader-batches *train-loader*))
        (length (data-loader-batches *val-loader*)))

;; --- the model ---------------------------------------------------------------

(defparameter *config*
  (gpt-config :vocab-size (tokenizer-vocab-size *tokenizer*)
              :n-embd *n-embd*
              :n-layer *n-layer*
              :n-head *n-head*
              :block-size *block-size*
              :dropout *dropout*))

(format t "configured size: ~,4f M parameters~%" (torch:forward *config*))

(defparameter *model* (gpt-from-config *config*))

;; --- training ----------------------------------------------------------------

(defparameter *trainer*
  (gpt-trainer *model* *train-loader* *val-loader*
               :learning-rate *learning-rate*
               :weight-decay 0.1
               :warmup-steps *warmup-steps*
               :max-steps *max-steps*
               :grad-clip 1.0))

(defparameter *losses*
  (gpt-trainer-train *trainer* :log-interval 25 :eval-interval 50))

(defparameter *train-losses* (car *losses*))

(format t "first loss ~,4f -> last loss ~,4f~%" (car *train-losses*)
        (car (last *train-losses*)))
(format t "loss fell: ~a~%"
        (< (car (last *train-losses*)) (car *train-losses*)))

;; The untrained model's loss on a vocabulary of this size is log(vocab-size) --
;; a uniform guess -- so beating it is the first thing training has to do.
(format t "beats a uniform guess: ~a~%"
        (< (car (last *train-losses*))
           (log (* 1.0 (tokenizer-vocab-size *tokenizer*)))))

;; --- generation --------------------------------------------------------------

(torch:eval *model*)

(defun show-sample (prompt)
  ;; The book's generation cell: encode the prompt, sample new-tokens more, and
  ;; decode the whole thing. Every draw comes from the seeded linalg generator,
  ;; so the sample is the same text on every backend.
  (let ((ids
         (gpt-generate *model* (tokenizer-encode *tokenizer* prompt)
                       *new-tokens*
                       :temperature 0.8
                       :top-k 5)))
    (format t "prompt ~a -> ~a~%" prompt (tokenizer-decode *tokenizer* ids))))

(show-sample "吾輩は")

(show-sample "この書生")

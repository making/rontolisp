;;;; train.lisp -- the offline trainer.  Runs on the interpreter or (much
;;;; faster) compiled to the JVM; see gen.sh, which is what you should run.
;;;;
;;;; It trains the ch07 SimpleConvNet (net.lisp) with Adam on real handwritten
;;;; kana (Kuzushiji-49) mixed with augmented multi-font synthetic glyphs
;;;; (dataset.lisp), reports accuracy on a held-out K49 test split AND on the
;;;; clean reference glyphs, and writes the learned parameters to weights.bin
;;;; (RLW1).  The inference programs read that file at startup -- nothing is
;;;; baked into them, which is what let the model grow past the old 12.5k-weight
;;;; limit (see net.lisp).
;;;;
;;;; The convolution is the expensive part; im2col turns it into linalg:matmul,
;;;; so --simd pays for itself here (gen.sh passes it).

(load "dataset.lisp")
(load "../../deep-learning-from-scratch/common/trainer.lisp")

(defparameter *k49-train-limit* 40000)  ; real samples used (the file holds 36777)
(defparameter *k49-test-limit* 4600)    ; held-out real samples (the whole split)
(defparameter *epochs* 12)
(defparameter *batch-size* 64)
(defparameter *lr* 0.001)               ; Adam

(linalg:seed 42)

(format t ";; loading K49 (~a train / ~a test)~%" *k49-train-limit* *k49-test-limit*)
(defparameter *real* (k49-load "data/k49-train.bin" *k49-train-limit*))
(defparameter *test* (k49-load "data/k49-test.bin" *k49-test-limit*))

(format t ";; augmenting the synthetic glyphs~%")
(defparameter *synth* (samples->batch (synthetic-samples)))

(defparameter *train*
  (shuffle-batch (concat-batches *synth* *real*)))

(format t ";; dataset: ~a synthetic + ~a real = ~a samples~%"
        (car (linalg:shape (first *synth*)))
        (car (linalg:shape (first *real*)))
        (car (linalg:shape (first *train*))))

(defparameter *net* (make-hiragana-net))

(time
 (train *net* (scn-params *net*)
        (first *train*) (second *train*)
        (first *test*) (second *test*)
        :epochs *epochs* :mini-batch-size *batch-size*
        :optimizer (make-instance 'adam :lr *lr*)
        :eval-limit 2000))

;;; The clean reference glyphs (the display font, one per class) are what the
;;; page shows and what a user tries to copy, so they get their own score:
;;; a model can be good on K49 and still fumble the letterforms the page
;;; advertises.
(defun reference-accuracy (net)
  (let ((correct 0) (class 0))
    (dolist (variants *glyphs*)
      (let ((scores (classify net (glyph->image (first variants)))))
        (when (= (linalg:argmax scores) class)
          (setq correct (+ correct 1))))
      (setq class (+ class 1)))
    correct))

(format t ";; reference-glyph accuracy ~a/~a~%"
        (reference-accuracy *net*) *nclasses*)

(save-hiragana-net *net* "weights.bin")
(format t ";; wrote weights.bin~%")

;; ch06/hyperparameter_optimization.py -- random search over the learning
;; rate and weight-decay strength (Deep Learning from Scratch).
;;
;; lr is drawn log-uniformly (the book searches 10^U(-6, -2) over 50
;; epochs; three epochs need livelier nets, so this port searches
;; 10^U(-3, 0)) and lambda from 10^U(-8, -4); each trial
;; trains briefly on a small train/validation split, and the trials are
;; ranked by validation accuracy -- random search over log-uniform ranges,
;; the book's recipe. The book runs 100 trials of 50 epochs; here 8 trials
;; of 3 epochs on a [10 10] net keep the interpreter run short, and the
;; ranking still finds the healthy-lr region.
;;
;; Data: run ../download-mnist.sh once, then from examples/deep-learning-from-scratch/:
;;   rontolisp ch06/hyperparameter-optimization.lisp   (add --simd to speed it up)

(load "../dataset/mnist.lisp")
(load "../common/multi-layer-net.lisp")
(load "../common/trainer.lisp")

(defparameter *train-limit* 500)
(defparameter *validation-rate* 0.2)
(defparameter *trials* 8)
(defparameter *epochs* 3)

(linalg:seed 42)

(let* ((x-all
        (mnist-load-images "dataset/train-images-idx3-ubyte" *train-limit*))
       (t-all
        (mnist-load-labels "dataset/train-labels-idx1-ubyte" *train-limit* 0 t))
       ;; shuffle, then split off the validation set (the book's
       ;; shuffle_dataset + slicing)
       (perm (linalg:permutation *train-limit*))
       (x-shuffled (linalg:take-rows x-all perm))
       (t-shuffled (linalg:take-rows t-all perm))
       (val-num (truncate (* *train-limit* *validation-rate*)))
       (x-val (linalg:take-rows x-shuffled (linalg:arange val-num)))
       (t-val (linalg:take-rows t-shuffled (linalg:arange val-num)))
       (x-train
        (linalg:take-rows x-shuffled (linalg:arange val-num *train-limit*)))
       (t-train
        (linalg:take-rows t-shuffled (linalg:arange val-num *train-limit*)))
       (results nil))
  (dotimes (trial *trials*)
    (let* ((lr (expt 10.0 (aref (linalg:uniform -3.0 0.0 1) 0)))
           (lam (expt 10.0 (aref (linalg:uniform -8.0 -4.0 1) 0)))
           (net (make-multi-layer-net 784 '(10 10) 10 :weight-decay-lambda lam))
           (accs
            (train net (mln-params net) x-train t-train x-val t-val
                   :epochs *epochs*
                   :mini-batch-size 16
                   :optimizer (make-instance 'sgd :lr lr)
                   :verbose nil))
           (final-val (cadr (car (last accs)))))
      (format t "trial ~a: val acc ~a/~a | lr ~,6f, weight decay ~,10f~%"
              (+ trial 1) final-val val-num lr lam)
      (setq results (cons (list final-val (+ trial 1) lr) results))))
  (format t "=========== ranking (by val acc) ===========~%")
  (dolist (r (sort results (lambda (a b) (> (car a) (car b)))))
    (format t "trial ~a: val acc ~a/~a (lr ~,6f)~%" (cadr r) (car r) val-num
            (caddr r))))

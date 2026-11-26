;; common/trainer.py -- the training loop (Deep Learning from Scratch).
;;
;; The book's Trainer class is glue, so it becomes one function over the
;; net generics (net-gradient / net-loss / net-accuracy-count), usable
;; with any of the network classes. Batches come from linalg:choice, the
;; optimizer is any instance answering the (update optimizer params grads)
;; generic of common/optimizer.lisp, and accuracies are evaluated per
;; epoch (optionally on a subset, the book's
;; evaluate_sample_num_per_epoch). Returns the list of per-epoch
;; (train-count test-count) pairs.

(load "optimizer.lisp")

(defun train (net params x-train t-train x-test t-test &key (epochs 3)
                  (mini-batch-size 16) optimizer (verbose t) (eval-limit nil))
  (let* ((train-size (car (linalg:shape x-train)))
         (test-size (car (linalg:shape x-test)))
         (iter-per-epoch (max (floor train-size mini-batch-size) 1))
         (max-iter (* epochs iter-per-epoch))
         (opt (if optimizer optimizer (make-instance 'sgd :lr 0.01)))
         (n-train-eval (if eval-limit (min eval-limit train-size) train-size))
         (n-test-eval (if eval-limit (min eval-limit test-size) test-size))
         (x-train-eval
          (if eval-limit
              (linalg:take-rows x-train (linalg:arange n-train-eval))
              x-train))
         (t-train-eval
          (if eval-limit
              (linalg:take-rows t-train (linalg:arange n-train-eval))
              t-train))
         (x-test-eval
          (if eval-limit
              (linalg:take-rows x-test (linalg:arange n-test-eval))
              x-test))
         (t-test-eval
          (if eval-limit
              (linalg:take-rows t-test (linalg:arange n-test-eval))
              t-test))
         (acc-list nil)
         (epoch 0))
    (dotimes (i max-iter)
      (let* ((batch-mask (linalg:choice train-size mini-batch-size))
             (x-batch (linalg:take-rows x-train batch-mask))
             (t-batch (linalg:take-rows t-train batch-mask))
             (grads (net-gradient net x-batch t-batch)))
        (update opt params grads)
        (when (= (mod (+ i 1) iter-per-epoch) 0)
          (setq epoch (+ epoch 1))
          (let ((train-count (net-accuracy-count net x-train-eval t-train-eval))
                (test-count (net-accuracy-count net x-test-eval t-test-eval)))
            (setq acc-list (cons (list train-count test-count) acc-list))
            (when verbose
              (format t "=== epoch ~a: train acc ~a/~a, test acc ~a/~a ===~%"
                      epoch train-count n-train-eval test-count
                      n-test-eval))))))
    (reverse acc-list)))

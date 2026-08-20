;; gpt/trainer.lisp -- llm_from_scratch/gpt/trainer.py, ported.
;;
;; GPTTrainer: AdamW over two parameter GROUPS, a warmup-then-cosine learning
;; rate, gradient-norm clipping, and a step loop that evaluates on the held-out
;; split as it goes.
;;
;; Two deliberate differences from the book, both because the port would
;; otherwise carry a defect across:
;;
;;   * torch.optim's parameter GROUPS -- one weight-decayed, one not -- become
;;     TWO optimizers over the same model. The split itself is the book's, but
;;     decided by torch:module-kind rather than by `'bias' in name`: a
;;     substring test over dotted parameter names calls a layer named `blinear`
;;     a bias, while the kind is what the layer IS.
;;   * The book's get_lr multiplies by step/warmup_steps for the LOG LINE only;
;;     nothing writes that rate back, so its optimizer runs the whole warmup at
;;     the base rate and the printed schedule is not the one being trained
;;     with. Here gpt-trainer-lr is the single answer and the loop writes it
;;     into both optimizers, so the logged rate IS the rate.
;;
;; The elapsed-time column of the book's log line is dropped: it is the one
;; number that cannot come out the same on four backends, and this example is
;; checked by its output.

(load "model.lisp")
(load "dataset.lisp")

(defparameter *trainer-betas* '(0.9 0.95))

(defun gpt-parameter-groups (model)
  ;; The book's decay / no-decay split: a linear layer's WEIGHT decays;
  ;; its bias, every LayerNorm gain and bias, and every embedding table do not
  ;; -- which is exactly what `'bias' in name or 'ln' in name or 'embedding' in
  ;; name` selects, said in terms of the layer rather than of its name.
  ;; Returns (decay no-decay).
  (let ((decay nil) (no-decay nil))
    (gpt-apply model
               (lambda (sub)
                 (let ((kind (torch:module-kind sub)))
                   (do ((p (torch:fields sub) (cddr p)))
                       ((null p))
                     (let ((name (car p)) (v (cadr p)))
                       (when (and (torch:tensorp v) (torch:requires-grad-p v))
                         (if (and (eq kind :linear) (eq name :weight))
                             (setq decay (cons v decay))
                             (setq no-decay (cons v no-decay)))))))))
    (list (reverse decay) (reverse no-decay))))

(defun gpt-trainer (model train-loader val-loader &key (learning-rate 3.0e-4)
                          (weight-decay 0.1) (warmup-steps 1000)
                          (max-steps 10000) (grad-clip 1.0))
  ;; The trainer: the model, the two loaders, the schedule's knobs, and the two
  ;; AdamW optimizers the parameter split calls for. Both run at the same
  ;; learning rate -- only the decay differs, which is what a parameter group
  ;; is for.
  (let* ((groups (gpt-parameter-groups model))
         (decay (car groups))
         (no-decay (cadr groups)))
    (torch:module :gpt-trainer (list :model model
                                     :train-loader train-loader
                                     :val-loader val-loader
                                     :learning-rate learning-rate
                                     :warmup-steps warmup-steps
                                     :max-steps max-steps
                                     :grad-clip grad-clip
                                     :optimizers
                                     (list (torch:adamw decay
                                            :lr learning-rate
                                            :betas *trainer-betas*
                                            :weight-decay weight-decay)
                                           (torch:adamw no-decay
                                                        :lr learning-rate
                                                        :betas *trainer-betas*
                                                        :weight-decay 0.0)))
                  (function gpt-trainer-train))))

(defun gpt-trainer-lr (self step)
  ;; The learning rate AT step: a linear warmup from one warmup-step's worth of
  ;; the base rate up to it, then CosineAnnealingLR's
  ;; base * (1 + cos(pi * t / t-max)) / 2 over the remaining
  ;; t-max = max-steps - warmup-steps, decaying to 0 at the last step.
  (let* ((base (torch:field self :learning-rate))
         (warmup (torch:field self :warmup-steps))
         (total (torch:field self :max-steps)))
    (if (< step warmup)
        (/ (* base (+ step 1)) warmup)
        (let* ((t-max (max 1 (- total warmup)))
               (progress (min 1.0 (/ (* 1.0 (- step warmup)) t-max))))
          (* base 0.5 (+ 1.0 (cos (* pi progress))))))))

(defun gpt-trainer-set-lr (self lr)
  ;; The schedule's write: a learning rate is an ordinary optimizer FIELD, so
  ;; turning it needs no scheduler object.
  (dolist (o (torch:field self :optimizers)) (torch:set-field o :lr lr)))

(defun gpt-trainer-step (self x y)
  ;; One training step: forward, backward, clip, update -- and the loss as a
  ;; plain number, so nothing of the tape is retained past the step.
  (let ((loss (gpt-loss (torch:field self :model) x y))
        (clip (torch:field self :grad-clip)))
    (dolist (o (torch:field self :optimizers)) (torch:zero-grad o))
    (torch:backward loss)
    (when (> clip 0) (torch:clip-grad-norm (torch:field self :model) clip))
    (dolist (o (torch:field self :optimizers)) (torch:step o))
    (torch:item loss)))

(defun gpt-trainer-evaluate (self &key (max-batches 10))
  ;; The mean loss over up to max-batches validation batches, with the model in
  ;; EVALUATION mode (dropout off) and no tape built. The mode is restored
  ;; afterwards, like the book's model.eval() / model.train() pair.
  (let ((model (torch:field self :model))
        (loader (torch:field self :val-loader))
        (total 0.0)
        (count 0))
    (torch:eval model)
    (torch:no-grad
      (dolist (batch (data-loader-batches loader))
        (when (< count max-batches)
          (let ((pair (data-loader-collate loader batch)))
            (setq total
             (+ total (torch:item (gpt-loss model (car pair) (cadr pair)))))
            (setq count (+ count 1))))))
    (torch:train model)
    (if (= count 0) 0.0 (/ total count))))

(defun gpt-trainer-train (self &key (log-interval 100) (eval-interval 500))
  ;; The main loop: epochs over the training loader until max-steps is reached,
  ;; logging the running loss and the scheduled rate every log-interval steps
  ;; and evaluating every eval-interval. Returns (train-losses val-losses),
  ;; both newest last.
  (let ((model (torch:field self :model))
        (loader (torch:field self :train-loader))
        (max-steps (torch:field self :max-steps))
        (train-losses nil)
        (val-losses nil)
        (window nil)
        (step 0))
    (torch:train model)
    (format t "model parameters: ~a tensors~%"
            (length (torch:parameters model)))
    (do ()
        ((>= step max-steps))
      (dolist (batch (data-loader-batches loader))
        (when (< step max-steps)
          (gpt-trainer-set-lr self (gpt-trainer-lr self step))
          (let* ((pair (data-loader-collate loader batch))
                 (loss (gpt-trainer-step self (car pair) (cadr pair))))
            (setq train-losses (cons loss train-losses))
            (setq window (cons loss window))
            (when (= 0 (mod step log-interval))
              (format t "step ~a/~a | loss ~,4f | lr ~,6f~%" step max-steps
                      (gpt-mean window) (gpt-trainer-lr self step))
              (setq window nil))
            (when (and (> step 0) (= 0 (mod step eval-interval)))
              (let ((v (gpt-trainer-evaluate self)))
                (setq val-losses (cons v val-losses))
                (format t "validation loss ~,4f~%" v)))
            (setq step (+ step 1))))))
    (list (reverse train-losses) (reverse val-losses))))

(defun gpt-mean (values)
  ;; The mean of a list of numbers; 0.0 for the empty list.
  (let ((total 0.0) (n 0))
    (dolist (v values)
      (setq total (+ total v))
      (setq n (+ n 1)))
    (if (= n 0) 0.0 (/ total n))))

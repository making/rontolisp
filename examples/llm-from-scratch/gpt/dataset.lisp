;; gpt/dataset.lisp -- llm_from_scratch/gpt/dataset.py, ported.
;;
;; TextDataset and create_dataloaders: the corpus as one long token vector, and
;; the autoregressive (x y) pair at position i -- block-size tokens and the same
;; window shifted one to the right, so predicting y from x IS next-token
;; prediction.
;;
;; torch.utils.data has no counterpart here and needs none: a Dataset is a
;; module holding the token vector, and a DataLoader is a module holding an
;; index list -- the epoch's mini-batches are torch:shuffled-batches over it and
;; a batch is COLLATED on demand, which is the only part of a DataLoader that
;; does real work.

(load "tokenizer.lisp")

(defun text-dataset (text tokenizer &key (block-size 128))
  ;; The dataset over a corpus: fields :tokens (the ids as a simple vector,
  ;; indexable in constant time -- a list would make every item a walk),
  ;; :block-size and :length. Prints its size, like the book's __init__.
  (let* ((ids (tokenizer-encode tokenizer text))
         (n (length ids))
         (v (make-array n :initial-element 0))
         (i 0))
    (dolist (id ids)
      (setf (aref v i) id)
      (setq i (+ i 1)))
    (format t "dataset size: ~a tokens~%" n)
    (torch:module :text-dataset (list :tokens v
                                      :block-size block-size
                                      :length (- n block-size))
                  (function text-dataset-item))))

(defun text-dataset-length (dataset)
  ;; How many windows the corpus holds: tokens - block-size, so the last one
  ;; still has a target for its final position (the book's __len__).
  (torch:field dataset :length))

(defun text-dataset-item (dataset idx)
  ;; The (x y) pair at idx as two LISTS of ids: x is tokens[idx : idx+block],
  ;; y the same window shifted by one (the book's __getitem__).
  (let ((v (torch:field dataset :tokens))
        (b (torch:field dataset :block-size))
        (x nil)
        (y nil))
    (do ((k (- b 1) (- k 1)))
        ((< k 0) (list x y))
      (setq x (cons (aref v (+ idx k)) x))
      (setq y (cons (aref v (+ idx k 1)) y)))))

(defun text-dataset-batch (dataset indices)
  ;; The COLLATE step: the windows at indices as the two (batch block-size)
  ;; index tensors torch:embedding and torch:cross-entropy-loss take. Every
  ;; window is block-size long, so torch:pad-sequence pads nothing here and is
  ;; simply the batch-first stack.
  (let ((xs nil) (ys nil))
    (dolist (i indices)
      (let ((pair (text-dataset-item dataset i)))
        (setq xs (cons (car pair) xs))
        (setq ys (cons (cadr pair) ys))))
    (list (torch:pad-sequence (reverse xs)) (torch:pad-sequence (reverse ys)))))

(defun data-loader (dataset indices batch-size shuffle)
  ;; A loader over a SUBSET of the dataset (a DataLoader over a random_split
  ;; part): fields :dataset, :indices, :batch-size and :shuffle.
  (torch:module :data-loader (list :dataset dataset
                                   :indices indices
                                   :batch-size batch-size
                                   :shuffle shuffle)
                (function data-loader-batches)))

(defun data-loader-batches (loader)
  ;; One epoch as a list of INDEX batches -- reshuffled under :shuffle t, in
  ;; order otherwise. The tensors are not built here: data-loader-collate makes
  ;; each batch as the training loop reaches it, so an epoch costs one batch of
  ;; memory rather than all of them.
  (torch:shuffled-batches (torch:field loader :indices)
                          (torch:field loader :batch-size)
                          :shuffle (torch:field loader :shuffle)))

(defun data-loader-collate (loader indices)
  ;; One index batch as its (x y) tensor pair.
  (text-dataset-batch (torch:field loader :dataset) indices))

(defun create-dataloaders
    (text tokenizer &key (block-size 128) (batch-size 64) (train-split 0.9))
  ;; The book's create_dataloaders: one dataset, a RANDOM split of its windows
  ;; into a training and a validation part (torch.utils.data.random_split), and
  ;; a loader over each -- the training one reshuffled every epoch, the
  ;; validation one always in the same order. The split comes from the seeded
  ;; linalg generator, so linalg:seed reproduces it on every backend. Returns
  ;; (train-loader val-loader).
  (let* ((dataset (text-dataset text tokenizer :block-size block-size))
         (n (text-dataset-length dataset))
         (n-train (truncate (* train-split n)))
         (perm (linalg:permutation n))
         (train nil)
         (val nil))
    (do ((k (- n 1) (- k 1)))
        ((< k 0))
      (let ((i (truncate (aref perm k))))
        (if (< k n-train) (setq train (cons i train)) (setq val (cons i val)))))
    (list (data-loader dataset train batch-size t)
          (data-loader dataset val batch-size nil))))

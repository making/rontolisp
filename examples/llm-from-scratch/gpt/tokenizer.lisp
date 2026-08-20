;; gpt/tokenizer.lisp -- llm_from_scratch/gpt/tokenizer.py, ported.
;;
;; SimpleTokenizer, the character-level tokenizer: the vocabulary is the SORTED
;; set of characters occurring in the corpus, an id is that character's position
;; in it. Python's `dict` becomes a hash table in each direction; `sorted(set(t))`
;; becomes remove-duplicates + sort, which is the same thing said in Lisp.
;;
;; A tokenizer is a torch:module with no parameters -- the tables are ordinary
;; fields, so torch:parameters walks it and collects nothing, exactly like the
;; positional encoding's buffer in transformer/utils.lisp.

(defun simple-tokenizer-chars (text)
  ;; The corpus' distinct characters, in ascending char-code order. This list IS
  ;; the vocabulary: index i holds the character encoded as i.
  (sort (remove-duplicates (coerce text 'list)) (function char<)))

(defun simple-tokenizer (text)
  ;; The tokenizer over a corpus: fields :chars (the vocabulary as a list),
  ;; :vocab-size, and the two lookup tables. The forward is `encode', so
  ;; (torch:forward tokenizer "text") works, but the two named functions below
  ;; are what the rest of the port calls.
  (let* ((chars (simple-tokenizer-chars text))
         (to-idx (make-hash-table))
         (from-idx (make-hash-table))
         (i 0))
    (dolist (ch chars)
      (setf (gethash ch to-idx) i)
      (setf (gethash i from-idx) ch)
      (setq i (+ i 1)))
    (torch:module :simple-tokenizer (list :chars chars
                                          :vocab-size (length chars)
                                          :char-to-idx to-idx
                                          :idx-to-char from-idx)
                  (function tokenizer-encode))))

(defun tokenizer-vocab-size (tokenizer) (torch:field tokenizer :vocab-size))

(defun tokenizer-encode (tokenizer text)
  ;; A string as a LIST of token ids. An unknown character encodes as 0, like
  ;; the book's `char_to_idx.get(ch, 0)`.
  (let ((table (torch:field tokenizer :char-to-idx)) (acc nil))
    (dotimes (i (length text) (reverse acc))
      (let ((id (gethash (char text i) table)))
        (setq acc (cons (if (null id) 0 id) acc))))))

(defun tokenizer-decode (tokenizer tokens)
  ;; Token ids -- a list, a linalg index array or a tensor -- back to a string.
  ;; An id outside the vocabulary contributes nothing, like the book's
  ;; `idx_to_char.get(int(idx), '')`.
  (let ((table (torch:field tokenizer :idx-to-char))
        (ids
         (cond ((consp tokens) tokens)
               ((torch:tensorp tokens)
                (linalg:to-list (linalg:flatten (torch:data tokens))))
               ((arrayp tokens) (linalg:to-list (linalg:flatten tokens)))
               (t (list tokens))))
        (out ""))
    (dolist (id ids out)
      (let ((ch (gethash (truncate id) table)))
        (unless (null ch) (setq out (concatenate 'string out (string ch))))))))

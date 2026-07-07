;; Loads the REAL assoc-utils (public domain, Eitaro Fukamachi) via
;; asdf:load-system and exercises its public alist read/convert API. Run with:
;;   rontolisp examples/asdf/assoc-utils-demo.lisp --system-path src/test/resources/assoc-utils
;; (see examples/asdf/README.md for the compile-path variants).

(asdf:load-system :assoc-utils)

(defvar *a* (list (cons "name" "eitaro") (cons "loc" "vienna")))

;; aget (assoc with *assoc-test*) + default
(print (assoc-utils:aget *a* "name"))
(print (assoc-utils:aget *a* "missing" "none"))

;; alist-keys / alist-values (mapcar car/cdr)
(print (assoc-utils:alist-keys *a*))
(print (assoc-utils:alist-values *a*))

;; alist-plist ((intern name :keyword) over the keys) and the inverse plist-alist
;; ((string-downcase key) over the keyword keys -- string-downcase accepts a string
;; designator, so a keyword coerces to its name).
(print (assoc-utils:alist-plist *a*))
(print (assoc-utils:plist-alist (list :name "eitaro" :loc "vienna")))

;; remove-from-alist (remove-if) and the define-modify-macro place variant
(print (assoc-utils:remove-from-alist *a* "loc"))
(let ((b (list (cons "x" 1) (cons "y" 2))))
  (assoc-utils:delete-from-alistf b "x")
  (print b))

;; alist-hash / hash-alist (loop being the hash-keys ... using (hash-value ...))
(let ((h (assoc-utils:alist-hash (list (cons "k" "v")))))
  (print (assoc-utils:hash-alist h)))

;; with-keys (the alist equivalent of with-slots)
(print (assoc-utils:with-keys ((nm "name") (lc "loc")) *a*
         (format nil "~a in ~a" nm lc)))

;; alist-get (reduce #'aget* over a key path; integer keys index into lists)
(print (assoc-utils:alist-get (list (cons "user" (list (cons "age" 42)))) (list "user" "age")))

;; alist= (equalp over string< :key #'car sorted copies)
(print (if (assoc-utils:alist= (list (cons "a" "1") (cons "b" "2"))
                               (list (cons "b" "2") (cons "a" "1")))
           "equal" "different"))

;; Loads the REAL alexandria 1.0.1 (public domain / 0-clause MIT) via
;; asdf:load-system and exercises its public API. Run with:
;;   rontolisp examples/asdf/alexandria-demo.lisp --system-path src/test/resources/alexandria
;; (see examples/asdf/README.md for the compile-path variants; this demo uses
;; handler-case, so both wasm run commands need -W exceptions=y).

(asdf:load-system :alexandria)

;; binding constructs
(print (alexandria:if-let ((x (find 3 '(1 2 3)))) (* x 10) :none))
(print (alexandria:if-let ((x (find 9 '(1 2 3)))) (* x 10) :none))
(print (alexandria:when-let ((x 5)) (* x 2)))
(print (alexandria:when-let* ((x 1) (y (+ x 1))) (list x y)))

;; control flow
(print (alexandria:switch (3) (1 :one) (3 :three) (t :other)))
(print (alexandria:eswitch (:a) (:a :got-a) (:b :got-b)))
(print (alexandria:cswitch (2) (1 :one) (2 :two)))
(print (alexandria:xor nil 3 nil))
(print (alexandria:whichever 42))
(print (alexandria:nth-value-or 0 (values nil :second) :fallback))

;; definitions
(alexandria:define-constant +greeting+ "hello" :test #'string=)
(print +greeting+)

;; macro-writing macros
(defmacro my-square (x) (alexandria:once-only (x) `(* ,x ,x)))
(let ((calls 0))
  (flet ((bump () (incf calls)))
    (print (my-square (bump)))
    (print calls)))
(defmacro my-double (x) (alexandria:with-gensyms (g) `(let ((,g ,x)) (+ ,g ,g))))
(print (my-double 21))
(print (funcall (alexandria:named-lambda greeter (who) (list :hi who)) :you))
(print (alexandria:destructuring-case '(:add 1 2)
         ((:add a b) (list :sum (+ a b)))
         ((t &rest rest) rest)))
(print (multiple-value-list (alexandria:parse-body '("doc" (+ 1 2)) :documentation t)))

;; functions
(print (funcall (alexandria:compose #'1+ #'1+) 40))
(print (mapcar (alexandria:compose #'car #'cdr) '((1 2 3) (4 5 6))))
(print (funcall (alexandria:multiple-value-compose #'list (lambda (x) (values x (* x 10)))) 4))
(print (funcall (alexandria:curry #'+ 1 2) 3))
(print (funcall (alexandria:rcurry #'- 1) 10))
(print (funcall (alexandria:conjoin #'evenp #'plusp) 4))
(print (funcall (alexandria:disjoin #'evenp #'minusp) 3))
(print (funcall (alexandria:ensure-function #'car) '(1 2)))

;; lists
(print (alexandria:iota 5))
(print (alexandria:iota 4 :start 1 :step 2))
(print (alexandria:flatten '(1 (2 (3 (4))) 5)))
(print (alexandria:mappend #'list '(1 2) '(3 4)))
(print (list (alexandria:proper-list-p '(1 2 3)) (alexandria:circular-list-p '(1 2 3))))
(print (list (alexandria:lastcar '(1 2 3)) (alexandria:ensure-list 1) (alexandria:ensure-cons '(1 2))))
(print (alexandria:alist-plist '((:a . 1) (:b . 2))))
(print (alexandria:plist-alist '(:a 1 :b 2)))
(print (alexandria:remove-from-plist '(:a 1 :b 2 :c 3) :b))
(print (alexandria:delete-from-plist (list :a 1 :b 2) :a))
(print (list (alexandria:assoc-value '((:a . 1) (:b . 2)) :b)
             (alexandria:rassoc-value '((:a . 1) (:b . 2)) 1)))
(print (list (alexandria:set-equal '(1 2 3) '(3 2 1)) (alexandria:setp '(1 2 2))))
(print (alexandria:doplist (k v '(:a 1 :b 2) :done) (print (list k v))))

;; modify macros over setf places
(let ((xs (list 1 2)) (n 3))
  (alexandria:appendf xs '(3 4))
  (alexandria:removef xs 2)
  (alexandria:maxf n 10)
  (alexandria:minf n 4)
  (print (list xs n)))

;; sequences
(print (list (alexandria:emptyp '()) (alexandria:emptyp "x")))
(print (alexandria:length= '(1 2) #(3 4)))
(print (list (alexandria:first-elt "abc") (alexandria:last-elt "abc") (alexandria:last-elt '(1 2 3))))
(print (list (alexandria:starts-with-subseq "he" "hello") (alexandria:ends-with-subseq "lo" "hello")))
(print (list (alexandria:starts-with 1 '(1 2 3)) (alexandria:ends-with 3 '(1 2 3))))
(print (alexandria:extremum '(3 1 4 1 5) #'<))
(print (alexandria:extremum '(3 1 4 1 5) #'>))
(print (alexandria:sequence-of-length-p '(1 2 3) 3))
(alexandria:map-combinations (lambda (c) (print c)) '(1 2 3) :length 2)
(alexandria:map-permutations (lambda (p) (print p)) '(1 2) :length 2)
(let* ((a (vector 1 2 3)) (b (alexandria:copy-array a)))
  (setf (aref b 0) 99)
  (print (list (aref a 0) (aref b 0))))
(print (alexandria:copy-sequence 'list #(1 2 3)))
(print (alexandria:copy-sequence 'vector '(1 2 3)))
(print (alexandria:rotate (list 1 2 3 4 5) 2))
(print (alexandria:rotate (list 1 2 3 4 5) -2))
(print (let ((x '(1 2))) (alexandria:coercef x 'vector) x))

;; io
(print (with-input-from-string (s "stream content")
         (alexandria:read-stream-content-into-string s)))

;; hash tables (results sorted -- iteration order is unspecified)
(let ((h (alexandria:alist-hash-table '((:a . 1) (:b . 2)) :test #'eq)))
  (print (sort (alexandria:hash-table-keys h) #'string< :key #'symbol-name))
  (print (sort (alexandria:hash-table-values h) #'<))
  (print (alexandria:ensure-gethash :c h 3))
  (print (gethash :c h)))
(print (alexandria:hash-table-plist (alexandria:plist-hash-table '(:x 1))))
(print (alexandria:hash-table-alist (alexandria:copy-hash-table (alexandria:plist-hash-table '(:x 1)))))
(alexandria:maphash-keys #'print (alexandria:plist-hash-table '(:x 1)))
(alexandria:maphash-values #'print (alexandria:plist-hash-table '(:x 1)))

;; numbers
(print (list (alexandria:clamp 15 0 10) (alexandria:clamp -1 0 10) (alexandria:clamp 5 0 10)))
(print (alexandria:lerp 1/2 0 10))
(print (alexandria:mean '(1 2 3 4)))
(print (alexandria:variance '(1 2 3 4)))
(print (alexandria:factorial 10))
(print (alexandria:binomial-coefficient 10 3))
(print (list (alexandria:subfactorial 5) (alexandria:count-permutations 5 2)))
(print (alexandria:median '(3 1 4 1 5)))

;; symbols
(print (alexandria:symbolicate 'foo '- 'bar))
(print (alexandria:make-keyword "HELLO"))

;; conditions
(print (handler-case (alexandria:required-argument :x) (error () :signalled)))
(print (handler-case (alexandria:simple-parse-error "bad ~A" 1) (error () :parse-error)))
(print (alexandria:ignore-some-conditions (error) (error "boom")))
(alexandria:unwind-protect-case ()
    (print :body)
  (:normal (print :normal))
  (:abort (print :abort)))

;; features
(print (alexandria:featurep :rontolisp))

;; alexandria-2
(print (alexandria-2:subseq* "hello world" 6))
(print (alexandria-2:line-up-first 5 (+ 1) (* 2)))
(print (alexandria-2:line-up-last 5 (+ 1) (- 20)))
(print (alexandria-2:delete-from-plist* (list :a 1 :b 2) :a))
(print (list (alexandria-2:dim-in-bounds-p '(2 3) 1 2)
             (alexandria-2:dim-in-bounds-p '(2 3) 2 2)))
(print (alexandria-2:row-major-index '(2 3) 1 2))
(print (alexandria-2:rmajor-to-indices '(2 3) 5))

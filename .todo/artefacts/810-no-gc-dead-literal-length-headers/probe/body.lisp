;;; Shared body: the classification boundary of the header-free literal layout.
;;; One import per case so each has few enough sites for the byte arithmetic in
;;; chooseFoldedImports to take the fold (a dozen sites of one import decline it).
;;; ha..hi are (:string); h2 is (:string :string); h3 is (:string :s32 :string).

;; 1. literal used ONLY at a folded import site -> header-free
(defun c1 () (ha "only-folded"))

;; 2. literal at a folded site AND passed to length -> headered, length correct
(defun c2 () (hb "both-ways") (print (length "both-ways")))

;; 3. literal at a folded site AND printed (print reads the header) -> headered
(defun c3 () (hc "shown") (print "shown") (princ "shown") (terpri))

;; 4. the same spelling in two functions: folded-only here, value use in c4b
(defun c4a () (hd "shared"))
(defun c4b () (print (length "shared")))

;; 5. literal only in non-import positions
(defun c5 () (print (length "plain")) (princ "plain") (terpri))

;; 6. empty literal in a folded position (and "x" only ever folded)
(defun c6 () (he "") (h2 "" "x") (h2 "x" ""))

;; 7. UTF-8 literal in a folded position (the host sees BYTES)
(defun c7 () (hf "日本語テキスト") (h2 "é" "日本"))

;; 9. one spelling: two folded slots plus a value use in the :s32 slot -> headered
(defun c9 () (h3 "n" (length "n") "n"))

;; 10. runtime-pinned spellings handed to a folded site: the printer's "T"/"NIL"
;;     and __ftoa's "NaN" must keep their headers whatever the fold does
(defun c10 () (hg "T") (hg "NIL") (hg "NaN") (print t) (print nil) (print 1.5) (terpri))

;; 11. literals folded either side of runtime-string traffic
(defun c11 (s) (hh "before-runtime") (princ s) (terpri) (print (length s)) (hh "after-runtime"))

;; 12. the same literal twice at folded sites and once concatenated (value use)
(defun c12 () (hi "twice") (hi "twice") (princ (concatenate 'string "twice" "!")) (terpri))

(defun run-all (s)
  (c1) (c2) (c3) (c4a) (c4b) (c5) (c6) (c7) (c9) (c10) (c11 s) (c12)
  (princ "done") (terpri))

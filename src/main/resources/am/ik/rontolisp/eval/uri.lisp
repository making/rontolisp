;;; java.net.URI's reading of a URI reference, as the front ends that have one share
;;; it (the Clojure HTTP client and clojure.java.io, the Java subset's java.net.URI
;;; and java.net.http): the cut into components, the characters each component takes,
;;; and the resolution of a reference against a base.
;;;
;;; Written here from RFC 3986 (the reference parse of its appendix B,
;;; remove_dot_segments of its section 5.2.4, the resolution of 5.2.2), RFC 2396's
;;; character classes, which java.net.URI is specified by, and a JDK's answers where
;;; it departs from them: nothing is copied or translated from a JDK
;;; (.kb/java-frontend-provenance.md). Spliced by eval/UriLibrary into a program whose
;;; front end's run-time library names one of these functions.

(defun rontolisp::%uri-first-of (url chars start)
  "The index of the first character of URL from START that is one of CHARS,
   the length of URL when there is none."
  (or (position-if (lambda (c) (find c chars)) url :start start) (length url)))

(defun rontolisp::%uri-hex-p (c)
  "Whether the character C is a hex digit."
  (let ((code (char-code c)))
    (or (and (>= code 48) (<= code 57)) (and (>= code 65) (<= code 70))
        (and (>= code 97) (<= code 102)))))

(defun rontolisp::%uri-char-p (c set)
  "Whether java.net.URI takes C unescaped in a component whose punctuation is
   the string SET: a letter, a digit, an unreserved mark, a character of SET,
   or a character past ASCII that is no control and no space."
  (let ((code (char-code c)))
    (cond ((>= code 128)
           (not
            (or (<= code 160) (= code 5760) (and (>= code 8192) (<= code 8202))
                (= code 8232) (= code 8233) (= code 8239) (= code 8287)
                (= code 12288))))
          ((or (and (>= code 48) (<= code 57)) (and (>= code 65) (<= code 90))
               (and (>= code 97) (<= code 122)))
           t)
          (t (if (or (find c "_-!.~'()*") (find c set)) t nil)))))

(defun rontolisp::%uri-scan (url start end set what)
  "Checks the characters START..END of URL against java.net.URI's rule for the
   component WHAT, whose punctuation is SET, a % having to start an escape
   pair: nil when every character is taken, else the refusal (text . index)."
  (let ((i start) (refusal nil))
    (loop
      (if (or refusal (>= i end)) (return refusal))
      (let ((c (char url i)))
        (cond ((char= c #\%)
               (if (and (< (+ i 2) end)
                        (rontolisp::%uri-hex-p (char url (+ i 1)))
                        (rontolisp::%uri-hex-p (char url (+ i 2))))
                   (setq i (+ i 3))
                   (setq refusal (cons "Malformed escape pair" i))))
              ((rontolisp::%uri-char-p c set) (setq i (+ i 1)))
              (t (setq refusal
                       (cons (concatenate 'string "Illegal character in " what)
                             i))))))))

(defun rontolisp::%uri-literal-refusal (uri start end)
  "The refusal of the authority START..END of URI when its host is an address
   in brackets that has no closing bracket or holds what no IPv6 address
   does; nil for any other authority."
  (let ((at (position #\@ uri :start start :end end :from-end t)))
    (let ((host (if at (+ at 1) start)))
      (if (and (< host end) (char= (char uri host) #\[))
          (let ((close (position #\] uri :start host :end end)))
            (cond ((null close)
                   (cons "Expected closing bracket for IPv6 address" end))
                  ((position-if-not (lambda (c)
                                      (or (rontolisp::%uri-hex-p c)
                                          (char= c #\:) (char= c #\.))) uri
                                    :start (+ host 1)
                                    :end close)
                   (cons "Malformed IPv6 address" (+ host 1)))
                  (t nil)))
          nil))))

(defun rontolisp::%uri-refusal (uri)
  "java.net.URI's refusal of the string URI as a URI reference, the pair
   (text . index) its URISyntaxException holds, or nil when it takes it: a
   scheme of a letter then letters, digits and + - ., an authority, a path, a
   query and a fragment of the characters each takes, or after a scheme with
   no slash an opaque part that is not empty."
  (let* ((n (length uri))
         (stop (rontolisp::%uri-first-of uri "/?#" 0))
         (colon (position #\: uri :end stop))
         (refusal nil)
         (p 0))
    (if colon
        (progn
          (if (= colon 0) (setq refusal (cons "Expected scheme name" 0)))
          (dotimes (i colon)
            (let ((c (char uri i)))
              (if (and (null refusal)
                       (not
                        (or (and (< (char-code c) 128) (alpha-char-p c))
                            (and (> i 0)
                                 (or (and (char>= c #\0) (char<= c #\9))
                                     (find c "+-."))))))
                  (setq refusal (cons "Illegal character in scheme name" i)))))
          (setq p (+ colon 1))))
    (cond (refusal refusal)
          ((and colon (or (= p n) (char= (char uri p) #\#)))
           (cons "Expected scheme-specific part" p))
          ((and colon (not (char= (char uri p) #\/)))
           ;; an opaque part, up to its fragment
           (let ((q (rontolisp::%uri-first-of uri "#" p)))
             (or (rontolisp::%uri-scan uri p q ";/?:@&=+$,[]" "opaque part")
                 (if (< q n)
                     (rontolisp::%uri-scan uri (+ q 1) n ";/?:@&=+$,[]"
                                           "fragment")))))
          (t
           (if (and (< (+ p 1) n) (char= (char uri p) #\/)
                    (char= (char uri (+ p 1)) #\/))
               (let ((q (rontolisp::%uri-first-of uri "/?#" (+ p 2))))
                 (setq refusal
                       (cond ((and (= q (+ p 2)) (= q n))
                              (cons "Expected authority" q))
                             ((rontolisp::%uri-literal-refusal uri (+ p 2) q))
                             (t (rontolisp::%uri-scan uri (+ p 2) q ";:@&=+$,[]"
                                                      "authority"))))
                 (setq p q)))
           (if (null refusal)
               (let ((q (rontolisp::%uri-first-of uri "?#" p)))
                 (setq refusal
                       (rontolisp::%uri-scan uri p q ";/:@&=+$," "path"))
                 (setq p q)))
           (if (and (null refusal) (< p n) (char= (char uri p) #\?))
               (let ((q (rontolisp::%uri-first-of uri "#" (+ p 1))))
                 (setq refusal
                  (rontolisp::%uri-scan uri (+ p 1) q ";/?:@&=+$,[]" "query"))
                 (setq p q)))
           (if (and (null refusal) (< p n))
               (setq refusal
                (rontolisp::%uri-scan uri (+ p 1) n ";/?:@&=+$,[]" "fragment")))
           refusal))))

(defun rontolisp::%uri-split (url)
  "The parts of the URI reference URL as RFC 3986's appendix B cuts them, the
   list (scheme authority path query fragment): each nil when absent but the
   path, a string. Two slashes with nothing before the next slash are no
   authority, and the path is what follows them (java.net.URI resolves ///g as
   /g and //?q as ?q)."
  (let* ((n (length url))
         (stop (rontolisp::%uri-first-of url ":/?#" 0))
         (scheme
          (if (and (< stop n) (> stop 0) (char= (char url stop) #\:))
              (subseq url 0 stop)))
         (at (if scheme (+ stop 1) 0))
         (path-end (rontolisp::%uri-first-of url "?#" at))
         (hash (position #\# url :start path-end))
         (authority nil))
    (if (and (<= (+ at 2) path-end) (char= (char url at) #\/)
             (char= (char url (+ at 1)) #\/))
        (let ((authority-end
               (or (position #\/ url :start (+ at 2) :end path-end) path-end)))
          (if (> authority-end (+ at 2))
              (setq authority (subseq url (+ at 2) authority-end)))
          (setq at authority-end)))
    (list scheme authority (subseq url at path-end)
          (if (and (< path-end n) (char= (char url path-end) #\?))
              (subseq url (+ path-end 1) (or hash n)))
          (if hash (subseq url (+ hash 1))))))

(defun rontolisp::%uri-remove-dots (path)
  "PATH with its dot segments removed, as java.net.URI removes them from a
   relative reference merged with its base (its answers, probed): RFC 3986's
   remove_dot_segments, but for two departures. Empty segments go too (a//b is
   a/b). A .. with no segment of its own before it stays where it is
   (/a/../../g is /../g, where the RFC answers /g). The result ends in a slash
   when the input's last step was one, a ., or a .. that removed a segment."
  (let ((kept nil) (directory nil) (from 0) (n (length path)))
    (loop
      (let* ((slash (or (position #\/ path :start from) n))
             (segment (subseq path from slash)))
        (cond
         ((or (string= segment "") (string= segment ".")) (setq directory t))
         ((and (string= segment "..") kept (not (string= (car kept) "..")))
          (setq kept (cdr kept))
          (setq directory t))
         (t
          (setq kept (cons segment kept))
          (setq directory nil)))
        (if (>= slash n) (return nil))
        (setq from (+ slash 1))))
    (let ((out (make-string-output-stream)))
      (if (and (> n 0) (char= (char path 0) #\/)) (write-char #\/ out))
      (setq kept (reverse kept))
      (loop
        (if (null kept) (return nil))
        (write-string (car kept) out)
        (setq kept (cdr kept))
        (if (or kept directory) (write-char #\/ out)))
      (get-output-stream-string out))))

(defun rontolisp::%uri-resolve (base location)
  "The URI the reference LOCATION names from the URI BASE: RFC 3986's section
   5.2.2 with java.net.URI's departures (its answers, which the JDK client's
   redirects were probed to follow). Only a relative path has its dot segments
   removed: an absolute LOCATION, a network path and an absolute path are
   taken as written. A LOCATION of a fragment alone keeps the base's path and
   query; any other empty path is the base's directory, and the base's query
   goes (where the RFC keeps both)."
  (let ((ref (rontolisp::%uri-split location)))
    (if (car ref)
        location
        (let* ((at (rontolisp::%uri-split base))
               (path (nth 2 ref))
               (query (nth 3 ref))
               (authority (or (nth 1 ref) (nth 1 at))))
          (cond ((nth 1 ref))
                ((and (string= path "") (null query) (nth 4 ref))
                 (setq path (nth 2 at))
                 (setq query (nth 3 at)))
                ((and (> (length path) 0) (char= (char path 0) #\/)))
                (t
                 (let ((slash (position #\/ (nth 2 at) :from-end t)))
                   (setq path
                         (rontolisp::%uri-remove-dots
                          (cond (slash (concatenate 'string
                                        (subseq (nth 2 at) 0 (+ slash 1)) path))
                                ((or (string= path "") (null authority)) path)
                                (t (concatenate 'string "/" path))))))))
          (concatenate 'string
           (if (car at) (concatenate 'string (car at) ":") "")
           (if authority (concatenate 'string "//" authority) "") path
           (if query (concatenate 'string "?" query) "")
           (if (nth 4 ref) (concatenate 'string "#" (nth 4 ref)) ""))))))

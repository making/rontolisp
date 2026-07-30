;; The connection details, out of a URL instead of out of the source: every
;; program in this directory calls (database-url-parts (uiop:getenv
;; "DATABASE_URL")). Reading the environment is the caller's business -- this
;; file only knows how to take a URL apart -- and a literal top-level (load
;; "database-url.lisp") is spliced in at compile time, so these defuns compile
;; natively on every backend.
;;
;;   export DATABASE_URL=postgresql://user:password@host:port/database
;;
;; postgres:// is accepted as an alias for postgresql://. The port is optional
;; (5432), so is a trailing query string -- an ?sslmode=..., say, which these
;; examples recognise and drop -- and the password is optional too, which is
;; what the trust-auth server in the README wants. %XX escapes in the user and
;; the password are decoded, so a password holding an @ or a : travels as %40 /
;; %3A. Note that + stays a literal plus: this is URI userinfo, not a query
;; string, which is why rontolisp:url-decode is the wrong tool here.

(defun db-url-hex-value (c)
  "The value of one hexadecimal digit, or nil if C is not one."
  (let ((code (char-code c)))
    (cond ((and (>= code 48) (<= code 57)) (- code 48))
          ((and (>= code 97) (<= code 102)) (- code 87))
          ((and (>= code 65) (<= code 70)) (- code 55))
          (t nil))))

(defun db-url-decode (s)
  "S with its %XX escapes turned back into characters. Anything that is not a
   well-formed escape -- a lone %, a % followed by non-hex -- is left as it
   stands."
  (let ((n (length s)) (out "") (i 0))
    (do ()
        ((>= i n) out)
      (let* ((escapep (and (char= (char s i) #\%) (< (+ i 2) n)))
             (hi (if escapep (db-url-hex-value (char s (+ i 1))) nil))
             (lo (if escapep (db-url-hex-value (char s (+ i 2))) nil))
             (escaped (and hi lo)))
        (setq out (concatenate 'string out
                               (string (if escaped
                                           (code-char (+ (* 16 hi) lo))
                                           (char s i)))))
        (setq i (+ i (if escaped 3 1)))))))

(defun db-url-error (reason url)
  "REASON, the offending URL, and a reminder of the shape one has."
  (error "~a: ~a~%Expected postgresql://user:password@host:port/database" reason url))

(defun db-url-part (s empty-reason url)
  "S, or an error naming URL when S is empty. Nothing in a connection URL is
   worth defaulting silently."
  (if (string= s "") (db-url-error empty-reason url) s))

(defun db-url-port (text url)
  "TEXT as a port number. :junk-allowed stops parse-integer from signalling on
   its own terms, so the whole of TEXT has to be consumed for the number to
   count -- a 5432x is a typo, not a port."
  (multiple-value-bind (port end) (parse-integer text :junk-allowed t)
    (if (and port (= end (length text)))
        port
        (db-url-error "the port is not a number" url))))

(defun database-url-parts (url)
  "Splits postgresql://user:password@host:port/database into five values --
   database, user, password, host, port -- which is exactly the argument order
   cl-postgres:open-database takes. The password is nil when URL carries none.
   URL is whatever the caller got hold of, nil included: an unset environment
   variable is an error here rather than a fallback address, because a default
   would be the hardcoded connection this file exists to remove."
  (when (or (null url) (string= url ""))
    (error "no database URL given.~%Expected postgresql://user:password@host:port/database"))
  (let ((mark (search "://" url)))
    (when (null mark)
      (db-url-error "not a URL" url))
    (let ((scheme (string-downcase (subseq url 0 mark))))
      (when (and (string/= scheme "postgresql") (string/= scheme "postgres"))
        (db-url-error (concatenate 'string "the scheme is " scheme "://, not postgresql://")
                      url)))
    (let* ((body (subseq url (+ mark 3)))
           ;; A trailing ?sslmode=... and friends is not part of the authority.
           (query (position #\? body))
           (located (if query (subseq body 0 query) body))
           ;; The authority ends at the first / ; what follows is the database.
           (slash (position #\/ located))
           (authority (if slash (subseq located 0 slash) located))
           (database (if slash (subseq located (+ slash 1)) ""))
           ;; The LAST @ separates the userinfo, so an unescaped @ inside the
           ;; password cuts the string in the right place anyway.
           (at (position #\@ authority :from-end t))
           (userinfo (if at (subseq authority 0 at) ""))
           (hostport (if at (subseq authority (+ at 1)) authority))
           (user-end (position #\: userinfo))
           (user (db-url-decode (if user-end (subseq userinfo 0 user-end) userinfo)))
           (secret (if user-end (db-url-decode (subseq userinfo (+ user-end 1))) ""))
           (host-end (position #\: hostport :from-end t))
           (host (if host-end (subseq hostport 0 host-end) hostport))
           (port-text (if host-end (subseq hostport (+ host-end 1)) "5432"))
           (port (db-url-port port-text url)))
      (values (db-url-part database "no database in the URL" url)
              (db-url-part user "no user in the URL" url)
              ;; nil, not "": a server on trust auth wants no password at all.
              (if (string= secret "") nil secret)
              (db-url-part host "no host in the URL" url)
              port))))

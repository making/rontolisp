;; A leaf-module shim replacing tiny-routes' src/middleware/path-template.lisp
;; for the OPT-IN "tiny-routes/lite" system ONLY -- the full "tiny-routes"
;; system keeps the real file and its cl-ppcre engine (see tiny-routes-lite.asd
;; beside this file). Same package, same four exports, and the same matcher
;; dispatch; what changes is HOW a keyword template matches: a ppcre-free
;; parser + backtracking matcher accepting exactly the templates whose upstream
;; compilation is ^literal([^/]+)literal...$. A :name token is
;; :([A-Za-z_][A-Za-z0-9_-]*) anywhere in the template (mid-segment included,
;; and the token-name scan is as greedy as upstream's: "/a/:x-:y" parses as
;; the tokens :X- and :Y, adjacent); every other character must be a
;; regex-INERT literal, so a template containing any of . \ [ ] ( ) { } | ^ $
;; * + ? -- which upstream hands to cl-ppcre as live regex syntax -- signals a
;; self-describing error at route-BUILD time, as does :regex t. Within the
;; subset the matcher reproduces the scanner's greedy-with-backtracking
;; semantics (a token takes the longest run of non-/ characters that lets the
;; rest of the template match, one character minimum), pinned
;; template-for-template against the real engine by
;; TinyRoutesLiteUpstreamParityTest; outside it, it refuses loudly. So no
;; template ever matches DIFFERENTLY from the full system.
;;
;; make-path-template-exact-matcher, wrap-request-path-info-matcher,
;; wrap-request-matches-path-template, path-parameter and with-path-parameters
;; are reproduced VERBATIM from tiny-routes v0.1.1 (Johnny Ruiz, BSD 3-Clause)
;; -- they never touched cl-ppcre. Written in canonical shape (qualified
;; names, no in-package); the defpackage registers the package exactly as the
;; replaced file's uiop:define-package would, exports included, so
;; middleware.lisp's :use-reexport re-exports the same four names. No
;; *path-token-scanner* defvar exists here: the token scan is the parser
;; below, and nothing runs at load time.

(defpackage #:tiny-routes.middleware.path-template
  (:use #:cl)
  (:export #:path-parameter #:with-path-parameters
           #:wrap-request-path-info-matcher
           #:wrap-request-matches-path-template))

(defun tiny-routes.middleware.path-template::%lite-token-start-p (ch)
  (or (and (char<= #\A ch) (char<= ch #\Z))
      (and (char<= #\a ch) (char<= ch #\z)) (char= ch #\_)))

(defun tiny-routes.middleware.path-template::%lite-token-char-p (ch)
  (or (tiny-routes.middleware.path-template::%lite-token-start-p ch)
      (and (char<= #\0 ch) (char<= ch #\9)) (char= ch #\-)))

(defun tiny-routes.middleware.path-template::%lite-check-template
    (path-template)
  ;; Reject every character upstream's scanner would interpret as regex
  ;; syntax. The check runs only for a KEYWORD template (the one matcher
  ;; upstream compiles to a regex); an exact template is compared with
  ;; string= there and here, metacharacters included.
  (dotimes (i (length path-template))
    (let ((ch (char path-template i)))
      (when (find ch ".\\[](){}|^$*+?")
        (error "tiny-routes/lite: path template ~S contains the regex metacharacter ~A; the ppcre-free matcher accepts only literal characters and :name tokens -- load the full \"tiny-routes\" system for regex-capable templates"
               path-template ch)))))

(defun tiny-routes.middleware.path-template::%lite-parse-template
    (path-template)
  ;; The template as a list of parts, in order: a string (a literal run) or a
  ;; keyword (a :name token, interned like upstream's plist keys). The scan
  ;; mirrors the upstream token scanner exactly: a token starts at a `:`
  ;; followed by [A-Za-z_] and extends greedily over [A-Za-z0-9_-]*; any
  ;; other `:` is an ordinary literal character.
  (let ((parts nil) (len (length path-template)) (lit-start 0) (i 0))
    (do ()
        ((>= i len))
      (if (and (char= (char path-template i) #\:) (< (+ i 1) len)
               (tiny-routes.middleware.path-template::%lite-token-start-p
                (char path-template (+ i 1))))
          (let ((name-end (+ i 1)))
            (do ()
                ((or (>= name-end len)
                     (not
                      (tiny-routes.middleware.path-template::%lite-token-char-p
                       (char path-template name-end)))))
              (setq name-end (+ name-end 1)))
            (when (> i lit-start)
              (setq parts (cons (subseq path-template lit-start i) parts)))
            (setq parts
                  (cons (intern
                         (string-upcase (subseq path-template (+ i 1) name-end))
                         :keyword) parts))
            (setq i name-end)
            (setq lit-start name-end))
          (setq i (+ i 1))))
    (when (> len lit-start)
      (setq parts (cons (subseq path-template lit-start len) parts)))
    (nreverse parts)))

(defun tiny-routes.middleware.path-template::%lite-match-parts (parts path pos)
  ;; Backtracking matcher: :fail, or the token values matched from POS on, in
  ;; template order (nil for a match binding no tokens). A token is greedy
  ;; like upstream's ([^/]+): longest first, down to one character, recursing
  ;; on the rest of the template -- so "/a/b-c-d" against the parts of
  ;; "/a/:x-:y" (two ADJACENT tokens) binds "b-c-" then "d", exactly as the
  ;; regex engine backtracks.
  (cond ((null parts) (if (= pos (length path)) nil :fail))
        ((stringp (car parts))
         (let ((end (+ pos (length (car parts)))))
           (if (and (<= end (length path))
                    (string= (car parts) (subseq path pos end)))
               (tiny-routes.middleware.path-template::%lite-match-parts
                (cdr parts) path end)
               :fail)))
        (t (let ((limit pos) (len (length path)))
             (do ()
                 ((or (>= limit len) (char= (char path limit) #\/)))
               (setq limit (+ limit 1)))
             (do ((end limit (- end 1)) (found :fail))
                 ((or (<= end pos) (not (eq found :fail))) found)
               (let ((rest
                      (tiny-routes.middleware.path-template::%lite-match-parts
                       (cdr parts) path end)))
                 (unless (eq rest :fail)
                   (setq found (cons (subseq path pos end) rest)))))))))

(defun tiny-routes.middleware.path-template::make-path-template-exact-matcher
    (path-template)
  "Return a closure that accepts a path and returns t when path matches
PATH-TEMPLATE."
  (check-type path-template string)
  (lambda (path) (string= path path-template)))

(defun tiny-routes.middleware.path-template::make-path-template-keyword-matcher
    (path-template)
  "Return a closure that accepts a path and returns an plist of matched
groups when path matches PATH-TEMPLATE."
  (check-type path-template string)
  (tiny-routes.middleware.path-template::%lite-check-template path-template)
  (let ((parts
         (tiny-routes.middleware.path-template::%lite-parse-template
          path-template))
        (token-names nil))
    (dolist (part parts)
      (when (keywordp part) (setq token-names (cons part token-names))))
    (setq token-names (nreverse token-names))
    ;; token-names and the matched values are both in template order, so the
    ;; pairs build back-to-front and one nreverse yields upstream's
    ;; (:name1 v1 :name2 v2 ...) plist order.
    (lambda (path)
      (let ((values
             (tiny-routes.middleware.path-template::%lite-match-parts parts path
                                                                      0)))
        (if (eq values :fail)
            nil
            (let ((result nil))
              (do ((names token-names (cdr names)) (vals values (cdr vals)))
                  ((null names) (nreverse result))
                (setq result (cons (car vals) (cons (car names) result))))))))))

(defun tiny-routes.middleware.path-template::make-path-template-regex-matcher
    (path-template)
  (check-type path-template string)
  (error
   "tiny-routes/lite: :regex path templates need cl-ppcre -- load the full \"tiny-routes\" system instead of \"tiny-routes/lite\""))

(defun tiny-routes.middleware.path-template:wrap-request-path-info-matcher
    (handler path-info-matcher)
  "Wrap HANDLER such that it is called only if the result of applying
PATH-INFO-MATCHER to the request's path-info returns non-nil.

If the result of applying PATH-INFO-MATCHER to the request is a list,
then it is made available to the request under `:path-parameters'."
  (check-type path-info-matcher function)
  (lambda (request)
    (let* ((path-info (tiny-routes.request:path-info request ""))
           (params (funcall path-info-matcher path-info)))
      (cond ((null params) nil)
            ((listp params)
             (funcall handler
                      (tiny-routes.request:request-append request
                       :path-parameters params)))
            (t (funcall handler request))))))

(defun tiny-routes.middleware.path-template:wrap-request-matches-path-template
    (handler path-template &key regex)
  "Wrap HANDLER such that it is called only if the request path matches
the PATH-TEMPLATE.

If PATH-TEMPLATE is t, nil, the empty string, or \"*\", then return
HANDLER unchanged.

If REGEX is non-nil, then interpret path-template as a regular
expression."
  (check-type path-template (or symbol string))
  (cond
   ((or (null path-template) (eq path-template t) (string= path-template "")
        (string= path-template "*"))
    handler)
   (regex
    (tiny-routes.middleware.path-template:wrap-request-path-info-matcher handler
     (tiny-routes.middleware.path-template::make-path-template-regex-matcher
      path-template)))
   ((find #\: path-template)
    (tiny-routes.middleware.path-template:wrap-request-path-info-matcher handler
     (tiny-routes.middleware.path-template::make-path-template-keyword-matcher
      path-template)))
   (t
    (tiny-routes.middleware.path-template:wrap-request-path-info-matcher handler
     (tiny-routes.middleware.path-template::make-path-template-exact-matcher
      path-template)))))

(defun tiny-routes.middleware.path-template:path-parameter
    (request path-parameter &optional default)
  "Return the value mapped to PATH-PARAMETER from REQUEST or DEFAULT."
  (getf (getf request :path-parameters) path-parameter default))

(defmacro tiny-routes.middleware.path-template:with-path-parameters
    (vars path-parameters &body body)
  "Bind the variables in VARS to the corresponding values present in
PATH-PARAMETERS."
  (let ((gpath-parameters (gensym "path-parameters")))
    `(let* ((,gpath-parameters ,path-parameters))
       (destructuring-bind (&key ,@vars &allow-other-keys) ,gpath-parameters
         ,@body))))

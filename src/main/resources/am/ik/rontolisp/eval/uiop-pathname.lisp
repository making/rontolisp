;;;; uiop/pathname -- the pathname algebra. Canonical shape; see .kb/uiop.md.
;;;;
;;;; A rontolisp pathname carries ONE flat namestring (.kb/pathnames.md), so
;;;; upstream's component-wise algebra collapses onto namestring work over the
;;;; prelude family (%pathname-split / %path-ns / pathname / namestring /
;;;; wild-pathname-p / translate-pathname). Logical pathnames do not exist and
;;;; cannot (no logical host, no translations table), so logical-pathname-p is
;;;; nil for everything, physical-pathname-p is pathnamep, physicalize-pathname
;;;; is the coercing identity, and make-pathname-logical signals
;;;; not-implemented-error naming the missing model rather than answering a
;;;; physical pathname that claims to be logical.

;;; Normalizing directory components. These three run over CLHS-standard
;;; directory LISTS -- the shape cl:pathname-directory answers here too -- and
;;; are portable computation, kept upstream-shaped.

(defun uiop/pathname:normalize-pathname-directory-component (%npd-directory)
  (cond ((stringp %npd-directory) (list :absolute %npd-directory))
        ((or (null %npd-directory)
             (and (consp %npd-directory)
                  (or (eq (car %npd-directory) :absolute)
                      (eq (car %npd-directory) :relative))))
         %npd-directory)
        (t (uiop/utility:parameter-error
            "~S: Unrecognized pathname directory component ~S"
            'uiop/pathname:normalize-pathname-directory-component
            %npd-directory))))

(defun uiop/pathname:denormalize-pathname-directory-component
    (%dnd-directory-component)
  %dnd-directory-component)

(defun uiop/pathname:merge-pathname-directory-components
    (%mpd-specified %mpd-defaults)
  (let ((%mpd-dir
         (uiop/pathname:normalize-pathname-directory-component %mpd-specified)))
    (cond ((null %mpd-dir) %mpd-defaults)
          ((eq (car %mpd-dir) :absolute) %mpd-specified)
          (t (let ((%mpd-defdir
                    (uiop/pathname:normalize-pathname-directory-component
                     %mpd-defaults))
                   (%mpd-reldir (cdr %mpd-dir)))
               (cond ((null %mpd-defdir) %mpd-dir)
                     ((not (eq (first %mpd-reldir) :back))
                      (append %mpd-defdir %mpd-reldir))
                     (t (let ((%mpd-defabs (first %mpd-defdir))
                              (%mpd-defrev (reverse (rest %mpd-defdir))))
                          (do ()
                              ((not
                                (and (eq (car %mpd-reldir) :back)
                                     (or (and (eq %mpd-defabs :absolute)
                                              (null %mpd-defrev))
                                         (stringp (car %mpd-defrev)))))
                               (cons %mpd-defabs
                                (append (reverse %mpd-defrev) %mpd-reldir)))
                            (setq %mpd-reldir (cdr %mpd-reldir))
                            (setq %mpd-defrev (cdr %mpd-defrev)))))))))))

;; nil, the arm upstream picks for the implementations whose make-pathname
;; refuses :unspecific -- and the only honest one here, where a component that
;; is not present is nil.
(defparameter uiop/pathname:*unspecific-pathname-type* nil)

(defun uiop/pathname:make-pathname* (&rest %mkps-keys)
  (apply #'make-pathname %mkps-keys))

;; :unspecific becomes nil (upstream's portable arm); everything else passes
;; through. Real even though no logical pathname exists: the function is pure
;; component surgery.
(defun uiop/pathname:make-pathname-component-logical (%mcl-x)
  (if (eq %mcl-x :unspecific) nil %mcl-x))

(defun uiop/pathname:make-pathname-logical (%mpl-pathname %mpl-host)
  (declare (ignore %mpl-pathname %mpl-host))
  (uiop/utility:not-implemented-error "UIOP/PATHNAME:MAKE-PATHNAME-LOGICAL"
   "rontolisp defines no logical hosts (.kb/pathnames.md)"))

;; The safer defaults-aware merge, portable across ASDF-loaded libraries. A
;; rontolisp pathname carries a flat namestring, so upstream's component-wise
;; care collapses onto cl:merge-pathnames, whose rontolisp definition already
;; implements the same rule (an absolute specified wins, a relative one is
;; appended to the defaults' directory, an absent component is taken from the
;; defaults). Lisp source rather than a Java built-in so all four backends have
;; it: as an interpreter-only primitive, a call with non-literal arguments was
;; "The function UIOP:MERGE-PATHNAMES* is undefined" on the JVM and on both WASM
;; backends.
(defun uiop/pathname:merge-pathnames* (%mps-specified &optional %mps-defaults)
  (merge-pathnames %mps-specified %mps-defaults))

;; The neutral defaults: the empty pathname, the same value
;; *default-pathname-defaults* starts as -- rontolisp absolutizes nowhere and
;; models no host or device, so there is nothing more neutral to build.
(defun uiop/pathname:nil-pathname (&optional %nlp-defaults)
  (declare (ignore %nlp-defaults))
  (pathname ""))

(defvar uiop/pathname:*nil-pathname* #P"")

;;; Predicates.

(defun uiop/pathname:pathname-equal (%peq-p1 %peq-p2)
  (let ((%peq-a (if (stringp %peq-p1) (pathname %peq-p1) %peq-p1))
        (%peq-b (if (stringp %peq-p2) (pathname %peq-p2) %peq-p2)))
    (or (and (null %peq-a) (null %peq-b))
        (and (pathnamep %peq-a) (pathnamep %peq-b)
             (string= (namestring %peq-a) (namestring %peq-b))))))

(defun uiop/pathname:logical-pathname-p (%lpp-x)
  (declare (ignore %lpp-x))
  nil)

(defun uiop/pathname:physical-pathname-p (%ppp-x) (pathnamep %ppp-x))

(defun uiop/pathname:physicalize-pathname (%phz-x)
  (when %phz-x (pathname %phz-x)))

;; A rontolisp namestring is the host spelling, so "absolute" is the leading
;; separator -- there is no device or host component to weigh. Answers the
;; PATHNAME rather than T, like upstream (a generalized boolean).
(defun uiop/pathname:absolute-pathname-p (%apn-path)
  (let ((%apn-s (and %apn-path (namestring %apn-path))))
    (when (and (> (length %apn-s) 0) (char= (char %apn-s 0) #\/))
      (pathname %apn-s))))

(defun uiop/pathname:relative-pathname-p (%rpp-pathspec)
  (and %rpp-pathspec (or (stringp %rpp-pathspec) (pathnamep %rpp-pathspec))
       (let ((%rpp-s (namestring %rpp-pathspec)))
         (when (or (= (length %rpp-s) 0) (not (char= (char %rpp-s 0) #\/)))
           (pathname %rpp-s)))))

(defun uiop/pathname:hidden-pathname-p (%hpp-pathname)
  (and %hpp-pathname
       (let ((%hpp-n (pathname-name %hpp-pathname)))
         (and (stringp %hpp-n) (> (length %hpp-n) 0) (char= (char %hpp-n 0) #\.)
              t))))

;; Answers the parsed PATHNAME when a name or type component is present --
;; whether the namestring names a FILE rather than a directory. Does not touch
;; the filesystem.
(defun uiop/pathname:file-pathname-p (%fpp-pathname)
  (when %fpp-pathname
    (let* ((%fpp-p (pathname (%path-ns %fpp-pathname)))
           (%fpp-parts (%pathname-split %fpp-p))
           (%fpp-n (second %fpp-parts))
           (%fpp-t (third %fpp-parts)))
      (unless (and (or (null %fpp-n) (equal %fpp-n ""))
                   (or (null %fpp-t) (equal %fpp-t "")))
        %fpp-p))))

;;; Directory pathnames.

(defun uiop/pathname:pathname-directory-pathname (%pdp-pathname)
  (when %pdp-pathname (pathname (first (%pathname-split %pdp-pathname)))))

;; One level up from the pathname's DIRECTORY: /foo/bar/baz/file.type ->
;; /foo/bar/. The parent of the root is the root, and the parent of a
;; single-level relative directory is the empty pathname, exactly as upstream's
;; (:relative :back) merge answers on those shapes.
(defun uiop/pathname:pathname-parent-directory-pathname (%ppd-pathname)
  (when %ppd-pathname
    (let ((%ppd-d (first (%pathname-split %ppd-pathname))))
      (cond ((string= %ppd-d "") (pathname ""))
            ((string= %ppd-d "/") (pathname "/"))
            (t (let* ((%ppd-s (subseq %ppd-d 0 (- (length %ppd-d) 1)))
                      (%ppd-i (position #\/ %ppd-s :from-end t)))
                 (pathname (if %ppd-i (subseq %ppd-s 0 (+ %ppd-i 1)) ""))))))))

;; T for a non-wild namestring with no name and no type -- i.e. one that is
;; empty or ends in the separator. Does not touch the filesystem.
(defun uiop/pathname:directory-pathname-p (%dpp-pathname)
  (when %dpp-pathname
    (let* ((%dpp-s (namestring %dpp-pathname))
           (%dpp-parts (%pathname-split %dpp-s))
           (%dpp-n (second %dpp-parts))
           (%dpp-t (third %dpp-parts)))
      (and (not (wild-pathname-p %dpp-s)) (or (null %dpp-n) (equal %dpp-n ""))
           (or (null %dpp-t) (equal %dpp-t "")) t))))

;; A pathname's namestring is flat here, so "the pathname in directory form" is
;; "the namestring with a trailing slash": merge-pathnames against one of these
;; appends, which is exactly what smart-buffer's *temporary-directory* is built
;; by.
(defun uiop/pathname:ensure-directory-pathname (%edp-path)
  (let ((%edp-s (namestring %edp-path)))
    (pathname
     (if (or (string= %edp-s "")
             (char= (char %edp-s (- (length %edp-s) 1)) #\/))
         %edp-s
         (concatenate 'string %edp-s "/")))))

;;; Parsing filenames.

;; Four values: :absolute or :relative, the directory components (a list of
;; strings, .. read as DOT-DOT or :back), the last component (a file-namestring,
;; nil for a directory namestring), and whether the whole string was a bare
;; file-namestring. Components that are empty or "." are dropped.
(defun uiop/pathname:split-unix-namestring-directory-components (%sun-namestring
                                                                 &key
                                                                 ((:ensure-directory
                                                                   %sun-ensure-directory))
                                                                 ((:dot-dot
                                                                   %sun-dot-dot)))
  (check-type %sun-namestring string)
  (if (and (not (position #\/ %sun-namestring)) (not %sun-ensure-directory)
           (> (length %sun-namestring) 0))
      (values :relative nil %sun-namestring t)
      (let ((%sun-relative
             (if (and (> (length %sun-namestring) 0)
                      (char= (char %sun-namestring 0) #\/))
                 :absolute :relative))
            (%sun-comps nil)
            (%sun-start 0)
            (%sun-last nil))
        (dotimes (%sun-i (length %sun-namestring))
          (when (char= (char %sun-namestring %sun-i) #\/)
            (setq %sun-comps
                  (cons (subseq %sun-namestring %sun-start %sun-i) %sun-comps))
            (setq %sun-start (+ %sun-i 1))))
        (setq %sun-last (subseq %sun-namestring %sun-start))
        (let ((%sun-path nil))
          (dolist (%sun-c (reverse %sun-comps))
            (cond ((or (equal %sun-c "") (equal %sun-c ".")) nil)
                  ((equal %sun-c "..")
                   (setq %sun-path (cons (or %sun-dot-dot :back) %sun-path)))
                  (t (setq %sun-path (cons %sun-c %sun-path)))))
          (setq %sun-path (reverse %sun-path))
          (cond ((equal %sun-last "") (values %sun-relative %sun-path nil nil))
                (%sun-ensure-directory
                 (values %sun-relative
                         (append %sun-path
                                 (cond ((equal %sun-last ".") nil)
                                       ((equal %sun-last "..")
                                        (list (or %sun-dot-dot :back)))
                                       (t (list %sun-last)))) nil nil))
                (t (values %sun-relative %sun-path %sun-last nil)))))))

;; NAME and TYPE of a filename with no directory component: the last dot
;; separates them, except a lone leading dot, which makes the whole filename the
;; NAME (with *unspecific-pathname-type*, i.e. nil, as the type). The same rule
;; %pathname-split renders for the whole CL family, so the two cannot disagree.
(defun uiop/pathname:split-name-type (%snt-filename)
  (check-type %snt-filename string)
  (when (= (length %snt-filename) 0)
    (uiop/utility:parameter-error "~S: empty filename"
                                  'uiop/pathname:split-name-type))
  (let* ((%snt-parts (%pathname-split %snt-filename))
         (%snt-name (second %snt-parts))
         (%snt-type (third %snt-parts)))
    (if (null %snt-type)
        (values %snt-filename uiop/pathname:*unspecific-pathname-type*)
        (values %snt-name %snt-type))))

;; Coerce NAME into a pathname using Unix syntax. A pathname passes through,
;; nil stays nil, a symbol is downcased; a string is decomposed, its "." and
;; empty components dropped, and the result handed to ensure-pathname with any
;; remaining keys (:want-relative and friends).
(defun uiop/pathname:parse-unix-namestring (%pun-name &rest %pun-keys &key
                                            ((:type %pun-type))
                                            ((:defaults %pun-defaults))
                                            ((:dot-dot %pun-dot-dot))
                                            ((:ensure-directory
                                              %pun-ensure-directory))
                                            &allow-other-keys)
  (declare (ignore %pun-defaults))
  (block nil
    (when %pun-ensure-directory (setq %pun-type :directory))
    (cond ((null %pun-name) (return nil))
     ((pathnamep %pun-name) (return %pun-name))
     ((symbolp %pun-name) (setq %pun-name (string-downcase (string %pun-name))))
     ((stringp %pun-name) nil)
     (t (uiop/utility:parameter-error "~S: not a valid namestring ~S"
                                      'uiop/pathname:parse-unix-namestring
                                      %pun-name)))
    (multiple-value-bind (%pun-rel %pun-path %pun-file) (uiop/pathname:split-unix-namestring-directory-components
                                                         %pun-name
                                                         :dot-dot %pun-dot-dot
                                                         :ensure-directory
                                                         (eq %pun-type
                                                             :directory))
      (let ((%pun-dir (if (eq %pun-rel :absolute) "/" "")))
        (dolist (%pun-c %pun-path)
          (setq %pun-dir
                (concatenate 'string %pun-dir
                 (if (or (eq %pun-c :back) (eq %pun-c :up)) ".." %pun-c) "/")))
        (let ((%pun-ns
               (cond ((null %pun-file) %pun-dir)
                     ((stringp %pun-type)
                      (concatenate 'string %pun-dir %pun-file "." %pun-type))
                     (t (concatenate 'string %pun-dir %pun-file)))))
          (apply #'uiop/pathname:ensure-pathname (pathname %pun-ns)
                 (uiop/utility:remove-plist-keys '(:type :dot-dot :defaults)
                                                 %pun-keys)))))))

;; The Unix-style namestring of a pathname -- which IS the namestring here.
;; nil and strings pass through, as upstream.
(defun uiop/pathname:unix-namestring (%uns-pathname)
  (cond ((null %uns-pathname) nil)
        ((stringp %uns-pathname) %uns-pathname)
        ((pathnamep %uns-pathname) (namestring %uns-pathname))
        (t (uiop/utility:parameter-error "~S: invalid unix-namestring ~S"
                                         'uiop/pathname:unix-namestring
                                         %uns-pathname))))

;;; Absolute and relative pathnames.

;; SUBPATH under PATHNAME's directory: an absolute pathname OBJECT passes
;; through; anything else is parsed as a relative unix namestring (given TYPE)
;; and merged under PATHNAME's directory.
(defun uiop/pathname:subpathname
    (%spn-pathname %spn-subpath &key ((:type %spn-type)))
  (or (and (pathnamep %spn-subpath)
           (uiop/pathname:absolute-pathname-p %spn-subpath))
      (uiop/pathname:merge-pathnames*
       (uiop/pathname:parse-unix-namestring %spn-subpath
                                            :type %spn-type
                                            :want-relative t)
       (uiop/pathname:pathname-directory-pathname %spn-pathname))))

(defun uiop/pathname:subpathname*
    (%sps-pathname %sps-subpath &key ((:type %sps-type)))
  (and %sps-pathname
       (uiop/pathname:subpathname
        (uiop/pathname:ensure-directory-pathname %sps-pathname) %sps-subpath
        :type %sps-type)))

;; The root of the pathname's host and device: with neither modeled, the one
;; root is #P"/". The argument is still validated as a designator.
(defun uiop/pathname:pathname-root (%prt-pathname)
  (namestring %prt-pathname)
  (pathname "/"))

;; Same host as the argument, all other fields nil: no host is modeled, so the
;; answer is the empty pathname.
(defun uiop/pathname:pathname-host-pathname (%php-pathname)
  (namestring %php-pathname)
  (pathname ""))

;; upstream: an absolute path passes through, a relative one is merged against
;; DEFAULTS (a pathname designator, or a function answering one), and a relative
;; path with no absolute default is an ERROR. The error arm is deliberately not
;; taken here: rontolisp absolutizes NOWHERE -- truename carries the argument
;; namestring, *load-truename* is the path as resolved against the loading file,
;; and with no chdir a relative namestring denotes the same file for the whole
;; run (uiop:get-pathname-defaults answers *default-pathname-defaults*, #P"", for
;; exactly that reason). What callers do with the value is use it as the
;; IDENTITY of a file -- rove keys its file-to-suite map by it and looks the key
;; up again by asdf:component-pathname -- and the path as resolved is that
;; identity, so it is answered as itself. ON-ERROR is accepted and unused:
;; nothing fails.
(defun uiop/pathname:ensure-absolute-pathname
    (%eap-path &optional %eap-defaults %eap-on-error)
  (declare (ignore %eap-on-error))
  (let ((%eap-base
         (if (functionp %eap-defaults) (funcall %eap-defaults) %eap-defaults)))
    (cond ((null %eap-path) nil)
          ((uiop/pathname:absolute-pathname-p %eap-path))
          ((and %eap-base (uiop/pathname:absolute-pathname-p %eap-base))
           (uiop/pathname:merge-pathnames* %eap-path %eap-base))
          (t (pathname (namestring %eap-path))))))

;; When MAYBE-SUBPATH sits under BASE-PATHNAME, the relative pathname that
;; merges back onto it: both must be pathname OBJECTS, both absolute, and the
;; base in directory form -- then the answer is the base-relative remainder.
(defun uiop/pathname:subpathp (%sbp-maybe-subpath %sbp-base-pathname)
  (and (pathnamep %sbp-maybe-subpath) (pathnamep %sbp-base-pathname)
       (uiop/pathname:absolute-pathname-p %sbp-maybe-subpath)
       (uiop/pathname:absolute-pathname-p %sbp-base-pathname)
       (uiop/pathname:directory-pathname-p %sbp-base-pathname)
       (let* ((%sbp-s (namestring %sbp-maybe-subpath))
              (%sbp-b (namestring %sbp-base-pathname))
              (%sbp-n (length %sbp-b)))
         (when (and (<= %sbp-n (length %sbp-s))
                    (string= %sbp-b (subseq %sbp-s 0 %sbp-n)))
           (pathname (subseq %sbp-s %sbp-n))))))

;; The subpathp remainder when there is one, the pathname itself otherwise --
;; the shortest spelling that still names the file given the base.
(defun uiop/pathname:enough-pathname (%enh-maybe-subpath %enh-base-pathname)
  (let ((%enh-sub
         (when %enh-maybe-subpath (pathname (%path-ns %enh-maybe-subpath))))
        (%enh-base
         (when %enh-base-pathname
           (uiop/pathname:ensure-absolute-pathname
            (pathname (%path-ns %enh-base-pathname))))))
    (or (and %enh-base (uiop/pathname:subpathp %enh-sub %enh-base)) %enh-sub)))

(defun uiop/pathname:call-with-enough-pathname
    (%cwe-maybe-subpath %cwe-defaults-pathname %cwe-thunk)
  (let ((%cwe-enough
         (uiop/pathname:enough-pathname %cwe-maybe-subpath
                                        %cwe-defaults-pathname))
        (*default-pathname-defaults*
         (or %cwe-defaults-pathname *default-pathname-defaults*)))
    (funcall %cwe-thunk %cwe-enough)))

;;; Checking constraints. Upstream implements ensure-pathname in
;;; filesystem.lisp (the existence constraints); the export is homed here, so
;;; the definition is too. Lite next to upstream, deliberately: a failed check
;;; calls (or ON-ERROR 'error) with a two-argument report instead of upstream's
;;; ~? chain, :want-logical always fails (nothing is logical),
;;; :resolve-symlinks and :truenamize are accepted and ignored (no backend
;;; resolves a symlink), and :truename answers what probe-file answers.
(defun uiop/pathname:ensure-pathname (%ens-path &rest %ens-keys &key
                                      ((:on-error %ens-on-error))
                                      ((:defaults %ens-defaults))
                                      ((:type %ens-type))
                                      ((:dot-dot %ens-dot-dot))
                                      ((:namestring %ens-namestring))
                                      ((:empty-is-nil %ens-empty-is-nil))
                                      ((:want-pathname %ens-want-pathname))
                                      ((:want-logical %ens-want-logical))
                                      ((:want-physical %ens-want-physical))
                                      ((:ensure-physical %ens-ensure-physical))
                                      ((:want-relative %ens-want-relative))
                                      ((:want-absolute %ens-want-absolute))
                                      ((:ensure-absolute %ens-ensure-absolute))
                                      ((:ensure-subpath %ens-ensure-subpath))
                                      ((:want-file %ens-want-file))
                                      ((:want-directory %ens-want-directory))
                                      ((:ensure-directory
                                        %ens-ensure-directory))
                                      ((:want-non-wild %ens-want-non-wild))
                                      ((:want-wild %ens-want-wild))
                                      ((:wilden %ens-wilden))
                                      ((:want-existing %ens-want-existing))
                                      ((:ensure-directories-exist
                                        %ens-ensure-directories-exist))
                                      ((:truename %ens-truename))
                                      ((:resolve-symlinks
                                        %ens-resolve-symlinks))
                                      ((:truenamize %ens-truenamize))
                                      &allow-other-keys)
  (declare
   (ignore %ens-keys %ens-namestring %ens-resolve-symlinks %ens-truenamize))
  (block nil
    ;; The default ON-ERROR signals DIRECTLY rather than through call-function:
    ;; a funcalled #'error wrapper is a raw trap on the WASM backends where a
    ;; direct (error ...) is a catchable signal, and the default path must be
    ;; catchable everywhere. A custom ON-ERROR still goes through call-function.
    (flet ((%ens-err (%ens-what)
             (if (or (null %ens-on-error) (eq %ens-on-error t)
                     (eq %ens-on-error 'error))
                 (error "Invalid pathname ~S: ~A" %ens-path %ens-what)
                 (uiop/utility:call-function %ens-on-error
                                             "Invalid pathname ~S: ~A" %ens-path
                                             %ens-what))))
      (let ((%ens-p %ens-path))
        (when (stringp %ens-p)
          (when (and (string= %ens-p "") %ens-empty-is-nil) (return nil))
          (setq %ens-p
                (uiop/pathname:parse-unix-namestring %ens-p
                 :defaults %ens-defaults
                 :type %ens-type
                 :dot-dot %ens-dot-dot
                 :ensure-directory %ens-ensure-directory)))
        (when (null %ens-p)
          (when %ens-want-pathname (%ens-err "Expected a pathname, not NIL"))
          (return nil))
        (setq %ens-p (pathname %ens-p))
        (when %ens-want-logical (%ens-err "Expected a logical pathname"))
        (when (and %ens-want-physical
                   (not (uiop/pathname:physical-pathname-p %ens-p)))
          (%ens-err "Expected a physical pathname"))
        (when %ens-ensure-physical
          (setq %ens-p (uiop/pathname:physicalize-pathname %ens-p)))
        (when (and %ens-want-relative
                   (not (uiop/pathname:relative-pathname-p %ens-p)))
          (%ens-err "Expected a relative pathname"))
        (when (and %ens-want-absolute
                   (not (uiop/pathname:absolute-pathname-p %ens-p)))
          (%ens-err "Expected an absolute pathname"))
        (when (and %ens-ensure-absolute
                   (not (uiop/pathname:absolute-pathname-p %ens-p)))
          (setq %ens-p
                (uiop/pathname:ensure-absolute-pathname %ens-p %ens-defaults
                                                        %ens-on-error)))
        (when %ens-ensure-subpath
          (unless (uiop/pathname:absolute-pathname-p %ens-defaults)
            (%ens-err
             "cannot be checked to be a subpath of a non-absolute pathname"))
          (unless (uiop/pathname:subpathp %ens-p
                                          (pathname (%path-ns %ens-defaults)))
            (%ens-err "is not a sub pathname of the defaults")))
        (when (and %ens-want-file (not (uiop/pathname:file-pathname-p %ens-p)))
          (%ens-err "Expected a file pathname"))
        (when (and %ens-want-directory
                   (not (uiop/pathname:directory-pathname-p %ens-p)))
          (%ens-err "Expected a directory pathname"))
        (when (and %ens-ensure-directory
                   (not (uiop/pathname:directory-pathname-p %ens-p)))
          (setq %ens-p (uiop/pathname:ensure-directory-pathname %ens-p)))
        (when (and %ens-want-non-wild (wild-pathname-p %ens-p))
          (%ens-err "Expected a non-wildcard pathname"))
        (when (and %ens-want-wild (not (wild-pathname-p %ens-p)))
          (%ens-err "Expected a wildcard pathname"))
        (when (and %ens-wilden (not (wild-pathname-p %ens-p)))
          (setq %ens-p (uiop/pathname:wilden %ens-p)))
        (when %ens-want-existing
          (let ((%ens-x (probe-file %ens-p)))
            (if %ens-x
                (when %ens-truename (return %ens-x))
                (%ens-err "Expected an existing pathname"))))
        (when %ens-ensure-directories-exist (ensure-directories-exist %ens-p))
        (when %ens-truename
          (let ((%ens-t (probe-file %ens-p)))
            (if %ens-t
                (return %ens-t)
                (%ens-err "Can't get a truename for pathname"))))
        %ens-p))))

;;; Wildcard pathnames. A wild component is a string holding the two wildcards
;;; %wild-match reads (* and ?), so these are namestring literals rather than
;;; upstream's :wild keywords -- the "*" spelling upstream itself uses where
;;; make-pathname takes strings.

(defparameter uiop/pathname:*wild* "*")

(defparameter uiop/pathname:*wild-file* #P"*.*")

(defparameter uiop/pathname:*wild-file-for-directory* #P"*.*")

(defparameter uiop/pathname:*wild-directory* #P"*/")

(defparameter uiop/pathname:*wild-inferiors* #P"**/")

(defparameter uiop/pathname:*wild-path* #P"**/*.*")

;; Any file in any subdirectory of the given pathname's directory. The flat
;; merge appends *wild-path* under the argument's directory, exactly what
;; upstream's component merge answers on Unix.
(defun uiop/pathname:wilden (%wld-path)
  (uiop/pathname:merge-pathnames* uiop/pathname:*wild-path* %wld-path))

;;; Translating pathnames.

(defun uiop/pathname:relativize-directory-component (%rdc-directory-component)
  (let ((%rdc-d
         (uiop/pathname:normalize-pathname-directory-component
          %rdc-directory-component)))
    (if (and (consp %rdc-d) (eq (car %rdc-d) :absolute))
        (cons :relative (cdr %rdc-d))
        %rdc-d)))

(defun uiop/pathname:relativize-pathname-directory (%rpd-pathspec)
  (let ((%rpd-s (namestring %rpd-pathspec)))
    (pathname
     (if (and (> (length %rpd-s) 0) (char= (char %rpd-s 0) #\/))
         (subseq %rpd-s 1)
         %rpd-s))))

(defun uiop/pathname:directory-separator-for-host (&optional %dsh-pathname)
  (declare (ignore %dsh-pathname))
  #\/)

;; Fold host and device into the directory, for output translations. On Unix a
;; physical pathname is already in that shape, and every rontolisp pathname is
;; a Unix-shaped physical one, so this is the coercing identity.
(defun uiop/pathname:directorize-pathname-host-device (%dph-pathname)
  (pathname %dph-pathname))

;; The output-translations wrapper around cl:translate-pathname: a function
;; destination is called with (path absolute-source), t answers the path, a
;; relative destination is first merged with ROOT, and anything that is not a
;; pathname is a parameter-error, as upstream.
(defun uiop/pathname:translate-pathname* (%tps-path %tps-absolute-source
                                                    %tps-destination &optional
                                                    %tps-root %tps-source)
  (declare (ignore %tps-source))
  (cond
   ((functionp %tps-destination)
    (funcall %tps-destination %tps-path %tps-absolute-source))
   ((eq %tps-destination t) %tps-path)
   ((not (pathnamep %tps-destination))
    (uiop/utility:parameter-error "~S: Invalid destination"
                                  'uiop/pathname:translate-pathname*))
   ((not (uiop/pathname:absolute-pathname-p %tps-destination))
    (translate-pathname %tps-path %tps-absolute-source
     (uiop/pathname:merge-pathnames* %tps-destination %tps-root)))
   (%tps-root (translate-pathname
               (uiop/pathname:directorize-pathname-host-device %tps-path)
               %tps-absolute-source %tps-destination))
   (t (translate-pathname %tps-path %tps-absolute-source %tps-destination))))

(defvar uiop/pathname:*output-translation-function* 'identity)

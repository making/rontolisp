;;;; cli.lisp -- the lispresen command.
;;;;
;;;;   lispresen DECK [-o OUT] [--theme NAME] [--runtime URL|hosted|none] [--embed] [--check] [--strict]
;;;;   lispresen new FILE [--lang ja|en] [--force]
;;;;   lispresen serve DECK [--port N] [--host ADDR] [--theme NAME] [--runtime URL|hosted|none]
;;;;   lispresen --version
;;;;
;;;; Reads DECK, writes its HTML and the page image a :page-image draws, prints
;;;; diagnostics to standard error; new writes a starter deck to FILE; serve serves the
;;;; deck's page over HTTP, rebuilt and reloaded when its files change. Exit status: 0
;;;; success, 1 errors (or warnings under --strict), 2 usage. Runs unchanged on every
;;;; backend, serve on those that can open a server socket (the interpreter, the JVM, a
;;;; native executable and SBCL):
;;;;
;;;;   rontolisp lispresen/cli.lisp -- talk.lisp
;;;;   rontolisp lispresen/cli.lisp -o lispresen.jar && java -jar lispresen.jar talk.lisp
;;;;   rontolisp lispresen/cli.lisp -o lispresen.wasm && wasmtime run --dir . lispresen.wasm talk.lisp
;;;;
;;;; and on another Common Lisp:
;;;;
;;;;   sbcl --script lispresen/cli.lisp talk.lisp

#-rontolisp (require :asdf)

;;; build.lisp, starter.lisp and serve.lisp are the CLI's alone; the browser editor
;;; leaves them out.
#+rontolisp (load "src/all.lisp")
#+rontolisp (load "src/build.lisp")
#+rontolisp (load "src/starter.lisp")
#+rontolisp (load "src/serve.lisp")
#-rontolisp
(dolist (name '("all" "build" "starter" "serve"))
  (load (merge-pathnames (format nil "src/~a.lisp" name) *load-truename*)))

;;; serve's server: Clack's rontolisp handler where rontolisp can own a server socket
;;; (the interpreter and the JVM bind it, a native executable's runner does), SBCL's
;;; sockets on SBCL. A WASM module has none: Preview 1 accepts no connection, and a
;;; component's server is its host's (wasmtime serve), which would make the whole CLI a
;;; served component.
#+(or (and rontolisp (not rontolisp-wasm)) rontolisp-native)
(asdf:load-system "clack-handler-rontolisp")
#+sbcl (require :sb-bsd-sockets)

(defparameter *usage*
  "usage: lispresen DECK [-o OUT] [--theme NAME] [--runtime URL|hosted|none] [--embed] [--check] [--strict]
       lispresen new FILE [--lang ja|en] [--force]
       lispresen serve DECK [--port N] [--host ADDR] [--theme NAME] [--runtime URL|hosted|none]
       lispresen --version
  DECK and OUT may be - for standard input / output; OUT defaults to DECK with .html.
  --theme NAME  light, dark, high-contrast or midnight, overriding the deck's :theme
  --runtime R   where runnable code loads the runtime from, overriding the deck's
                :runtime: a directory URL, hosted (the project site) or none (static code)
  --embed       inline images as data: URIs, so the HTML file stands alone
  --check       report diagnostics without writing HTML or the page image
  --strict      fail on warnings too
  new FILE      write a starter deck to FILE (- for standard output), dated today (UTC)
  --lang L      the starter deck's language: ja (the default) or en
  --force       overwrite FILE when it exists
  serve DECK    serve DECK's page at http://ADDR:N/, rebuilt when a file it was built
                from changes and reloaded in the browser, the slide kept
  --port N      the port to listen on (default 8000)
  --host ADDR   the address to listen on (default 127.0.0.1, this machine only)
  --version     print the version and the commit lispresen was built from")

(defun usage-error (message)
  (format *error-output* "lispresen: ~a~%~a~%" message *usage*)
  (finish-output *error-output*)
  (uiop:quit 2))

(defun read-standard-input ()
  (with-output-to-string (out)
    (loop for line = (read-line *standard-input* nil nil)
          while line
          do (write-line line out))))

(defun default-output (deck)
  "DECK with its extension replaced by .html."
  (let ((dot (position #\. deck :from-end t))
        (slash (position #\/ deck :from-end t)))
    (concatenate 'string
                 (if (and dot (> dot 0) (or (null slash) (> dot (1+ slash))))
                     (subseq deck 0 dot)
                     deck) ".html")))

(defun read-deck (deck)
  (if (string= deck "-")
      (read-standard-input)
      (handler-case (uiop:read-file-string deck) (error () nil))))

(defun read-octets (path)
  "The bytes of the file PATH, or nil when it cannot be read."
  (handler-case (with-open-file (s path :element-type '(unsigned-byte 8))
                  (let ((octets
                         (make-array (file-length s)
                                     :element-type '(unsigned-byte 8))))
                    (read-sequence octets s)
                    octets))
    (error () nil)))

(defun file-reader (deck)
  "A function from a path DECK names to the file's bytes; a relative path is relative to
DECK's directory, or to the working directory when DECK is standard input."
  (let* ((slash (position #\/ deck :from-end t))
         (dir
          (if (and slash (string/= deck "-")) (subseq deck 0 (1+ slash)) "")))
    (lambda (src)
      (read-octets
       (if (and (> (length src) 0) (char= (char src 0) #\/))
           src
           (concatenate 'string dir src))))))

(defun write-output (html target)
  (if (string= target "-")
      (progn
        (write-string html)
        (finish-output))
      (with-open-file (s target
                         :direction :output
                         :if-exists :supersede
                         :if-does-not-exist :create)
        (write-string html s))))

(defun write-octets (octets path)
  "Writes OCTETS to the file PATH; nil, reported, when it cannot."
  (handler-case (with-open-file (s path
                                   :direction :output
                                   :element-type '(unsigned-byte 8)
                                   :if-exists :supersede
                                   :if-does-not-exist :create)
                  (write-sequence octets s)
                  t)
    (error ()
      (format *error-output* "lispresen: cannot write ~a~%" path)
      (finish-output *error-output*)
      nil)))

(defun beside (target path)
  "PATH, relative to the directory of the file TARGET, or to the working directory when
TARGET is standard output."
  (let ((slash (position #\/ target :from-end t)))
    (if (and slash (string/= target "-"))
        (concatenate 'string (subseq target 0 (1+ slash)) path)
        path)))

(defun today ()
  "Today's date as YYYY-MM-DD, in UTC: no backend but SBCL knows the local time zone, and
a starter deck is the same on every one."
  (multiple-value-bind (second minute hour day month year)
      (decode-universal-time (get-universal-time) 0)
    (declare (ignore second minute hour))
    (format nil "~4,'0d-~2,'0d-~2,'0d" year month day)))

(defun fail (control &rest args)
  (format *error-output* "lispresen: ~?~%" control args)
  (finish-output *error-output*)
  (uiop:quit 1))

(defun new-deck (args)
  "lispresen new: writes the starter deck to the file ARGS name, never over an existing
one unless --force is given."
  (let ((file nil) (language :ja) (force nil))
    (loop while args
          do
            (let ((a (pop args)))
              (cond ((string= a "--lang")
                     (let ((lang (pop args)))
                       (setf language
                             (cond ((equal lang "ja") :ja)
                                   ((equal lang "en") :en)
                                   (t (usage-error "--lang needs ja or en"))))))
                    ((string= a "--force") (setf force t))
                    ((and (> (length a) 1) (char= (char a 0) #\-))
                     (usage-error (format nil "unknown option ~a" a)))
                    (file (usage-error "give one file"))
                    (t (setf file a)))))
    (unless file (usage-error "new needs a file"))
    (let ((text (lispresen:starter-deck (today) language)))
      (if (string= file "-")
          (progn
            (write-string text)
            (finish-output))
          (let ((s (handler-case (open file
                                       :direction :output
                                       :if-exists (if force :supersede nil)
                                       :if-does-not-exist :create)
                     (error () (fail "cannot write ~a" file)))))
            (unless s (fail "~a exists; --force overwrites it" file))
            (write-string text s)
            (close s))))
    (uiop:quit 0)))

;;; --- serve ---------------------------------------------------------------------------

#+(or (and rontolisp (not rontolisp-wasm)) rontolisp-native)
(defun listen-and-serve (app host port)
  "Serves the Clack application APP on HOST:PORT until the process is stopped."
  (handler-case (clack.handler.rontolisp:run app :port port :address host)
    (error (e) (fail "cannot serve on ~a:~d: ~a" host port e))))

#+sbcl
(defun percent-decode (s)
  "S with its %XX escapes decoded as UTF-8."
  (let ((octets (make-array (length s) :element-type '(unsigned-byte 8) :fill-pointer 0))
        (i 0))
    (loop while (< i (length s))
          do (let ((c (char s i)))
               (if (and (char= c #\%) (<= (+ i 3) (length s))
                        (digit-char-p (char s (+ i 1)) 16)
                        (digit-char-p (char s (+ i 2)) 16))
                   (progn
                     (vector-push (parse-integer s :start (1+ i) :end (+ i 3) :radix 16)
                                  octets)
                     (incf i 3))
                   (progn
                     (loop for b across (lispresen::octets-of (string c))
                           do (vector-push b octets))
                     (incf i)))))
    (or (lispresen::utf-8-string (coerce octets '(simple-array (unsigned-byte 8) (*))))
        s)))

#+sbcl
(defun serve-connection (app socket)
  "One request on SOCKET: its request line and fields read, APP's response written."
  (let* ((stream (sb-bsd-sockets:socket-make-stream socket :input t :output t
                                                    :element-type :default
                                                    :external-format :latin-1
                                                    :buffering :full :timeout 10))
         (line (string-right-trim '(#\Return) (or (read-line stream nil) "")))
         (first-space (position #\Space line))
         (second-space (and first-space (position #\Space line :start (1+ first-space))))
         (target (if first-space (subseq line (1+ first-space) second-space) "/"))
         (query (position #\? target)))
    (loop for field = (read-line stream nil)
          while (and field (string/= (string-right-trim '(#\Return) field) "")))
    (destructuring-bind (status headers &optional body)
        (funcall app
                 (list :request-method
                       (intern (string-upcase (subseq line 0 (or first-space 0))) :keyword)
                       :path-info (percent-decode (subseq target 0 query))
                       :query-string (and query (subseq target (1+ query)))))
      (format stream "HTTP/1.1 ~d ~a~c~c" status (if (= status 200) "OK" "") #\Return
              #\Linefeed)
      (loop for (name value) on headers by #'cddr
            do (format stream "~(~a~): ~a~c~c" name value #\Return #\Linefeed))
      (format stream "content-length: ~d~c~cconnection: close~c~c~c~c" (length body)
              #\Return #\Linefeed #\Return #\Linefeed #\Return #\Linefeed)
      (unless (string= (subseq line 0 (or first-space 0)) "HEAD")
        (write-sequence body stream))
      (finish-output stream))))

#+sbcl
(defun listen-and-serve (app host port)
  "Serves the Clack application APP on HOST:PORT, one connection at a time, until the
process is stopped."
  (let ((listener (make-instance 'sb-bsd-sockets:inet-socket :type :stream
                                                              :protocol :tcp)))
    (setf (sb-bsd-sockets:sockopt-reuse-address listener) t)
    (handler-case
        (progn
          (sb-bsd-sockets:socket-bind listener
                                      (sb-bsd-sockets:host-ent-address
                                       (sb-bsd-sockets:get-host-by-name host))
                                      port)
          (sb-bsd-sockets:socket-listen listener 64))
      (error (e) (fail "cannot serve on ~a:~d: ~a" host port e)))
    (loop
      (let ((socket (sb-bsd-sockets:socket-accept listener)))
        (unwind-protect (handler-case (serve-connection app socket) (error () nil))
          (sb-bsd-sockets:socket-close socket))))))

#-(or (and rontolisp (not rontolisp-wasm)) rontolisp-native sbcl)
(defun listen-and-serve (app host port)
  (declare (ignore app host port))
  (fail "serve needs a server socket, which this build cannot open (a WASM module accepts no connection); serve with the native executable, the JVM, the interpreter or SBCL"))

(defun serve-deck (args)
  "lispresen serve: serves DECK's page until the process is stopped."
  (let ((deck nil) (port 8000) (host "127.0.0.1") (theme nil) (runtime nil))
    (loop while args
          do
            (let ((a (pop args)))
              (cond ((string= a "--port")
                     (let ((n (and args
                                   (handler-case (parse-integer (pop args))
                                     (error () nil)))))
                       (unless (and n (<= 1 n 65535))
                         (usage-error "--port needs a port number, 1 to 65535"))
                       (setf port n)))
                    ((string= a "--host")
                     (unless args (usage-error "--host needs an address"))
                     (setf host (pop args)))
                    ((string= a "--theme")
                     (unless args (usage-error "--theme needs a name"))
                     (setf theme (pop args)))
                    ((string= a "--runtime")
                     (unless args
                       (usage-error "--runtime needs a URL, hosted or none"))
                     (setf runtime (pop args)))
                    ((and (> (length a) 1) (char= (char a 0) #\-))
                     (usage-error (format nil "unknown option ~a" a)))
                    (deck (usage-error "give one deck"))
                    (t (setf deck a)))))
    #-(or (and rontolisp (not rontolisp-wasm)) rontolisp-native sbcl)
    (listen-and-serve nil host port)
    (unless deck (usage-error "serve needs a deck"))
    (when (string= deck "-") (usage-error "serve needs a deck file, not standard input"))
    (let ((served
           (lispresen:make-served deck :theme theme :runtime runtime
                                  :read-octets #'read-octets)))
      (unless served (fail "cannot read ~a" deck))
      (format *error-output* "lispresen: serving ~a at http://~a:~d/ (Ctrl-C stops)~%" deck
              host port)
      (finish-output *error-output*)
      (listen-and-serve (lambda (env) (lispresen:served-respond served env)) host port)
      (uiop:quit 0))))

(defun main (args)
  (when (equal (first args) "new") (new-deck (rest args)))
  (when (equal (first args) "serve") (serve-deck (rest args)))
  (let ((deck nil)
        (out nil)
        (theme nil)
        (runtime nil)
        (embed nil)
        (check nil)
        (strict nil))
    (loop while args
          do
            (let ((a (pop args)))
              (cond ((string= a "-o")
                     (unless args (usage-error "-o needs a file"))
                     (setf out (pop args)))
                    ((string= a "--theme")
                     (unless args (usage-error "--theme needs a name"))
                     (setf theme (pop args)))
                    ((string= a "--runtime")
                     (unless args
                       (usage-error "--runtime needs a URL, hosted or none"))
                     (setf runtime (pop args)))
                    ((string= a "--embed") (setf embed t))
                    ((string= a "--check") (setf check t))
                    ((string= a "--strict") (setf strict t))
                    ((string= a "--help")
                     (write-line *usage*)
                     (uiop:quit 0))
                    ((or (string= a "--version") (string= a "-v"))
                     (write-line (lispresen:version-line))
                     (uiop:quit 0))
                    ((and (> (length a) 1) (char= (char a 0) #\-))
                     (usage-error (format nil "unknown option ~a" a)))
                    (deck (usage-error "give one deck"))
                    (t (setf deck a)))))
    (unless deck (usage-error "no deck given"))
    (let ((source (read-deck deck)))
      (unless source
        (format *error-output* "lispresen: cannot read ~a~%" deck)
        (finish-output *error-output*)
        (uiop:quit 1))
      (multiple-value-bind (html diagnostics image) (lispresen:render source
                                                                      :theme theme
                                                                      :runtime runtime
                                                                      :read-file
                                                                      (file-reader
                                                                       deck)
                                                                      :embed embed
                                                                      :page-image t)
        (let ((name (if (string= deck "-") "<stdin>" deck))
              (errors 0)
              (warnings 0))
          (dolist (d diagnostics)
            (if (eq (lispresen:diagnostic-severity d) :error)
                (incf errors)
                (incf warnings))
            (write-line (lispresen:format-diagnostic name d) *error-output*))
          (finish-output *error-output*)
          (unless check
            (let ((target
                   (or out (if (string= deck "-") "-" (default-output deck)))))
              (write-output html target)
              (when (and image
                         (not (write-octets (second image)
                                            (beside target (first image)))))
                (incf errors))))
          (uiop:quit
           (if (or (> errors 0) (and strict (> warnings 0))) 1 0)))))))

(main (uiop:command-line-arguments))

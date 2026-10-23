;;;; serve.lisp -- what lispresen serve answers: the deck's page, rebuilt when a file it
;;;; was built from changes, and the files beside the deck its page names.
;;;;
;;;; The application is a Clack one (the environment plist in, (status headers body)
;;;; out), so the host serves it with whatever server it has (cli.lisp). It keeps the
;;;; last build and, on each request for the page or for the build's version, compares
;;;; the deck's text and the write dates of the files the build read and the browser was
;;;; served with what they were; a difference rebuilds, or for a served file only bumps
;;;; the version. The page carries a script that asks for the version every half second
;;;; and reloads when it moves -- keeping the slide, since the player keeps it in the URL
;;;; (#N) -- and, after a build with diagnostics, a panel listing them. Both exist only in
;;;; what serve answers: render's output is not touched.

(in-package :lispresen)

(defstruct (served (:constructor %make-served))
  deck            ; the deck's path, as given
  dir             ; its directory, "" or ending in /
  name            ; what diagnostics call it
  theme runtime   ; render's options
  read-octets     ; a function from a path to the file's bytes, or nil
  lock            ; held while a request reads or rebuilds the build
  (source nil)    ; the deck text the build is of
  (read nil)      ; ((path . write date) ...) of the files the build read
  (sent nil)      ; the same of the files the browser was served
  (version 0)     ; moves with every rebuild and every served file that changed
  (html nil)
  (diagnostics nil))

(defmacro with-served ((served) &body body)
  "BODY with SERVED's lock held: the interpreter and the JVM answer each request on a
thread of its own."
  #+rontolisp `(rontolisp:with-mutex ((served-lock ,served)) ,@body)
  #-rontolisp `(progn (served-lock ,served) ,@body))

(defun make-served (deck &key theme runtime read-octets)
  "A server of the deck file DECK, built once, or nil when the deck cannot be read.
READ-OCTETS reads a file's bytes (nil when it cannot)."
  (let* ((slash (position #\/ deck :from-end t))
         (served
          (%make-served :deck deck
                        :dir (if slash (subseq deck 0 (1+ slash)) "")
                        :name deck
                        :theme theme
                        :runtime runtime
                        :read-octets read-octets
                        :lock #+rontolisp (rontolisp:make-mutex) #-rontolisp nil)))
    (when (served-text served)
      (rebuild served)
      served)))

(defun served-path (served src)
  "The file SRC names, relative to the deck's directory unless absolute."
  (if (and (> (length src) 0) (char= (char src 0) #\/))
      src
      (concatenate 'string (served-dir served) src)))

(defun write-date (path)
  (handler-case (file-write-date path) (error () nil)))

(defun served-text (served)
  "The deck's text, or nil while it cannot be read (an editor replacing the file)."
  (let ((octets (funcall (served-read-octets served) (served-deck served))))
    (and octets (utf-8-string octets))))

(defun rebuild (served)
  "Renders the deck again and reports the diagnostics on standard error."
  (let ((text (served-text served))
        (read nil))
    (multiple-value-bind (html diagnostics) (render text
                                                    :theme (served-theme served)
                                                    :runtime (served-runtime served)
                                                    :read-file
                                                    (lambda (src)
                                                      (let ((path
                                                             (served-path served
                                                                          src)))
                                                        (pushnew path read
                                                                 :test #'string=)
                                                        (funcall
                                                         (served-read-octets
                                                          served)
                                                         path))))
      (setf (served-source served) text
            (served-html served) html
            (served-diagnostics served) diagnostics
            (served-read served)
            (mapcar (lambda (path) (cons path (write-date path))) (reverse read)))
      (incf (served-version served))
      (let ((errors (count :error diagnostics :key #'diagnostic-severity)))
        (format *error-output* "lispresen: built ~a: ~d error~:p, ~d warning~:p~%"
                (served-name served) errors (- (length diagnostics) errors)))
      (dolist (d diagnostics)
        (write-line (format-diagnostic (served-name served) d) *error-output*))
      (finish-output *error-output*))))

(defun changed-p (entry)
  (not (eql (write-date (car entry)) (cdr entry))))

(defun refresh (served)
  "Rebuilds when the deck's text or a file the build read changed; moves the version
when a file the browser was served changed, so the page reloads and fetches it again."
  (let ((text (served-text served)))
    (cond ((null text))
          ((or (string/= text (served-source served))
               (find-if #'changed-p (served-read served)))
           (rebuild served))
          (t
           (dolist (entry (served-sent served))
             (when (changed-p entry)
               (setf (cdr entry) (write-date (car entry)))
               (incf (served-version served))
               (format *error-output* "lispresen: ~a changed~%" (car entry))
               (finish-output *error-output*)))))))

;;; --- the page ----------------------------------------------------------------------

(defparameter *reload-script*
  "<script>(function(){var v='VERSION',k='lispresen-serve-slide',L=window.lispresen,want=0,shown;function cur(){return L?L.current()+1:1;}try{want=+sessionStorage.getItem(k)||0;sessionStorage.removeItem(k);}catch(e){}shown=cur();if(!want)want=shown;function poll(){if(cur()!==shown)want=shown=cur();fetch('/__lispresen/version',{cache:'no-store'}).then(function(r){return r.text();}).then(function(t){if(t!==v){try{sessionStorage.setItem(k,want);}catch(e){}history.replaceState(null,'','#'+want);location.reload();}else setTimeout(poll,500);},function(){setTimeout(poll,1000);});}setTimeout(poll,500);})();</script>"
  "Reloads the page once the build's version moves, on the slide the viewer was on: the
player keeps the slide in the URL (#N), but a build that lost slides (an unbalanced
parenthesis swallows the rest of the deck) shows its last one there, so the slide the
viewer chose rides the reload in session storage until they move.")

(defun write-diagnostics-panel (served out)
  "The diagnostics of the build as a panel over the slides; a click hides it."
  (let* ((diagnostics (served-diagnostics served))
         (errors (count :error diagnostics :key #'diagnostic-severity)))
    (format out "<style>#lispresen-serve{position:fixed;left:12px;right:12px;bottom:12px;z-index:2147483647;max-height:40vh;overflow:auto;margin:0;padding:10px 14px;background:#fff;color:#111;border:3px solid ~a;border-radius:8px;font:14px/1.5 Menlo,Consolas,monospace;white-space:pre-wrap;cursor:pointer;box-shadow:0 4px 18px rgba(0,0,0,.3)}@media print{#lispresen-serve{display:none}}</style>"
            (if (> errors 0) "#c62828" "#e08a00"))
    (format out "<pre id=\"lispresen-serve\" role=\"alert\" onclick=\"this.remove()\">")
    (write-escaped
     (format nil "lispresen serve: ~d error~:p, ~d warning~:p (click to hide)" errors
             (- (length diagnostics) errors))
     out)
    (dolist (d diagnostics)
      (terpri out)
      (write-escaped (format-diagnostic (served-name served) d) out))
    (write-string "</pre>" out)))

(defun served-page (served)
  "The page: the build's HTML with the reload script, and the diagnostics panel after a
build that has any, put in before </body>."
  (let* ((html (served-html served))
         (at (or (search "</body>" html :from-end t) (length html)))
         (version (format nil "~d" (served-version served)))
         (script (copy-seq *reload-script*))
         (mark (search "VERSION" script)))
    (with-output-to-string (out)
      (write-string html out :end at)
      (when (served-diagnostics served) (write-diagnostics-panel served out))
      (write-string script out :end mark)
      (write-string version out)
      (write-string script out :start (+ mark 7))
      (write-string html out :start at))))

;;; --- responses ---------------------------------------------------------------------

(defun octets-of (s)
  "The UTF-8 bytes of the string S."
  (let ((n 0))
    (loop for ch across s
          do (let ((c (char-code ch)))
               (incf n (cond ((< c #x80) 1) ((< c #x800) 2) ((< c #x10000) 3) (t 4)))))
    (let ((out (make-array n :element-type '(unsigned-byte 8)))
          (i 0))
      (flet ((put (b)
               (setf (aref out i) b)
               (incf i)))
        (loop for ch across s
              do (let ((c (char-code ch)))
                   (cond ((< c #x80) (put c))
                         ((< c #x800)
                          (put (logior #xC0 (ash c -6)))
                          (put (logior #x80 (logand c #x3F))))
                         ((< c #x10000)
                          (put (logior #xE0 (ash c -12)))
                          (put (logior #x80 (logand (ash c -6) #x3F)))
                          (put (logior #x80 (logand c #x3F))))
                         (t
                          (put (logior #xF0 (ash c -18)))
                          (put (logior #x80 (logand (ash c -12) #x3F)))
                          (put (logior #x80 (logand (ash c -6) #x3F)))
                          (put (logior #x80 (logand c #x3F))))))))
      out)))

(defparameter *content-types*
  '(("html" . "text/html; charset=utf-8") ("css" . "text/css; charset=utf-8")
    ("js" . "text/javascript; charset=utf-8") ("mjs" . "text/javascript; charset=utf-8")
    ("json" . "application/json") ("wasm" . "application/wasm")
    ("png" . "image/png") ("jpg" . "image/jpeg") ("jpeg" . "image/jpeg")
    ("gif" . "image/gif") ("webp" . "image/webp") ("avif" . "image/avif")
    ("svg" . "image/svg+xml") ("ico" . "image/x-icon") ("mp4" . "video/mp4")
    ("webm" . "video/webm") ("ttf" . "font/ttf")
    ("otf" . "font/otf") ("woff" . "font/woff") ("woff2" . "font/woff2")
    ("txt" . "text/plain; charset=utf-8") ("lisp" . "text/plain; charset=utf-8")
    ("clj" . "text/plain; charset=utf-8") ("scm" . "text/plain; charset=utf-8"))
  "Content types by file extension; anything else is application/octet-stream.")

(defun content-type (path)
  (or (cdr (assoc (string-downcase (or (extension path) "")) *content-types*
                  :test #'string=))
      "application/octet-stream"))

(defun respond (status type octets)
  (list status (list :content-type type :cache-control "no-store") octets))

(defun not-found ()
  (respond 404 "text/plain; charset=utf-8" (octets-of (format nil "not found~%"))))

(defun served-file-path (served path-info)
  "The file beside the deck the request path PATH-INFO names, or nil for a path that
leaves the deck's directory."
  (let ((parts nil) (start 1))
    (loop for slash = (position #\/ path-info :start start)
          do (push (subseq path-info start slash) parts)
             (if slash (setf start (1+ slash)) (return)))
    (unless (find-if (lambda (part) (or (string= part "..") (string= part ""))) parts)
      (served-path served (subseq path-info 1)))))

(defun serve-file (served path-info)
  (let* ((path (served-file-path served path-info))
         (octets (and path (funcall (served-read-octets served) path))))
    (if octets
        (progn
          (unless (assoc path (served-sent served) :test #'string=)
            (push (cons path (write-date path)) (served-sent served)))
          (respond 200 (content-type path) octets))
        (not-found))))

(defun served-respond (served env)
  "The response to the Clack request ENV: the page at /, the build's version at
/__lispresen/version, a file beside the deck anywhere else."
  (let ((method (getf env :request-method))
        (path (or (getf env :path-info) "/")))
    (if (not (member method '(:get :head)))
        (respond 405 "text/plain; charset=utf-8" (octets-of (format nil "GET only~%")))
        (with-served (served)
          (cond ((string= path "/")
                 (refresh served)
                 (respond 200 "text/html; charset=utf-8"
                          (octets-of (served-page served))))
                ((string= path "/__lispresen/version")
                 (refresh served)
                 (respond 200 "text/plain; charset=utf-8"
                          (octets-of (format nil "~d" (served-version served)))))
                (t (serve-file served path)))))))

;;;; http-reactor.lisp -- the ONE host-driven-reactor transport, shared by both
;;;; Clack handler backends: the reactor leg of clack-handler-rontolisp
;;;; (#+rontolisp-reactor, i.e. --no-wasi / --no-gc) and the explicit
;;;; clack-handler-reactor backend delegate here, so the two cannot drift and a
;;;; program that mixes them still stores ONE application.
;;;;
;;;; A reactor owns no socket: the host has already parsed the request and
;;;; calls a function -- the synthesized handle-request wasm-export on the WASM
;;;; backends (eval/HttpReactorInliner), %http-reactor-dispatch directly on the
;;;; interpreter and the JVM. There is no re-implementation of the server model
;;;; here: %http-reactor-handle builds the raw tuple and rides the same
;;;; backend-free rontolisp::%http-make-env / %http-normalize-response entry
;;;; points every other transport meets in (http-server.lisp), so it inherits
;;;; the percent-decoding, the "?" split, the header lowercasing and
;;;; comma-joining, the Host split, the content-length parsing and the
;;;; :raw-body shape, and CANNOT drift from what a served request sees. What is
;;;; left is the JSON envelope, and that is all this file is.
;;;;
;;;; The transport takes a request HEAD and a BODY SOURCE, and the head is the
;;;; JSON envelope. The body source is an abstract Lisp value -- one of
;;;;
;;;;   nil       no body,
;;;;   a string  already buffered (what the envelope's own "body" key carries,
;;;;             and what a host that prefers today's shape hands over),
;;;;   a THUNK   arity 0, answering the next chunk (a string), nil or "" for
;;;;             end of stream -- possibly a FUTURE of one, so a suspending
;;;;             host import is a legal pull source.
;;;;
;;;; -- so ONE transport serves every backend: on the interpreter and the JVM
;;;; the host passes a closure or a string directly, and on the WASM backends
;;;; the synthesized export builds the thunk over a host import. The body key
;;;; below is exactly the string case of that source, which is why the
;;;; envelope keeps working unchanged.
;;;;
;;;; The envelope, both directions (the host speaks JSON because the other side
;;;; of a reactor boundary is normally JavaScript):
;;;;
;;;;   in   {"method": "GET",            ; defaults to "GET"
;;;;         "target": "/path?a=1",      ; RAW -- path and query still joined and
;;;;                                     ;   still encoded. %http-make-env owns
;;;;                                     ;   that split; a pre-split path would
;;;;                                     ;   leave :query-string nil.
;;;;         "headers": {"host": "..."}, ; content-length MUST be here for a body:
;;;;                                     ;   lack/request parses nothing without
;;;;                                     ;   it, and a chunked request carries
;;;;                                     ;   none, so the host sets it from the
;;;;                                     ;   bytes it actually read.
;;;;         "body": "...",              ; already read; "" or absent for none.
;;;;                                     ;   The in-band body SOURCE: a host that
;;;;                                     ;   passes one out of band (the second
;;;;                                     ;   dispatch argument) wins over it.
;;;;         "scheme": "https",          ; optional, defaults to "http"
;;;;         "remote-addr": "203.0.113.7"}  ; optional, Clack's :remote-addr
;;;;
;;;;   out  {"status": 200,
;;;;         "headers": [["content-type", "application/json"]],  ; an ARRAY of
;;;;                                     ;   pairs, not an object: a name may
;;;;                                     ;   repeat, and an application that sets
;;;;                                     ;   two cookies answers two Set-Cookie
;;;;                                     ;   headers. The host feeds it to the
;;;;                                     ;   Headers constructor as is.
;;;;         "body": "..."}
;;;;
;;;; :remote-port is always nil (a reactor host exposes no peer port), and
;;;; :server-name / :server-port come from the Host header.
;;;;
;;;; %http-reactor-handle CATCHES: on a reactor an uncaught Lisp error is a trap
;;;; that takes the whole instance down, and the host would have to throw the
;;;; instance away. It answers 500 with the condition's report instead, which is
;;;; what every other rontolisp transport does with a handler error. Consequence:
;;;; a program that loads this library compiles in EH mode on the WASM backends.
;;;;
;;;; Unlike http-server.lisp this library is NOT self-contained: it names
;;;; rontolisp:json-parse / json-stringify / plist-hash-table (the JsonLibrary
;;;; splice picks the call sites up downstream) and http-server.lisp's
;;;; %http-make-env / %http-normalize-response / %http-body-stream (the
;;;; HttpServerLibrary splice runs after this one).

(defvar rontolisp::%http-reactor-app nil)

;; The registered :raw-body mode: T for the buffered Gray stream a CLACK
;; application needs (what both handler backends' run asks for), nil for
;; rontolisp's own asynchronous stream, which is the default of the
;; rontolisp:http-handler directive and therefore of a reactor built from one.
;; A reactor serves through ONE application slot, so one flag describes it --
;; the same "one program, one mode" rule the socket transports get from the
;; directive at compile time.
(defvar rontolisp::%http-reactor-buffered nil)

(defun rontolisp::%http-reactor-register (app &optional raw-body)
  ;; The one application store, shared by both handler backends' run. A
  ;; function rather than a bare setq at the call sites so the interpreter's
  ;; lazy library load triggers on the FIRST touch, whichever entry point that
  ;; is. RAW-BODY is the mode -- :buffered or (the default) :stream.
  (setq rontolisp::%http-reactor-app app)
  (setq rontolisp::%http-reactor-buffered (eq raw-body :buffered))
  nil)

(defun rontolisp::%http-reactor-header-alist (table)
  ;; The headers JSON object -> the ((name . value) ...) alist the raw tuple
  ;; wants. nil (no headers) passes straight through.
  (if (null table)
      nil
      (let ((out nil))
        (maphash (lambda (name value) (setq out (cons (cons name value) out)))
                 table)
        (nreverse out))))

(defun rontolisp::%http-reactor-header-pairs (alist)
  ;; The response header alist -> a JSON array of [name, value]. See the
  ;; envelope note above for why this is not an object. A VECTOR, not a list:
  ;; a response without headers (tiny-routes' (ok "...") builds one) must
  ;; reach the host as [], and json-stringify renders an empty LIST as false,
  ;; which the Headers constructor on the other side rejects.
  (let ((out nil))
    (dolist (pair alist) (setq out (cons (list (car pair) (cdr pair)) out)))
    (coerce (nreverse out) 'vector)))

(defun rontolisp::%http-reactor-pull (thunk)
  ;; One chunk from a pull source, RESOLVED: a suspending host import and an
  ;; async-lambda both answer a settled future here, and a future wrapping nil
  ;; is not nil -- without the resolve such a source could never report EOF.
  ;; The same rule %stream-new applies at the read, applied here because this
  ;; transport is synchronous code where await is not legal.
  (let ((chunk (funcall thunk)))
    (if (rontolisp:futurep chunk) (rontolisp::%future-force chunk) chunk)))

(defun rontolisp::%http-reactor-body-text (source)
  ;; The body source drained to ONE string: a string is already it, and a pull
  ;; thunk is looped until it reports end of stream. What :buffered costs, and
  ;; the only place the whole body has to exist at once.
  (if (stringp source)
      source
      (with-output-to-string (out)
        (let ((chunk (rontolisp::%http-reactor-pull source)))
          (while (and chunk (> (length chunk) 0))
            (write-string chunk out)
            (setq chunk (rontolisp::%http-reactor-pull source)))))))

(defun rontolisp::%http-reactor-body-stream (source)
  ;; The rontolisp-native :raw-body: ONE first-class pull stream whatever the
  ;; source was, so (rontolisp:await (rontolisp:read-all (getf env :raw-body)))
  ;; -- the portable spelling every other backend already serves -- is what a
  ;; handler writes on a reactor too. An already-buffered string becomes the
  ;; stream that answers it once and then EOF; a thunk IS the read side.
  (if (stringp source)
      (let ((sent nil))
        (rontolisp::%stream-new (lambda ()
                                  (if sent
                                      nil
                                      (progn
                                        (setq sent t)
                                        source))) (lambda () nil)))
      (rontolisp::%stream-new source (lambda () nil))))

(defun rontolisp::%http-reactor-raw-body (source buffered)
  ;; The body source -> the :raw-body value the registered mode asks for. nil
  ;; (and an empty in-band body) stays nil in BOTH modes: upstream guards
  ;; :raw-body with (when raw-body ...), and a bodiless GET must not pay for a
  ;; stream it would only find empty.
  (cond ((null source) nil)
        ((and (stringp source) (= (length source) 0)) nil)
        (buffered (rontolisp::%http-body-stream
                   (rontolisp::%http-reactor-body-text source)))
        (t (rontolisp::%http-reactor-body-stream source))))

(defun rontolisp::%http-reactor-request-tuple (req body buffered)
  ;; The positional raw tuple %http-make-env consumes:
  ;;   (method request-uri header-alist body server-protocol url-scheme
  ;;    local-name local-port remote-addr remote-port)
  ;; The Host header supplies :server-name / :server-port, so the two
  ;; placeholders below never win when the host sends one. BODY is the
  ;; out-of-band body source; the envelope's own "body" key is the fallback,
  ;; which is what keeps a host that has not moved yet working unchanged.
  (list (or (gethash "method" req) "GET") (or (gethash "target" req) "/")
   (rontolisp::%http-reactor-header-alist (gethash "headers" req))
   (rontolisp::%http-reactor-raw-body (or body (gethash "body" req)) buffered)
   "HTTP/1.1" (gethash "scheme" req) "localhost" 80 (gethash "remote-addr" req)
   nil))

(defun rontolisp::%http-reactor-envelope (status headers body)
  (rontolisp:json-stringify
   (rontolisp:plist-hash-table
    (list :status status
          :headers (rontolisp::%http-reactor-header-pairs headers)
          :body body))))

(defun rontolisp::%http-reactor-handle
    (app request-json &optional body buffered)
  "Run the Clack application APP against the JSON request head REQUEST-JSON and
the body source BODY and answer the JSON response. BUFFERED selects the
:raw-body shape. See the envelope in this file's header."
  ;; The application's answer may be a FUTURE (an async-defun handler, e.g. one
  ;; awaiting rontolisp:fetch): resolve it at this boundary, the same courtesy
  ;; %http-serve-request extends on the socket transports -- but through the
  ;; %future-force FUNCTION, because this transport is synchronous code where
  ;; the await special form is not legal. On a reactor the future is settled at
  ;; creation, so the force never blocks.
  (handler-case (let* ((req (rontolisp:json-parse request-json))
                       (env
                        (rontolisp::%http-make-env
                         (rontolisp::%http-reactor-request-tuple req body
                                                                 buffered)))
                       (answer (funcall app env))
                       (triple
                        (rontolisp::%http-normalize-response
                         (if (rontolisp:futurep answer)
                             (rontolisp::%future-force answer)
                             answer))))
                  (rontolisp::%http-reactor-envelope (car triple)
                                                     (car (cdr triple))
                                                     (car (cdr (cdr triple)))))
    (error (e)
      (rontolisp::%http-reactor-envelope 500
       (list (cons "content-type" "application/json"))
       (format nil "~a~%"
               (rontolisp:json-stringify
                (rontolisp:plist-hash-table
                 (list :error (format nil "~a" e)))))))))

(defun rontolisp::%http-reactor-dispatch (request-json &optional body)
  "Run the application the handler backend stored against the JSON request head
REQUEST-JSON and the optional body source BODY and answer the JSON response.
The host's entry point: on the WASM backends the synthesized wasm-export calls
this, on every other backend the host calls it directly. BODY is nil, a string
or a pull thunk (see this file's header); a host that leaves it out keeps the
envelope's own \"body\" key."
  (rontolisp::%http-reactor-handle rontolisp::%http-reactor-app request-json
                                   body rontolisp::%http-reactor-buffered))

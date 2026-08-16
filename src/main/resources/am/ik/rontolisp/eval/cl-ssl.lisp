;; The cl+ssl package: a CLIENT-side shim satisfying the built-in ASDF system
;; "cl+ssl". The real library is a CFFI binding to OpenSSL and cannot load
;; here (its cffi dependency's .asd errors with "Sorry, this Lisp is not yet
;; supported"); every CL HTTP client (dexador, drakma, and any other
;; usocket+cl+ssl stack) reaches TLS through it, so without the shim https://
;; is dead on all of them. Written in canonical shape; the package is seeded
;; in PackageRegistry.
;;
;; The substrate is rontolisp:tls-upgrade, which wraps an ALREADY-CONNECTED
;; stream handle in TLS as a client -- the shape make-ssl-client-stream has
;; (a client connects, possibly issues a proxy CONNECT, then upgrades) and
;; tls-connect cannot express. Interpreter/JVM only, like the rest of the TLS
;; family: on the WASM backends tls-upgrade is a compile error
;; (.kb/tcp-sockets.md).
;;
;; What has no backing SIGNALS instead of being accepted and ignored:
;; - client certificates (:key/:certificate/:password on
;;   make-ssl-client-stream, and use-certificate-chain-file) -- silently
;;   connecting UNAUTHENTICATED where the caller configured a client identity
;;   is worse than a message;
;; - a :verify-location CA path on make-context -- the JDK trust-store knob
;;   (the javax.net.ssl.trustStore system properties, re-read per connection
;;   by the primitive) is the supported spelling of "trust these CAs".

;; The global context installed by with-global-context, consulted by
;; ssl-check-verify-p (and through it by make-ssl-client-stream's :verify
;; default). Internal, as upstream.
(defparameter cl+ssl::*ssl-global-context* nil)

;; OpenSSL's SSL_VERIFY_NONE / SSL_VERIFY_PEER values, as upstream exports
;; them; only their identity matters here (make-context records the mode and
;; ssl-check-verify-p compares against +ssl-verify-none+).
(defconstant cl+ssl:+ssl-verify-none+ 0)

(defconstant cl+ssl:+ssl-verify-peer+ 1)

;; Upstream loads libssl and seeds its RNG here; the JDK TLS stack behind
;; tls-upgrade needs neither.
(defun cl+ssl:ensure-initialized (&rest args)
  (declare (ignore args))
  t)

;; A context is just its recorded :verify-mode -- the one thing
;; make-ssl-client-stream's default consults. :verify-location names a CA
;; file/directory; only the "use the defaults" spellings are accepted (see
;; the header).
(defun cl+ssl:make-context (&key (verify-mode cl+ssl:+ssl-verify-peer+)
                                 (verify-location :default) &allow-other-keys)
  (unless (or (null verify-location) (eq verify-location :default))
    (error
     "cl+ssl:make-context: :verify-location has no backing here; point the javax.net.ssl.trustStore system properties at your trust store instead (they are re-read on every connection)"))
  (list :verify-mode verify-mode))

(defmacro cl+ssl:with-global-context ((context &rest options) &body body)
  (declare (ignore options))
  `(let ((cl+ssl::*ssl-global-context* ,context)) ,@body))

;; Whether a new client stream verifies by default: the global context's
;; verify mode when one is installed, verifying otherwise (upstream's
;; conservative default).
(defun cl+ssl:ssl-check-verify-p ()
  (let ((ctx cl+ssl::*ssl-global-context*))
    (if ctx (not (eql (getf ctx :verify-mode) cl+ssl:+ssl-verify-none+)) t)))

;; A client certificate chain (dexador reaches this for a .pem
;; :ssl-cert-file).
(defun cl+ssl:use-certificate-chain-file (path)
  (declare (ignore path))
  (error
   "cl+ssl:use-certificate-chain-file: client certificates are not supported (rontolisp:tls-upgrade has no client-identity support)"))

;; The one entry point that does the work: upgrades the given
;; already-connected stream handle to TLS against :hostname. :verify defaults
;; from the global context exactly like upstream; nil skips certificate and
;; hostname verification (tls-upgrade's :insecure), any other value verifies.
(defun cl+ssl:make-ssl-client-stream (stream &key hostname
                                      (verify (cl+ssl:ssl-check-verify-p)) key
                                      certificate password &allow-other-keys)
  (when (or key certificate password)
    (error
     "cl+ssl:make-ssl-client-stream: client certificates (:key/:certificate/:password) are not supported (rontolisp:tls-upgrade has no client-identity support)"))
  (unless hostname
    (error
     "cl+ssl:make-ssl-client-stream: :hostname is required (the name the server certificate is verified against, and the SNI sent)"))
  (rontolisp:tls-upgrade stream hostname :insecure (if verify nil t)))

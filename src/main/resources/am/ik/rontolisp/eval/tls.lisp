;;;; tls.lisp -- rontolisp:tls-connect / rontolisp:tls-upgrade over wit-imported
;;;; wasi:tls@0.3.0-draft (CLIENT-side TLS only; the proposal defines no server
;;;; interface, so the tls-listen family stays off every WASM target).
;;;;
;;;; This is the --component implementation (spliced by eval/TlsLibrary when a
;;;; --component program references rontolisp:tls-connect or rontolisp:tls-upgrade;
;;;; the cl+ssl shim's make-ssl-client-stream is such a reference). The interpreter
;;;; and the JVM keep their SSLSocket implementations; Preview 1 keeps the compile
;;;; error (no wasi:tls host API there).
;;;;
;;;; The whole of it is ordinary Lisp over the WIT bindings and sockets.lisp's
;;;; entry table (TlsLibrary always splices after SocketsLibrary's trigger has
;;;; fired -- this file references rontolisp:tcp-connect and the %sock package, so
;;;; the sockets splice sees them). Mechanics, following wasmtime's own
;;;; p3_tls_sample_application:
;;;;   - connector.receive takes the socket's IN-FLIGHT recv stream (transferring
;;;;     it to the host) and answers the cleartext read stream;
;;;;   - connector.send takes the read end of a fresh cleartext pair and answers
;;;;     the ciphertext stream, which goes STRAIGHT into tcp-socket.send -- no
;;;;     guest-side pump. That wiring is only possible while tcp-socket.send has
;;;;     not been called yet (it may be called at most once), which is why
;;;;     sockets.lisp defers the send half of its plumbing to the first write:
;;;;     tls-upgrade requires a handle that has not been written to.
;;;;   - connector.connect (static async, CONSUMING the connector) performs the
;;;;     handshake; its error arm re-signals rontolisp:wit-error at the await.
;;;;   - on success the socket ENTRY is swapped onto the cleartext ends IN PLACE,
;;;;     so tls-upgrade answers the SAME fd it was given (the component
;;;;     divergence from the interpreter/JVM's new-handle convention) and every
;;;;     stream built-in keeps working on it unchanged.
;;;;
;;;; What the draft interface does not expose, and this file therefore SIGNALS
;;;; for instead of silently ignoring: :insecure (skip verification -- a program
;;;; that asks not to verify certificates must not verify them, or the reverse),
;;;; client certificates and ALPN. Verification runs against the host's own
;;;; trust anchors (wasmtime's rustls provider compiles in the Mozilla webpki
;;;; roots; there is no CA-file knob).

;; The WIT interfaces are lowered by TlsLibrary (which calls
;; WitImportDirective.lower itself), so these directives never reach
;; WitImportInliner -- but they are written here, in tls.lisp's own source, so the
;; file reads as the program it is. The types interface is imported for its
;; `error` RESOURCE (the err arm of every connector result); the only thing done
;; with one is releasing it.
(rontolisp:wit-import "tls.wit"
                      :interface "wasi:tls/types@0.3.0-draft"
                      :package %tls-err)

(rontolisp:wit-import "tls.wit"
                      :interface "wasi:tls/client@0.3.0-draft"
                      :package %tls)

(rontolisp:async-defun rontolisp::%tls-upgrade-f (fd host)
  ;; Failure (handshake rejection, an entry that is not a connected socket, a
  ;; handle already written to) yields nil, the WASM error convention --
  ;; matching the tcp family. The transform setup order is the sample
  ;; application's: receive, send, then the handshake.
  (let ((e (rontolisp::%sock-entry fd)))
    (if (and e (= (rontolisp::%sock-e-kind e) 1)
             (null (rontolisp::%sock-e-tx e)))
        (handler-case (let* ((conn (%tls:connector-new))
                             (rp
                              (%tls:connector-receive conn
                               (rontolisp::%sock-e-recv e)))
                             (sp (%sock:sock-stream-new))
                             (tp (%tls:connector-send conn (car sp))))
                        (%tls:tls-future-drop-readable (car (cdr rp)))
                        (%tls:tls-future-drop-readable (car (cdr tp)))
                        (%sock:sock-future-drop-readable
                         (%sock:tcp-socket-send (rontolisp::%sock-e-sock e)
                                                (car tp)))
                        ;; Swap the entry onto the cleartext ends BEFORE the handshake:
                        ;; either way the entry then owns handles we hold, so a close
                        ;; after a failed handshake drops live ends, never a transferred
                        ;; one.
                        (rontolisp::%sock-set-streams e (car rp) (cdr sp))
                        (rontolisp:await (%tls:connector-connect conn host))
                        fd)
          (rontolisp:wit-error (c)
            ;; The handshake's err arm carries an own<error> RESOURCE handle
            ;; (an integer) -- release it. The plumbing calls above signal no
            ;; wit-error of their own (none returns a result envelope).
            (let ((payload (rontolisp:wit-error-payload c)))
              (if (integerp payload) (%tls-err:error-drop payload) nil)
              nil)))
        nil)))

;;; The option check is shared and RUN-TIME (the value is a runtime
;;; expression, so a compile-time gate could only see literals): the draft
;;; interface has no verification knob, and silently verifying where the
;;; caller asked not to (or the reverse) is worse than a message. The cl+ssl
;;; shim's verify path passes a literal :insecure with value nil, which is the
;;; verified default and passes through.
(defun rontolisp::%tls-check-insecure (who opt value)
  (when opt
    (if (eq opt :insecure)
        (when value
          (error
           (concatenate 'string who
                        ": :insecure is not supported on the WASM component backend (wasi:tls@0.3.0-draft exposes no way to skip certificate verification)")))
        (error (concatenate 'string who ": unknown option (only :insecure)"))))
  nil)

(defun rontolisp:tls-upgrade (stream host &optional opt value)
  (rontolisp::%tls-check-insecure "rontolisp:tls-upgrade" opt value)
  (rontolisp::%future-force (rontolisp::%tls-upgrade-f stream host)))

(defun rontolisp:tls-connect (host port &optional opt value)
  ;; tcp-connect + tls-upgrade, exactly: on this backend the upgrade IS the
  ;; natural primitive (connector.send/receive transform the socket's own
  ;; streams), so tls-connect is the derived one -- the mirror image of the
  ;; JVM, where the handshaken connect is primitive and the upgrade derived.
  ;; `host` doubles as the server name the certificate is verified against, so
  ;; like tcp-connect it must be an IPv4 literal (or localhost) TODAY -- for a
  ;; real-world host, tcp-connect to its address and tls-upgrade with the DNS
  ;; name.
  (rontolisp::%tls-check-insecure "rontolisp:tls-connect" opt value)
  (let ((fd (rontolisp:tcp-connect host port)))
    (if fd
        (let ((up (rontolisp:tls-upgrade fd host)))
          (if up
              up
              (progn
                (close fd)
                nil)))
        nil)))

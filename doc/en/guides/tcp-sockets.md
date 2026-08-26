# TCP Sockets

The `rontolisp` package provides four functions for plain TCP networking,
plus encrypted variants for both sides (`tls-connect` and `tls-listen`). They
are **not part of Common Lisp**; reference them with the `rontolisp:`
qualifier (see [Packages](../reference/packages.md)). A connected socket is a
**bidirectional stream handle** in the same handle space as file streams, so
the standard stream functions work on it directly: `read-line`, `write-line`,
`write-string`, `write-char`, `read-char`, `read-byte`, `write-byte` and
`close`. Unlike buffered file
output, socket
writes are sent immediately (`write-line` flushes per line), and `read-line`
returns `nil` once the peer has closed the connection. A socket carries BYTES:
`write-string` puts the string's UTF-8 bytes on the wire and `read-char` reads
one character back out of them, so `read-byte` and `read-char` can be mixed on
the same handle. At end of stream the reads follow their own Common Lisp
defaults: `read-char` and `read-byte` signal `end-of-file` unless you pass the
eof arguments — `(read-char sock nil :eof)` yields `:eof` — while `read-line`
answers `nil`, as it does on a file. The printing functions (`print`, `princ`, `format`) do not
take a socket; render with `(format nil ...)` and send the result with
`write-line` or `write-string`.

| Function | Purpose |
|----------|---------|
| [`rontolisp:tcp-connect`](../reference/functions/rontolisp-tcp-connect.md) | Open a client connection: `(rontolisp:tcp-connect host port)` |
| [`rontolisp:tcp-listen`](../reference/functions/rontolisp-tcp-listen.md) | Bind a listening socket: `(rontolisp:tcp-listen port &optional host)` |
| [`rontolisp:tcp-accept`](../reference/functions/rontolisp-tcp-accept.md) | Wait for a client connection: `(rontolisp:tcp-accept listener)` |
| [`rontolisp:tcp-local-port`](../reference/functions/rontolisp-tcp-local-port.md) | Read the bound port back (useful after listening on port `0`) |
| [`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md) | Open an **encrypted** client connection: `(rontolisp:tls-connect host port)` |
| [`rontolisp:tls-upgrade`](../reference/functions/rontolisp-tls-upgrade.md) | Wrap an **already-connected** stream handle in TLS as a client: `(rontolisp:tls-upgrade stream host)` |
| [`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md) | Bind an **encrypted** listening socket from a PKCS12 keystore: `(rontolisp:tls-listen keystore password port &optional host)` |
| [`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md) | Bind an **encrypted** listening socket from PEM files: `(rontolisp:tls-listen-pem cert-file key-file port &optional host)` |

> **Backend support.** The interpreter and JVM-compiled classes use the JDK
> socket classes and accept hostnames or IP literals. The WASM backend is
> **component-only** (`--component`, over `wasi:sockets@0.3.0`): the tcp
> functions compile in Preview 1 (core-module) mode but raise call-time
> errors, hosts must be
> IPv4 literals, and the component must run with `-S tcp=y
> -S inherit-network=y` (a tcp component always
> compiles in exception-handling mode). Combining the tcp functions with
> [`rontolisp:http-handler`](http-handler.md) compiles into one component
> and runs under `wasmtime serve` — add `-S cli=y` to the flags above
> (without it the serve linker reports the `wasi:sockets@0.3.0`
> `tcp-socket` resource as missing at instantiation). wasmCloud's
> `wash dev` (2.5.2) hosts that component too and provides
> `wasi:sockets` 0.3, with one difference: a loopback destination names a
> per-workload virtual network, not the machine's real 127.0.0.1 — a
> connect to a loopback address only reaches a listener inside the same
> wasmCloud workload (such as a service component bound there), while
> non-loopback addresses go out over the real network. In the **browser
> playground** every tcp function signals an error (the browser sandbox has no
> raw TCP), so the runnable example below only works outside the browser. See
> the [tcp-connect](../reference/functions/rontolisp-tcp-connect.md) reference
> page for the shared limitations (TCP only, no UDP). The TLS *client*
> functions
> ([`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md)
> and
> [`rontolisp:tls-upgrade`](../reference/functions/rontolisp-tls-upgrade.md))
> run on the interpreter, the JVM and the WASM `--component` backend (add
> `-S tls=y` to the flags above); the TLS *server* functions
> ([`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md)
> and
> [`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md))
> are interpreter/JVM only — the `wasi:tls` proposal defines no server
> interface, so they are a permanent compile error on every WASM target.

The programs in this guide are complete and self-contained: copy each one into
a file and run it with any backend. They use only the `rontolisp:tcp-*`
primitives; the [usocket-compatible shim](#the-usocket-compatible-shim) at the
end shows how the same programs look through the portability API that existing
Common Lisp code expects.

## A first round trip

The snippet below is self-contained: it listens on an ephemeral port, connects
to itself over the loopback interface, and echoes one line back through the
accepted handle:

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (sock (rontolisp:tcp-connect "127.0.0.1" port)))
  (write-line "ping" sock)
  (let* ((peer (rontolisp:tcp-accept listener))
         (line (read-line peer)))
    (write-line line peer)
    (let ((reply (read-line sock)))
      (close peer)
      (close sock)
      (close listener)
      reply)))   ; => "ping"
```

## An echo server

A real server binds a fixed port and serves connections in an accept loop.
Save the following as `echo-server.lisp`. Each accepted handle is read line by
line until `read-line` returns `nil` (the client closed), and every line is
written straight back:

```console
(let ((listener (rontolisp:tcp-listen 7777)))
  (if listener
      (progn
        (write-line "echo server listening on 127.0.0.1:7777")
        (do ((n 1 (+ n 1))) (nil)
          (let ((sock (rontolisp:tcp-accept listener)))
            (write-line (format nil "client ~a connected" n))
            (do ((line (read-line sock) (read-line sock)))
                ((null line) (close sock) (write-line "client disconnected"))
              (write-line line sock)))))
      (write-line "tcp-listen failed (is port 7777 already in use?)")))
```

The `(if listener ...)` check matters on the WASM component backend, where a
failed bind returns `nil` instead of signaling an error (the interpreter and
JVM signal). The server loops forever — stop it with `Ctrl-C`.

### Running it

On the interpreter:

```bash
rontolisp echo-server.lisp
```

Compiled to a JVM class (the class is named after the output file):

```bash
rontolisp echo-server.lisp -o EchoServer.class
java EchoServer
```

Compiled to a WASM component (wasmtime 46+; note the two `-S` flags that grant
network access — without them the component still starts, but `tcp-listen`
returns `nil`):

```bash
rontolisp echo-server.lisp -o echo-server.wasm --component
wasmtime run -S tcp=y -S inherit-network=y echo-server.wasm
```

Whichever backend serves, talk to it with any TCP client, for example
`nc` (netcat):

```console
$ nc 127.0.0.1 7777
hello
hello
world
world
```

## An echo client

The matching client connects to the server, sends every line read from
standard input, and prints each reply until stdin ends. Save it as
`echo-client.lisp`:

```console
(let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
  (if sock
      (do ((line (read-line) (read-line)))
          ((null line) (close sock))
        (write-line line sock)
        (write-line (read-line sock)))
      (write-line "cannot connect to 127.0.0.1:7777 (is echo-server.lisp running?)")))
```

Start `echo-server.lisp` first (any backend), then pipe input to the client —
the server and the client can each run on a *different* backend:

```bash
echo hello | rontolisp echo-client.lisp
```

## An HTTP server

Because a socket handle is a line stream and `read-line` strips one trailing
carriage return, HTTP's CRLF-terminated request line and headers read as plain
lines (the blank line ending the headers reads as `""`); response header lines
get their carriage return back via `code-char 13` before `write-line` appends
the newline. That is enough to answer `curl` and browsers. Save the following
as `http-hello.lisp` — it serves a small HTML page showing the request line and
a running request counter, one connection per request:

```console
;; Appends the carriage return of an HTTP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; Consumes the request headers up to the blank line that ends them.
(defun drain-headers (sock)
  (do ((line (read-line sock) (read-line sock)))
      ((or (null line) (string= line "")))))

(let ((listener (rontolisp:tcp-listen 8080)))
  (if listener
      (progn
        (write-line "http server listening on http://127.0.0.1:8080/")
        (do ((n 1 (+ n 1))) (nil)
          (let* ((sock (rontolisp:tcp-accept listener))
                 (request (read-line sock)))
            (if request
                (let ((body (format nil "<h1>hello from rontolisp</h1><p>request ~a: ~a</p>" n request)))
                  (drain-headers sock)
                  (write-line (crlf "HTTP/1.1 200 OK") sock)
                  (write-line (crlf "Content-Type: text/html") sock)
                  ;; + 1: write-line terminates the body with a newline
                  (write-line (crlf (format nil "Content-Length: ~a" (+ (length body) 1))) sock)
                  (write-line (crlf "Connection: close") sock)
                  (write-line (crlf "") sock)
                  (write-line body sock)
                  (write-line (format nil "served request ~a: ~a" n request))))
            (close sock))))
      (write-line "tcp-listen failed (is port 8080 already in use?)")))
```

Run it on any backend and open <http://127.0.0.1:8080/> in a browser or with
`curl http://127.0.0.1:8080/`.

> For real HTTP work there is no need to hand-roll the protocol over a socket:
> the *client* side is `rontolisp:fetch` (see the
> [HTTP Requests guide](http-fetch.md)), and the *server* side
> `rontolisp:http-handler` parses requests and adapts responses for you (see
> the [Serving HTTP guide](http-handler.md)). The hand-rolled server above is
> here to show the socket primitives, not as the recommended way to serve HTTP.

## A miniature Redis server

A larger example: an in-memory key-value server that speaks enough of RESP2
(the Redis serialization protocol) that the real `redis-cli` connects and
works, and — like real Redis — also accepts "inline commands" (a plain
space-separated line), so `telnet 127.0.0.1 6379` or `nc 127.0.0.1 6379` work
too. Both framings arrive as CRLF-terminated lines, which `read-line` reads as
plain lines. The store is a hash table with string keys that survives across
connections. It supports (case-insensitive) `PING`, `SET`, `GET`, `DEL`,
`EXISTS`, `INCR`, `KEYS`, `DBSIZE` and `QUIT`. Save it as `kv-server.lisp`:

```console
;; --- small string helpers ---------------------------------------------------

;; Appends the carriage return of a RESP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; "SET key value" -> ("SET" "key" "value")
(defun split-words (s)
  (cond ((string= s "") nil)
        (t (let ((p (position #\space s)))
             (if p
                 (cons (subseq s 0 p) (split-words (subseq s (+ p 1))))
                 (list s))))))

;; ("hello" "world") -> "hello world"
(defun join-words (ws)
  (cond ((null ws) "")
        ((null (cdr ws)) (car ws))
        (t (concatenate 'string (car ws) " " (join-words (cdr ws))))))

;; t when s is a non-empty run of decimal digits (with an optional leading -).
(defun integer-string-p (s)
  (let* ((n (length s))
         (start (if (and (> n 0) (char= (char s 0) #\-)) 1 0)))
    (and (> n start)
         (do ((i start (+ i 1)))
             ((or (>= i n) (not (digit-char-p (char s i))))
              (>= i n))))))

;; --- RESP replies -----------------------------------------------------------

(defun reply-simple (s sock)
  (write-line (crlf (concatenate 'string "+" s)) sock))

(defun reply-error (s sock)
  (write-line (crlf (concatenate 'string "-ERR " s)) sock))

(defun reply-int (n sock)
  (write-line (crlf (format nil ":~a" n)) sock))

(defun reply-bulk (s sock)
  (if s
      (progn
        (write-line (crlf (format nil "$~a" (length s))) sock)
        (write-line (crlf s) sock))
      (write-line (crlf "$-1") sock)))

(defun reply-array-header (n sock)
  (write-line (crlf (format nil "*~a" n)) sock))

;; --- request framing --------------------------------------------------------

;; Reads one RESP bulk-string element: the "$<len>" header line, then the
;; payload line (the payload must not contain a newline).
(defun read-bulk (sock)
  (let ((header (read-line sock)))
    (if header (read-line sock) nil)))

(defun read-resp-array (count sock acc)
  (if (<= count 0)
      (reverse acc)
      (let ((arg (read-bulk sock)))
        (if arg
            (read-resp-array (- count 1) sock (cons arg acc))
            nil))))

;; Reads one command as a list of argument strings: a "*<n>" line starts a
;; RESP2 array (what redis-cli sends); anything else is an inline command
;; (what telnet/nc users type). nil at connection close.
(defun read-command (sock)
  (let ((line (read-line sock)))
    (cond ((null line) nil)
          ((string= line "") (read-command sock))
          ((char= (char line 0) #\*)
           (let ((count (subseq line 1)))
             (if (integer-string-p count)
                 (read-resp-array (parse-integer count) sock nil)
                 (list "!bad-frame"))))
          (t (split-words line)))))

;; --- commands ---------------------------------------------------------------

;; Handles one command; returns nil after QUIT (closing the session).
(defun handle-command (args store sock)
  (let ((cmd (string-upcase (car args)))
        (key (cadr args)))
    (cond ((string= cmd "PING")
           (if key (reply-bulk key sock) (reply-simple "PONG" sock))
           t)
          ((string= cmd "SET")
           (if (and key (cddr args))
               (progn
                 (setf (gethash key store) (join-words (cddr args)))
                 (reply-simple "OK" sock))
               (reply-error "wrong number of arguments for 'set' command" sock))
           t)
          ((string= cmd "GET")
           (if key
               (reply-bulk (gethash key store) sock)
               (reply-error "wrong number of arguments for 'get' command" sock))
           t)
          ((string= cmd "DEL")
           (let ((removed 0))
             (dolist (k (cdr args))
               (when (gethash k store)
                 (remhash k store)
                 (incf removed)))
             (reply-int removed sock))
           t)
          ((string= cmd "EXISTS")
           (reply-int (if (and key (gethash key store)) 1 0) sock)
           t)
          ((string= cmd "INCR")
           (let ((current (if key (or (gethash key store) "0") "0")))
             (cond ((null key)
                    (reply-error "wrong number of arguments for 'incr' command" sock))
                   ((integer-string-p current)
                    (let ((n (+ (parse-integer current) 1)))
                      (setf (gethash key store) (format nil "~a" n))
                      (reply-int n sock)))
                   (t (reply-error "value is not an integer or out of range" sock))))
           t)
          ((string= cmd "KEYS")
           (let ((pattern (or key "*"))
                 (keys nil))
             (maphash (lambda (k v)
                        (if (or (string= pattern "*") (string= pattern k))
                            (push k keys)))
                      store)
             (reply-array-header (length keys) sock)
             (dolist (k keys)
               (reply-bulk k sock)))
           t)
          ((string= cmd "DBSIZE")
           (reply-int (hash-table-count store) sock)
           t)
          ((string= cmd "COMMAND")
           ;; redis-cli asks COMMAND DOCS on connect; an empty array satisfies it.
           (reply-array-header 0 sock)
           t)
          ((string= cmd "QUIT")
           (reply-simple "OK" sock)
           nil)
          (t (reply-error (format nil "unknown command '~a'" (car args)) sock)
             t))))

;; --- server loop ------------------------------------------------------------

(let ((store (make-hash-table))
      (listener (rontolisp:tcp-listen 6379)))
  (if listener
      (progn
        (write-line "mini-redis listening on 127.0.0.1:6379 (try: redis-cli -p 6379 ping)")
        (do ((n 1 (+ n 1))) (nil)
          (let ((sock (rontolisp:tcp-accept listener)))
            (do ((args (read-command sock) (read-command sock)))
                ((or (null args) (not (handle-command args store sock)))
                 (close sock))))))
      (write-line "tcp-listen failed (is port 6379 already in use? a real redis, perhaps)")))
```

Run it on any backend, then talk to it with the real `redis-cli`:

```bash
redis-cli -p 6379 set greeting hello
redis-cli -p 6379 get greeting
redis-cli -p 6379 incr counter
```

## TLS connections

[`rontolisp:tls-connect`](../reference/functions/rontolisp-tls-connect.md) is
the encrypted counterpart of `tcp-connect`: it performs a TLS handshake after
connecting and returns the same kind of stream handle, so `read-line`,
`write-line`, `read-byte`, `write-byte` and `close` work unchanged. The server
certificate is validated against the JDK default trust store and the hostname
is verified; point the `javax.net.ssl.trustStore` system properties at your
own trust store to accept self-signed certificates, or pass `:insecure t` to
skip verification entirely (development only). See the reference page for
details and an HTTPS-by-hand example:

```console
(let ((sock (rontolisp:tls-connect "example.com" 443)))
  ...  ; speak any TLS-wrapped protocol over the handle
  (close sock))
```

To start TLS **over a connection you already opened** — the shape an HTTP
client library needs, since it connects (and possibly issues a proxy `CONNECT`)
before starting TLS — use
[`rontolisp:tls-upgrade`](../reference/functions/rontolisp-tls-upgrade.md): it
takes an existing socket handle plus the server name to verify against and
returns a new handle over the same connection. The bundled
[`cl+ssl` shim system](asdf-systems.md#built-in-shim-systems) rides it, which
is what gives `usocket`+`cl+ssl` client libraries their `https://` path.

The *server* side is
[`rontolisp:tls-listen`](../reference/functions/rontolisp-tls-listen.md): it
takes a PKCS12 keystore file and returns a listener that the plain
`rontolisp:tcp-accept` / `rontolisp:tcp-local-port` / `close` work on; each
accepted connection completes its handshake on the first read. To serve
straight from PEM files (certbot / OpenSSL output) instead of a PKCS12
keystore, use
[`rontolisp:tls-listen-pem`](../reference/functions/rontolisp-tls-listen-pem.md).
The TLS *client* functions (`tls-connect` / `tls-upgrade`) also run on the
WASM `--component` backend, over wasmtime's experimental
`wasi:tls@0.3.0-draft` interface — add `-S tls=y` to the socket run flags;
failures answer `nil` there, `:insecure` signals (the draft exposes no
verification knob), and `tls-upgrade` upgrades the handle **in place** (it
answers the same handle, and requires that nothing was written to it yet). The
TLS *server* functions are interpreter/JVM only — a permanent compile error on
every WASM target, because the `wasi:tls` proposal defines no server
interface.

Both server programs below need a PKCS12 keystore holding the server key and
certificate. Generate a self-signed one for localhost with the JDK `keytool`
(or export one from OpenSSL with `openssl pkcs12 -export`):

```bash
keytool -genkeypair -alias rontolisp-tls -keyalg EC -dname CN=localhost \
  -validity 365 -ext SAN=ip:127.0.0.1,dns:localhost \
  -storetype PKCS12 -keystore tls-server.p12 \
  -storepass changeit -keypass changeit
```

### An HTTPS server

This is the TLS twin of the HTTP server above: identical once the listener
exists, because a `tls-listen` listener hands `tcp-accept` the same kind of
stream handle. `tls-listen` never returns `nil` — a missing keystore, a wrong
password or a busy port signals an error instead — so there is no `nil` check.
Save it as `https-hello.lisp`:

```console
;; Appends the carriage return of an HTTP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; Consumes the request headers up to the blank line that ends them.
(defun drain-headers (sock)
  (do ((line (read-line sock) (read-line sock)))
      ((or (null line) (string= line "")))))

(let ((listener (rontolisp:tls-listen "tls-server.p12" "changeit" 8443)))
  (write-line "https server listening on https://127.0.0.1:8443/")
  (do ((n 1 (+ n 1))) (nil)
    (let* ((sock (rontolisp:tcp-accept listener))
           (request (read-line sock)))
      (if request
          (let ((body (format nil "<h1>hello from rontolisp over TLS</h1><p>request ~a: ~a</p>" n request)))
            (drain-headers sock)
            (write-line (crlf "HTTP/1.1 200 OK") sock)
            (write-line (crlf "Content-Type: text/html") sock)
            ;; + 1: write-line terminates the body with a newline
            (write-line (crlf (format nil "Content-Length: ~a" (+ (length body) 1))) sock)
            (write-line (crlf "Connection: close") sock)
            (write-line (crlf "") sock)
            (write-line body sock)
            (write-line (format nil "served request ~a: ~a" n request))))
      (close sock))))
```

Run it on the interpreter or the JVM, then (using `-k` because the certificate
is self-signed):

```bash
curl -k https://127.0.0.1:8443/
```

### A TLS Redis server

The same is true of the key-value server: swap `tcp-listen` for `tls-listen`
and everything else is unchanged. This serves the RESP2 protocol over TLS on
port 6380 (like a real Redis with `--tls-port`). Save it as
`kv-server-tls.lisp`:

```console
;; --- small string helpers ---------------------------------------------------

;; Appends the carriage return of a RESP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; "SET key value" -> ("SET" "key" "value")
(defun split-words (s)
  (cond ((string= s "") nil)
        (t (let ((p (position #\space s)))
             (if p
                 (cons (subseq s 0 p) (split-words (subseq s (+ p 1))))
                 (list s))))))

;; ("hello" "world") -> "hello world"
(defun join-words (ws)
  (cond ((null ws) "")
        ((null (cdr ws)) (car ws))
        (t (concatenate 'string (car ws) " " (join-words (cdr ws))))))

;; t when s is a non-empty run of decimal digits (with an optional leading -).
(defun integer-string-p (s)
  (let* ((n (length s))
         (start (if (and (> n 0) (char= (char s 0) #\-)) 1 0)))
    (and (> n start)
         (do ((i start (+ i 1)))
             ((or (>= i n) (not (digit-char-p (char s i))))
              (>= i n))))))

;; --- RESP replies -----------------------------------------------------------

(defun reply-simple (s sock)
  (write-line (crlf (concatenate 'string "+" s)) sock))

(defun reply-error (s sock)
  (write-line (crlf (concatenate 'string "-ERR " s)) sock))

(defun reply-int (n sock)
  (write-line (crlf (format nil ":~a" n)) sock))

(defun reply-bulk (s sock)
  (if s
      (progn
        (write-line (crlf (format nil "$~a" (length s))) sock)
        (write-line (crlf s) sock))
      (write-line (crlf "$-1") sock)))

(defun reply-array-header (n sock)
  (write-line (crlf (format nil "*~a" n)) sock))

;; --- request framing --------------------------------------------------------

;; Reads one RESP bulk-string element: the "$<len>" header line, then the
;; payload line (the payload must not contain a newline).
(defun read-bulk (sock)
  (let ((header (read-line sock)))
    (if header (read-line sock) nil)))

(defun read-resp-array (count sock acc)
  (if (<= count 0)
      (reverse acc)
      (let ((arg (read-bulk sock)))
        (if arg
            (read-resp-array (- count 1) sock (cons arg acc))
            nil))))

;; Reads one command as a list of argument strings: a "*<n>" line starts a
;; RESP2 array (what redis-cli sends); anything else is an inline command
;; (what telnet/nc users type). nil at connection close.
(defun read-command (sock)
  (let ((line (read-line sock)))
    (cond ((null line) nil)
          ((string= line "") (read-command sock))
          ((char= (char line 0) #\*)
           (let ((count (subseq line 1)))
             (if (integer-string-p count)
                 (read-resp-array (parse-integer count) sock nil)
                 (list "!bad-frame"))))
          (t (split-words line)))))

;; --- commands ---------------------------------------------------------------

;; Handles one command; returns nil after QUIT (closing the session).
(defun handle-command (args store sock)
  (let ((cmd (string-upcase (car args)))
        (key (cadr args)))
    (cond ((string= cmd "PING")
           (if key (reply-bulk key sock) (reply-simple "PONG" sock))
           t)
          ((string= cmd "SET")
           (if (and key (cddr args))
               (progn
                 (setf (gethash key store) (join-words (cddr args)))
                 (reply-simple "OK" sock))
               (reply-error "wrong number of arguments for 'set' command" sock))
           t)
          ((string= cmd "GET")
           (if key
               (reply-bulk (gethash key store) sock)
               (reply-error "wrong number of arguments for 'get' command" sock))
           t)
          ((string= cmd "DEL")
           (let ((removed 0))
             (dolist (k (cdr args))
               (when (gethash k store)
                 (remhash k store)
                 (incf removed)))
             (reply-int removed sock))
           t)
          ((string= cmd "EXISTS")
           (reply-int (if (and key (gethash key store)) 1 0) sock)
           t)
          ((string= cmd "INCR")
           (let ((current (if key (or (gethash key store) "0") "0")))
             (cond ((null key)
                    (reply-error "wrong number of arguments for 'incr' command" sock))
                   ((integer-string-p current)
                    (let ((n (+ (parse-integer current) 1)))
                      (setf (gethash key store) (format nil "~a" n))
                      (reply-int n sock)))
                   (t (reply-error "value is not an integer or out of range" sock))))
           t)
          ((string= cmd "KEYS")
           (let ((pattern (or key "*"))
                 (keys nil))
             (maphash (lambda (k v)
                        (if (or (string= pattern "*") (string= pattern k))
                            (push k keys)))
                      store)
             (reply-array-header (length keys) sock)
             (dolist (k keys)
               (reply-bulk k sock)))
           t)
          ((string= cmd "DBSIZE")
           (reply-int (hash-table-count store) sock)
           t)
          ((string= cmd "COMMAND")
           ;; redis-cli asks COMMAND DOCS on connect; an empty array satisfies it.
           (reply-array-header 0 sock)
           t)
          ((string= cmd "QUIT")
           (reply-simple "OK" sock)
           nil)
          (t (reply-error (format nil "unknown command '~a'" (car args)) sock)
             t))))

;; --- server loop ------------------------------------------------------------

(let ((store (make-hash-table))
      (listener (rontolisp:tls-listen "tls-server.p12" "changeit" 6380)))
  (write-line "mini-redis (TLS) listening on 127.0.0.1:6380 (try: redis-cli --tls --insecure -p 6380 ping)")
  (do ((n 1 (+ n 1))) (nil)
    (let ((sock (rontolisp:tcp-accept listener)))
      (do ((args (read-command sock) (read-command sock)))
          ((or (null args) (not (handle-command args store sock)))
           (close sock))))))
```

Run it on the interpreter or the JVM, then talk to it over TLS (`--insecure`
because the certificate is self-signed):

```bash
redis-cli --tls --insecure -p 6380 set greeting hello
redis-cli --tls --insecure -p 6380 get greeting
```

## The usocket-compatible shim

Existing Common Lisp networking code is usually written against the
[usocket](https://github.com/usocket/usocket) portability library rather than
an implementation's own socket API. rontolisp ships a built-in `usocket`
package reproducing its core API over the `rontolisp:tcp-*` built-ins, so such
code runs with fewer changes -- Postmodern's cl-postgres socket layer
(`socket-connect` with `:element-type '(unsigned-byte 8)` + `socket-stream`)
works verbatim:

```lisp
(let* ((listener (usocket:socket-listen "127.0.0.1" usocket:*auto-port*))
       (port (usocket:get-local-port listener))
       (client (usocket:socket-connect "127.0.0.1" port :element-type '(unsigned-byte 8))))
  (write-line "hello" (usocket:socket-stream client))
  (let* ((server (usocket:socket-accept listener))
         (line (read-line (usocket:socket-stream server))))
    (usocket:socket-close server)
    (usocket:socket-close client)
    (usocket:socket-close listener)
    line)) ; => "hello"
```

The mapping is direct because a rontolisp socket IS its stream handle:
`usocket:socket-stream` is the identity function, `usocket:socket-close` is
`close`, and `usocket:socket-listen` flips the host-first argument order onto
`rontolisp:tcp-listen`. `usocket:*wildcard-host*` (`"0.0.0.0"`) and
`usocket:*auto-port*` (`0`) work as in usocket, the `get-local-*` /
`get-peer-*` accessors read ports and addresses back, and the `with-*`
convenience macros
([`with-client-socket` / `with-connected-socket` / `with-server-socket` /
`with-socket-listener`](../reference/macros/usocket-with-macros.md)) bind and
close sockets around a body. The package loads on first use, and it is also
the built-in ASDF system `"usocket"`: `(asdf:load-system "usocket")`,
`(ql:quickload :usocket)` and a third-party `.asd`'s
`:depends-on ("usocket")` all resolve to it without touching the network.

The servers earlier in this guide, rewritten against this shim, wrap the
accept loop in `with-server-socket` (which closes each connection on every
exit) and take the listen failure as a typed `usocket:socket-error`:

```console
(handler-case
    (let ((listener (usocket:socket-listen "127.0.0.1" 7777 :reuse-address t)))
      (write-line "echo server listening on 127.0.0.1:7777")
      (do ((n 1 (+ n 1))) (nil)
        (usocket:with-server-socket (sock (usocket:socket-accept listener))
          (let ((stream (usocket:socket-stream sock)))
            (write-line (format nil "client ~a connected" n))
            (do ((line (read-line stream) (read-line stream)))
                ((null line) (write-line "client disconnected"))
              (write-line line stream))))))
  (usocket:socket-error (e)
    (declare (ignore e))
    (write-line "socket-listen failed (is port 7777 already in use?)")))
```

Limitations of the shim (deliberate -- rontolisp's socket model is lite):

- **TCP only.** `:protocol :datagram` (UDP) signals an error, and
  `socket-send` / `socket-receive` / `socket-shutdown` do not exist.
- **Typed conditions on the interpreter and the JVM.** A failure in
  `socket-connect`/`socket-listen`/`socket-accept` signals a typed
  `usocket:socket-error` (message preserved), so
  `(handler-case (usocket:socket-connect ...) (usocket:socket-error (e) ...))`
  works there. The subtypes (`connection-refused-error` &c) are defined but
  the re-signal always uses `socket-error` (catch that). On the WASM
  component backend a failed connect/accept yields a `nil` handle instead of
  signaling, so the `handler-case` pattern has nothing to catch there (test
  the handle for `nil` instead).
- **`socket-option` supports `:receive-timeout` only.**
  `(setf (usocket:socket-option sock :receive-timeout) seconds)` sets a real
  per-socket read deadline on the interpreter and the JVM (via
  [`rontolisp:tcp-set-timeout`](../reference/functions/rontolisp-tcp-set-timeout.md));
  a timed-out read signals an ordinary catchable `error`, not
  `usocket:timeout-error`, and the getter reads the set seconds back. On the
  WASM backends the write SIGNALS instead of installing a timeout that never
  fires. Every other option signals rather than being silently ignored.
- **`wait-for-input` is a `listen`-based poll**: on the interpreter and the
  JVM the wait is real (`listen` asks the kernel receive buffer, polled every
  10 ms until data arrives or `:timeout` elapses; `:ready-only` works). On
  the WASM backends it returns immediately claiming readiness when nothing is
  buffered — reads block anyway, so the common wait-then-read loop behaves
  identically, but a `:timeout` poll cannot be honoured there. Stream sockets
  only (a listener in the list signals), and wait-list objects do not exist.
- **`socket-server` does not exist** (write your own accept loop).
- **The `with-*` macros close the socket on every exit** on the interpreter
  and the JVM (they expand over
  [`unwind-protect`](../reference/special-forms/unwind-protect.md)); this
  includes the WASM component backend (every tcp component already compiles
  in exception-handling mode). The compatibility keyword arguments
  (`:element-type`, `:timeout`, `:nodelay`, `:reuse-address`, ...) are
  accepted and ignored.
- **Backends**: interpreter and JVM are full; WASM is component-only like the
  tcp built-ins, and the address/peer accessors return real addresses and
  ports there (a failure returns `nil` instead of signaling).

## See also

The [`examples/net/` directory](https://github.com/making/rontolisp/tree/develop/examples/net)
ships these programs as ready-to-run files (written against the usocket shim),
each with per-backend run instructions in its header comment. For HTTP there is
no need to hand-roll the protocol over a socket in either direction: the
*client* side is `rontolisp:fetch` (see the
[HTTP Requests guide](http-fetch.md)), and for the *server* side
`rontolisp:http-handler` parses requests and adapts responses for you (see the
[Serving HTTP guide](http-handler.md)).

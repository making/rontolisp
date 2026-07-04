# rontolisp:tls-listen-pem

`(rontolisp:tls-listen-pem cert-file key-file port &optional host)`

Binds a listening **TLS** socket serving a certificate and private key read
from **PEM files** — the certbot / OpenSSL-friendly counterpart of
[`rontolisp:tls-listen`](rontolisp-tls-listen.md) (which takes a PKCS12
keystore). `cert-file` is a PEM certificate chain (leaf certificate first) and
`key-file` is the matching **unencrypted PKCS#8 private key** (`-----BEGIN
PRIVATE KEY-----`, RSA/EC/DSA/EdDSA). Everything else matches `tls-listen`: the
listener handle works with [`rontolisp:tcp-accept`](rontolisp-tcp-accept.md),
[`rontolisp:tcp-local-port`](rontolisp-tcp-local-port.md) and
[`close`](close.md), and an accepted connection handshakes on its first read or
write.

Generate a self-signed cert and key for local development with OpenSSL (this
writes an unencrypted PKCS#8 key thanks to `-nodes`):

```bash
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout key.pem -out cert.pem -days 365 -subj /CN=localhost \
  -addext subjectAltName=IP:127.0.0.1,DNS:localhost
```

The example below is a one-connection TLS echo server; test it with
`openssl s_client -connect 127.0.0.1:8443` (type a line, it comes back):

```console
(let* ((listener (rontolisp:tls-listen-pem "cert.pem" "key.pem" 8443))
       (sock (rontolisp:tcp-accept listener))    ; blocks for a client
       (line (read-line sock)))                  ; the handshake happens here
  (write-line line sock)
  (close sock)
  (close listener))
```

## Backend support

- **Interpreter**: reads the PEM files at run time, so `cert-file` and
  `key-file` may be computed at run time. A missing file, a malformed
  certificate, an encrypted or unsupported key, or a busy port signals an
  error.
- **JVM**: the certificate and key are parsed **at compile time** and the
  resulting keystore is embedded in the compiled class, so `cert-file` and
  `key-file` must be **string literals** (a computed path is a compile error);
  relative paths resolve against the source file's directory. The compiled
  program needs no PEM files at run time.
- **WASM**: not supported — `tls-listen-pem` is a **compile error** in both
  Preview 1 and `--component` mode. The `wasi:tls` proposal is **client-only**
  (there is no server-side TLS interface for WASM components), so a TLS
  *server* has no WASM path.
- **Browser playground**: not supported — the browser sandbox provides no raw
  TCP sockets, so `tls-listen-pem` signals an error.

## Limitations

- The private key must be an **unencrypted PKCS#8** key
  (`-----BEGIN PRIVATE KEY-----`). Encrypted keys and legacy PKCS#1
  (`-----BEGIN RSA PRIVATE KEY-----`) or SEC1 (`-----BEGIN EC PRIVATE KEY-----`)
  formats are not read; convert with
  `openssl pkcs8 -topk8 -nocrypt -in old.pem -out key.pem`.
- No client-certificate authentication (mutual TLS) options.
- On the JVM backend the paths are compile-time literals (see Backend support);
  for a keystore chosen at run time on the JVM, use
  [`rontolisp:tls-listen`](rontolisp-tls-listen.md) with a PKCS12 file instead.

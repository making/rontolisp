# 454. The Clack `:raw-body` stream reports a binary element type, so `read-stream-to-string` builds a byte buffer

Difficulty: Medium

`examples/cloudflare-workers/httpbin-tiny-routes/check.lisp` answers 500 on its
`POST /post` probe, on the interpreter, the JVM and wasm alike:

```
<-- {"status":500,...,"body":"{\"error\":\"WRITE-BYTE expects a binary output stream\"}\n"}
```

`examples.yaml` expects that probe to echo `"json":{"name":"rontolisp"}`, so
`ExamplesE2eTest` is RED on this one case (253 run, 3 failures -- the same
example on its three backends). It is the only failing example.

## Not the `--optimize` flip

Found while verifying the acceptance of `.todo/448`. It is not that flip: the
interpreter leg passes no `--optimize` at all, and an exec jar built at
`56d8ce94~1` -- the commit before the default flipped -- fails byte-identically.
`CiSpecE2eTest` is green on all four backends against the native binary.

`ExamplesE2eTest` is not in CI (`.github/workflows/ci.yaml` runs `clean test`,
where no exec jar exists so the class aborts, and the native job runs only
`CiSpecE2eTest`), which is why this went unnoticed.

## The mechanism

tiny-routes' `wrap-request-body` reads the body through
`tiny-routes.middleware.request-body:read-stream-to-string`, which sizes its
buffer off the stream:

```lisp
(with-output-to-string (output-stream)
  (let* ((buffer (make-array content-length
                             :element-type (stream-element-type input-stream)))
         (position (read-sequence buffer input-stream)))
    (write-sequence buffer output-stream :end position)))
```

Our Clack `:raw-body` is `rontolisp::http-request-body-stream`
(`src/main/resources/am/ik/rontolisp/eval/http-server.lisp`), a BIVALENT stream
-- it defines both `stream-read-byte` and a UTF-8 decoding `stream-read-char`
over one cursor -- but it is defined on `fundamental-binary-input-stream`, so
`stream-element-type` answers `(unsigned-byte 8)`. tiny-routes therefore
allocates an octet buffer, `read-sequence` fills it with bytes, and
`write-sequence` hands an octet vector to a CHARACTER output stream.

The strictness is not the bug. SBCL signals on the same two lines:

```
The value 65 is not of type CHARACTER when setting an element of (ARRAY CHARACTER)
```

So upstream, the stream a Clack application receives must answer an element
type whose buffer a string stream accepts. Ours answers the binary one.

Prime suspect for the regression window: `976a00b5` ("Make every HTTP body
stream a byte stream so a relayed fetch reply crosses byte-exact", 2026-08-15).
Confirm it rather than assume it -- the sibling `httpbin-clack` example passes,
so whatever changed did not move lack's own request chain.

## What to decide

The fix is one decision, not a patch site: what a bivalent `:raw-body` should
answer for `stream-element-type`, given that `976a00b5` deliberately made the
body octets end to end so a relayed reply stays byte-exact. Candidates:

- answer `character` and let `read-sequence` into a character buffer decode
  through the existing `stream-read-char` (makes tiny-routes work; check what
  it costs the byte-exact relay path `976a00b5` bought);
- answer `:default`, the usual spelling for a bivalent stream, and check that
  `(make-array n :element-type :default)` and `read-sequence` behave on every
  backend -- upstream Clack handlers over flexi-streams do this;
- keep `(unsigned-byte 8)` and make `write-sequence` of an octet vector into a
  character stream decode. **Rejected as written**: SBCL signals there, so this
  would be a divergence from the oracle, not a fix.

Whichever wins is an HTTP body-stream contract change, so it needs the four
backends and a `.kb` note next to `976a00b5`'s reasoning (`.kb/http-server.md`
/ the fetch-body topic) saying WHY the element type is what it is -- the
re-evaluation trigger the next visitor needs.

## Acceptance

- `ExamplesE2eTest` green, including `cloudflare-workers/httpbin-tiny-routes`
  on interpreter / JVM / wasm.
- The byte-exact relay that `976a00b5` bought still holds (its own examples and
  the `dog-relay` Worker).
- A pinning test at the unit level, so this cannot regress behind an
  `ExamplesE2eTest` that CI does not run -- and consider whether CI should run
  `ExamplesE2eTest` at all, which is the reason this sat unnoticed.

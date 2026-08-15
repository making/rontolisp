# A relayed fetch reply is text, so a binary upstream body does not cross byte-exact

Difficulty: High

`rontolisp:fetch`'s `:body` is a CHARACTER stream on every backend: on the
`--no-wasi` reactor it is `%http-reactor-body-stream` over the lenient UTF-8
decoder (`%http-reactor-text-source`), and `read-all` answers a string. A
handler that relays that stream as its own response body -- the natural proxy
shape, `(list status headers (getf res :body))` -- therefore forwards TEXT: the
transport's sink re-encodes each chunk (`%http-reactor-octets` UTF-8 encodes a
string), so a byte the decoder could not read as UTF-8 comes out as its own
two-byte encoding. Measured while building `examples/cloudflare-workers/dog-relay`
(2026-08-15): relaying a dog.ceo JPEG through the streaming boundary answered
`c3 bf d8 ...` where the picture began `ff d8 ff`; a stray `ff` decoded to
U+00FF and re-encoded. Sequences that happen to be valid UTF-8 round-trip, so
the corruption is silent and content-dependent.

This is NOT the streaming boundary's "binary crosses exactly" claim being false:
that claim is about a request body a handler READS (`:raw-body`, octets in) and
a body a handler ANSWERS as an `(unsigned-byte 8)` vector (octets out), both
pinned. What has no byte path is a FETCHED reply relayed as-is -- the third leg,
`env.readResponseBody` -> `env.writeResponseBody` with nothing in between.

## Why it is not a small fix

The stream abstraction is character-oriented by contract (`stream-read` answers
a string, `read-all` concatenates strings), and every backend's fetch decodes
at the same place. A byte-exact relay needs one of:

- a BIVALENT reply stream -- octet chunks kept as vectors until something asks
  for text, the way `:raw-body`'s Gray stream (`%http-body-stream`) already is
  for the request side -- with `stream-read` / `read-all` decoding on demand
  and the reactor sink taking the vector arm of `%http-reactor-octets`
  untouched; or
- an explicit byte-read API on the fetch stream (`stream-read-bytes` or an
  `:element-type` on fetch), and the relay spelling that uses it.

Either changes what `(getf res :body)` IS on all four backends, so it is a
cross-backend design decision with a `.kb/fetch-http.md` entry and a
four-backend pin, not a reactor patch.

## Gate

- `dog-relay` (or a sibling) relaying a JPEG from dog.ceo answers the upstream's
  exact octets, verified `ff d8 ff` first and length equal, on the reactor.
- The same relay program on the interpreter, the JVM and the `--component`
  backend answers the same octets.
- `read-all` on the same reply still answers the decoded TEXT it does today
  (the document-shaped consumers must not move).

# Shiori

A reading journal: a bookshelf, reading progress, quotes and notes, tags, and a
dashboard that piles up the books finished each month. The UI is in Japanese.

| Layer | Library |
| --- | --- |
| storage | [cl-postgres](https://github.com/marijnh/Postmodern): every statement prepared, every value a `$n` parameter |
| HTML | [cl-mustache](https://github.com/kanru/cl-mustache): one layout, a partial per page and per shared piece |
| routing | [tiny-routes](https://github.com/jeko2000/tiny-routes) (`tiny-routes/lite`) served through Clack |
| browser | [htmx](https://four.htmx.org) 4.0.1 from a CDN; everything works without it |

```
shiori.lisp          the entry: loads the parts below, the routes, the server
db.lisp              the connection and the query helpers
schema.lisp          the schema and the sample books of the first start
view.lisp            templates and assets, how a book is drawn (spines, stars, tags)
http.lisp            requests, responses, flash messages, middleware
home.lisp            the dashboard
library.lisp         search, filters, sorting, pages
book.lisp            a book's page; progress, rating and notes updated in place
book-form.lisp       registering, editing and removing a book
templates/*.mustache layout, pages (home, library, book, book-form, error) and pieces
static/app.css       the stylesheet
static/app.js        the page's script: toasts, the request-failure dialog, the spine preview
static/favicon.svg
```

The parts are joined by top-level `load`s, which the compiler splices in place,
so a build is still one program. The templates, the stylesheet and the script
are read when the program is compiled, so the built class or component carries
them and runs from any directory.

## Run

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres

rontolisp examples/db/shiori/shiori.lisp                     # interpreter

rontolisp examples/db/shiori/shiori.lisp -o Shiori.class     # JVM
java -cp . Shiori

rontolisp examples/db/shiori/shiori.lisp -o shiori --native            # native executable
./shiori

rontolisp examples/db/shiori/shiori.lisp -o shiori.wasm --component --optimize
wasmtime serve -S cli=y -S tcp=y -S inherit-network=y --env DATABASE_URL shiori.wasm
```

Open <http://127.0.0.1:8080>. The first start creates the schema and shelves
eighteen books with notes, dated relative to today. Schema creation and seeding
run under `pg_advisory_xact_lock`, so instances starting together do not race.
`DATABASE_URL` is parsed by [`../database-url.lisp`](../database-url.lisp); see
[`../README.md`](../README.md) for its format and for wasmCloud and Spin.

On Cloud Foundry, a service bound to the app and tagged `postgresql` comes
first: the `uri` of its credentials in `VCAP_SERVICES`, whatever the offering
is called. A user-provided service gives it the same way a broker's PostgreSQL
does, so one can replace the other without a change to the app:

```bash
cf create-user-provided-service shiori-db -p '{"uri":"postgres://user:password@db.example.com:5432/shiori"}' -t postgresql
cf bind-service shiori shiori-db
```

## Routes

| Request | Without htmx | With htmx (`HX-Request`) |
| --- | --- | --- |
| `GET /` | dashboard | same, boosted |
| `GET /books?q=&status=&tag=&sort=&page=` | library page | `HX-Target: div#shelf` gets only the `shelf` partial |
| `GET /books/new`, `GET /books/:id/edit` | form | same |
| `POST /books`, `POST /books/:id` | `303` to the book, or `422` with the form and its errors | `HX-Location` instead of `303`; the `422` form is swapped in |
| `POST /books/:id/delete` | `303` to `/books` | `HX-Location` |
| `POST /books/:id/progress` | `303` to the book | the `progress` partial, plus `<hx-partial>`s for the spine, the status and the finish date |
| `POST /books/:id/rating` | `303` | the `stars` partial; a `422` swaps nothing (`hx-status:422`) |
| `POST /books/:id/notes` | `303` to `#notes` | the new `note` partial, prepended; a `422` swaps nothing |
| `POST /notes/:id/delete` | `303` | empty body; `hx-swap="delete"` removes the note |

A message after a redirect rides in a short-lived `shiori_flash` cookie that the
next page shows once. An in-place update instead sends
`HX-Trigger: {"toast": {"value": "...", "target": "body"}}`. The message is
URL-encoded because a header is ISO-8859-1, and the event is raised on the body
because the form that sent the request may already have been swapped out.

htmx 4 swaps every status but 204 and 304, so a boosted `422` form or `404`
page needs no configuration. When a request that expected a fragment
(`HX-Request-Type: partial`) gets a whole page instead -- a deleted book, a
`500` -- the page answers with `HX-Retarget: body` and `HX-Reswap: innerHTML`.
Inheritance is explicit in htmx 4: the layout sets `hx-boost:inherited`.

A request that gets no usable answer changes nothing on the page and opens a
dialog instead (`static/app.js`): `htmx:error` covers a network failure, the
browser being offline and htmx's timeout; `htmx:before:swap` cancels an error
response that is not one of this site's HTML pages (a proxy's `502`). The
dialog's retry sends the same request again -- the form is resubmitted with its
submitter, the link clicked -- and a failed post says that what was typed is
still in the form.

## Security

There is no login: Shiori is one person's journal, and anyone who can reach the
port can read and change it. Do not expose it beyond the machine or network you
trust.

- **Cross-site request forgery.** A page on another site can make the visitor's
  browser post to Shiori (on localhost, on a LAN) even with no login involved.
  `wrap-same-origin` answers a POST with `403` unless `Sec-Fetch-Site` is
  `same-origin` or `none`; a browser that does not send it is checked by
  `Origin` against `Host`. A request with neither header did not come from a
  browser page and is let through. No token is needed in any form.
- **Injection.** Every value reaches PostgreSQL as a `$n` parameter, and every
  value reaches the HTML through `{{...}}`, which escapes `< > & " '`; no
  template uses `{{{...}}}`. The page's script writes text with `textContent`.
- **Content-Security-Policy.** Script loads only from the site and from the
  pinned htmx URL, which also carries an `integrity` hash. There is no inline
  script and no `hx-on` (htmx compiles it with `Function`), so the policy needs
  neither `unsafe-inline` nor `unsafe-eval` for scripts. Only style
  *attributes* are allowed inline: a spine is drawn from them.
  `frame-ancestors 'none'` keeps the pages out of other sites' frames.
- **Limits.** A request body is capped at 64 KiB; the flash cookie is
  `HttpOnly; SameSite=Lax`.

## Notes

- **One connection per request**, opened by a post-match middleware: a request
  for the stylesheet or a 404 opens none.
- **Mustache and NIL.** cl-mustache prints `nil` as `NIL`, so every optional
  value sits inside a `{{#...}}` section. SQL NULL is turned into `nil` by the
  row reader, and column names become keywords (`current_page` →
  `{{current-page}}`).
- **Spines.** A book's cloth colour and height come from a hash of its title and
  its width from its page count (`spine-style`). The form's live preview in
  `static/app.js` computes the same in JavaScript from `*spine-json*`.
- **Caching.** The stylesheet URL carries a hash of its text and is served
  `immutable`.
- **Search** uses `strpos`, not `like`, so `%` and `_` typed by the visitor are
  plain characters. The `ORDER BY` is chosen from a fixed list by key.

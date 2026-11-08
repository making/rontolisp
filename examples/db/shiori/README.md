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
shiori.lisp          the whole server
templates/*.mustache layout, pages (home, library, book, book-form, error) and pieces
static/app.css       the stylesheet
static/favicon.svg
```

The templates and the stylesheet are read when the program is compiled, so the
built class or component carries them and runs from any directory.

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
dialog instead (`layout.mustache`): `htmx:error` covers a network failure, the
browser being offline and htmx's timeout; `htmx:before:swap` cancels an error
response that is not one of this site's HTML pages (a proxy's `502`). The
dialog's retry sends the same request again -- the form is resubmitted with its
submitter, the link clicked -- and a failed post says that what was typed is
still in the form.

## Notes

- **One connection per request**, opened by a post-match middleware: a request
  for the stylesheet or a 404 opens none.
- **Mustache and NIL.** cl-mustache prints `nil` as `NIL`, so every optional
  value sits inside a `{{#...}}` section. SQL NULL is turned into `nil` by the
  row reader, and column names become keywords (`current_page` →
  `{{current-page}}`).
- **Spines.** A book's cloth colour and height come from a hash of its title and
  its width from its page count (`spine-style`). The form's live preview in
  `layout.mustache` computes the same in JavaScript from `*spine-json*`.
- **Caching.** The stylesheet URL carries a hash of its text and is served
  `immutable`.
- **Search** uses `strpos`, not `like`, so `%` and `_` typed by the visitor are
  plain characters. The `ORDER BY` is chosen from a fixed list by key.

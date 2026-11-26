# Shiori in Java: `shiori/`

A reading journal -- a bookshelf, reading progress, quotes and notes, tags, and a dashboard
that piles up the books finished each month -- written in Java: the same application as
[`db/shiori`](../../db/shiori/README.md), its pages, its routes and its htmx answers, on
Java libraries compiled with the program. The UI is in Japanese.

| Layer | Library |
| --- | --- |
| storage | [jdbc-postgresql](https://github.com/making/rontolisp/tree/develop/jdbc-postgresql): JDBC, every value a parameter |
| HTML | [jmustache](https://github.com/samskivert/jmustache): a layout each page inherits (`{{<layout}}`), a template per page and per piece pages share |
| routing | [webmvc-fn-lite](https://github.com/making/rontolisp/tree/develop/webmvc-fn-lite) (Spring MVC's functional endpoints) on [servlet-httpserver](https://github.com/making/rontolisp/tree/develop/servlet-httpserver) |
| forms | [YAVI](https://github.com/making/yavi): every form read into a typed value by a schema |
| JSON | [tiny-json](https://github.com/making/tiny-json): `VCAP_SERVICES`, and what the page's script is handed |
| browser | [htmx](https://four.htmx.org) 4.0.1 from a CDN; everything works without it |

The classes are grouped by feature, and each feature by layer. The dependencies point one
way: a handler to a repository and the domain, a repository to the domain and `jdbc`; no
domain record knows HTTP, SQL or a template.

```
src/main/java/com/example/shiori/
  Shiori.java            the entry: the server, and the listener that starts the application
  ShioriRouter.java      every route and the handler answering it, the filters around them
  DatabaseUrl.java       DATABASE_URL or VCAP_SERVICES
  Schema.java            the tables and the sample books of the first start
  book/                  books: the domain, its forms' schemas and its repository
    Book.java            a stored book: its progress, whether a page is in it
    BookDetails.java     what is written down about a book, read from the book form and
                         written back to it (a reversible schema)
    Status.java          where a book stands, read from and written back as its value
    BookSearch.java      what the library is asked for, read from the query string
    Progress.java, Rating.java
                         what a book's page sends to change it in place
    ReadingStats.java, TagCount.java
                         the dashboard's numbers
    BookRepository.java  the SQL of books and tags
    web/                 the library, a book's page, the book form; BookCard and Spines
                         draw a book (its spine, stars, tags, dates)
  note/                  quotes and notes: Note, NewNote, NoteRepository
    web/                 a note written down or removed; NoteView
  home/web/              the dashboard
  web/                   what every page shares: the templates and the assets, a page in
                         the layout (Page), a piece for htmx (Fragment), a redirect with its
                         flash message (Redirect), the error pages, the filters, how a value
                         is written (Formats)
  jdbc/                  the connections (Database) and the statements run on them
src/main/resources/com/example/shiori/web/
  templates/*.mustache   layout, pages and pieces
  static/                the stylesheet, the page's script and the icon, as db/shiori's
```

The templates and the assets are resources of the package `web`, an asset read by a constant
name and a template by `"templates/" + name + ".mustache"`, so a compiled program carries them
and no other file ([Resources](../../../doc/en/java/index.md#resources)): a served component
has no file system to read them from.

## Run

The server and `DATABASE_URL` are [`db/`](../../db/README.md#setup)'s, in either of the two
forms [`jdbc/`](../jdbc/README.md) reads. It is a Maven project
([`examples/java/`](../README.md)); the three modules of this repository it runs on are
`-SNAPSHOT`s on Central's snapshot repository. The application listens on the port of
`PORT`, 8080 without one:

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres

cd examples/java/shiori
mvn package               # target/shiori.war and target/shiori.wasm (a component)
mvn exec:exec             # JDK
wasmtime serve -S cli=y -S inherit-network=y --env DATABASE_URL target/shiori.wasm
mvn -Pnative package && ./target/shiori   # native executable
```

From the repository's root, the command line compiles the same files for the interpreter and
the JVM:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
APP=examples/java/shiori/src/main/java/com/example/shiori/Shiori.java
LIBS="--java-source-path examples/java/shiori/src/main/resources --java-source-dep am.ik.servlet:servlet-httpserver:0.1.0-SNAPSHOT --java-source-dep am.ik.spring:webmvc-fn-lite:0.1.0-SNAPSHOT --java-source-dep am.ik.jdbc:jdbc-postgresql:0.1.0-SNAPSHOT --java-source-dep am.ik.json:tiny-json:0.4.1 --java-source-dep com.samskivert:jmustache:1.16 --java-source-dep am.ik.yavi:yavi:0.18.1 --java-snapshots"
java -jar $JAR $APP $LIBS                                     # interpreter
java -jar $JAR $APP $LIBS -o Shiori.class && java Shiori      # JVM
```

Open <http://127.0.0.1:8080>. The first start makes the schema and shelves eighteen books
with notes, dated relative to today, under a lock of the server's, so instances starting
together do not race. On Cloud Foundry a service bound to the app and tagged `postgresql`
comes first, as in [`db/shiori`](../../db/shiori/README.md).

A served component opens a socket only with the command world's interfaces and the host's
network: without `-S cli=y` `wasmtime serve` refuses the component at its start
(`wasi:sockets/types@0.3.0 .. tcp-socket has the wrong type`); without
`-S inherit-network=y` every page is the 500 page, the connection refused
(`Permission denied`). `-S allow-ip-name-lookup=y` when the URL names the server by name.

The application starts with its servlet context: `Shiori` is a `@WebListener`, which `main`
gives the container it starts (`listener(new Shiori())`) and a servlet container finds by
its annotation. So a servlet container takes the same classes as a WAR, `target/shiori.war`: the libraries in
`WEB-INF/lib`, the templates and the assets beside the classes, the Servlet API and
`servlet-httpserver` `provided`; `main`, the one method naming `servlet-httpserver`, is never
called there. Deploy it by copying it into Tomcat's `webapps/`, `DATABASE_URL` in its
environment.

Measured on Tomcat 11.0.27 (embedded, the WAR at the root): every answer is the same, field
for field. A form is read as UTF-8 in any container (`RequestFilters.utf8`): Tomcat's
default for a form is ISO-8859-1, which made `試し` of a title `è©¦ã` there until the
filter set it.

The routes, the htmx answers, the flash messages and the security measures (the
same-origin check of a POST, the Content-Security-Policy, every value a parameter and every
template value escaped) are [`db/shiori`](../../db/shiori/README.md#routes)'s.

## Notes

- **A form is a `Map`, read by a YAVI schema.** `ServerRequest.params()` holds the fields a
  form posts (a urlencoded body's included). Each input is a domain record holding its own
  schema (`BookDetails`, `BookSearch`, `Progress`, `Rating`, `NewNote`); the handler binds it
  to a form's trimmed text (`readingParameters(Requests.FORM)`) and takes the typed value or
  the violations with a `switch` over `Validated`. A field two forms share is one validator
  (`BookDetails.CURRENT_PAGE`, `BookDetails.RATING`), a status is the `Status` its value maps
  to, and an id in a path is read by a validator too (`Requests.id`). The library's search
  never refuses a request: an unknown status, sort or page is replaced (`recover`). The book
  form's schema is reversible: a refused form is shown again from what was sent and an
  edited book from what is stored, written back (`SCHEMA.toMap`, `ParameterTree.ofValues`),
  both through the fields the schema describes. No schema rejects an unknown key: a form
  here is the pages' own, and a key one does not name is ignored, as before.
- **A repository holds the SQL and reads a row into a domain record**; the database answers
  values (a date, a count), and the view writes them (`Formats`: `2026年1月31日`, `1,234`),
  except today, which is the database's `current_date` so the dashboard and the stored
  dates agree on the day. A template reads a view model, a record of what it shows
  (`BookCard`, `NoteView`, the handlers' nested records), never a domain record.
- **The connections are kept across statements**, as a pool keeps them (`jdbc/Database`): a
  statement takes one an earlier one gave back, asking the server whether it is still there
  when it has waited more than two seconds, and gives it back unless it failed or a
  transaction was left open; a transaction holds one for all its statements. A login is a
  few round trips, and a SCRAM-SHA-256 one costs some 120 ms on wasm; under `wasmtime
  serve` the connections are the instance's, closed with it.
- **A page inherits the layout** (`{{<layout}}{{$content}}..{{/content}}{{/layout}}`), where
  the Lisp version names the page as a dynamic partial; the layout's values (the title, the
  flash message, the navigation) are the context around the page's.
- **jmustache escapes `=` and `` ` ``** besides `< > & " '` (`/static/app.css?v&#x3D;..` in an
  attribute is the same URL to a browser).

| Output | Size |
| --- | --- |
| `Shiori.class` (with `Shiori$Part1.class`) | 6.2 MB |
| `shiori.wasm` (component) | 4.0 MB |
| `shiori` (native, linux-x86_64) | 39 MB |

The Lisp version is 7.2 MB as a class and 6.3 MB as a component. A page takes 2 to 17 ms
on a JDK and 7 to 115 ms compiled (the component under `wasmtime serve`, the native
executable), the dashboard (six statements) and the library the most; on the interpreter
65 to 350 ms. Of the program's 7,025 functions and lambdas, 141 are ones a thread can be
suspended in, which wasm holds twice (some 240 KB of the component's code);
`wasmtime serve` compiles the component in about two seconds at its first start and takes
it from its cache after that.

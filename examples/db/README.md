# db

Talking to a database from rontolisp, using the real upstream driver rather than
a rontolisp-specific binding.

| Program | Library | Upstream |
| --- | --- | --- |
| [`postgres-hello.lisp`](postgres-hello.lisp) | cl-postgres 1.33.11 (zlib), Postmodern's low-level PostgreSQL driver | <https://github.com/marijnh/Postmodern> |

Start a server first:

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
rontolisp examples/db/postgres-hello.lisp
```

**Interpreter only today**, and slow to start: `ql:quickload` pulls md5,
split-sequence, ironclad, cl-base64, cl-ppcre, uax-15 and alexandria, and
uax-15 parses UnicodeData.txt at load time. `trust`, `password` and `md5`
authentication all complete. SCRAM-SHA-256 completes too, but only when the server
allows enough time: its 4096-round PBKDF2 outruns the default 60-second
`authentication_timeout` in interpreted Lisp, so start the server with
`-c authentication_timeout=600` for it.

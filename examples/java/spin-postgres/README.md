# JDBC through Spin's connection: `spin-postgres/`

A servlet keeping notes in PostgreSQL through plain JDBC, where the connection is the
host's: the driver on its path is
[`jdbc-spin-postgres`](../../../jdbc-spin-postgres/README.md), which runs every statement
on Spin's `spin:postgres/postgres`. Spin opens the connections, pools them and, when the
URL asks for it, wraps them in TLS with the server's certificate verified. The program
opens no socket, speaks no PostgreSQL protocol and names no class of a driver.

- [`Notes.java`](src/main/java/com/example/notes/Notes.java): the statements, each on a
  connection `DriverManager` hands out: a query with a parameter, rows written with their
  keys returned, a transaction of several rows rolled back when one is refused.
- [`NotesServlet.java`](src/main/java/com/example/notes/NotesServlet.java): `GET /` lists
  the notes (`?q=` those whose title holds a word), `POST /` adds one for each `title=`,
  all of them or none; a refusal's SQLSTATE decides the status.
- [`App.java`](src/main/java/com/example/notes/App.java): `DATABASE_URL`, a
  `jdbc:postgresql:` URL, and `DATABASE_CA`, a certificate of your own.

## The host

**Spin 4.1.0 or later** (measured: 4.1.0, 4.3.0 and canary 4.4.0-pre0). Its wasmtime
(48 and later) runs the component's `wasi:http@0.3.0`, and it serves
`spin:postgres/postgres@4.2.0`. No other host implements the interface: `wasmtime serve`
refuses the component at its start (`component imports instance
spin:postgres/postgres@4.2.0, but a matching implementation was not found`).

## Build

From a checkout, install what the pom names once
([`examples/java/`](../README.md#the-applications-that-use-a-library)), then:

```bash
cd examples/java/spin-postgres
mvn package               # target/spin-postgres.wasm, a component
```

or from the repository's root with the command line:

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar \
  examples/java/spin-postgres/src/main/java/com/example/notes/App.java \
  --java-source-path jdbc-spin-postgres/src/main/java:jdbc-spin-postgres/src/main/resources:rontolisp-java-api/src/main/java:servlet-httpserver/src/main/java \
  --java-source-dep jakarta.servlet:jakarta.servlet-api:6.1.0 \
  -o examples/java/spin-postgres/target/spin-postgres.wasm --component
```

## Plain

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine

cd examples/java/spin-postgres
SPIN_VARIABLE_DB_HOST=127.0.0.1:54329 spin up --listen 127.0.0.1:3000 \
  --env DATABASE_URL='jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres&sslmode=disable'
```

```bash
curl -d title='Buy milk' http://127.0.0.1:3000/
curl --data-urlencode title='お茶を淹れる🍵' http://127.0.0.1:3000/
curl -d title= http://127.0.0.1:3000/                    # 400
curl -d title=first -d title= http://127.0.0.1:3000/     # 400, and "first" is not kept
curl http://127.0.0.1:3000/
curl 'http://127.0.0.1:3000/?q=milk'
```

```
added 1: Buy milk
added 2: お茶を淹れる🍵
SQLSTATE 23514: ERROR: new row for relation "note" violates check constraint "note_title_check"
  Detail: Failing row contains (3, , 2026-11-25).
SQLSTATE 23514: ERROR: new row for relation "note" violates check constraint "note_title_check"
  Detail: Failing row contains (5, , 2026-11-25).
PostgreSQL 17.12 in the clear
1  2026-11-25  Buy milk
2  2026-11-25  お茶を淹れる🍵
PostgreSQL 17.12 in the clear
1  2026-11-25  Buy milk
```

`db_host` ([`spin.toml`](spin.toml)) is the server the component may reach
(`allowed_outbound_hosts`); a connection elsewhere is refused (`address
postgres://127.0.0.1:5432 is not permitted`). `--env` hands `DATABASE_URL` in.

## TLS

A server that takes only TLS, its certificate signed by a CA of your own:

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=notes CA" -keyout ca.key -out ca.crt
openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" -keyout server.key -out server.csr
printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\n' > san.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 -extfile san.ext -out server.crt
printf 'local all all trust\nhostssl all all all scram-sha-256\n' > pg_hba.conf
chmod 644 server.key

docker run --rm -p 54330:5432 -e POSTGRES_PASSWORD=secret -v "$PWD":/tls:ro \
  postgres:17-alpine sh -c 'cp /tls/server.* /tls/pg_hba.conf /tmp && chown postgres /tmp/server.* /tmp/pg_hba.conf && \
    chmod 600 /tmp/server.key && exec docker-entrypoint.sh postgres -c ssl=on -c hba_file=/tmp/pg_hba.conf \
    -c ssl_cert_file=/tmp/server.crt -c ssl_key_file=/tmp/server.key'

SPIN_VARIABLE_DB_HOST=127.0.0.1:54330 spin up --listen 127.0.0.1:3000 \
  --env DATABASE_URL='jdbc:postgresql://127.0.0.1:54330/postgres?user=postgres&password=secret&sslmode=require' \
  --env DATABASE_CA="$(cat ca.crt)"
```

```
PostgreSQL 17.12 over TLSv1.3
```

`DATABASE_CA` becomes the connection's `sslrootcert` property, which the driver hands to
Spin's connection builder; without it Spin verifies the server's certificate against the
system's roots (the `SSL_CERT_FILE` of Spin's process among them). Spin checks the chain
and the server's name whenever it speaks TLS, as libpq's `verify-full` does, so the driver
takes `sslmode=verify-full` as `require`; `prefer` is the default (TLS when the server
offers it, verified) and `disable` connects in the clear. A server offering TLS with a
certificate Spin cannot verify is refused even without `sslmode`.

## On a JDK

The same classes run on a JDK, where no host implements the interface: the driver takes no
URL there, and `DriverManager` goes on to
[`jdbc-postgresql`](../../../jdbc-postgresql/README.md), a runtime dependency of the pom
the component is compiled without.

```bash
PORT=3000 DATABASE_URL='jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres' mvn exec:exec
```

It answers the requests above with the same lines (that driver speaks no TLS).

| Output | Size |
| --- | --- |
| `spin-postgres.wasm` (component) | 1.5 MB |

A `GET /` (two statements, each on a connection out of Spin's pool) took 5.4 ms in the
clear and 6.0 ms over TLS, the median of 30 (Spin 4.3.0, PostgreSQL 17 on the loopback).
What the interface does not carry -- a `timestamptz` column, `cancel`, a result read in pieces -- is in
the driver's [README](../../../jdbc-spin-postgres/README.md#what-the-interface-does-not-carry).

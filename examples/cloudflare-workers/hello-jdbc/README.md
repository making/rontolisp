# hello-jdbc — a JDBC program on a Worker, PostgreSQL through Hyperdrive

[`Visits.java`](Visits.java) is plain JDBC: `DriverManager.getConnection`, a
statement, a `PreparedStatement`. Compiled by rontolisp's **experimental** subset of
the Java language with the driver of
[`jdbc-postgresql`](https://github.com/making/rontolisp/tree/develop/jdbc-postgresql)
on its source path, it runs as a Cloudflare Worker: the driver's `java.net.Socket`
is the Worker's `connect()` of `cloudflare:sockets` (`--host-sockets`), and the
database is a [Hyperdrive](https://developers.cloudflare.com/hyperdrive/) binding.

```bash
./build.sh          # Visits.java -> src/worker.wasm + src/worker.js
npx wrangler dev    # http://localhost:8787, the database of localConnectionString
```

```console
$ curl http://localhost:8787/hello
/hello 1
$ curl http://localhost:8787/hello
/hello 2
```

`wrangler dev` connects to the `localConnectionString` of
[`wrangler.jsonc`](wrangler.jsonc); a PostgreSQL to match it:

```bash
docker run --rm -e POSTGRES_PASSWORD=secret -p 127.0.0.1:5432:5432 postgres:17-alpine
```

## What a Worker asks of a JDBC program

- **A connection is its request's.** A Worker's socket ends with the request that
  opened it, so `answer` connects, runs its statements and closes. A connection kept
  for the next request -- a static field, a pool -- fails at its first use there with
  a `SQLException` whose cause is `SocketException: Socket closed: the request that
  opened it has ended`. The pool is Hyperdrive's.
- **Nothing reaches the database while the program starts.** `main` runs when the
  instance starts, outside any request: a socket there cannot be waited for, and the
  instance ends. Make the schema in a request (as `visit` does) or beforehand.
- **The address is the request's.** A `--no-wasi` module has no environment, so
  [`src/index.js`](src/index.js) hands the binding's connection string to the
  program as the field `x-database-url` of the request it passes on. Run as a process
  (`rontolisp Visits.java`, `-o Visits.class`, `--native`), the same file reads
  `DATABASE_URL`.
- **TLS is Hyperdrive's.** The driver connects in the clear; the Worker reaches
  Hyperdrive inside Cloudflare's network, and Hyperdrive speaks TLS to the origin.
  A server that requires TLS of the Worker itself cannot be reached yet.

## The build

```bash
rontolisp Visits.java \
  --java-source-path ../../../jdbc-postgresql/src/main/java:../../../jdbc-postgresql/src/main/resources \
  -o src/worker.wasm --no-wasi --host-sockets --host-random --host-output --optimize=size --emit-js-glue
```

`--host-sockets` makes the module import its host's socket functions, which the
generated `src/worker.js` answers over `connect()`; `--host-random` gives a
SCRAM-SHA-256 login its nonce. The module is about 1.0 MB (270 KB gzipped).

Measured under `wrangler dev` 4.168.0 against PostgreSQL 17 on the loopback: a request
that connects, logs in, runs two statements and closes takes about 20 ms with `trust`
and about 210 ms with `scram-sha-256` (the login's key derivation, each connection);
the first request of an instance, which also runs `main`, about 0.4 s.

## Deploying to Cloudflare

```bash
npm install
npx wrangler login
npx wrangler hyperdrive create visits --connection-string="postgres://user:password@host:5432/database"
# put the id it prints into wrangler.jsonc
./build.sh
npx wrangler deploy
```

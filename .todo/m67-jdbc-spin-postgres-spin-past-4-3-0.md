# m67. jdbc-spin-postgres: Spin past 4.3.0

Difficulty: Low

Left by m55 (2026-11-26; `.kb/jdbc-spin-postgres.md`). The Spin of `ci.yaml` is pinned to
4.3.0 (`Install Spin`), the newest release on 2026-11-26 (canary 4.4.0-pre0 b8f889f answers
as 4.3.0). When a release changes the interface or what the host does, move the pin and
measure it against what `SocketPostgres` models:

- its answer to a `timestamptz` column (4.3.0 fails the query; under `query-async` the end
  of the rows, after none);
- a session given back inside a transaction (4.3.0 hands it out again inside it);
- how many rows a read of `query-async`'s stream answers: 4.3.0 writes one a write, so a
  fetch size reads a row a round trip (10,000 rows: 262-305 ms to the last through JDBC,
  against 62-100 ms whole). A host that writes rows together makes the pieces as fast as
  the whole; then the driver's default (whole) is worth measuring again.

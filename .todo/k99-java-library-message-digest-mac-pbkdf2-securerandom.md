# k99. Java library: `MessageDigest`, `Mac`, PBKDF2 and `SecureRandom`

Difficulty: High

**Read `.kb/java-frontend-provenance.md` before writing anything.** Algorithms from their RFCs
and FIPS texts; answers measured under a JDK. Theme: JDBC for PostgreSQL (`.todo/l01`):
`md5` and SCRAM-SHA-256 logins.

- `java.security.MessageDigest` (`MD5`, `SHA-1`, `SHA-256`, more if cheap),
  `NoSuchAlgorithmException`, `GeneralSecurityException`; `javax.crypto.Mac` (`HmacSHA256`,
  `HmacSHA1`), `javax.crypto.spec.SecretKeySpec`, `SecretKeyFactory` with
  `PBKDF2WithHmacSHA256` and `PBEKeySpec`; `java.security.SecureRandom` from the host's
  entropy on every target (wasi `random` on wasm).
- What pgjdbc and its SCRAM library (`com.ongres.scram`, a dependency of pgjdbc) call decides
  the first cut: grep their sources jars.
- Speed matters: a SCRAM login is 4096 rounds of HMAC-SHA-256. cl-postgres' PBKDF2 once ran
  over two minutes interpreted (`ClPostgresE2eTest`'s header, `.kb/asdf.md`); measure a login's
  cost on each backend and decide whether a digest is Java source or a native of ours
  (`JavaIntrinsics.NATIVES`), with the numbers in `.kb`.
- A program using no digest holds none (measure the size).

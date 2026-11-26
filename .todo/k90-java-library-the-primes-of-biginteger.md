# k90. Java library: the primes of `BigInteger` and its random constructors

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` before writing anything.** Split from `.todo/k72`.

`java.math` (k31, `.kb/java-frontend.md`, "java.math") refuses by name `isProbablePrime`,
`nextProbablePrime`, `BigInteger.probablePrime`, `new BigInteger(numBits, Random)` and
`new BigInteger(bitLength, certainty, Random)`.

- `isProbablePrime(certainty)` is used by programs of contests; its answer is true or false
  with a bound on the error the documentation states, so a test written from the literature
  (Miller-Rabin with bases chosen by rule, a Lucas test) answers what a JDK answers but with
  that probability -- measure against a JDK over seeded numbers, primes and Carmichael numbers.
- `nextProbablePrime`, and the constructors over a `Random`, give values a program prints: how
  many bits each takes of the `Random` and in which order is a JDK's answer to learn on seeded
  `Random`s (the `Random` itself is the specified generator).
- Measure: a program with a `BigInteger` and no prime holds none of it.

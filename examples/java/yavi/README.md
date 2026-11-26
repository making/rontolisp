# Validation with YAVI: `yavi/`

Two programs over [YAVI](https://github.com/making/yavi), one for each of its styles.

[`Member.java`](src/main/java/yavi/Member.java) checks a sign-up record with `ValidatorBuilder` and
prints each violation's message: a blank, a malformed address, numbers out of range, a
size over its limit, and a nickname of six `char`s that YAVI counts as three, composed.

[`Booking.java`](src/main/java/yavi/Booking.java) reads a record with a schema (`Yavi.schema()` ..
`fromMap`) from an input of unknown type -- the map a JSON body decodes to, then the
strings a form sends -- and makes the record only of a valid input: an enum from its
constant's name, a `LocalDate` from its text, a `long` or a `double` as the `Integer`
asked for, and a violation for a property that is missing, of another type or out of its
limits.

YAVI is not built in: it is compiled from its sources jar with the program, on every backend,
of what the program reaches of it. It is a Maven project ([`examples/java/`](../README.md)):

```bash
cd examples/java/yavi
mvn package               # target/member.wasm, target/booking.wasm and a -component.wasm of each
wasmtime run target/member.wasm
wasmtime run target/member-component.wasm
mvn exec:exec             # Member on the JDK; -Drontolisp.mainClass=yavi.Booking for Booking
```

From the repository's root, `--java-source-dep` reads it from its sources jar (Maven Central,
through `~/.m2/repository`) for the interpreter and the JVM:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
DEP="--java-source-dep am.ik.yavi:yavi:0.18.1"
APP=examples/java/yavi/src/main/java/yavi/Member.java
java -jar $JAR $DEP $APP                                  # interpreter
java -jar $JAR $DEP $APP -o Member.class && java Member   # JVM
```

```
Ann: valid
kana, 6 chars: valid
typos: 4 violations
  "name" must not be blank
  "email" must be a valid email address
  "age" must be greater than or equal to 0
  The size of "nickname" must be less than or equal to 3. The given size is 5
empty: 3 violations
  "name" must not be blank
  "email" must not be blank
  "age" must not be null
long: 2 violations
  The size of "name" must be less than or equal to 20. The given size is 22
  "age" must be less than 150
```

`Booking.java` runs the same way:

```
as sent: Booking[guest=Ann, room=TWIN, checkIn=2026-12-24, nights=3]
a long: Booking[guest=Ken, room=SUITE, checkIn=2027-01-02, nights=14]
a double: Booking[guest=Ken, room=SUITE, checkIn=2027-01-02, nights=2]
a fraction: 1 violations
  "nights" must be of type integer
other types: 4 violations
  "guest" must be of type string
  "room" must be one of the following values: [SINGLE, TWIN, SUITE]
  "checkIn" must be of type LocalDate
  "nights" must be of type integer
out of limits: 3 violations
  "guest" must not be blank
  "checkIn" has to be after or equals to 2026-12-01
  "nights" must be less than or equal to 14
nothing: 4 violations
  "guest" is required
  "room" is required
  "checkIn" is required
  "nights" is required
a form: Booking[guest=Ann, room=SINGLE, checkIn=2026-12-31, nights=2]
a form's typo: 2 violations
  "checkIn" must be of type LocalDate
  "nights" must be of type integer
```

`member.wasm` is about 640 KB and `booking.wasm` about 980 KB: what each program reaches
of YAVI, not the whole library, and a schema pays for the types it asks for alone.
Whether a library works is decided by what the program reaches of it: only what the front
end supports compiles, and a class the subset lacks is an error at the place in the
library that reaches it. See
[Using a third-party library](../../../doc/en/java/index.md#using-a-third-party-library)
([サードパーティのライブラリを使う](../../../doc/ja/java/index.md#サードパーティのライブラリを使う)).

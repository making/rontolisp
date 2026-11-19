# Deviations

The subset is partial by design. Where a program that the subset accepts behaves
differently from the language specification is listed here, and it is the same on every
backend but for the [threads](#threads) of a `.wasm`.

## What differs

**A string's elements are code points.** `length`, `charAt` and `substring` count Unicode
code points where the language counts UTF-16 units, so they differ for a character outside
the Basic Multilingual Plane. `hashCode` is the specified number, and `toCharArray` answers
the UTF-16 units, which `new String(char[])` reads back. `toUpperCase` and `toLowerCase`
change one character into one: `"ß".toUpperCase()` is `ß`, where a JDK answers `SS`. A
surrogate with no partner in a string is its three bytes in UTF-8, where a JDK writes `?`.
`Character.digit`, `getNumericValue` and `Integer.parseInt` read a digit outside ASCII
(`'٣'`) on the interpreter and the JVM and not in a `.wasm`.

```java
void main() {
    String s = "a𝄞b";
    IO.println(s.length() + " " + s.indexOf("b") + " " + s.hashCode());
}
```

```
3 2 57834039
```

A JDK prints `4 3 57834039`.

**`==` of two boxes compares their values.** A box is its value, so two boxes of one value
are one object. The language promises that of the values from -128 to 127 and allows it of
every other.

```java
void main() {
    Integer a = 1000, b = 1000;
    Long big = 1000L;
    Object boxed = big;
    IO.println((a == b) + " " + boxed.equals(a) + " " + (boxed instanceof Long));
}
```

```
true false true
```

A JDK prints `false false true`.

**A type argument is not checked where the program runs.** The language checks a value read
through a type argument where it is used; rontolisp trusts the type. A value of another
class gets there only through a raw type or a cast a compiler warns of as unchecked, and it
is then used as it is, where the language throws `ClassCastException`. A cast to a type
variable tests its bound, a cast to a parameterized type its class.

**A store into an array is not checked.** A value stored through an array type above the
array's own (`Object[] a = new String[1]; a[0] = 1;`) is stored, where the language throws
`ArrayStoreException`.

**The class of an array of `long`, of `short` or of references is the one a widening
recorded.** In a program that casts or tests (`instanceof`) a value to such an array type,
or asks the class name (`getClass()`) of a value that can be one, the class is recorded
where the array's type is widened: assigned to `Object` or to an array type above its own,
or passed where a type parameter stands. In any other program, `toString()` of such an
array read as an `Object` names `Object[]` for an array of `long` or of references and
`char[]` for an array of `short`.

```java
void main() {
    Object names = new String[] {"a"};
    String text = names.toString();
    IO.println(text.substring(0, text.indexOf('@')));
}
```

```
[Ljava.lang.Object;
```

A JDK prints `[Ljava.lang.String;`.

**A class is initialized at its first use only where a program can tell.** The language
initializes a class when it is first used, and so does rontolisp for a class whose static
initialization prints, throws, writes a static field of another class, or reads from
another class anything but a `final` field holding a value nothing can change. Every other
class is initialized before `main`: nothing it does can be told from an initialization at
first use.

```java
class Config {
    static int level = 1;
}

class Table {
    static final int[] SQUARES = new int[4];

    static {
        for (int i = 0; i < SQUARES.length; i++) {
            SQUARES[i] = i * i;
        }
    }
}

class Report {
    static final int LEVEL = Config.level;

    static {
        IO.println("Report is initialized");
    }
}

void main() {
    IO.println("main starts");
    Config.level = 3;
    IO.println(Table.SQUARES[3] + " " + Report.LEVEL);
}
```

```
main starts
Report is initialized
9 3
```

An exception a `throw` statement throws out of a static initialization, there or in what it
calls, ends it as the language says: the exception is the cause of an
`ExceptionInInitializerError`, an `Error` passes as it is, and every later use of the class
throws `NoClassDefFoundError`. An error the run time raises there differs. It reaches the
program as itself, the class stays usable as far as its initialization got, and inside an
initialization that runs before `main` it is raised before `main` starts.

```java
class Table {
    static final int[] CELLS = new int[2];

    static {
        IO.println("filling");
        CELLS[2] = 1;
    }
}

void main() {
    try {
        IO.println(Table.CELLS.length);
    } catch (Throwable thrown) {
        IO.println(thrown);
    }
    IO.println(Table.CELLS.length);
}
```

```
filling
java.lang.ArrayIndexOutOfBoundsException: Index 2 out of bounds for length 2
2
```

A JDK prints `java.lang.ExceptionInInitializerError` for the first read and ends the
program at the second with a `NoClassDefFoundError`.

**Two messages of the run time's exceptions differ.** An error of the run time is the
exception the language names, and a `catch` takes it as that class: a member, an element or
the length read through `null`, a `null` box read as its primitive and a `switch` over
`null` are a `NullPointerException`, a division by zero an `ArithmeticException`, an index
outside an array, a string or a `StringBuilder` an `ArrayIndexOutOfBoundsException` or a
`StringIndexOutOfBoundsException`, a negative array length a `NegativeArraySizeException`,
a mismatched `String.format` an `IllegalFormatException`. The messages are a JDK's, but
two. A `NullPointerException` the run time raises has none, where a JDK's describes the
expression that was `null` by names that depend on how the class was compiled. A
`ClassCastException`'s message names the two classes and leaves out what a JDK adds of
their modules and class loaders. A stack overflow is no `StackOverflowError` a program can catch.

**An uncaught exception is reported without a stack trace.** Standard error gets what a JDK
writes but the frames: `Exception in thread "main"` and the exception's `toString()`, then
the exceptions it suppressed and its causes, and the program exits with the status 1. One
line stands for the frames, after the causes: the file and the line of the statement the
exception was raised in, and the method that statement is written in. The interpreter and
a JVM output write it; a `.wasm` output writes it when compiled with
[`--report-locations`](../compiling/wasm.md#uncaught-condition-locations---report-locations).
`printStackTrace()` writes the same lines but that one. An exception keeps no frames:
`getStackTrace()` answers an empty array, after `setStackTrace` too.

```java
public class Uncaught {
    static void check(int balance, int amount) {
        if (amount > balance) {
            throw new IllegalStateException("insufficient funds: " + (amount - balance));
        }
    }

    static void withdraw(int balance, int amount) {
        try {
            check(balance, amount);
        } catch (IllegalStateException e) {
            throw new RuntimeException("withdrawal refused", e);
        }
    }

    public static void main(String[] args) {
        withdraw(70, 100);
    }
}
```

```console
$ rontolisp Uncaught.java
Exception in thread "main" java.lang.RuntimeException: withdrawal refused
Caused by: java.lang.IllegalStateException: insufficient funds: 30
  at Uncaught.java:12 in Uncaught.withdraw(int,int)
```

**Some errors a compiler reports are not reported**, and such a program runs. A
`protected` member is reached from every class under its class, where the language allows
an instance member only through a reference of that class's own type and a constructor
only through `super(..)`. Of the warnings a compiler prints, only the
[unchecked ones](index.md#unchecked-warnings) of a call, a conversion, a cast and a
variable arity parameter and the one of a `catch` clause nothing reaches are.

**A record's `hashCode`** is computed from its components in order, `31 * h + hash`; the
language leaves the number unspecified.

**`hashCode` and `toString` of an object declaring neither** are an identity number of
rontolisp's own, `Name@` and that number in hexadecimal. A `HashMap` keyed by such objects,
enum constants included, walks them in another order than a JDK, whose own order changes
from run to run.

**The order of a `HashMap` differs from a JDK's in two cases.** More than eight keys whose
hash codes fall into one bucket of a table of 64 buckets or more are walked in the order
they were put. Keys of a `Float` with few digits get there from 25 keys on (`0.0`, `0.5`, `1.0` ..: their
hash codes differ in the high bits only), keys of a `Double` with few digits from 64, and so
do the keys of a class whose `hashCode` has few values; strings and integers do not. `Set.of` and `Map.of` have
one fixed order, where a JDK picks another at every start.

**The functions of `Math` are those of `StrictMath`**, so a result is the same on every
backend and processor. A JDK's `Math.sin`, `Math.exp` and the like may differ from it in
the last digit.

**A NaN has no payload.** Every NaN is one value: `floatToRawIntBits` and
`doubleToRawLongBits` give the bits `floatToIntBits` and `doubleToLongBits` give, and
`intBitsToFloat` and `longBitsToDouble` of any NaN's bits give that NaN.

**Regular expressions count characters, not UTF-16 units.** `java.util.regex` and the
`String` members that take a pattern run on an engine written for this project from the
`Pattern` documentation; a match's `start` and `end` count the characters of the input, as
`length` and `charAt` do here. Where a JDK's `find` steps between the halves of a surrogate
pair the matches differ. A character class or a property under `CANON_EQ` and `\N{name}`
are an error when the pattern is compiled. `Pattern` has `compile`, `matches`, `quote`,
`matcher`, `split`, `pattern`, `flags`, `asPredicate`, `asMatchPredicate` and the flag
constants; `Matcher` has `find`, `lookingAt`, `matches`, `group`, `start`, `end`,
`groupCount`, `hasMatch`, `reset`, `region`, `regionStart`, `regionEnd`, the anchoring and
transparent bounds, `hitEnd`, `requireEnd`, `replaceAll` and `replaceFirst` of a text or a
function, `appendReplacement`, `appendTail`, `toMatchResult` and `quoteReplacement`;
`results`, `usePattern`, `namedGroups` and the streams are not there. After `lookingAt` or
`matches` in a region with transparent bounds, a JDK's `start()` is 0 when a look-behind
read before the region; here it is the region's start.

**`String.format` has the conversions the [overview](index.md) lists**; any other is an
error naming it. The only locales are the root and the English ones, which format as
`String.format` without a locale does; the default locale is `Locale.US`.

**Two older classes are the newer ones.** A `StringBuffer` is a `StringBuilder` and
`getClass().getName()` says so; a `Vector` is an `ArrayList` to `instanceof`.

**The standard streams are streams of characters.** `System.in` reads the characters of
the host's standard input as the bytes of their UTF-8, so input that is no text does not
come through as its bytes; a byte written to `System.out` or `System.err` is a byte of
UTF-8, and its character is written once its last byte is. A read of several bytes from
`System.in` waits for the first and takes what is at hand after it; a `.wasm` cannot ask
its host what is at hand and reads to the end of the line. `System.out` and `System.err`
cannot be replaced (`System.setOut`) and stay open after `close()`.

**Six charsets.** `UTF-8`, which is the default, `ISO-8859-1`, `US-ASCII`, `UTF-16`,
`UTF-16BE` and `UTF-16LE`; any other name is an `UnsupportedEncodingException` or an
`UnsupportedCharsetException`. Bytes that are no UTF-8 or no UTF-16 decode to U+FFFD, as
many as a JDK 25 writes. After its first print, `charset()` of a `PrintStream` in `UTF-16`
answers another object of the same name.

**No object stands for a class.** `getClass()` and a class literal (`Name.class`) are read
where one is compared with the other by `==`, `!=` or `equals`, and before `getName()` and
`getSimpleName()`. Anywhere else each is an error.

**What a program asks of its host has fixed answers.** `Runtime.availableProcessors()` is
1. `System.getProperty` answers `line.separator`, `file.separator`, `path.separator` and
the encodings (`file.encoding`, `native.encoding`, `stdin.encoding`, `stdout.encoding`,
`stderr.encoding`, each `UTF-8`); any other key is `null`. `System.gc()` does nothing.
`System.getenv(name)` answers the host's variable -- a `.wasm` has those its host hands it
(`wasmtime run --env NAME=value`) -- and `getenv()` of every variable is not there.

## Threads

**The threads of a `.wasm` take turns.** A `.wasm` has one stack, so a thread there runs
until it waits -- in `wait`, `join` or `sleep`, at `yield`, at a monitor another thread
holds -- and a thread that can go on runs then. A started thread first runs when the thread
that started it waits. Every such execution is one the language allows, which promises no
thread a turn. Four kinds of program show it.

- A loop that never waits keeps its turn. The turn is given up once in about a thousand
  rounds by a loop that reads what another thread writes the way the language shares it: a
  `volatile` field, an atomic, a `synchronized` method or block, the interrupt. A loop that
  reads a plain field instead, one that asks an object through an interface inside its body
  (`while (true) { if (future.isDone()) break; }`), and one turning while its thread holds a
  monitor let no other thread run until they end. `Thread.yield()` or a `sleep` in the loop
  gives the turn.
- Threads that all wait for one another end the program with a message on standard error
  and the status 1, where a JDK waits for ever: two threads each holding the monitor the
  other enters, a pool that is never shut down, a `Timer` that is never cancelled. A
  program serving under `wasmtime serve` is not ended so: a request there may be waiting
  for what the next one brings ([The HTTP server](#the-http-server)).
- A `toString()` or a `hashCode()` that `String.format` or `printf` asks for, and the
  `toString()` of an exception being reported, cannot wait. One that has to -- it is
  `synchronized` and a sleeping thread holds its monitor -- ends the program the same way.
- A Java file with no `main` that a Common Lisp program loads is refused when it starts a
  thread or a server.

**What is shared without a monitor or `volatile` is the host's.** On the interpreter and
the JVM a field two threads read and write with neither may be read stale, as the language
allows. `volatile`, `synchronized`, `Thread.start` and `join` order what they order in the
language.

**A pool is not in every respect a JDK's.** A task put into `getQueue()` by the program
waits for the next `execute`. A scheduled pool keeps its tasks in a table of its own, so
its `getQueue()` is empty. `CompletableFuture.defaultExecutor()` answers the pool the
stages run on, which is not the object `ForkJoinPool.commonPool()` answers. A
`LongAccumulator` and a `DoubleAccumulator` hold one value, where a JDK folds the values
of each thread apart and so asks for a function with the identity given. A `ForkJoinPool`
starts a thread whenever none is free for a task: its parallelism is a number it answers,
2 for `new ForkJoinPool()` and 1 for the common pool. An exception a task threw is thrown
again as it is; a JDK throws a copy holding it when another thread ran the task.

**A `ConcurrentHashMap` keeps the order a JDK 25 walks it in**, but for a table of 64
chains or more in which nine keys share one chain.

**A thread notified in `wait` may still be `WAITING`.** `getState` answers `BLOCKED` for a
thread waiting to enter a monitor another thread holds; one that was notified and waits
to take its monitor again may answer `WAITING` or `TIMED_WAITING` until it has it. An
uncaught-exception handler is asked for the threads a program starts, not for the thread
of `main`. An `AtomicInteger`, an
`AtomicLong`, the adders and the accumulators are no `Number`.

**A thread's number is rontolisp's own.** `threadId()` and the number in `toString()` count
the threads of the program from 1.

## The HTTP client

**A request is sent by the transport of the target**
([`rontolisp:fetch`](../guides/http-fetch.md)), so what belongs to a connection is the
transport's and has no member: a proxy, an authenticator, a cookie handler, an
`SSLContext`, WebSocket. `version(..)` is kept and answered, and no transport reads it; a
reply's `version()` is `HTTP_1_1` whatever was spoken. A request with no `User-Agent` of
its own carries rontolisp's.

**A time limit is the transport's, in milliseconds.** `timeout` and `connectTimeout` are
[`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md)'s `:first-byte-timeout` and
`:connect-timeout`: a part of a millisecond counts as one, and the body of a reply is not
timed. In a component the limit of `timeout` is counted once for the connection and
once more for the start of the reply. A component under `wasmtime serve` cannot time a
request: one with a time limit is an `IOException` there.
The `timeout()` of an `HttpRequest` of a program's own class is an
`UnsupportedOperationException` where the request is sent, in a program that gives no
builder a time limit.

**A request is sent as `GET`, `HEAD`, `POST`, `PUT`, `DELETE`, `OPTIONS` or `PATCH`.** Any
other method a builder took (`TRACE`, `get`) and `expectContinue(true)` are an
`UnsupportedOperationException` where the request is sent.

**A body is read whole.** `send` returns, and the future of `sendAsync` completes, once
the whole reply is in: the stream `BodyHandlers.ofInputStream()` answers reads a body
already there. `BodyPublishers.ofInputStream` is read to its end before the request is
sent. A `BodyHandler` is a function choosing among the subscribers of `BodySubscribers`,
and a body is published by `BodyPublishers`; a subscriber or a publisher of a program's
own class is an `UnsupportedOperationException` where it is used. `BodyHandlers.ofString()`
reads the charset a reply's `Content-Type` names when it is UTF-8, ISO-8859-1 or US-ASCII,
and UTF-8 otherwise; `ofString(charset)` reads any.

**A failure is an `IOException` with the transport's words**, a refused connection a
`ConnectException` on every backend; a JDK has more classes (`UnknownHostException`,
`SSLHandshakeException`).

**`sendAsync` completes its future on a thread of the common pool**, or of the client's
`executor`. In a `.wasm` the [threads take turns](#threads). In a component a thread
waiting for a reply leaves the others their turns, as one waiting in `sleep` does. In a
`--native` executable it lets the threads that can go on take a turn first, and then no
other thread goes on until the reply is in -- a thread asleep included, and a request of
the program's own server: a `main` asking its own server waits for ever there.

**A `URI`** is checked as a JDK checks one, with its messages, but for an address in
brackets, of which only the characters are checked. `hashCode` is not a JDK's number, and
`compareTo` orders by the text. The constructors of several parts, `normalize`,
`relativize`, `toURL` and `toASCIIString` are not there.

## The HTTP server

**A server serves through the transport of the target**, the one a Clack application is
served through, so what belongs to a connection has no class or member: `HttpsServer` and
what configures it, `SimpleFileServer`; the backlog is not read. A program serves through
one server: binding a second while one is bound is an `UnsupportedOperationException`.

**Under `wasmtime serve` the host owns the port and the program's life.** The address of
`create` is not bound (`--addr` is) and `getAddress()` answers it as given. The host starts
an instance of the program for the requests it likes: wasmtime 49 hands one instance up to
128 requests, 16 of them at once, and drops it after a second with none in hand, so a
static field lasts as long as its instance and no longer. An instance's start is `main` up
to its end, or up to where no thread of the program can go on; from then on the program
runs while the host has a request of that instance in hand. A request stays in hand after
its response while a thread of the program waits for a time or for a reply that request
was the one to ask the host for, so a task scheduled by a handler runs once its handler
has answered; a thread asleep since before the first request goes on when a request
arrives. After `stop` an instance answers 503 until the host drops it. `wasi:http` tells
a handler neither its peer nor its port: `getRemoteAddress()` is `0.0.0.0:0`, and the port
of `getLocalAddress()` is 80.

**On one stack a request is a thread taking turns with the program's others**
([Threads](#threads)): in a `--native` executable and a component a handler may wait for a
thread, and `main` may wait for ever after `start()`. A thread that never waits keeps
every request from being answered.

- A `--native` executable takes a request where no thread can go on, so requests are
  answered one at a time unless a handler waits. With no thread left that can go on and
  no request to take, the program is refused as threads that all wait.
- Under `wasmtime serve` a handler that waits -- asleep, for a thread, for the reply to a
  request it sent, for what another request brings -- leaves its instance to the host,
  which hands that instance other requests meanwhile:

  ```console
  server.createContext("/held", exchange -> {
      RELEASED.await();                     // answered once /release has been asked
      exchange.sendResponseHeaders(204, -1);
  });
  server.createContext("/release", exchange -> {
      RELEASED.countDown();
      exchange.sendResponseHeaders(204, -1);
  });
  ```

  The request that ends another's wait has to reach the same instance, and which instance
  a request reaches is the host's choice: wasmtime 49 hands a request to an instance whose
  requests all wait, and may start another when several arrive at once. A request waiting
  for what never comes waits as long as the host lets it.

**A handler runs on the executor's thread, and the exchange ends with it.** With no
executor the thread that takes the requests runs the handlers one at a time, named
`HTTP-Dispatcher` as a JDK's. That thread hands an executor each request and waits until
its handler has returned, where a JDK's goes on to the next: an exchange still open when
its handler returns is closed there, and no thread can answer it later.

**A body in chunks goes out as it is written only under `wasmtime serve`.** After
`sendResponseHeaders(status, 0)` a chunk is on its way there when its write returns; the
interpreter, the JVM and a `--native` executable send the body whole, with its length, once
the handler has returned. A body of a length told ahead is sent when all of it is written.
The transport frames a response: a `Content-Length` or `Transfer-Encoding` field of the
handler's is dropped.

**Every request is answered.** A handler, a filter or an authenticator that throws, a
handler that returns having sent no head, and a body shorter than its length are a 500 with
no body, where a JDK closes the connection or leaves the request unanswered. An executor
that refuses a request is a 500 too. An exception `main` leaves uncaught after `start()`
ends the program.

**`stop` waits its delay as a JDK's does**, until the requests in hand are answered or the
delay has passed, and for all of it when a handler calls it. Requests still in hand after
a stop by a handler are answered before the server closes, where a JDK cuts them.

**The request is whole before the handler runs**: its body is read in, and a field sent
more than once is one value, the values joined by `, `. A write after the end of a body in
chunks is an `IOException` saying `stream closed`, where a JDK throws a class of its own.

**No name is looked up.** `InetAddress.getByName` and an `InetSocketAddress` of a host
take an address written in numbers and `localhost`; any other name is an
`UnknownHostException` from `getByName`, and an unresolved `InetSocketAddress`, whose name
the transport binds. `InetAddress.getLocalHost` and the reverse lookups are not there.

## What is refused

Each of these is an error naming the construct and its position.

- Annotation types, `assert`.
- A class literal and `getClass()` as a value: each is read compared with a class and
  before `getName()` and `getSimpleName()`, a literal also as the lock of a `synchronized`
  statement. A class literal of a primitive or an array type.
- A `switch` over a `long`, a `float`, a `double` or a `boolean`; a method reference and a
  field through a qualified `super` (`Interface.super::m`, `Outer.super.x`).
- A class extending `String`, `StringBuilder` or a box.
- Of `java.util.concurrent`, what the [overview](index.md) does not list: the queues
  ordered by priority or by delay, `ConcurrentSkipListMap`, the field updaters. A
  `Timer`'s `schedule` of a `Date`. Thread groups, `join` of a `Duration`.
- Any class or member of the library not listed in the [overview](index.md): streams
  (`java.util.stream`), `LinkedList`, `LinkedHashMap`, `TreeMap`, `Scanner`, files
  (`File`, `FileReader`, `java.nio.file`), `java.time` but `Duration`, `BigInteger`,
  `BigDecimal`,
  reflection. Of `java.net`, everything but `URI`, the addresses of a server and the
  [HTTP client](#the-http-client): `URL`, sockets, `HttpURLConnection`.

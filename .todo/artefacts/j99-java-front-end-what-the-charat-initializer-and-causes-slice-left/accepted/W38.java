import java.io.*; import java.util.*; import java.util.concurrent.*; import java.util.function.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } static void ex() throws Exception { } static void fnf() throws FileNotFoundException { }
  static <T> T run(Callable<T> c) throws Exception { return c.call(); }
  @SuppressWarnings("unchecked") static <E extends Throwable> void sneaky(Throwable t) throws E { throw (E) t; } }
class B { B() throws IOException { } B(String s) { } void m() { } void n() throws IOException { } void o() throws Exception { } }
interface F<X extends Exception> { void run() throws X; }
interface G<T, X extends Exception> { T get() throws X; }
class Bx<E extends Exception> { void run() throws E { } }
class My extends Exception { My(String m) { super(m); } } class Sub extends My { Sub() { super("s"); } }
class R implements AutoCloseable { public void close() throws IOException { } }
class Q implements Closeable { public void close() { } }
class C { void a(List<String> l) { l.forEach(s -> { try { U.io(); } catch (IOException e) { throw new UncheckedIOException(e); } }); Function<String, Integer> f = s -> { try { return U.make(); } catch (IOException e) { return 0; } }; } }

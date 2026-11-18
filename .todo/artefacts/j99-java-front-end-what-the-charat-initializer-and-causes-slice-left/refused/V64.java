import java.io.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
class B { B() throws IOException { } B(String s) { } void m() { } void n() throws IOException { } void o() throws Exception { } }
interface I { void m(); void n() throws IOException; }
class R implements AutoCloseable { public void close() throws IOException { } }
class Q implements AutoCloseable { public void close() { } }
class C { <E extends Exception> void m(E e) { throw e; } <E extends Exception> void n(E e) throws E { throw e; } <E extends RuntimeException> void o(E e) { throw e; } }

import java.io.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
class B { B() throws IOException { } B(String s) { } void m() { } void n() throws IOException { } void o() throws Exception { } }
interface I { void m(); void n() throws IOException; }
class R implements AutoCloseable { public void close() throws IOException { } }
class Q implements AutoCloseable { public void close() { } }
class C { void m() throws IOException { try { U.io(); } catch (final Exception e) { throw e; } } }

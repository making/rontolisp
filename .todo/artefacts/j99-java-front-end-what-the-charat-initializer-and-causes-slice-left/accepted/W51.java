import java.io.*; import java.util.*; import java.util.concurrent.*; import java.util.function.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } static void ex() throws Exception { } static void fnf() throws FileNotFoundException { } }
interface F<X extends Exception> { void run() throws X; }
class B { B() throws IOException { } B(String s) { } }
class C { static <X extends Exception> void call(F<X> f) throws X { f.run(); } void a() throws IOException { try { U.io(); } catch (Exception e) { call(() -> { throw e; }); } } }

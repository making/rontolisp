import java.io.*; import java.util.*; import java.util.concurrent.*; import java.util.function.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
interface F<X extends Exception> { void run() throws X; }
class C { void a(F<? extends IOException> f) { f.run(); } }

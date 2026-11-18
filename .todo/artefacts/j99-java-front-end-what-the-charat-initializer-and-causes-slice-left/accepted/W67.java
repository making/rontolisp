import java.io.*; import java.util.*; import java.util.concurrent.*; import java.util.function.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
interface F<X extends Exception> { void run() throws X; }
class C<E extends Exception> { void m(F<E> f) throws E { f.run(); } void n(C<IOException> c, F<IOException> f) { try { c.m(f); } catch (IOException e) { } } }

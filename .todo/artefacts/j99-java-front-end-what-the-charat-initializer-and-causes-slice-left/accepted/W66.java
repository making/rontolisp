import java.io.*; import java.util.*; import java.util.concurrent.*; import java.util.function.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
interface F<X extends Exception> { void run() throws X; }
class C { <E extends IOException> void m(F<E> f) throws E { try { f.run(); } catch (FileNotFoundException e) { } } <E extends Exception> void n(F<E> f) throws E { try { f.run(); } catch (RuntimeException e) { } } }

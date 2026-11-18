import java.io.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
class B { B() throws IOException { } B(String s) { } }
class C { void m() { java.util.concurrent.Callable<Integer> r = U::make; java.util.concurrent.Callable<Integer> q = () -> U.make(); } }

import java.io.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
class B { B() throws IOException { } B(String s) { } }
class C { void m() { Runnable r = () -> { throw new IOException(); }; } }

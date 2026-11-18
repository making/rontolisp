import java.io.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
class B { B() throws IOException { } B(String s) { } }
class C { void m() { U.io(); }  int g = U.make(); C() throws IOException { } C(int x) { } }

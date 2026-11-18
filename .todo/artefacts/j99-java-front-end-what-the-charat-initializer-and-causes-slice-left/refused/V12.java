import java.io.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
class B { B() throws IOException { } B(String s) { } }
class C { void m() { new Object() { int g = U.make(); }; } void n() throws IOException { new Object() { int g = U.make(); }; } }

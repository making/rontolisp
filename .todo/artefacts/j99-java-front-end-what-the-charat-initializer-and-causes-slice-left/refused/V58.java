import java.io.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } }
class B { B() throws IOException { } B(String s) { } void m() { } void n() throws IOException { } void o() throws Exception { } }
interface I { void m(); void n() throws IOException; }
class R implements AutoCloseable { public void close() throws IOException { } }
class Q implements AutoCloseable { public void close() { } }
class C implements Cloneable { void m() { try { Object o = clone(); } catch (CloneNotSupportedException e) { } } void n() { int[] a = { 1 }; int[] b = a.clone(); } }

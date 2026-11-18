import java.io.*;
class U { static void io() throws IOException { } static int make() throws IOException { return 1; } static void ex() throws Exception { } static void fnf() throws FileNotFoundException { } }
class B { B() throws IOException { } B(String s) { } void m() { } void n() throws IOException { } void o() throws Exception { } }
interface I { void m(); void n() throws IOException; }
interface F<X extends Exception> { void run() throws X; }
interface G<T, X extends Exception> { T get() throws X; }
class C { void a() { try { throw new FileNotFoundException(); } catch (FileNotFoundException e) { } catch (IOException e) { } catch (Exception e) { } } }

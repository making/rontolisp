import java.io.*;
class T1 {
    static void io() throws IOException { }
    T1() throws IOException { }
    T1(int x) { }
    void a() { throw new IOException("a"); }
    void b() { io(); }
    void c() { new T1(); }
    void d() { T1.io(); }
    void e() { this.b(); new T1().io(); }
    void f() { try { } catch (IOException e) { } }
    void g() { try { io(); } catch (IOException e) { } catch (FileNotFoundException e) { } }
    void h() { try { io(); } catch (FileNotFoundException e) { } }
    void i() { try { } catch (Exception e) { } }
    void j() { try { } catch (Throwable e) { } }
    void k() { try { } catch (RuntimeException e) { } catch (Error e) { } }
    void l() { try { int x = 1; } catch (InterruptedException e) { } }
    void m() { try { throw new FileNotFoundException(); } catch (FileNotFoundException e) { } catch (IOException e) { } }
    void n() { try { io(); } catch (IOException | RuntimeException e) { } }
    void o() { try { } catch (IOException | RuntimeException e) { } }
    void p() { try { io(); } catch (EOFException | FileNotFoundException e) { } }
    void q() { try { throw new Exception(); } catch (IOException e) { } }
    void r() { try { throw new Exception(); } catch (IOException e) { } catch (Exception e) { } }
}

import java.io.*;
public class Warn {
    static void fnf() throws FileNotFoundException { }
    static void eof() throws EOFException { }
    static void io() throws IOException { }
    static class My extends Exception { }
    static class Sub extends My { }
    static void sub() throws Sub { }
    public static void main(String[] args) throws Exception {
        try { fnf(); eof(); } catch (FileNotFoundException e) { } catch (EOFException e) { } catch (IOException e) { }
        try { fnf(); } catch (FileNotFoundException | IllegalStateException e) { } catch (IOException | Error e) { }
        try { fnf(); io(); } catch (FileNotFoundException e) { } catch (IOException e) { }
        try { sub(); } catch (Sub e) { } catch (My e) { } catch (Exception e) { } catch (Throwable e) { }
        try { fnf(); } catch (FileNotFoundException e) { } catch (Exception e) { }
        try { throw new Sub(); } catch (Sub e) { throw e; } catch (My e) { System.out.println("no"); }
        try { for (int i = 0; i < 2; i++) { try { fnf(); } catch (FileNotFoundException e) { } catch (IOException e) { } } } finally { }
        Runnable r = () -> { try { fnf(); } catch (FileNotFoundException e) { } catch (IOException e) { } };
        System.out.println("ran");
    }
}

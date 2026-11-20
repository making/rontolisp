package app;
public class Throws implements lib.Greeter {
    public Throws() { throw new IllegalStateException("no way"); }
    public String greet(String n) { return n; }
}

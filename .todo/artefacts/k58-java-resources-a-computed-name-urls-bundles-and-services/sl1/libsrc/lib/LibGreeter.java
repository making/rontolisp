package lib;
public class LibGreeter implements Greeter {
    public LibGreeter() { System.out.println("made LibGreeter"); }
    public String greet(String name) { return "lib hello " + name; }
    public static class Nested implements Greeter {
        public String greet(String name) { return "nested " + name; }
    }
}

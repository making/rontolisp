package lib;
public class Lib {
    public static java.net.URL find(String name) { return Lib.class.getResource(name); }
}

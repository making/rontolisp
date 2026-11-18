import java.nio.charset.Charset;

public class Looked {
    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "UTF-8";
        System.out.println("hé".getBytes(Charset.forName(name)).length + " " + "hé".getBytes(Charset.forName("UTF-8")).length);
    }
}

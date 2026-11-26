import java.io.*;
public class OldFile {
    public static void main(String[] args) throws Exception {
        File f = new File("o.txt");
        try (FileWriter w = new FileWriter(f)) { w.write("o"); }
        System.out.println(f.length() + " " + f.lastModified() / 1000000000000L + " " + f.canRead() + " " + f.delete());
    }
}

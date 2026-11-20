import am.ik.rontolisp.eval.*;
import java.io.FileNotFoundException;
import java.util.*;

public class Probe {
    public static void main(String[] args) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a.lisp", "(defun a () 1)");
        files.put("geo/Place.java", "package geo; public record Place(String name) { }");
        files.put("geo/name.txt", "hello");
        files.put("lib/app/x.clj", "(ns app.x) (defn f [] :from-lib)");
        files.put("lib/deps.edn", "{:paths [\".\"]}");
        files.put("deps.edn", "{:deps {my/lib {:local/root \"lib\"}}}");
        files.put("wit/w.wit", "package a:b; world w { export f: func() -> u32; }");
        SourceLoader old = path -> { String t = files.get(path); if (t == null) throw new FileNotFoundException(path); return t; };
        SourceLoader now = new UploadedFiles(files);
        for (SourceLoader l : List.of(old, now)) {
            System.out.println("== " + (l == old ? "old" : "new"));
            String[] lisp = { "(directory \"*.lisp\")", "(directory \"*.*\")", "(probe-file \"geo/\")", "(probe-file \"geo\")", "(probe-file \"a.lisp\")",
                "(uiop:directory-exists-p \"geo/\")", "(uiop:directory-files \"geo/\")", "(uiop:subdirectories \"./\")", "(probe-file \"nope/\")" };
            for (String s : lisp) {
                try { System.out.println(s + " => " + new PlaygroundRepl(l).eval(s)); } catch (Exception e) { System.out.println(s + " !! " + e.getMessage()); }
            }
            String[] clj = { "(require 'app.x) (app.x/f)", "(.isDirectory (clojure.java.io/file \"geo\"))", "(clojure.java.io/resource \"app/x.clj\")" };
            for (String s : clj) {
                try { System.out.println(s + " => " + new PlaygroundRepl(l).pick(SourceLanguage.CLOJURE).eval(s)); } catch (Exception e) { System.out.println(s + " !! " + e.getMessage()); }
            }
            String[] java = { "void main() throws Exception { String n = \"name\" + \".txt\"; var in = geo.Place.class.getResourceAsStream(n); IO.println(in == null ? null : new String(in.readAllBytes())); }" };
            for (String s : java) {
                try { System.out.println("java => " + new PlaygroundRepl(l).pick(SourceLanguage.JAVA).run(s)); } catch (Exception e) { System.out.println("java !! " + e.getMessage()); }
            }
        }
    }
}

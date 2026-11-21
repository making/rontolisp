import java.util.*;
import java.io.*;

public class Appended {
    static class Log implements Appendable, Flushable, Closeable {
        StringBuilder log = new StringBuilder();
        public Appendable append(CharSequence s) { log.append("[cs:" + s + "]"); return this; }
        public Appendable append(CharSequence s, int a, int b) { log.append("[r:" + s + "," + a + "," + b + "]"); return this; }
        public Appendable append(char c) { log.append("[c:" + c + "]"); return this; }
        public void flush() { log.append("[flush]"); }
        public void close() { log.append("[close]"); }
        public String toString() { return "LOG" + log; }
    }
    static class Bad implements Appendable {
        int n;
        public Appendable append(CharSequence s) throws IOException { n++; throw new IOException("bad" + n); }
        public Appendable append(CharSequence s, int a, int b) throws IOException { n++; throw new IOException("badr" + n); }
        public Appendable append(char c) throws IOException { n++; throw new IOException("badc" + n); }
    }
    static void t(String what, Runnable r) {
        try { r.run(); } catch (Exception e) { System.out.println(what + " -> " + e.getClass().getName() + ": " + e.getMessage()); }
    }
    public static void main(String[] args) throws Exception {
        Log log = new Log();
        Formatter f = new Formatter(log);
        f.format("ab%dcd%s|%n%%%5s%-3c.", 5, "x", "yz", 'q');
        System.out.println(log);
        log.log.setLength(0);
        t("mid", () -> f.format("ab%dcd%s", "x", 1));
        System.out.println(log);
        log.log.setLength(0);
        t("parse", () -> f.format("ab%#dcd", 5));
        System.out.println(log);
        log.log.setLength(0);
        t("missing", () -> f.format("ab%dcd%s", 5));
        System.out.println(log);
        log.log.setLength(0);
        t("unknown", () -> f.format("ab%qcd", 5));
        System.out.println(log);
        log.log.setLength(0);
        f.format("%S|%10.3s|%b|%h|%x|%e|%f|%g|%a|%tY", "ab", "abcdef", true, "a", 255, 1.5, 1.5, 1.5, 1.5, 0L);
        System.out.println(log);
        log.log.setLength(0);
        f.format("%s", (Object) null);
        f.format("%5s", (Object) null);
        f.format("");
        f.format("plain");
        System.out.println(log);
        log.log.setLength(0);
        System.out.println("out==log " + (f.out() == log) + " toString " + f.toString());
        System.out.println("locale " + f.locale());
        f.flush();
        f.close();
        f.close();
        System.out.println(log);
        t("format closed", () -> f.format("x"));
        t("out closed", () -> f.out());
        t("toString closed", () -> f.toString());
        t("flush closed", () -> f.flush());
        t("locale closed", () -> f.locale());
        t("ioException closed", () -> System.out.println("io " + f.ioException()));
        Formatter g = new Formatter();
        System.out.println("g out " + g.out().getClass().getName() + " locale " + g.locale() + " io " + g.ioException());
        g.format("a%d", 1).format("b");
        System.out.println("g " + g + " out " + g.out());
        g.flush(); g.close();
        t("g toString", () -> g.toString());
        Formatter h = new Formatter((Appendable) null);
        System.out.println("h out " + h.out().getClass().getName());
        Formatter l = new Formatter(Locale.UK);
        System.out.println("l locale " + l.locale() + " out " + l.out().getClass().getName());
        Formatter l2 = new Formatter(new StringBuilder("pre:"), Locale.ROOT);
        l2.format(Locale.US, "%d", 3);
        System.out.println("l2 " + l2 + " locale [" + l2.locale() + "]");
        Formatter ln = new Formatter((Locale) null);
        System.out.println("ln locale " + ln.locale());
        ln.format("%d %,d", 1, 1234567);
        System.out.println("ln " + ln);
        Formatter b = new Formatter(new Bad());
        b.format("ab%dc", 1);
        System.out.println("bad io " + b.ioException());
        b.format("z");
        System.out.println("bad io " + b.ioException());
        b.format("");
        System.out.println("bad io " + b.ioException());
        b.close();
        System.out.println("bad io after close " + b.ioException());
        Formatter p = new Formatter(System.out);
        p.format("to stdout %d%n", 7);
        p.flush();
        System.out.println("p out " + (p.out() == System.out));
        StringWriter sw = new StringWriter();
        Formatter w = new Formatter(sw);
        w.format("w%d", 1);
        System.out.println("sw " + sw + " w " + w);
        w.close();
        System.out.println("sw after close " + sw);
        t("null format", () -> new Formatter().format((String) null));
        t("null args", () -> System.out.println(new Formatter().format("%s %s", (Object[]) null)));
        t("null args2", () -> System.out.println(new Formatter().format("abc", (Object[]) null)));
        t("ctor null ps", () -> new Formatter((PrintStream) null));
        Formatter ff = new Formatter();
        try (ff) { ff.format("q"); }
        t("twr", () -> ff.out());
        FormatterClosedException fce = new FormatterClosedException();
        System.out.println("fce " + fce.getMessage() + " " + (fce instanceof IllegalStateException));
    }
}

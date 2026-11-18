public class InitJdk {
    static class Config {
        static int n = load();
        static int load() {
            System.out.println("loading");
            if (n == 0) throw new IllegalStateException("no config");
            return 1;
        }
        static int get() { return n; }
    }
    static class Broken {
        static int n;
        static { System.out.println("broken"); if (n == 0) throw new AssertionError("an error"); }
    }
    static class Base {
        static int b;
        static { System.out.println("base"); if (b == 0) throw new UnsupportedOperationException(); }
    }
    static class Derived extends Base {
        static int d = 5;
        static { System.out.println("derived"); }
    }
    static class Checked {
        static int c;
        static { try { if (c == 0) throw new Exception("checked"); } catch (RuntimeException e) { } catch (Exception e) { throw new RuntimeException("wrapped", e); } }
    }
    static class Host {
        static int h = 1 / (Init.class.getName().length() - 4);
    }

    static void show(String what, Throwable t) {
        System.out.println(what + ": " + t + " | " + t.getMessage() + " | cause " + t.getCause()
                + (t.getCause() != null ? " | " + t.getCause().getCause() : ""));
        if (t instanceof ExceptionInInitializerError e) System.out.println("  getException " + e.getException());
    }

    public static void main(String[] args) {
        for (int i = 0; i < 3; i++) {
            try { System.out.println(Config.get()); } catch (Throwable t) { show("config " + i, t); }
        }
        try { System.out.println(Config.n); } catch (Throwable t) { show("config field", t); }
        try { Config.n = 3; } catch (Throwable t) { show("config store", t); }
        try { System.out.println(new Config()); } catch (Throwable t) { show("config new", t); }
        for (int i = 0; i < 2; i++) {
            try { System.out.println(Broken.n); } catch (Throwable t) { show("broken " + i, t); }
        }
        for (int i = 0; i < 2; i++) {
            try { System.out.println(Derived.d); } catch (Throwable t) { show("derived " + i, t); }
        }
        try { System.out.println(Base.b); } catch (Throwable t) { show("base", t); }
        try { System.out.println(Checked.c); } catch (Throwable t) { show("checked", t); }
        try { System.out.println(Host.h); } catch (Throwable t) { show("host", t); }
        try { System.out.println(Host.h); } catch (Error t) { show("host again", t); }
        Thread other = new Thread(() -> {
            try { System.out.println(Later.n); } catch (Throwable t) { show("thread", t); }
        }, "worker");
        other.start();
        try { other.join(); } catch (InterruptedException e) { }
        try { System.out.println(Later.n); } catch (Throwable t) { show("later", t); }
        System.out.println(Config.get());
    }
    static class Later {
        static int n;
        static { if (n == 0) throw new IllegalArgumentException("later"); }
    }
}

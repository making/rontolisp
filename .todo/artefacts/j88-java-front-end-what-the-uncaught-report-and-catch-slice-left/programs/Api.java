public class Api {
    static String tryInit(Throwable t) {
        try { t.initCause(new RuntimeException("c")); return "set " + t.getCause(); }
        catch (RuntimeException e) { return e.toString(); }
    }
    public static void main(String[] args) {
        ExceptionInInitializerError a = new ExceptionInInitializerError();
        System.out.println(a + " | " + a.getMessage() + " | " + a.getCause() + " | " + a.getException() + " | " + tryInit(a));
        ExceptionInInitializerError b = new ExceptionInInitializerError("text");
        System.out.println(b + " | " + b.getMessage() + " | " + b.getCause() + " | " + b.getException() + " | " + tryInit(b) + " | " + b.getException());
        ExceptionInInitializerError c = new ExceptionInInitializerError(new IllegalStateException("x"));
        System.out.println(c + " | " + c.getMessage() + " | " + c.getCause() + " | " + c.getException() + " | " + tryInit(c));
        ExceptionInInitializerError d = new ExceptionInInitializerError((Throwable) null);
        System.out.println(d + " | " + d.getMessage() + " | " + d.getCause() + " | " + d.getException() + " | " + tryInit(d));
        NoClassDefFoundError n = new NoClassDefFoundError();
        System.out.println(n + " | " + n.getMessage() + " | " + tryInit(n));
        NoClassDefFoundError m = new NoClassDefFoundError("m");
        System.out.println(m + " | " + m.getMessage() + " | " + (m instanceof LinkageError) + " | " + ((Object) m instanceof Exception));
        LinkageError l = new LinkageError("l", new RuntimeException("r"));
        System.out.println(l + " | " + l.getCause() + " | " + new LinkageError() + " | " + new LinkageError("only"));
        System.out.println(java.util.Arrays.toString(ExceptionInInitializerError.class.getConstructors()));
        System.out.println(java.util.Arrays.toString(NoClassDefFoundError.class.getConstructors()));
        System.out.println(java.util.Arrays.toString(LinkageError.class.getConstructors()));
        System.out.println(java.util.Arrays.toString(ExceptionInInitializerError.class.getDeclaredMethods()));
    }
}

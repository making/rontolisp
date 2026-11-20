package app;
import java.util.ListResourceBundle;
public class Outer {
    public static class Texts extends ListResourceBundle {
        protected Object[][] getContents() { return new Object[][] { { "a", "outer root a" }, { "b", "outer root b" } }; }
    }
    public static class Texts_en extends ListResourceBundle {
        protected Object[][] getContents() { return new Object[][] { { "a", "outer en a" } }; }
    }
    static class Texts_fr extends ListResourceBundle {
        protected Object[][] getContents() { return new Object[][] { { "a", "outer fr a" } }; }
    }
    public class Texts_de extends ListResourceBundle {
        protected Object[][] getContents() { return new Object[][] { { "a", "outer de a" } }; }
    }
    public static class In {
        public static class Deep extends ListResourceBundle {
            protected Object[][] getContents() { return new Object[][] { { "a", "deep a" } }; }
        }
    }
}

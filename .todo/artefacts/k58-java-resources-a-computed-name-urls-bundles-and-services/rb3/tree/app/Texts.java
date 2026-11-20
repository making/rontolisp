package app;
import java.util.ListResourceBundle;
public class Texts extends ListResourceBundle {
    protected Object[][] getContents() { return new Object[][] { {"a", "texts root a"}, {"c", "root c"}, {"n", 42}, {"arr", new String[] {"x", "y"}} }; }
}

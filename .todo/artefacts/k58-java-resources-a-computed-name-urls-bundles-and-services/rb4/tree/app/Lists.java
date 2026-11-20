package app;
import java.util.ListResourceBundle;
public class Lists extends ListResourceBundle {
    protected Object[][] getContents() {
        Object[][] c = new Object[15][];
        String[] keys = {"k0","k1","k2","k3","k4","k5","k6","k7","k8","k9","k10","k11","k12","Aa","BB"};
        for (int i = 0; i < 15; i++) c[i] = new Object[] { keys[i], i };
        return c;
    }
}

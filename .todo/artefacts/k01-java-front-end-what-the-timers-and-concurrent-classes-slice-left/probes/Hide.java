import java.util.concurrent.*;
public class Hide {
    static class Inner { }
    class Deep { class Deeper { } }
    static class Base {
        private int core = 1;
        protected String name = "base";
        int shared = 10;
        int core() { return core; }
        String name() { return name; }
    }
    static class Sub extends Base {
        int core = 7;
        String name = "sub";
        String both() { return name + " " + super.name + " " + ((Base) this).name + " " + this.name + " " + core + " " + shared; }
    }
    static class Leaf extends Sub {
        long core = 99L;
        boolean name = true;
        String all() { return core + " " + ((Sub) this).core + " " + core() + " " + name + " " + super.name + " " + ((Base) this).name + " " + both(); }
    }
    record Named(String name) { }
    public static void main(String[] args) {
        Object o = new Inner();
        String s = o.toString();
        System.out.println(s.substring(0, s.indexOf('@')));
        s = String.valueOf(new Hide().new Deep());
        System.out.println(s.substring(0, s.indexOf('@')));
        s = "" + new Hide().new Deep().new Deeper();
        System.out.println(s.substring(0, s.indexOf('@')));
        System.out.println(new ThreadLocal<String>().toString().replaceAll("@.*", "") + " " + new Exchanger<String>().toString().replaceAll("@.*", "") + " " + o.getClass().getName());
        Sub sub = new Sub();
        Base base = sub;
        System.out.println(sub.core + " " + sub.core() + " " + sub.name + " " + base.name + " " + sub.name() + " " + sub.both());
        sub.core++;
        sub.name += "!";
        base.name = "changed";
        ((Base) sub).shared += 5;
        System.out.println(sub.core + " " + sub.core() + " " + sub.name + " " + base.name + " " + sub.name() + " " + sub.both());
        Leaf leaf = new Leaf();
        leaf.core += 1;
        ((Sub) leaf).core -= 1;
        System.out.println(leaf.all() + " " + leaf.core + " " + ((Sub) leaf).name + " " + ((Base) leaf).name);
        System.out.println(new Named("n").name() + " " + new Named("n"));
    }
}

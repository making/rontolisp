package app;

import java.util.*;
import java.util.function.*;
import lib.Greeter;

public class Main {
    public static class Mine implements Greeter {
        public Mine() { System.out.println("made Mine"); }
        public String greet(String name) { return "mine " + name; }
    }
    static void stream(ServiceLoader<Greeter> loader) {
        try {
            loader.stream().forEach(p -> {
                System.out.println("provider " + p.type().getName());
                try {
                    Greeter g = p.get();
                    Greeter h = p.get();
                    System.out.println("  got " + g.greet("z") + " same=" + (g == h));
                } catch (ServiceConfigurationError e) {
                    System.out.println("  get error " + e.getMessage());
                }
            });
        } catch (ServiceConfigurationError e) {
            System.out.println("stream error " + e.getMessage());
        }
    }
    public static void main(String[] args) throws Exception {
        ServiceLoader<Greeter> loader = ServiceLoader.load(Greeter.class);
        System.out.println("== stream first");
        stream(loader);
        System.out.println("== stream again");
        stream(loader);
        System.out.println("== iterator");
        Iterator<Greeter> it = loader.iterator();
        for (int i = 0; i < 20; i++) {
            try {
                if (!it.hasNext()) { System.out.println("end"); break; }
                System.out.println("next " + it.next().getClass().getName());
            } catch (ServiceConfigurationError e) {
                System.out.println("iterator error " + e.getMessage());
            }
        }
        System.out.println("== findFirst " + loader.findFirst().map(g -> g.getClass().getName()));
        loader.reload();
        System.out.println("== after reload");
        try { System.out.println(loader.findFirst().map(g -> g.getClass().getName())); } catch (ServiceConfigurationError e) { System.out.println("error " + e.getMessage()); }
        System.out.println("== null loader " + ServiceLoader.load(Greeter.class, null).stream().map(p -> p.type().getSimpleName()).toList());
        System.out.println("== installed " + ServiceLoader.loadInstalled(Greeter.class).stream().count());
        ServiceLoader<Greeter> two = ServiceLoader.load(Greeter.class);
        Iterator<Greeter> a = two.iterator();
        Iterator<Greeter> b = two.iterator();
        System.out.println(a.hasNext() + " " + a.next().getClass().getSimpleName() + " " + b.hasNext() + " " + b.next().getClass().getSimpleName());
        try { new ServiceLoader[0].getClass(); ServiceLoader.load(null); } catch (Exception e) { System.out.println(e.getClass().getName() + " " + e.getMessage()); }
    }
}

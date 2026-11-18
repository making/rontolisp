public class Names {
    public static void main(String[] args) throws InterruptedException {
        System.out.println(Thread.currentThread().getName());
        Thread a = new Thread(() -> System.out.println(Thread.currentThread().getName()));
        Thread b = new Thread(() -> System.out.println(Thread.currentThread().getName()), "worker");
        a.start();
        a.join();
        b.start();
        b.join();
        Thread c = new Thread(() -> System.out.println(Thread.currentThread().getName() + " " + Thread.currentThread().isDaemon()));
        c.setName("renamed");
        c.setDaemon(true);
        c.start();
        c.join();
        System.out.println(a.isAlive() + " " + a.getName());
    }
}

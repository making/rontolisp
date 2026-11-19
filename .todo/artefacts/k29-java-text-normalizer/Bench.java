import java.text.Normalizer;

public class Bench {
    static void run(String name, String s, Normalizer.Form form, int times) {
        long best = Long.MAX_VALUE;
        int total = 0;
        for (int round = 0; round < 5; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < times; i++) {
                total += Normalizer.normalize(s, form).length();
            }
            best = Math.min(best, (System.nanoTime() - start) / times);
        }
        System.out.println(name + " " + form + " " + s.length() + " chars: " + best + " ns a call (" + total + ")");
    }

    public static void main(String[] args) {
        String ascii = "The quick brown fox jumps over the lazy dog. 0123456789";
        String latin = "Le cœur déçu mais l'âme plutôt naïve, Louÿs rêva de crapaüter";
        String kana = "こんにちは、世界。がぎぐげごとパピプペポの検証を行います。";
        String loose = "éà がパ 각 abcdẹ́ naïve";
        long first = System.nanoTime();
        Normalizer.normalize("é", Normalizer.Form.NFD);
        System.out.println("the tables: " + (System.nanoTime() - first) / 1000 + " us");
        for (Normalizer.Form form : Normalizer.Form.values()) {
            run("ascii", ascii, form, 20000);
            run("latin", latin, form, 20000);
            run("kana", kana, form, 20000);
            run("loose", loose, form, 20000);
        }
    }
}

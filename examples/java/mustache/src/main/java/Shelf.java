import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

// A bookshelf page rendered by jmustache (com.samskivert:jmustache), read from its sources
// jar: a page, a partial and a layout, each a template beside the program.
public class Shelf {

    // what a template reads of a book: its components and two methods, found by name
    record Book(String title, String author, Integer pages, int read, List<String> tags) {

        Integer progress() {
            return pages == null || pages == 0 ? null : read * 100 / pages;
        }

        boolean isFinished() {
            return pages != null && read == pages;
        }
    }

    public static void main(String[] args) {
        // {{>book}} and {{<layout}} name the other templates: read where the program runs
        Mustache.Compiler compiler = Mustache.compiler()
            .withLoader(name -> new InputStreamReader(Shelf.class.getResourceAsStream("templates/" + name + ".mustache"),
                    StandardCharsets.UTF_8));
        Template shelf = compiler.loadTemplate("shelf");
        List<Book> books = List.of(new Book("Kokoro", "Natsume Soseki", 248, 248, List.of("novel", "meiji")),
                new Book("Pride & Prejudice", "Jane Austen", 432, 120, List.of("novel")),
                new Book("<script>alert(1)</script>", "Anonymous", null, 0, List.of()));
        System.out.print(shelf.execute(Map.of("title", "My shelf", "books", books, "flash", "Saved \"Kokoro\"")));
        System.out.print(shelf.execute(Map.of("title", "Empty shelf", "books", List.of())));
    }
}

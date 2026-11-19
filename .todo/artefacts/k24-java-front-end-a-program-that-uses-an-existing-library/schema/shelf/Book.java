package shelf;

import java.util.Map;

import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validated;
import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// The measurement's program for the schema style, the first example of YAVI's README:
// an input is read and the object made only when it is valid. Written here over YAVI's
// public API.
public record Book(String title, String isbn, Integer pages) {

    public static final ObjectInputValidator<Book> validator = Yavi.schema()
        ._string("title")
        ._string("isbn", c -> c.greaterThanOrEqual(10).lessThanOrEqual(17))
        ._integer("pages", c -> c.greaterThanOrEqual(1))
        .fromMap(Book::new);

    static void show(Map<String, Object> input) {
        Validated<Book> validated = validator.validate(input);
        System.out.println(validated.isValid());
        if (validated.isValid()) {
            System.out.println(validated.value());
        }
        else {
            ConstraintViolations violations = validated.errors();
            System.out.println(violations.size());
            violations.forEach(violation -> System.out.println(violation.message()));
        }
    }

    public static void main(String[] args) {
        show(Map.of("isbn", "978-0141439587", "pages", 474));
        show(Map.of("title", "Emma", "isbn", "12", "pages", 474));
        show(Map.of("title", "Emma", "isbn", "978-0141439587", "pages", "many"));
        show(Map.of("title", "Emma", "isbn", "978-0141439587", "pages", 474));
    }
}

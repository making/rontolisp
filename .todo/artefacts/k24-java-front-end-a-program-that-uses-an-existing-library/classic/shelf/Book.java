package shelf;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validator;

// The measurement's program for the classic style: an object that exists is checked.
// Written here over YAVI's public API; the shape follows its "Getting Started".
public record Book(String title, String isbn, Integer pages) {

    public static final Validator<Book> validator = ValidatorBuilder.<Book>of()
        .constraint(Book::title, "title", c -> c.notNull())
        .constraint(Book::isbn, "isbn", c -> c.notNull().greaterThanOrEqual(10).lessThanOrEqual(17))
        .constraint(Book::pages, "pages", c -> c.greaterThanOrEqual(1))
        .build();

    public static void main(String[] args) {
        ConstraintViolations violations = validator.validate(new Book(null, "12", 0));
        System.out.println(violations.isValid());
        System.out.println(violations.size());
        violations.forEach(violation -> System.out.println(violation.message()));
        ConstraintViolations none = validator.validate(new Book("Emma", "978-0141439587", 474));
        System.out.println(none.isValid());
        System.out.println(none.size());
    }
}

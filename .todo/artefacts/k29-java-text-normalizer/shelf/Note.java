package shelf;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validator;

public record Note(String title) {

    public static final Validator<Note> validator = ValidatorBuilder.<Note>of()
        .constraint(Note::title, "title", c -> c.notBlank().lessThanOrEqual(3))
        .build();

    static void check(String title) {
        ConstraintViolations violations = validator.validate(new Note(title));
        System.out.println(title.length() + " " + violations.isValid());
        violations.forEach(violation -> System.out.println(violation.message()));
    }

    public static void main(String[] args) {
        check("abc");
        check("abcd");
        check("ga゙ぎぐ");
        check("éàô");
        check("éàôü");
        check("각가각");
    }
}

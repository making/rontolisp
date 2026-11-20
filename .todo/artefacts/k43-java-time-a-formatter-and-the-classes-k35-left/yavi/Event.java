
import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validator;

// A date sent as text, checked by YAVI's local date constraint.
public record Event(String name, String date) {

    static final Validator<Event> validator = ValidatorBuilder.<Event>of()
        .constraint(Event::name, "name", c -> c.notBlank())
        .constraint(Event::date, "date", c -> c.notBlank().isoLocalDate())
        .build();

    public static void main(String[] args) {
        for (Event event : new Event[] {new Event("launch", "2026-12-01"), new Event("leap", "2026-02-29"),
                new Event("typo", "2026-13-01"), new Event("words", "next Monday")}) {
            ConstraintViolations violations = validator.validate(event);
            System.out.println(event.name() + ": " + (violations.isValid() ? "valid"
                    : violations.size() + " violations, " + violations.get(0).message()));
        }
    }
}

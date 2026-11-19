package tag;

import java.util.Map;

import am.ik.yavi.core.Validated;
import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// The measurement's smallest schema: a string and an integer, read from ints only.
public record Tag(String name, Integer count) {

    static final ObjectInputValidator<Tag> schema = Yavi.schema()
        ._string("name", c -> c.notBlank().lessThanOrEqual(8))
        ._integer("count", c -> c.notNull().greaterThanOrEqual(0))
        .fromMap(Tag::new);

    static void show(Map<String, Object> input) {
        Validated<Tag> tag = schema.validate(input);
        if (tag.isValid()) {
            System.out.println(tag.value());
            return;
        }
        tag.errors().forEach(violation -> System.out.println(violation.message()));
    }

    public static void main(String[] args) {
        show(Map.of("name", "java", "count", 3));
        show(Map.of("name", " ", "count", -1));
        show(Map.of("name", 1, "count", "three"));
        show(Map.of());
    }
}

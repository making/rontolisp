package yavi;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validator;

// A sign-up form checked with YAVI (am.ik.yavi:yavi), read from its sources jar.
public record Member(String name, String email, Integer age, String nickname) {

    static final Validator<Member> validator = ValidatorBuilder.<Member>of()
        .constraint(Member::name, "name", c -> c.notBlank().lessThanOrEqual(20))
        .constraint(Member::email, "email", c -> c.notBlank().email())
        .constraint(Member::age, "age", c -> c.notNull().greaterThanOrEqual(0).lessThan(150))
        .constraint(Member::nickname, "nickname", c -> c.lessThanOrEqual(3))
        .build();

    static void check(String label, Member member) {
        ConstraintViolations violations = validator.validate(member);
        if (violations.isValid()) {
            System.out.println(label + ": valid");
            return;
        }
        System.out.println(label + ": " + violations.size() + " violations");
        violations.forEach(violation -> System.out.println("  " + violation.message()));
    }

    public static void main(String[] args) {
        check("Ann", new Member("Ann", "ann@example.com", 30, "Ann"));
        // six chars, three letters: each kana is followed by a combining mark, and
        // YAVI counts the composed form
        String kana = "\u306f\u309a\u3072\u309a\u3075\u309a";
        check("kana, " + kana.length() + " chars", new Member("Ken", "ken@example.com", 41, kana));
        check("typos", new Member(" ", "ann.example.com", -1, "Annie"));
        check("empty", new Member(null, null, null, null));
        check("long", new Member("Bartholomew Montgomery", "bart@example.com", 150, null));
    }
}

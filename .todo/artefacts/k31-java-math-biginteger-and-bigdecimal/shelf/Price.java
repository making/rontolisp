package shelf;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import am.ik.yavi.core.ConstraintViolations;
import am.ik.yavi.core.Validated;
import am.ik.yavi.input.ObjectInputValidator;
import am.ik.yavi.validator.Yavi;

// k31's program for YAVI: a BigDecimal and a BigInteger read from an input of unknown
// type, checked against bounds, and the bounds written in the messages. Written here over
// YAVI's public API.
public record Price(String name, BigDecimal amount, BigInteger stock) {

    public static final ObjectInputValidator<Price> validator = Yavi.schema()
        ._string("name")
        ._bigDecimal("amount",
                c -> c.greaterThanOrEqual(new BigDecimal("0.01")).lessThan(new BigDecimal("1234567.8915")))
        ._bigInteger("stock",
                c -> c.greaterThanOrEqual(BigInteger.ZERO).lessThanOrEqual(new BigInteger("12345678901234567890")))
        .fromMap(Price::new);

    static void show(Object amount, Object stock) {
        Validated<Price> validated = validator.validate(Map.of("name", "Emma", "amount", amount, "stock", stock));
        if (validated.isValid()) {
            Price price = validated.value();
            System.out.println(price + " " + price.amount().scale() + " " + price.stock().bitLength());
        }
        else {
            ConstraintViolations violations = validated.errors();
            violations.forEach(violation -> System.out.println(violation.message()));
        }
    }

    public static void main(String[] args) {
        show(new BigDecimal("9.90"), new BigInteger("3"));
        show(9.9, 3);
        show(10, 3L);
        show(0.1f, (short) 3);
        show(1e3, 3.0);
        show(new BigInteger("12"), new BigDecimal("3.00"));
        show(0.001, -1);
        show(new BigDecimal("1234567.8915"), new BigInteger("12345678901234567891"));
        show(1e7, 3.5);
        show("9.90", "3");
        show(Double.NaN, 1e30);
        show(new BigDecimal("1E+3"), new BigDecimal("1E+3"));
        show(new BigDecimal("-0.0001"), new BigDecimal("1E+1000000000"));
    }
}

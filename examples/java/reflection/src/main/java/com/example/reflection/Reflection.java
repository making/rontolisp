package com.example.reflection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.core.Validator;
import am.ik.yavi.core.ViolationDetail;

// Greetings chosen by name. greetings.txt lists the classes; each is found by
// Class.forName, made by its constructor taking a name and asked its method greet, all
// by reflection. src/main/resources/META-INF/native-image declares them: a name it does
// not declare is not found. Then the properties of a value of a library, found by name:
// YAVI's own jar declares its ViolationDetail.
public class Reflection {

    record Signup(String name, Integer age) {
    }

    static final Validator<Signup> validator = ValidatorBuilder.<Signup>of()
        .constraint(Signup::name, "name", c -> c.notBlank())
        .constraint(Signup::age, "age", c -> c.notNull().lessThan(150))
        .build();

    // what a writer of JSON or of a log line does: asks a value for the properties its
    // type declares, by their names
    static String describe(Object value, Class<?> type, String... properties) throws ReflectiveOperationException {
        StringBuilder text = new StringBuilder();
        for (String property : properties) {
            Method accessor = type.getMethod(property);
            text.append(text.isEmpty() ? "" : ", ").append(property).append('=').append(accessor.invoke(value));
        }
        return text.toString();
    }

    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        try (InputStream in = Reflection.class.getResourceAsStream("/greetings.txt");
                BufferedReader names = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String name = names.readLine(); name != null; name = names.readLine()) {
                try {
                    Class<?> type = Class.forName(name);
                    Object greeting = type.getConstructor(String.class).newInstance("Ann");
                    Method greet = type.getMethod("greet");
                    System.out.println(type.getSimpleName() + ": " + greet.invoke(greeting));
                }
                catch (ClassNotFoundException ex) {
                    System.out.println("no class " + ex.getMessage());
                }
            }
        }
        for (ViolationDetail detail : validator.validate(new Signup(" ", 150)).details()) {
            System.out.println(describe(detail, ViolationDetail.class, "name", "messageKey", "message"));
        }
    }
}

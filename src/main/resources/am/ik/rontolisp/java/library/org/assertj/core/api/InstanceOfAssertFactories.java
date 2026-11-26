// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

// The factories asInstanceOf takes: of each kind of value the assertion Assertions makes.
@SuppressWarnings({ "rawtypes", "unchecked" })
public interface InstanceOfAssertFactories {

    InstanceOfAssertFactory<Boolean, AbstractBooleanAssert<?>> BOOLEAN = new InstanceOfAssertFactory<>(Boolean.class,
            actual -> new BooleanAssert(actual));

    InstanceOfAssertFactory<Character, AbstractCharacterAssert<?>> CHARACTER = new InstanceOfAssertFactory<>(
            Character.class, actual -> new CharacterAssert(actual));

    InstanceOfAssertFactory<Double, AbstractDoubleAssert<?>> DOUBLE = new InstanceOfAssertFactory<>(Double.class,
            actual -> new DoubleAssert(actual));

    InstanceOfAssertFactory<Float, AbstractFloatAssert<?>> FLOAT = new InstanceOfAssertFactory<>(Float.class,
            actual -> new FloatAssert(actual));

    InstanceOfAssertFactory<Integer, AbstractIntegerAssert<?>> INTEGER = new InstanceOfAssertFactory<>(Integer.class,
            actual -> new IntegerAssert(actual));

    InstanceOfAssertFactory<Long, AbstractLongAssert<?>> LONG = new InstanceOfAssertFactory<>(Long.class,
            actual -> new LongAssert(actual));

    InstanceOfAssertFactory<byte[], AbstractByteArrayAssert<?>> BYTE_ARRAY = new InstanceOfAssertFactory<>(byte[].class,
            actual -> new ByteArrayAssert(actual));

    InstanceOfAssertFactory<char[], AbstractCharArrayAssert<?>> CHAR_ARRAY = new InstanceOfAssertFactory<>(char[].class,
            actual -> new CharArrayAssert(actual));

    InstanceOfAssertFactory<double[], AbstractDoubleArrayAssert<?>> DOUBLE_ARRAY = new InstanceOfAssertFactory<>(
            double[].class, actual -> new DoubleArrayAssert(actual));

    InstanceOfAssertFactory<int[], AbstractIntArrayAssert<?>> INT_ARRAY = new InstanceOfAssertFactory<>(int[].class,
            actual -> new IntArrayAssert(actual));

    InstanceOfAssertFactory<long[], AbstractLongArrayAssert<?>> LONG_ARRAY = new InstanceOfAssertFactory<>(long[].class,
            actual -> new LongArrayAssert(actual));

    InstanceOfAssertFactory<Object[], ObjectArrayAssert<Object>> ARRAY = new InstanceOfAssertFactory<>(Object[].class,
            actual -> new ObjectArrayAssert<>(actual));

    InstanceOfAssertFactory<Throwable, AbstractThrowableAssert<?, Throwable>> THROWABLE = new InstanceOfAssertFactory<>(
            Throwable.class, actual -> new ThrowableAssert<>(actual));

    InstanceOfAssertFactory<String, AbstractStringAssert<?>> STRING = new InstanceOfAssertFactory<>(String.class,
            actual -> new StringAssert(actual));

    InstanceOfAssertFactory<Class, ClassAssert> CLASS = new InstanceOfAssertFactory<>(Class.class,
            actual -> new ClassAssert(actual));

    InstanceOfAssertFactory<Path, AbstractPathAssert<?>> PATH = new InstanceOfAssertFactory<>(Path.class,
            actual -> new PathAssert(actual));

    InstanceOfAssertFactory<File, AbstractFileAssert<?>> FILE = new InstanceOfAssertFactory<>(File.class,
            actual -> new FileAssert(actual));

    InstanceOfAssertFactory<LocalDate, AbstractLocalDateAssert<?>> LOCAL_DATE = new InstanceOfAssertFactory<>(
            LocalDate.class, actual -> new LocalDateAssert(actual));

    InstanceOfAssertFactory<LocalDateTime, AbstractLocalDateTimeAssert<?>> LOCAL_DATE_TIME = new InstanceOfAssertFactory<>(
            LocalDateTime.class, actual -> new LocalDateTimeAssert(actual));

    InstanceOfAssertFactory<LocalTime, AbstractLocalTimeAssert<?>> LOCAL_TIME = new InstanceOfAssertFactory<>(
            LocalTime.class, actual -> new LocalTimeAssert(actual));

    InstanceOfAssertFactory<Instant, AbstractInstantAssert<?>> INSTANT = new InstanceOfAssertFactory<>(Instant.class,
            actual -> new InstantAssert(actual));

    InstanceOfAssertFactory<Duration, AbstractDurationAssert<?>> DURATION = new InstanceOfAssertFactory<>(
            Duration.class, actual -> new DurationAssert(actual));

    InstanceOfAssertFactory<Future, FutureAssert<Object>> FUTURE = future(Object.class);

    InstanceOfAssertFactory<CompletableFuture, CompletableFutureAssert<Object>> COMPLETABLE_FUTURE = completableFuture(
            Object.class);

    InstanceOfAssertFactory<Iterable, IterableAssert<Object>> ITERABLE = iterable(Object.class);

    InstanceOfAssertFactory<List, ListAssert<Object>> LIST = list(Object.class);

    InstanceOfAssertFactory<Map, MapAssert<Object, Object>> MAP = map(Object.class, Object.class);

    InstanceOfAssertFactory<Optional, OptionalAssert<Object>> OPTIONAL = optional(Object.class);

    static <T> InstanceOfAssertFactory<T, ObjectAssert<T>> type(Class<T> type) {
        return new InstanceOfAssertFactory<>(type, actual -> new ObjectAssert<>(actual));
    }

    static <T extends Throwable> InstanceOfAssertFactory<T, AbstractThrowableAssert<?, T>> throwable(Class<T> type) {
        return new InstanceOfAssertFactory<>(type, actual -> new ThrowableAssert<>(actual));
    }

    static <ELEMENT> InstanceOfAssertFactory<ELEMENT[], ObjectArrayAssert<ELEMENT>> array(Class<ELEMENT[]> arrayType) {
        return new InstanceOfAssertFactory<>(arrayType, actual -> new ObjectArrayAssert<>(actual));
    }

    static <ELEMENT> InstanceOfAssertFactory<Iterable, IterableAssert<ELEMENT>> iterable(Class<ELEMENT> elementType) {
        return new InstanceOfAssertFactory<>(Iterable.class, new Type[] { elementType },
                actual -> new IterableAssert<ELEMENT>((Iterable<ELEMENT>) actual));
    }

    static <ELEMENT> InstanceOfAssertFactory<List, ListAssert<ELEMENT>> list(Class<ELEMENT> elementType) {
        return new InstanceOfAssertFactory<>(List.class, new Type[] { elementType },
                actual -> new ListAssert<ELEMENT>((List<ELEMENT>) actual));
    }

    static <K, V> InstanceOfAssertFactory<Map, MapAssert<K, V>> map(Class<K> keyType, Class<V> valueType) {
        return new InstanceOfAssertFactory<>(Map.class, new Type[] { keyType, valueType },
                actual -> new MapAssert<K, V>((Map<K, V>) actual));
    }

    static <VALUE> InstanceOfAssertFactory<Optional, OptionalAssert<VALUE>> optional(Class<VALUE> resultType) {
        return new InstanceOfAssertFactory<>(Optional.class, new Type[] { resultType },
                actual -> new OptionalAssert<VALUE>((Optional<VALUE>) actual));
    }

    static <T extends Comparable<? super T>> InstanceOfAssertFactory<T, AbstractComparableAssert<?, T>> comparable(
            Class<T> comparableType) {
        return new InstanceOfAssertFactory<>(comparableType, actual -> new GenericComparableAssert<>(actual));
    }

    static <RESULT> InstanceOfAssertFactory<Future, FutureAssert<RESULT>> future(Class<RESULT> resultType) {
        return new InstanceOfAssertFactory<>(Future.class, new Type[] { resultType },
                actual -> new FutureAssert<RESULT>((Future<RESULT>) actual));
    }

    static <RESULT> InstanceOfAssertFactory<CompletableFuture, CompletableFutureAssert<RESULT>> completableFuture(
            Class<RESULT> resultType) {
        return new InstanceOfAssertFactory<>(CompletableFuture.class, new Type[] { resultType },
                actual -> new CompletableFutureAssert<RESULT>((CompletableFuture<RESULT>) actual));
    }
}

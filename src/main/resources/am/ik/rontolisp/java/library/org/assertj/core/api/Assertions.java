// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.data.MapEntry;

// Where an assertion starts: the kind of the value picks the checks it takes.
public class Assertions {

    protected Assertions() {
    }

    public static AbstractStringAssert<?> assertThat(String actual) {
        return new StringAssert(actual);
    }

    public static AbstractBooleanAssert<?> assertThat(boolean actual) {
        return new BooleanAssert(actual);
    }

    public static AbstractBooleanAssert<?> assertThat(Boolean actual) {
        return new BooleanAssert(actual);
    }

    public static AbstractIntegerAssert<?> assertThat(int actual) {
        return new IntegerAssert(actual);
    }

    public static AbstractIntegerAssert<?> assertThat(Integer actual) {
        return new IntegerAssert(actual);
    }

    public static AbstractLongAssert<?> assertThat(long actual) {
        return new LongAssert(actual);
    }

    public static AbstractLongAssert<?> assertThat(Long actual) {
        return new LongAssert(actual);
    }

    public static AbstractFloatAssert<?> assertThat(float actual) {
        return new FloatAssert(actual);
    }

    public static AbstractFloatAssert<?> assertThat(Float actual) {
        return new FloatAssert(actual);
    }

    public static AbstractCharacterAssert<?> assertThat(char actual) {
        return new CharacterAssert(actual);
    }

    public static AbstractCharacterAssert<?> assertThat(Character actual) {
        return new CharacterAssert(actual);
    }

    public static AbstractByteArrayAssert<?> assertThat(byte[] actual) {
        return new ByteArrayAssert(actual);
    }

    public static AbstractIntArrayAssert<?> assertThat(int[] actual) {
        return new IntArrayAssert(actual);
    }

    public static AbstractLongArrayAssert<?> assertThat(long[] actual) {
        return new LongArrayAssert(actual);
    }

    public static AbstractCharArrayAssert<?> assertThat(char[] actual) {
        return new CharArrayAssert(actual);
    }

    public static AbstractDoubleArrayAssert<?> assertThat(double[] actual) {
        return new DoubleArrayAssert(actual);
    }

    public static AbstractDoubleAssert<?> assertThat(double actual) {
        return new DoubleAssert(actual);
    }

    public static AbstractDoubleAssert<?> assertThat(Double actual) {
        return new DoubleAssert(actual);
    }

    public static <ELEMENT> IterableAssert<ELEMENT> assertThat(Iterable<? extends ELEMENT> actual) {
        return new IterableAssert<>(actual);
    }

    public static <ELEMENT> ListAssert<ELEMENT> assertThat(List<? extends ELEMENT> actual) {
        return new ListAssert<>(actual);
    }

    public static <K, V> MapAssert<K, V> assertThat(Map<K, V> actual) {
        return new MapAssert<>(actual);
    }

    public static <VALUE> OptionalAssert<VALUE> assertThat(Optional<VALUE> actual) {
        return new OptionalAssert<>(actual);
    }

    public static <T> ObjectArrayAssert<T> assertThat(T[] actual) {
        return new ObjectArrayAssert<>(actual);
    }

    public static <T extends Throwable> AbstractThrowableAssert<?, T> assertThat(T actual) {
        return new ThrowableAssert<>(actual);
    }

    public static <T> ObjectAssert<T> assertThat(T actual) {
        return new ObjectAssert<>(actual);
    }

    // ---- what a piece of code throws

    public static AbstractThrowableAssert<?, ? extends Throwable> assertThatThrownBy(ThrowingCallable shouldRaiseThrowable) {
        Throwable thrown = ThrowableAssert.catchThrowable(shouldRaiseThrowable);
        if (thrown == null) {
            throw new AssertionError("\nExpecting code to raise a throwable.");
        }
        return new ThrowableAssert<>(thrown);
    }

    public static AbstractThrowableAssert<?, ? extends Throwable> assertThatCode(ThrowingCallable shouldRaiseOrNotThrowable) {
        return new ThrowableAssert<>(ThrowableAssert.catchThrowable(shouldRaiseOrNotThrowable));
    }

    public static <T extends Throwable> ThrowableTypeAssert<T> assertThatExceptionOfType(Class<? extends T> exceptionType) {
        return new ThrowableTypeAssert<>(exceptionType);
    }

    public static NotThrownAssert assertThatNoException() {
        return new NotThrownAssert();
    }

    public static ThrowableTypeAssert<NullPointerException> assertThatNullPointerException() {
        return assertThatExceptionOfType(NullPointerException.class);
    }

    public static ThrowableTypeAssert<IllegalArgumentException> assertThatIllegalArgumentException() {
        return assertThatExceptionOfType(IllegalArgumentException.class);
    }

    public static ThrowableTypeAssert<IllegalStateException> assertThatIllegalStateException() {
        return assertThatExceptionOfType(IllegalStateException.class);
    }

    public static ThrowableTypeAssert<IOException> assertThatIOException() {
        return assertThatExceptionOfType(IOException.class);
    }

    public static Throwable catchThrowable(ThrowingCallable shouldRaiseThrowable) {
        return ThrowableAssert.catchThrowable(shouldRaiseThrowable);
    }

    // ---- a failure of the test's own

    public static <T> T fail(String failureMessage) {
        throw new AssertionError(failureMessage);
    }

    public static <T> T fail(String failureMessage, Object... args) {
        throw new AssertionError(args.length == 0 ? failureMessage : String.format(failureMessage, args));
    }

    public static <T> T fail(String failureMessage, Throwable realCause) {
        throw new AssertionError(failureMessage, realCause);
    }

    public static <K, V> MapEntry<K, V> entry(K key, V value) {
        return MapEntry.entry(key, value);
    }
}

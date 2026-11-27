// Third-party: the names and signatures of this class are those of the class of the same
// name in AssertJ 3.27 (Apache-2.0), Copyright 2012-2025 the original author or authors.
// Changed: a part of the members, their bodies written for rontolisp's experimental Java
// subset. Notice: THIRD-PARTY-NOTICES.txt, "AssertJ".
package org.assertj.core.api;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import rontolisp.internal.Soft;

// Where a soft assertion starts: the assertion Assertions makes of the value, its
// failures collected by this provider.
public interface Java6StandardSoftAssertionsProvider extends SoftAssertionsProvider {

    default BooleanAssert assertThat(boolean actual) {
        return Soft.soft(new BooleanAssert(actual), this);
    }

    default BooleanAssert assertThat(Boolean actual) {
        return Soft.soft(new BooleanAssert(actual), this);
    }

    default CharacterAssert assertThat(char actual) {
        return Soft.soft(new CharacterAssert(actual), this);
    }

    default CharacterAssert assertThat(Character actual) {
        return Soft.soft(new CharacterAssert(actual), this);
    }

    default ByteArrayAssert assertThat(byte[] actual) {
        return Soft.soft(new ByteArrayAssert(actual), this);
    }

    default CharArrayAssert assertThat(char[] actual) {
        return Soft.soft(new CharArrayAssert(actual), this);
    }

    default ClassAssert assertThat(Class<?> actual) {
        return Soft.soft(new ClassAssert(actual), this);
    }

    default <T extends Comparable<? super T>> AbstractComparableAssert<?, T> assertThat(T actual) {
        return Soft.soft(new GenericComparableAssert<>(actual), this);
    }

    default <T> IterableAssert<T> assertThat(Iterable<? extends T> actual) {
        return Soft.soft(new IterableAssert<>(actual), this);
    }

    default DoubleAssert assertThat(double actual) {
        return Soft.soft(new DoubleAssert(actual), this);
    }

    default DoubleAssert assertThat(Double actual) {
        return Soft.soft(new DoubleAssert(actual), this);
    }

    default DoubleArrayAssert assertThat(double[] actual) {
        return Soft.soft(new DoubleArrayAssert(actual), this);
    }

    default FileAssert assertThat(File actual) {
        return Soft.soft(new FileAssert(actual), this);
    }

    default <RESULT> FutureAssert<RESULT> assertThat(Future<RESULT> actual) {
        return Soft.soft(new FutureAssert<>(actual), this);
    }

    default FloatAssert assertThat(float actual) {
        return Soft.soft(new FloatAssert(actual), this);
    }

    default FloatAssert assertThat(Float actual) {
        return Soft.soft(new FloatAssert(actual), this);
    }

    default IntegerAssert assertThat(int actual) {
        return Soft.soft(new IntegerAssert(actual), this);
    }

    default IntArrayAssert assertThat(int[] actual) {
        return Soft.soft(new IntArrayAssert(actual), this);
    }

    default IntegerAssert assertThat(Integer actual) {
        return Soft.soft(new IntegerAssert(actual), this);
    }

    default <T> ListAssert<T> assertThat(List<? extends T> actual) {
        return Soft.soft(new ListAssert<>(actual), this);
    }

    default LongAssert assertThat(long actual) {
        return Soft.soft(new LongAssert(actual), this);
    }

    default LongAssert assertThat(Long actual) {
        return Soft.soft(new LongAssert(actual), this);
    }

    default LongArrayAssert assertThat(long[] actual) {
        return Soft.soft(new LongArrayAssert(actual), this);
    }

    default <T> ObjectAssert<T> assertThat(T actual) {
        return Soft.soft(new ObjectAssert<>(actual), this);
    }

    default <T> ObjectArrayAssert<T> assertThat(T[] actual) {
        return Soft.soft(new ObjectArrayAssert<>(actual), this);
    }

    default <K, V> MapAssert<K, V> assertThat(Map<K, V> actual) {
        return Soft.soft(new MapAssert<>(actual), this);
    }

    default StringAssert assertThat(String actual) {
        return Soft.soft(new StringAssert(actual), this);
    }

    default <T extends Throwable> ThrowableAssert<T> assertThat(T actual) {
        return Soft.soft(new ThrowableAssert<>(actual), this);
    }

    // ---- what a piece of code throws: one that throws nothing is a failure collected,
    // and the checks that follow are of no throwable

    default AbstractThrowableAssert<?, ? extends Throwable> assertThatThrownBy(ThrowingCallable shouldRaiseThrowable) {
        return assertThat(ThrowableAssert.catchThrowable(shouldRaiseThrowable)).hasBeenThrown();
    }

    default AbstractThrowableAssert<?, ? extends Throwable> assertThatThrownBy(ThrowingCallable shouldRaiseThrowable,
            String description, Object... args) {
        return assertThat(ThrowableAssert.catchThrowable(shouldRaiseThrowable)).as(description, args).hasBeenThrown();
    }

    default AbstractThrowableAssert<?, ? extends Throwable> assertThatCode(ThrowingCallable shouldRaiseOrNotThrowable) {
        return assertThat(ThrowableAssert.catchThrowable(shouldRaiseOrNotThrowable));
    }

    default <T> ObjectAssert<T> assertThatObject(T actual) {
        return Soft.soft(new ObjectAssert<>(actual), this);
    }

    default <T extends Throwable> ThrowableTypeAssert<T> assertThatExceptionOfType(Class<T> throwableType) {
        return new SoftThrowableTypeAssert<>(throwableType, this);
    }

    default ThrowableTypeAssert<RuntimeException> assertThatRuntimeException() {
        return assertThatExceptionOfType(RuntimeException.class);
    }

    default ThrowableTypeAssert<NullPointerException> assertThatNullPointerException() {
        return assertThatExceptionOfType(NullPointerException.class);
    }

    default ThrowableTypeAssert<IllegalArgumentException> assertThatIllegalArgumentException() {
        return assertThatExceptionOfType(IllegalArgumentException.class);
    }

    default ThrowableTypeAssert<IOException> assertThatIOException() {
        return assertThatExceptionOfType(IOException.class);
    }

    default ThrowableTypeAssert<IllegalStateException> assertThatIllegalStateException() {
        return assertThatExceptionOfType(IllegalStateException.class);
    }

    default ThrowableTypeAssert<Exception> assertThatException() {
        return assertThatExceptionOfType(Exception.class);
    }

    default ThrowableTypeAssert<ReflectiveOperationException> assertThatReflectiveOperationException() {
        return assertThatExceptionOfType(ReflectiveOperationException.class);
    }

    default ThrowableTypeAssert<IndexOutOfBoundsException> assertThatIndexOutOfBoundsException() {
        return assertThatExceptionOfType(IndexOutOfBoundsException.class);
    }
}

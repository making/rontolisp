package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lock-free buffer holds exactly what the JDK's own would, through every growth step.
 */
class UnsynchronizedByteArrayOutputStreamTest {

	@Test
	void mixedWritesFromAnEmptyBufferMatchTheJdkBuffer() {
		Random random = new Random(42);
		ByteArrayOutputStream expected = new ByteArrayOutputStream(0);
		UnsynchronizedByteArrayOutputStream actual = new UnsynchronizedByteArrayOutputStream(0);
		for (int i = 0; i < 5000; i++) {
			if (random.nextBoolean()) {
				int b = random.nextInt();
				expected.write(b);
				actual.write(b);
			}
			else {
				byte[] chunk = new byte[random.nextInt(300)];
				random.nextBytes(chunk);
				int off = chunk.length == 0 ? 0 : random.nextInt(chunk.length);
				expected.write(chunk, off, chunk.length - off);
				actual.write(chunk, off, chunk.length - off);
				expected.writeBytes(chunk);
				actual.writeBytes(chunk);
			}
		}
		assertThat(actual.toByteArray()).isEqualTo(expected.toByteArray());
		assertThat(actual.size()).isEqualTo(expected.size());
	}

	@Test
	void anOutOfRangeSliceIsRefusedAndWritesNothing() {
		UnsynchronizedByteArrayOutputStream out = new UnsynchronizedByteArrayOutputStream();
		assertThatThrownBy(() -> out.write(new byte[4], 3, 2)).isInstanceOf(IndexOutOfBoundsException.class);
		assertThat(out.size()).isZero();
	}

}

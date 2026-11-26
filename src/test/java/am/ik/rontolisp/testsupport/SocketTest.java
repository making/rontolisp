package am.ik.rontolisp.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A test that blocks on a real socket -- an accept, a read, a TLS handshake:
 * {@code @Test} on a hang guard.
 *
 * <p>
 * A blocked socket call never returns to JUnit's default {@code SAME_THREAD} timeout,
 * which only interrupts: a platform thread in {@code ServerSocket.accept} or a socket
 * read ignores the interrupt (the JDK closes the socket on interrupt for a virtual thread
 * only). {@code SEPARATE_THREAD} fails the test at the deadline and abandons the blocked
 * thread, so the run goes on. Five minutes is far past what any socket test costs under a
 * loaded parallel run; it bounds a hang, it does not time a test
 * ({@code .kb/tcp-sockets.md}, "Socket tests").
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Test
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public @interface SocketTest {

}

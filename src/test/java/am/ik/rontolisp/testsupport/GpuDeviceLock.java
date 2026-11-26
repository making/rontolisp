package am.ik.rontolisp.testsupport;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.eval.LinalgGpu;
import org.junit.jupiter.api.parallel.ResourceLocksProvider;

/**
 * Serializes the methods it is declared on while a GPU is present, and leaves them
 * concurrent while none is: {@code @ResourceLock(providers = GpuDeviceLock.class)}.
 *
 * <p>
 * Every {@code --gpu} class a test runs in process carries its own copy of
 * {@code am.ik.gpu} and probes the device from it, so concurrent methods would put
 * several independent residencies and command queues on one device at once. Nothing
 * measures that as safe, so with a device the methods take turns, as they did before the
 * class ran concurrently; without one every probe declines and there is nothing to share.
 */
public final class GpuDeviceLock implements ResourceLocksProvider {

	/** The lock key, shared by every method that may drive the device. */
	public static final String KEY = "am.ik.gpu.device";

	@Override
	public Set<Lock> provideForMethod(List<Class<?>> enclosingInstanceTypes, Class<?> testClass, Method testMethod) {
		return LinalgGpu.available() ? Set.of(new Lock(KEY)) : Set.of();
	}

}

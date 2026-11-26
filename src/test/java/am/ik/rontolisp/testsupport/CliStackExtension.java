package am.ik.rontolisp.testsupport;

import java.lang.reflect.Method;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * Runs every test method of the class it extends on the CLI's program stack
 * ({@link CliStack}): {@code @ExtendWith(CliStackExtension.class)}.
 *
 * <p>
 * For a class whose methods compile or interpret in process -- the compile path's passes
 * recurse with the program's shape, and the CLI gives them 16 MiB. Only the method body
 * moves: lifecycle methods and resource locks stay on the JUnit worker, which waits for
 * the body, and {@link ThreadStdio}'s slots are inheritable, so a capture the body opens
 * reaches every thread the program starts.
 */
public final class CliStackExtension implements InvocationInterceptor {

	@Override
	public void interceptTestMethod(Invocation<@Nullable Void> invocation,
			ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
		proceedOnTheCliStack(invocation, extensionContext);
	}

	@Override
	public void interceptTestTemplateMethod(Invocation<@Nullable Void> invocation,
			ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
		proceedOnTheCliStack(invocation, extensionContext);
	}

	@Override
	public void interceptDynamicTest(Invocation<@Nullable Void> invocation,
			DynamicTestInvocationContext invocationContext, ExtensionContext extensionContext) throws Throwable {
		proceedOnTheCliStack(invocation, extensionContext);
	}

	private static void proceedOnTheCliStack(Invocation<@Nullable Void> invocation, ExtensionContext context)
			throws Throwable {
		CliStack.callThrowing(context.getDisplayName(), invocation::proceed);
	}

}

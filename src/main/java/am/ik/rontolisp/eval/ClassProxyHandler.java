package am.ik.rontolisp.eval;

import org.jspecify.annotations.Nullable;

/**
 * The callback a {@code java:subclass} object's generated methods call: the one dispatch
 * entry a generated subclass needs, typed with JDK types only so a class defined at run
 * time in a child loader can name it.
 */
public interface ClassProxyHandler {

	/**
	 * Runs an overridden method's body.
	 * @param slot the method's index in the implementation's slots
	 * @param self the proxy object the method was called on (the callable's {@code this})
	 * @param args the method's arguments, boxed
	 * @return the method's value, boxed (never {@code null} for a primitive return)
	 * @throws Throwable what the callable raised, recorded on its way out
	 */
	@Nullable Object invoke(int slot, Object self, Object[] args) throws Throwable;

}

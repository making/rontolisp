package am.ik.jvm;

import java.util.function.Consumer;

import am.ik.jvm.ConstantPool.Utf8Constant;

/**
 * Definition for JVM class file methods.
 */
public final class MethodsDef extends CountingDef<MethodsDef> {

	/** Creates a new empty methods definition. */
	public MethodsDef() {
	}

	/**
	 * Add a method with the given access flags, name, descriptor, and body.
	 * @param accessFlag the access flags
	 * @param name the UTF-8 constant for the method name
	 * @param descriptor the UTF-8 constant for the method descriptor
	 * @param consumer a consumer that writes the method body
	 * @return this instance for chaining
	 */
	public MethodsDef add(int accessFlag, Utf8Constant name, Utf8Constant descriptor,
			Consumer<ByteCodeWriter> consumer) {
		return this.add(byteCode -> consumer.accept(byteCode.writeU2(accessFlag).writeU2(name).writeU2(descriptor)));
	}

}

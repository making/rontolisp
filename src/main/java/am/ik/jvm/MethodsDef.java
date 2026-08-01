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
		try {
			return this
				.add(byteCode -> consumer.accept(byteCode.writeU2(accessFlag).writeU2(name).writeU2(descriptor)));
		}
		catch (IllegalArgumentException invalid) {
			// Name the method: the limit checks below this point (the 65535-byte code
			// ceiling in ByteCodeWriter) see only the bytes, and a class-file-level
			// message without the culprit is undebuggable in a many-method class.
			byte[] utf8 = name.bytes();
			String methodName = utf8.length > 2
					? new String(utf8, 2, utf8.length - 2, java.nio.charset.StandardCharsets.UTF_8) : "?";
			throw new IllegalArgumentException("method " + methodName + ": " + invalid.getMessage(), invalid);
		}
	}

}

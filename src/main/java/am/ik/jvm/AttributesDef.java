package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

import am.ik.jvm.ConstantPool.Utf8Constant;

/**
 * Definition for JVM class file attributes.
 */
public final class AttributesDef extends CountingDef<AttributesDef> {

	/** Creates a new empty attributes definition. */
	public AttributesDef() {
	}

	/**
	 * Add an attribute with the given name and content.
	 * @param attributeName the UTF-8 constant for the attribute name
	 * @param consumer a consumer that writes the attribute content
	 * @return this instance for chaining
	 */
	public AttributesDef add(Utf8Constant attributeName, Consumer<ByteCodeWriter> consumer) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		final ByteCodeWriter out = new ByteCodeWriter(stream);
		consumer.accept(out);
		return this.add(attribute -> attribute.writeU2(attributeName)
			.writeU4(stream.size()) // attribute_length
			.write(stream.toByteArray()) // attribute
		);
	}

}

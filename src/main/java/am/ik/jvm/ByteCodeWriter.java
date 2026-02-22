package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.Constant;

/**
 * Writer for JVM class file bytecode.
 */
public class ByteCodeWriter {

	private final OutputStream out;

	/**
	 * Create a new writer that writes to the given output stream.
	 * @param out the target output stream
	 */
	public ByteCodeWriter(OutputStream out) {
		this.out = out;
	}

	/**
	 * Write a 4-byte unsigned integer.
	 * @param n the value to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeU4(int n) {
		try {
			this.out.write(ByteBuffer.allocate(4).putInt(n).array());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write a 2-byte unsigned integer from a short value.
	 * @param n the value to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeU2(short n) {
		try {
			this.out.write(ByteBuffer.allocate(2).putShort(n).array());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write a 2-byte unsigned integer from an int value.
	 * @param n the value to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeU2(int n) {
		return this.writeU2((short) n);
	}

	/**
	 * Write a 2-byte unsigned integer from a constant pool entry index.
	 * @param c the constant whose index is written
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeU2(Constant c) {
		return this.writeU2(c.index());
	}

	/**
	 * Write a UTF-8 string with its length prefix.
	 * @param s the string to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeUtf8Info(String s) {
		try {
			final int len = s.length();
			this.writeU2(len);
			out.write(s.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write a code attribute body with its length prefix.
	 * @param code the bytecode instructions to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeCode(Object... code) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		new ByteCodeWriter(stream).write(code);
		final byte[] bytes = stream.toByteArray();
		return this.writeU4(bytes.length).write(bytes);
	}

	/**
	 * Write raw bytes to the output.
	 * @param bytes the bytes to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter write(byte[] bytes) {
		try {
			out.write(bytes);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write a sequence of objects (Integer, Byte, byte[], or String) to the output.
	 * @param objects the objects to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter write(Object... objects) {
		try {
			for (Object o : objects) {
				if (o instanceof Integer) {
					out.write((Integer) o);
				}
				else if (o instanceof Byte) {
					out.write((Byte) o);
				}
				else if (o instanceof byte[]) {
					out.write((byte[]) o);
				}
				else if (o instanceof String) {
					out.write(((String) o).getBytes(StandardCharsets.UTF_8));
				}
				else {
					throw new IllegalStateException(o.getClass() + " is not supported");
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write the class file version (minor and major).
	 * @param minorVersion the minor version number
	 * @param majorVersion the major version number
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeVersion(int minorVersion, int majorVersion) {
		return this.writeU2(minorVersion).writeU2(majorVersion);
	}

	/**
	 * Write a constant pool to the output.
	 * @param constantPool the constant pool to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeConstantPool(ConstantPool constantPool) {
		return this.write(constantPool.toByteArray());
	}

	/**
	 * Write a constant pool built by the given consumer.
	 * @param consumer a consumer that populates the constant pool
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeConstantPool(Consumer<ConstantPool> consumer) {
		final ConstantPool constantPool = new ConstantPool();
		consumer.accept(constantPool);
		return this.writeConstantPool(constantPool);
	}

	/**
	 * Write the class declaration (access flags, this class, super class).
	 * @param accessFlag the access flags
	 * @param thisClass the class constant for this class
	 * @param superClass the class constant for the super class
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeClass(int accessFlag, ClassConstant thisClass, ClassConstant superClass) {
		return this.writeU2(accessFlag).writeU2(thisClass).writeU2(superClass);
	}

	/**
	 * Write the interfaces section.
	 * @param consumer a consumer that populates the interfaces
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeInterfaces(Consumer<CountingDef<?>> consumer) {
		final CountingDef<?> def = new CountingDef<>();
		return this.write(def.toByteArray());
	}

	/**
	 * Write the fields section.
	 * @param consumer a consumer that populates the fields
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeFields(Consumer<CountingDef<?>> consumer) {
		final CountingDef<?> def = new CountingDef<>();
		return this.write(def.toByteArray());
	}

	/**
	 * Write the methods section from a methods definition.
	 * @param methodsDef the methods definition to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeMethods(MethodsDef methodsDef) {
		return this.write(methodsDef.toByteArray());
	}

	/**
	 * Write the methods section built by the given consumer.
	 * @param consumer a consumer that populates the methods definition
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeMethods(Consumer<MethodsDef> consumer) {
		final MethodsDef methodsDef = new MethodsDef();
		consumer.accept(methodsDef);
		return this.writeMethods(methodsDef);
	}

	/**
	 * Write the attributes section from an attributes definition.
	 * @param attributesDef the attributes definition to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeAttributes(AttributesDef attributesDef) {
		return this.write(attributesDef.toByteArray());
	}

	/**
	 * Write the attributes section built by the given consumer.
	 * @param consumer a consumer that populates the attributes definition
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeAttributes(Consumer<AttributesDef> consumer) {
		final AttributesDef attributesDef = new AttributesDef();
		consumer.accept(attributesDef);
		return this.writeAttributes(attributesDef);
	}

}

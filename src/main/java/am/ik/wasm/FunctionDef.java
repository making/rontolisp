package am.ik.wasm;

/**
 * Definition for WASM function section entries.
 */
public class FunctionDef extends CountingDef<FunctionDef> {

	/** Creates a new empty function definition. */
	public FunctionDef() {
	}

	/**
	 * Add a function entry referencing a type signature.
	 * @param signatureIndex the index of the function type signature
	 * @return this instance for chaining
	 */
	public FunctionDef addFunction(int signatureIndex) {
		// The type index is a WASM u32 LEB128 field; a raw byte silently truncates
		// once a module needs 128 or more distinct function signatures, which
		// produces a section whose declared size is one byte short of its own
		// contents -- an invalid module that only shows up downstream.
		return this.add(function -> function.writeUnsignedLeb128(signatureIndex));
	}

}

package am.ik.wasm;

/**
 * Definition for WASM tag section entries (exception-handling proposal). Each tag is an
 * exception tag: an attribute byte (always {@code 0x00} = exception) followed by the
 * index of a function type describing the exception's parameters.
 */
public class TagDef extends CountingDef<TagDef> {

	/** Creates a new empty tag definition. */
	public TagDef() {
	}

	/**
	 * Add an exception tag over the given function type.
	 * @param typeIndex the index of the tag's function type (its params are the exception
	 * payload; the result list must be empty)
	 * @return this instance for chaining
	 */
	public TagDef addTag(int typeIndex) {
		return this.add(tag -> tag.write(0x00).writeUnsignedLeb128(typeIndex));
	}

}

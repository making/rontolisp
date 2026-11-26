package annotated;

import packed.Handling;

/** The classes an initializer may be handed: below its interface, or carrying its annotation. */
public class Plugins {

	public static class Direct implements Handling.Plugin {

	}

	/** Its file names its superclass alone. */
	public static class Indirect extends Direct {

	}

	public interface Extended extends Handling.Plugin {

	}

	public abstract static class ByExtended implements Extended {

	}

	@Handling.Marked
	public static class Marked {

	}

	public static class MarkedMethod {

		@Handling.Marked
		public void marked() {
		}

	}

	public static class MarkedField {

		@Handling.Marked
		public int marked;

	}

	public static class MarkedBelow extends Marked {

	}

}

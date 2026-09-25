package am.ik.rontolisp;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lookups {@link ClosRegistry} keeps between registrations -- the member-name indexes
 * behind {@link ClosRegistry#findClass} and the {@link ClosRegistry#descendantStructTags}
 * answers -- must follow every registration that can move them.
 */
class ClosRegistryTest {

	private static ClosRegistry.ClassInfo classNamed(String name) {
		return new ClosRegistry.ClassInfo(name, List.of(), List.of(name), List.of(), List.of(),
				Set.of(ClosRegistry.normalize(name)));
	}

	@Test
	void aMemberLookupFollowsEveryClassRegistration() {
		ClosRegistry registry = new ClosRegistry();
		assertThat(registry.findClass("WIDGET")).isNull();
		registry.registerClass(classNamed("PA::WIDGET"));
		assertThat(registry.findClass("WIDGET")).extracting(ClosRegistry.ClassInfo::name).isEqualTo("PA::WIDGET");
		assertThat(registry.findClass("PC::WIDGET")).extracting(ClosRegistry.ClassInfo::name).isEqualTo("PA::WIDGET");
		// A second package defining the member makes the bare spelling ambiguous.
		registry.registerClass(classNamed("PB::WIDGET"));
		assertThat(registry.findClass("WIDGET")).isNull();
		assertThat(registry.findClass("PC::WIDGET")).isNull();
		assertThat(registry.findClass("PB:WIDGET")).extracting(ClosRegistry.ClassInfo::name).isEqualTo("PB::WIDGET");
		// A redefinition replaces the entry the index answers with.
		ClosRegistry.ClassInfo redefined = new ClosRegistry.ClassInfo("PA::GADGET", List.of("PA::WIDGET"),
				List.of("PA::GADGET", "PA::WIDGET"), List.of(), List.of(), Set.of("PA::GADGET", "PA::WIDGET"));
		registry.registerClass(classNamed("PA::GADGET"));
		assertThat(registry.findClass("GADGET")).extracting(ClosRegistry.ClassInfo::ancestors)
			.isEqualTo(Set.of("PA::GADGET"));
		registry.registerClass(redefined);
		assertThat(registry.findClass("GADGET")).isSameAs(redefined);
	}

	@Test
	void anAliasMemberLookupFollowsEveryAliasRegistration() {
		ClosRegistry registry = new ClosRegistry();
		registry.registerClass(classNamed("PA::SOLO"));
		registry.registerClass(classNamed("PB::OTHER"));
		assertThat(registry.findClass("SOLO-ALIAS")).isNull();
		registry.registerClassAlias("PA::SOLO-ALIAS", "PA::SOLO");
		assertThat(registry.findClass("SOLO-ALIAS")).extracting(ClosRegistry.ClassInfo::name).isEqualTo("PA::SOLO");
		// Two packages aliasing the member make the bare spelling ambiguous.
		registry.registerClassAlias("PB::SOLO-ALIAS", "PB::OTHER");
		assertThat(registry.findClass("SOLO-ALIAS")).isNull();
		assertThat(registry.findClass("PB::SOLO-ALIAS")).extracting(ClosRegistry.ClassInfo::name)
			.isEqualTo("PB::OTHER");
	}

	@Test
	void descendantStructTagsFollowEveryStructRegistration() {
		ClosRegistry registry = new ClosRegistry();
		assertThat(registry.descendantStructTags("BASE")).isEmpty();
		registry.registerStruct("BASE", List.of("A"), List.of(LispNil.INSTANCE));
		assertThat(registry.descendantStructTags("BASE")).containsExactly("%struct-BASE");
		registry.registerStruct("DERIVED", "BASE", List.of("A", "B"), List.of(LispNil.INSTANCE, LispNil.INSTANCE));
		assertThat(registry.descendantStructTags("BASE")).containsExactly("%struct-BASE", "%struct-DERIVED");
		assertThat(registry.descendantStructTags("DERIVED")).containsExactly("%struct-DERIVED");
	}

}

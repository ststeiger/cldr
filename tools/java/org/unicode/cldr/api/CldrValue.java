package org.unicode.cldr.api;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.unicode.cldr.api.AttributeKey.AttributeSupplier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A CLDR element value and associated "value" attributes, along with its distinguishing {@link
 * CldrPath}.
 *
 * <p>In CLDR, a path contains attributes that are one of three types; "distinguishing", "value"
 * and "metadata", and a path can be parsed to extract value attributes.
 *
 * <p>CldrValue instance hold only the "value" attributes, with "distinguishing" attributes being
 * held by the associated {@link CldrPath}, and "metadata" attributes being ignored completely
 * since they are synthetic and internal to the core CLDR classes.
 *
 * <p>Note that while the ordering of "value" attributes is stable, it should not be relied upon.
 * Unlike "distinguishing" attributes in CldrPath, "value" attributes don't conceptually form a
 * sequence. It is expected that users will only lookup attribute values directly by their keys and
 * never care about their order.
 *
 * <p>CldrValue is an immutable value type with efficient equality semantics.
 *
 * <p>See <a href="https://www.unicode.org/reports/tr35/#Definitions">the LDML specification</a>
 * for more details.
 */
public final class CldrValue implements AttributeSupplier {
    /**
     * Parses a full CLDR path string, possibly containing "distinguishing", "value" and even
     * private "metadata" attributes into a normalized CldrValue instance. Attributes will be parsed
     * and handled according to their type:
     * <ul>
     * <li>Value attributes will be added to the returned CldrValue instance.
     * <li>Distinguishing attributes will be added to the associated CldrPath instance.
     * <li>Other non-public attributes will be ignored.
     * </ul>
     *
     * <p>The path string must be structured correctly (e.g. "//ldml/foo[@bar="baz]") and must
     * represent a known DTD type, based on the first path element (e.g. "//ldml/...").
     *
     * @param fullPath the full path string, possibly containing all types of attribute.
     * @param value the primary leaf value associated with the path (possibly empty).
     * @return the parsed value instance, referencing the associated distinguishing path.
     * @throws IllegalArgumentException if the path is not well formed.
     */
    public static CldrValue parseValue(String fullPath, String value) {
        LinkedHashMap<AttributeKey, String> valueAttributes = new LinkedHashMap<>();
        CldrPath path = CldrPaths.processXPath(fullPath, ImmutableList.of(), valueAttributes::put);
        return new CldrValue(value, valueAttributes, path);
    }

    // Note: If this is ever made public, it should be modified to enforce attribute order
    // according to the DTD. It works now because the code calling it handles ordering correctly.
    static CldrValue create(String value, Map<AttributeKey, String> valueAttributes, CldrPath path) {
        return new CldrValue(value, valueAttributes, path);
    }

    private final String value;
    private final ImmutableMap<AttributeKey, String> attributes;
    private final CldrPath path;
    // Cached to avoid repeated recalculation from the map (which cannot cache its hash code).
    private final int hashCode;

    private CldrValue(String value, Map<AttributeKey, String> attributes, CldrPath path) {
        // Empty value strings are permitted (but null is not).
        this.value = checkNotNull(value);
        this.attributes = checkAttributeMap(attributes);
        this.path = checkNotNull(path);
        this.hashCode = Objects.hash(value, this.attributes, path);
    }

    private static ImmutableMap<AttributeKey, String> checkAttributeMap(
        Map<AttributeKey, String> attributes) {
        // Keys are checked on creation, but values need to be checked.
        for (String v : attributes.values()) {
            checkArgument(!v.contains("\""), "unsupported '\"' in attribute value: %s", v);
        }
        return ImmutableMap.copyOf(attributes);
    }

    /**
     * Returns the primary (non-attribute) CLDR value associated with a distinguishing path. For a
     * CLDR element with no explicitly associated value, an empty string is returned.
     *
     * @return the primary value of this CLDR value instance.
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns the raw value of an attribute associated with this CLDR value or distinguishing
     * path, or null if not present. For almost all use cases it is preferable to use the accessor
     * methods on the {@link AttributeKey} class, which provide additional useful semantic checking
     * and common type conversion. You should only use this method directly if there's a strong
     * performance requirement.
     *
     * @param key the key identifying an attribute.
     * @return the attribute value or {@code null} if not present.
     * @see AttributeKey
     */
    @Override
    /* @Nullable */ public String get(AttributeKey key) {
        if (getPath().getDataType().isValueAttribute(key)) {
            return attributes.get(key);
        }
        return getPath().get(key);
    }

    /**
     * Returns the data type for this value, as defined by its path.
     *
     * @return the value's data type.
     */
    @Override
    public CldrDataType getDataType() {
        return getPath().getDataType();
    }

    /**
     * Returns the "value" attributes associated with this value. Attribute ordering is stable,
     * with attributes from earlier path elements preceding attributes for later ones. However it
     * is recommended that callers avoid relying on specific ordering semantics and always look up
     * attribute values by key if possible.
     *
     * @return a map of the value attributes for this CLDR value instance.
     */
    // TODO: This is used in only two places outside this package, so consider making it non-public.
    public ImmutableMap<AttributeKey, String> getValueAttributes() {
        return attributes;
    }

    /**
     * Returns the CldrPath associated with this value. All value instances are associated with
     * a distinguishing path.
     */
    public CldrPath getPath() {
        return path;
    }

    /**
     * Returns a combined full path string in the XPath style {@code //foo/bar[@x="y"]/baz},
     * with value attributes inserted in correct DTD order for each path element.
     *
     * <p>Note that while in most cases the values attributes simply follow the path attributes on
     * each element, this is not necessarily always true, and DTD ordering can place value
     * attributes before path attributes in an element.
     *
     * @return the full XPath representation containing both distinguishing and value attributes.
     */
    public String getFullPath() {
        return getPath().getFullPath(this);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof CldrValue)) {
            return false;
        }
        CldrValue other = (CldrValue) obj;
        return this.path.equals(other.path)
            && this.value.equals(other.value)
            && this.attributes.equals(other.attributes);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return hashCode;
    }

    /** @return a debug-only representation of this CLDR value. */
    @Override
    public String toString() {
        if (value.isEmpty()) {
            return String.format("attributes=%s, path=%s", attributes, path);
        } else if (attributes.isEmpty()) {
            return String.format("value=\"%s\", path=%s", value, path);
        } else {
            return String.format("value=\"%s\", attributes=%s, path=%s", value, attributes, path);
        }
    }
}

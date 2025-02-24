package org.unicode.cldr.api;

import com.google.common.collect.Lists;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.CldrUtility;
import org.unicode.cldr.util.XPathParts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Serializes a CLDRFile as a sequence of {@code (CldrPath, CldrValue)} pairs.
 *
 * <p>Ordering affects the cost of processing the CLDRFile, but not excessively. In testing, the
 * resolved paths for "en_GB" took ~65ms to process in ARBITRARY order, and ~340ms for DTD order.
 * It's expected that compared to producing these paths, any matching and processing of path
 * elements will be the most significant issue.
 */
final class CldrFileDataSource extends AbstractDataSource {
    private static final Pattern CAPTURE_SORT_INDEX = Pattern.compile("#[0-9]+");

    private final CLDRFile source;

    CldrFileDataSource(CLDRFile source) {
        this.source = checkNotNull(source);
    }

    @Override
    public void accept(PathOrder order, ValueVisitor visitor) {
        Iterator<String> paths;
        switch (order) {
        case ARBITRARY:
            paths = source.iterator();
            break;

        case NESTED_GROUPING:
            // Distinguishing paths when sorted by string order should yield "nested grouping".
            // This is because lexicographical order is determined by the earliest character
            // difference, which either occurs in the element name or the attribute declaration.
            // Either way, the string before the first difference will agree on zero or more
            // complete path elements and order is always decided by a change to the lowest path
            // element. This should therefore result in common parent prefixes always being visited
            // consecutively. It also (like DTD ordering) greatly improves the performance when
            // parsing paths because consecutive paths share common parent elements.
            paths = source.iterator(null, Comparator.naturalOrder());
            break;

        case DTD:
            paths = source.iterator(null, source.getComparator());
            break;

        default:
            throw new AssertionError("Unknown path ordering: " + order);
        }
        read(paths, source, visitor);
    }

    @Override
    /* @Nullable */
    public CldrValue get(CldrPath path) {
        String xpath = getInternalPathString(path);
        XPathParts fullPath = XPathParts.getFrozenInstance(source.getFullXPath(xpath));
        int length = fullPath.size();
        Map<AttributeKey, String> attributes = new LinkedHashMap<>();
        for (int n = 0; n < length; n++) {
            CldrPaths.processPathAttributes(
                fullPath.getElement(n),
                fullPath.getAttributes(n),
                path.getDataType(),
                e -> {},
                attributes::put);
        }
        // This is MUCH faster if you pass the distinguishing path in.
        return CldrValue.create(source.getStringValue(xpath), attributes, path);
    }

    private static String getInternalPathString(CldrPath p) {
        // This is the distinguishing xpath, but possibly with a sort index present (e.g.
        // foo#42[@bar="x"]). So to get the internal path as used by CLDRFile, we must convert '#N'
        // into '[@_q="N"]'
        String dpath = p.toString();
        if (dpath.indexOf('#') != -1) {
            dpath = CAPTURE_SORT_INDEX.matcher(dpath).replaceAll("[@_q=\"$1\"]");
        }
        return dpath;
    }

    private void read(Iterator<String> paths, CLDRFile src, ValueVisitor visitor) {
        Map<AttributeKey, String> valueAttributes = new LinkedHashMap<>();

        // This is a bit fiddly since we add path elements in reverse order to the 'stack' but want
        // to access them using the path element index. E.g. if we add the path a->b->c->d to the
        // stack we get "(d,c,b,a)" in the array, but really want "(a,b,c,d)" to avoid having to
        // use recursion or other tricks to reverse the order of addition, we can just create a
        // reversed _view_ onto the list and pass that around. We could just insert the elements at
        // the front of the array (rather than adding them at the end) but that means repeated
        // copying of existing elements to make room, so it's slower.
        //
        // This has the path elements pushed into it in reverse order.
        List<CldrPath> previousElementStack = new ArrayList<>();
        // This views the path elements in forward order.
        List<CldrPath> previousElements = Lists.reverse(previousElementStack);

        while (paths.hasNext()) {
            String dPath = paths.next();
            // This is MUCH faster if you pass the distinguishing path in.
            String value = src.getStringValue(dPath);

            // Since early 2019 there's been the possibility of getting the inheritance marker as
            // a value for a path. This indicates that the value does NOT actually exist for a
            // locale and would be inherited. According to the CldrUtility class:
            // ""If CLDRFile ever finds this value in a data field, writing of the field should be
            // suppressed.""
            // TODO: Remove this null check once everything is settled (null should not be possible)
            if (value == null || value.equals(CldrUtility.INHERITANCE_MARKER)) {
                continue;
            }

            // There's a cache behind XPathParts which probably makes it faster to lookup these
            // instances rather than parse them each time (it all depends on whether this is the
            // first time the full paths are used).
            CldrPath cldrPath = CldrPaths.processXPath(
                src.getFullXPath(dPath), previousElements, valueAttributes::put);

            if (CldrPaths.isLeafPath(cldrPath) && CldrPaths.shouldEmit(cldrPath)) {

                safeVisit(CldrValue.create(value, valueAttributes, cldrPath), visitor);
            }

            // Prepare the element stack for next time by pushing the current path onto it.
            pushPathElements(cldrPath, previousElementStack);

            valueAttributes.clear();
        }
    }

    /**
     * Pushes the elements of the given path into the list. This is efficient but results in the
     * list order being reversed (e.g. path "a->b->c->d" results in "(d,c,b,a)". A reversed view
     * of this stack is used to present the path elements in "forward order".
     */
    private static void pushPathElements(CldrPath cldrPath, List<CldrPath> stack) {
        stack.clear();
        for (CldrPath p = cldrPath; p != null; p = p.getParent()) {
            stack.add(p);
        }
    }
}

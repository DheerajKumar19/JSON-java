package org.json;

/*
Public Domain.
*/

import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * This provides static methods to convert an XML text into a JSONObject, and to
 * covert a JSONObject into an XML text.
 *
 * @author JSON.org
 * @version 2016-08-10
 */
@SuppressWarnings("boxing")
public class XML {

    /**
     * The Character '&amp;'.
     */
    public static final Character AMP = '&';

    /**
     * The Character '''.
     */
    public static final Character APOS = '\'';

    /**
     * The Character '!'.
     */
    public static final Character BANG = '!';

    /**
     * The Character '='.
     */
    public static final Character EQ = '=';

    /**
     * The Character <pre>{@code '>'. }</pre>
     */
    public static final Character GT = '>';

    /**
     * The Character '&lt;'.
     */
    public static final Character LT = '<';

    /**
     * The Character '?'.
     */
    public static final Character QUEST = '?';

    /**
     * The Character '"'.
     */
    public static final Character QUOT = '"';

    /**
     * The Character '/'.
     */
    public static final Character SLASH = '/';

    /**
     * Null attribute name
     */
    public static final String NULL_ATTR = "xsi:nil";

    public static final String TYPE_ATTR = "xsi:type";

    /**
     * Creates an iterator for navigating Code Points in a string instead of
     * characters. Once Java7 support is dropped, this can be replaced with
     * <code>
     * string.codePoints()
     * </code>
     * which is available in Java8 and above.
     *
     * @see <a href=
     * "http://stackoverflow.com/a/21791059/6030888">http://stackoverflow.com/a/21791059/6030888</a>
     */
    private static Iterable<Integer> codePointIterator(final String string) {
        return new Iterable<Integer>() {
            @Override
            public Iterator<Integer> iterator() {
                return new Iterator<Integer>() {
                    private int nextIndex = 0;
                    private int length = string.length();

                    @Override
                    public boolean hasNext() {
                        return this.nextIndex < this.length;
                    }

                    @Override
                    public Integer next() {
                        int result = string.codePointAt(this.nextIndex);
                        this.nextIndex += Character.charCount(result);
                        return result;
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    /**
     * Replace special characters with XML escapes:
     *
     * <pre>{@code
     * &amp; (ampersand) is replaced by &amp;amp;
     * &lt; (less than) is replaced by &amp;lt;
     * &gt; (greater than) is replaced by &amp;gt;
     * &quot; (double quote) is replaced by &amp;quot;
     * &apos; (single quote / apostrophe) is replaced by &amp;apos;
     * }</pre>
     *
     * @param string The string to be escaped.
     * @return The escaped string.
     */
    public static String escape(String string) {
        StringBuilder sb = new StringBuilder(string.length());
        for (final int cp : codePointIterator(string)) {
            switch (cp) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    if (mustEscape(cp)) {
                        sb.append("&#x");
                        sb.append(Integer.toHexString(cp));
                        sb.append(';');
                    } else {
                        sb.appendCodePoint(cp);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * @param cp code point to test
     * @return true if the code point is not valid for an XML
     */
    private static boolean mustEscape(int cp) {
        /* Valid range from https://www.w3.org/TR/REC-xml/#charsets
         *
         * #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
         *
         * any Unicode character, excluding the surrogate blocks, FFFE, and FFFF.
         */
        // isISOControl is true when (cp >= 0 && cp <= 0x1F) || (cp >= 0x7F && cp <= 0x9F)
        // all ISO control characters are out of range except tabs and new lines
        return (Character.isISOControl(cp)
                && cp != 0x9
                && cp != 0xA
                && cp != 0xD
        ) || !(
                // valid the range of acceptable characters that aren't control
                (cp >= 0x20 && cp <= 0xD7FF)
                        || (cp >= 0xE000 && cp <= 0xFFFD)
                        || (cp >= 0x10000 && cp <= 0x10FFFF)
        )
                ;
    }

    /**
     * Removes XML escapes from the string.
     *
     * @param string string to remove escapes from
     * @return string with converted entities
     */
    public static String unescape(String string) {
        StringBuilder sb = new StringBuilder(string.length());
        for (int i = 0, length = string.length(); i < length; i++) {
            char c = string.charAt(i);
            if (c == '&') {
                final int semic = string.indexOf(';', i);
                if (semic > i) {
                    final String entity = string.substring(i + 1, semic);
                    sb.append(XMLTokener.unescapeEntity(entity));
                    // skip past the entity we just parsed.
                    i += entity.length() + 1;
                } else {
                    // this shouldn't happen in most cases since the parser
                    // errors on unclosed entries.
                    sb.append(c);
                }
            } else {
                // not part of an entity
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Throw an exception if the string contains whitespace. Whitespace is not
     * allowed in tagNames and attributes.
     *
     * @param string A string.
     * @throws JSONException Thrown if the string contains whitespace or is empty.
     */
    public static void noSpace(String string) throws JSONException {
        int i, length = string.length();
        if (length == 0) {
            throw new JSONException("Empty string.");
        }
        for (i = 0; i < length; i += 1) {
            if (Character.isWhitespace(string.charAt(i))) {
                throw new JSONException("'" + string
                        + "' contains a space character.");
            }
        }
    }

    /**
     * Scan the content following the named tag, attaching it to the context.
     *
     * @param x       The XMLTokener containing the source string.
     * @param context The JSONObject that will include the new material.
     * @param name    The tag name.
     * @return true if the close tag is processed.
     * @throws JSONException
     */
    private static boolean parse(XMLTokener x, JSONObject context, String name, XMLParserConfiguration config,
                                 Function add_prefix) throws JSONException, ParseTerminationException {
        char c;
        int i;
        JSONObject jsonObject = null;
        String string;
        String tagName;
        Object token;
        XMLXsiTypeConverter<?> xmlXsiTypeConverter;

        // Test for and skip past these forms:
        // <!-- ... -->
        // <! ... >
        // <![ ... ]]>
        // <? ... ?>
        // Report errors for these forms:
        // <>
        // <=
        // <<

        token = x.nextToken();

        // <!

        if (token == BANG) {
            c = x.next();
            if (c == '-') {
                if (x.next() == '-') {
                    x.skipPast("-->");
                    return false;
                }
                x.back();
            } else if (c == '[') {
                token = x.nextToken();
                if ("CDATA".equals(token)) {
                    if (x.next() == '[') {
                        string = x.nextCDATA();
                        if (string.length() > 0) {
                            context.accumulate((String) add_prefix.apply(config.getcDataTagName()), string);
                        }
                        return false;
                    }
                }
                throw x.syntaxError("Expected 'CDATA['");
            }
            i = 1;
            do {
                token = x.nextMeta();
                if (token == null) {
                    throw x.syntaxError("Missing '>' after '<!'.");
                } else if (token == LT) {
                    i += 1;
                } else if (token == GT) {
                    i -= 1;
                }
            } while (i > 0);
            return false;
        } else if (token == QUEST) {

            // <?
            x.skipPast("?>");
            return false;
        } else if (token == SLASH) {

            // Close tag </

            token = x.nextToken();
            if (name == null) {
                throw x.syntaxError("Mismatched close tag " + token);
            }
            if (!token.equals(name)) {
                throw x.syntaxError("Mismatched " + name + " and " + token);
            }
            if (x.nextToken() != GT) {
                throw x.syntaxError("Misshaped close tag");
            }
            return true;

        } else if (token instanceof Character) {
            throw x.syntaxError("Misshaped tag");

            // Open tag <

        } else {
            tagName = (String) token;
            token = null;
            jsonObject = new JSONObject();
            boolean nilAttributeFound = false;
            xmlXsiTypeConverter = null;
            for (; ; ) {
                if (token == null) {
                    token = x.nextToken();
                }
                // attribute = value
                if (token instanceof String) {
                    string = (String) token;
                    token = x.nextToken();
                    if (token == EQ) {
                        token = x.nextToken();
                        if (!(token instanceof String)) {
                            throw x.syntaxError("Missing value");
                        }

                        if (config.isConvertNilAttributeToNull()
                                && NULL_ATTR.equals(string)
                                && Boolean.parseBoolean((String) token)) {
                            nilAttributeFound = true;
                        } else if (config.getXsiTypeMap() != null && !config.getXsiTypeMap().isEmpty()
                                && TYPE_ATTR.equals(string)) {
                            xmlXsiTypeConverter = config.getXsiTypeMap().get(token);
                        } else if (!nilAttributeFound) {
                            jsonObject.accumulate((String) add_prefix.apply(string),
                                    config.isKeepStrings()
                                            ? ((String) token)
                                            : stringToValue((String) token));
                        }
                        token = null;
                    } else {
                        jsonObject.accumulate((String) add_prefix.apply(string), "");
                    }


                } else if (token == SLASH) {
                    // Empty tag <.../>
                    if (x.nextToken() != GT) {
                        throw x.syntaxError("Misshaped tag");
                    }
                    if (config.getForceList().contains(tagName)) {
                        // Force the value to be an array
                        if (nilAttributeFound) {
                            context.append((String) add_prefix.apply(tagName), JSONObject.NULL);
                        } else if (jsonObject.length() > 0) {
                            context.append((String) add_prefix.apply(tagName), jsonObject);
                        } else {
                            context.put((String) add_prefix.apply(tagName), new JSONArray());
                        }
                    } else {
                        if (nilAttributeFound) {
                            context.accumulate((String) add_prefix.apply(tagName), JSONObject.NULL);
                        } else if (jsonObject.length() > 0) {
                            context.accumulate((String) add_prefix.apply(tagName), jsonObject);
                        } else {
                            context.accumulate((String) add_prefix.apply(tagName), "");
                        }
                    }
                    return false;

                } else if (token == GT) {
                    // Content, between <...> and </...>
                    for (; ; ) {
                        token = x.nextContent();
                        if (token == null) {
                            if (tagName != null) {
                                throw x.syntaxError("Unclosed tag " + tagName);
                            }
                            return false;
                        } else if (token instanceof String) {
                            string = (String) token;
                            if (string.length() > 0) {
                                if (xmlXsiTypeConverter != null) {
                                    jsonObject.accumulate(config.getcDataTagName(),
                                            stringToValue(string, xmlXsiTypeConverter));
                                } else {
                                    jsonObject.accumulate(config.getcDataTagName(),
                                            config.isKeepStrings() ? string : stringToValue(string));
                                }
                            }

                        } else if (token == LT) {
                            // Nested element
                            if (parse(x, jsonObject, tagName, config, add_prefix)) {
                                if (config.getForceList().contains(tagName)) {
                                    // Force the value to be an array
                                    if (jsonObject.length() == 0) {
                                        context.put((String) add_prefix.apply(tagName), new JSONArray());
                                    } else if (jsonObject.length() == 1
                                            && jsonObject.opt(config.getcDataTagName()) != null) {
                                        context.append((String) add_prefix.apply(tagName), jsonObject.opt(config.getcDataTagName()));
                                    } else {
                                        context.append((String) add_prefix.apply(tagName), jsonObject);
                                    }
                                } else {
                                    if (jsonObject.length() == 0) {
                                        context.accumulate((String) add_prefix.apply(tagName), "");
                                    } else if (jsonObject.length() == 1
                                            && jsonObject.opt(config.getcDataTagName()) != null) {
                                        context.accumulate((String) add_prefix.apply(tagName), jsonObject.opt(config.getcDataTagName()));
                                    } else {
                                        context.accumulate((String) add_prefix.apply(tagName), jsonObject);
                                    }
                                }

                                return false;
                            }
                        }
                    }
                } else {
                    throw x.syntaxError("Misshaped tag");
                }
            }
        }
    }

    /**
     * This method tries to convert the given string value to the target object
     *
     * @param string        String to convert
     * @param typeConverter value converter to convert string to integer, boolean e.t.c
     * @return JSON value of this string or the string
     */
    public static Object stringToValue(String string, XMLXsiTypeConverter<?> typeConverter) {
        if (typeConverter != null) {
            return typeConverter.convert(string);
        }
        return stringToValue(string);
    }

    /**
     * This method is the same as {@link JSONObject#stringToValue(String)}.
     *
     * @param string String to convert
     * @return JSON value of this string or the string
     */
    // To maintain compatibility with the Android API, this method is a direct copy of
    // the one in JSONObject. Changes made here should be reflected there.
    // This method should not make calls out of the XML object.
    public static Object stringToValue(String string) {
        if ("".equals(string)) {
            return string;
        }

        // check JSON key words true/false/null
        if ("true".equalsIgnoreCase(string)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(string)) {
            return Boolean.FALSE;
        }
        if ("null".equalsIgnoreCase(string)) {
            return JSONObject.NULL;
        }

        /*
         * If it might be a number, try converting it. If a number cannot be
         * produced, then the value will just be a string.
         */

        char initial = string.charAt(0);
        if ((initial >= '0' && initial <= '9') || initial == '-') {
            try {
                return stringToNumber(string);
            } catch (Exception ignore) {
            }
        }
        return string;
    }

    /**
     * direct copy of {@link JSONObject#stringToNumber(String)} to maintain Android support.
     */
    private static Number stringToNumber(final String val) throws NumberFormatException {
        char initial = val.charAt(0);
        if ((initial >= '0' && initial <= '9') || initial == '-') {
            // decimal representation
            if (isDecimalNotation(val)) {
                // Use a BigDecimal all the time so we keep the original
                // representation. BigDecimal doesn't support -0.0, ensure we
                // keep that by forcing a decimal.
                try {
                    BigDecimal bd = new BigDecimal(val);
                    if (initial == '-' && BigDecimal.ZERO.compareTo(bd) == 0) {
                        return Double.valueOf(-0.0);
                    }
                    return bd;
                } catch (NumberFormatException retryAsDouble) {
                    // this is to support "Hex Floats" like this: 0x1.0P-1074
                    try {
                        Double d = Double.valueOf(val);
                        if (d.isNaN() || d.isInfinite()) {
                            throw new NumberFormatException("val [" + val + "] is not a valid number.");
                        }
                        return d;
                    } catch (NumberFormatException ignore) {
                        throw new NumberFormatException("val [" + val + "] is not a valid number.");
                    }
                }
            }
            // block items like 00 01 etc. Java number parsers treat these as Octal.
            if (initial == '0' && val.length() > 1) {
                char at1 = val.charAt(1);
                if (at1 >= '0' && at1 <= '9') {
                    throw new NumberFormatException("val [" + val + "] is not a valid number.");
                }
            } else if (initial == '-' && val.length() > 2) {
                char at1 = val.charAt(1);
                char at2 = val.charAt(2);
                if (at1 == '0' && at2 >= '0' && at2 <= '9') {
                    throw new NumberFormatException("val [" + val + "] is not a valid number.");
                }
            }
            // integer representation.
            // This will narrow any values to the smallest reasonable Object representation
            // (Integer, Long, or BigInteger)

            // BigInteger down conversion: We use a similar bitLength compare as
            // BigInteger#intValueExact uses. Increases GC, but objects hold
            // only what they need. i.e. Less runtime overhead if the value is
            // long lived.
            BigInteger bi = new BigInteger(val);
            if (bi.bitLength() <= 31) {
                return Integer.valueOf(bi.intValue());
            }
            if (bi.bitLength() <= 63) {
                return Long.valueOf(bi.longValue());
            }
            return bi;
        }
        throw new NumberFormatException("val [" + val + "] is not a valid number.");
    }

    /**
     * direct copy of {@link JSONObject#isDecimalNotation(String)} to maintain Android support.
     */
    private static boolean isDecimalNotation(final String val) {
        return val.indexOf('.') > -1 || val.indexOf('e') > -1
                || val.indexOf('E') > -1 || "-0".equals(val);
    }

    /**
     * Convert a well-formed (but not necessarily valid) XML string into a
     * JSONObject. Some information may be lost in this transformation because
     * JSON is a data format and XML is a document format. XML uses elements,
     * attributes, and content text, while JSON uses unordered collections of
     * name/value pairs and arrays of values. JSON does not does not like to
     * distinguish between elements and attributes. Sequences of similar
     * elements are represented as JSONArrays. Content text may be placed in a
     * "content" member. Comments, prologs, DTDs, and <pre>{@code
     * &lt;[ [ ]]>}</pre>
     * are ignored.
     *
     * @param string The source string.
     * @return A JSONObject containing the structured data from the XML string.
     * @throws JSONException Thrown if there is an errors while parsing the string
     */
    public static JSONObject toJSONObject(String string) throws JSONException {
        return toJSONObject(string, XMLParserConfiguration.ORIGINAL);
    }

    /**
     * Convert a well-formed (but not necessarily valid) XML into a
     * JSONObject. Some information may be lost in this transformation because
     * JSON is a data format and XML is a document format. XML uses elements,
     * attributes, and content text, while JSON uses unordered collections of
     * name/value pairs and arrays of values. JSON does not does not like to
     * distinguish between elements and attributes. Sequences of similar
     * elements are represented as JSONArrays. Content text may be placed in a
     * "content" member. Comments, prologs, DTDs, and <pre>{@code
     * &lt;[ [ ]]>}</pre>
     * are ignored.
     *
     * @param reader The XML source reader.
     * @return A JSONObject containing the structured data from the XML string.
     * @throws JSONException Thrown if there is an errors while parsing the string
     */
    public static JSONObject toJSONObject(Reader reader) throws JSONException {
        return toJSONObject(reader, XMLParserConfiguration.ORIGINAL);
    }

    /**
     * Convert a well-formed (but not necessarily valid) XML into a
     * JSONObject. Some information may be lost in this transformation because
     * JSON is a data format and XML is a document format. XML uses elements,
     * attributes, and content text, while JSON uses unordered collections of
     * name/value pairs and arrays of values. JSON does not does not like to
     * distinguish between elements and attributes. Sequences of similar
     * elements are represented as JSONArrays. Content text may be placed in a
     * "content" member. Comments, prologs, DTDs, and <pre>{@code
     * &lt;[ [ ]]>}</pre>
     * are ignored.
     * <p>
     * All values are converted as strings, for 1, 01, 29.0 will not be coerced to
     * numbers but will instead be the exact value as seen in the XML document.
     *
     * @param reader      The XML source reader.
     * @param keepStrings If true, then values will not be coerced into boolean
     *                    or numeric values and will instead be left as strings
     * @return A JSONObject containing the structured data from the XML string.
     * @throws JSONException Thrown if there is an errors while parsing the string
     */
    public static JSONObject toJSONObject(Reader reader, boolean keepStrings) throws JSONException {
        if (keepStrings) {
            return toJSONObject(reader, XMLParserConfiguration.KEEP_STRINGS);
        }
        return toJSONObject(reader, XMLParserConfiguration.ORIGINAL);
    }

    /**
     * Convert a well-formed (but not necessarily valid) XML into a
     * JSONObject. Some information may be lost in this transformation because
     * JSON is a data format and XML is a document format. XML uses elements,
     * attributes, and content text, while JSON uses unordered collections of
     * name/value pairs and arrays of values. JSON does not does not like to
     * distinguish between elements and attributes. Sequences of similar
     * elements are represented as JSONArrays. Content text may be placed in a
     * "content" member. Comments, prologs, DTDs, and <pre>{@code
     * &lt;[ [ ]]>}</pre>
     * are ignored.
     * <p>
     * All values are converted as strings, for 1, 01, 29.0 will not be coerced to
     * numbers but will instead be the exact value as seen in the XML document.
     *
     * @param reader The XML source reader.
     * @param config Configuration options for the parser
     * @return A JSONObject containing the structured data from the XML string.
     * @throws JSONException Thrown if there is an errors while parsing the string
     */
    public static JSONObject toJSONObject(Reader reader, XMLParserConfiguration config) throws JSONException {
        JSONObject jo = new JSONObject();
        XMLTokener x = new XMLTokener(reader);
        Function<String, String> func = a -> a;
        while (x.more()) {
            x.skipPast("<");
            if (x.more()) {
                parse(x, jo, null, config, func);
            }
        }
        return jo;
    }

    /**
     * Convert a well-formed (but not necessarily valid) XML string into a
     * JSONObject. Some information may be lost in this transformation because
     * JSON is a data format and XML is a document format. XML uses elements,
     * attributes, and content text, while JSON uses unordered collections of
     * name/value pairs and arrays of values. JSON does not does not like to
     * distinguish between elements and attributes. Sequences of similar
     * elements are represented as JSONArrays. Content text may be placed in a
     * "content" member. Comments, prologs, DTDs, and <pre>{@code
     * &lt;[ [ ]]>}</pre>
     * are ignored.
     * <p>
     * All values are converted as strings, for 1, 01, 29.0 will not be coerced to
     * numbers but will instead be the exact value as seen in the XML document.
     *
     * @param string      The source string.
     * @param keepStrings If true, then values will not be coerced into boolean
     *                    or numeric values and will instead be left as strings
     * @return A JSONObject containing the structured data from the XML string.
     * @throws JSONException Thrown if there is an errors while parsing the string
     */
    public static JSONObject toJSONObject(String string, boolean keepStrings) throws JSONException {
        return toJSONObject(new StringReader(string), keepStrings);
    }

    /**
     * Convert a well-formed (but not necessarily valid) XML string into a
     * JSONObject. Some information may be lost in this transformation because
     * JSON is a data format and XML is a document format. XML uses elements,
     * attributes, and content text, while JSON uses unordered collections of
     * name/value pairs and arrays of values. JSON does not does not like to
     * distinguish between elements and attributes. Sequences of similar
     * elements are represented as JSONArrays. Content text may be placed in a
     * "content" member. Comments, prologs, DTDs, and <pre>{@code
     * &lt;[ [ ]]>}</pre>
     * are ignored.
     * <p>
     * All values are converted as strings, for 1, 01, 29.0 will not be coerced to
     * numbers but will instead be the exact value as seen in the XML document.
     *
     * @param string The source string.
     * @param config Configuration options for the parser.
     * @return A JSONObject containing the structured data from the XML string.
     * @throws JSONException Thrown if there is an errors while parsing the string
     */

    /***************   SWE 262 P MileStone 2  Start Here ****************/

    public static JSONObject toJSONObject(String string, XMLParserConfiguration config) throws JSONException {
        return toJSONObject(new StringReader(string), config);
    }

    /**
     * Extract a sub-object from a well-formed (but not necessarily valid) XML string on a certain key path and convert into a JSONObject.
     * If the path is invalid, it returns null, else it returns a sub-object JSONObject
     *
     * @param reader The XML source reader
     * @param path   The JSONPointer with specific key path
     * @return A JSONObject containing the structured data from the part corresponding to the path in the XML file.
     * @throws JSONException Thrown if there is an error while parsing the XML
     */
    public static JSONObject toJSONObject(Reader reader, JSONPointer path) throws JSONException {
        // Get a split path
        String[] uri = get_uri(path);
        if (uri.length == 0)            //get_uri副程式當中，如果path是無效的(結尾是'/')就return null
        {
            return null;
        }

        JSONObject jo = new JSONObject();
        XMLTokener x = new XMLTokener(reader);
        XMLParserConfiguration config = XMLParserConfiguration.ORIGINAL;

        try {
            while (x.more()) {
                x.skipPast("<");
                if (x.more()) {
                    parse(x, jo, null, config, uri, new JSONObject(), 0, new HashMap<String, Integer>(), true);
                }
            }
        } catch (ParseTerminationException e) {
            return new JSONObject(e.getMessage());
        } catch (JSONException e) {
            throw new JSONException("parse function error");
        }
        return null;
    }

    /**
     * Convert a well-formed (but not necessarily valid) XML into a JSONObject and replace the value of a specific key path.
     * If the path is invalid it returns unmodified JSONObject, else it returns modified JSONObject with replacement
     *
     * @param reader      The XML source reader
     * @param path        The JSONPointer with specific key path
     * @param replacement The object that is going to replace the value of key path
     * @return A JSONObject containing the structured data from the XML string with/without replacing the value of key path
     * @throws JSONException Thrown if there is an error while parsing the string and certain path not found
     */
    public static JSONObject toJSONObject(Reader reader, JSONPointer path, JSONObject replacement) throws JSONException {
        // Get a split path
        String[] uri = get_uri(path);

        JSONObject jo = new JSONObject();
        XMLTokener x = new XMLTokener(reader);
        XMLParserConfiguration config = XMLParserConfiguration.ORIGINAL;

        // If the path is invalid or it's empty we use the existing parse(), else we use our customize parse()
        try {
            if (uri.length == 0) {
                jo = toJSONObject(reader, config);
            } else {
                while (x.more()) {
                    x.skipPast("<");
                    if (x.more()) {
                        parse(x, jo, null, config, uri, replacement, 0, new HashMap<String, Integer>(), false);
                    }
                }
            }
        } catch (JSONException e) {
            throw new JSONException("parse function error");
        }
        return jo;
    }

    /***************   SWE 262 P MileStone 2  Our Code Ends Here ****************/

    /***************   SWE 262 P MileStone 3  Start Here ****************/

    public static JSONObject toJSONObject(Reader reader, Function add_prefix) throws JSONException {
        if (add_prefix.apply("test") == null ||                                       //prefix=null
                add_prefix.apply("test").equals("test") ||                          //prefix=""
                add_prefix.apply("test1").equals(add_prefix.apply("test2")))     //prefix are same
        {
            return null;
        }

        JSONObject jo = new JSONObject();
        XMLTokener x = new XMLTokener(reader);
        XMLParserConfiguration config = XMLParserConfiguration.ORIGINAL;

        try {
            while (x.more()) {
                x.skipPast("<");
                if (x.more()) {
                    parse(x, jo, null, XMLParserConfiguration.ORIGINAL, add_prefix);
                }
            }
        } catch (ParseTerminationException e) {
            return new JSONObject(e.getMessage());
        } catch (JSONException e) {
            throw new JSONException("parse_M3 function error");
        }
        return jo;
    }

    /***************   SWE 262 P MileStone 3  Our Code Ends Here ****************/


    /***************   SWE 262 P MileStone 5  Start Here ****************/

    static class Thread implements Callable<JSONObject> {
        Reader reader;
        Function<String, String> func;

        public Thread(Reader reader, Function<String, String> f) {
            this.reader = reader;
            this.func = f;
        }

        @Override
        public JSONObject call() throws Exception {
            return toJSONObject(reader, func);      //MileStone3 function
        }
    }

    public static Future<JSONObject> toJSONObject(Reader reader, Function<String, String> func, Consumer<Exception> consumer) {
        // Creates a thread pool that creates new threads as needed but will reuse previously constructed threads when they are available.
        ExecutorService executor_service = Executors.newCachedThreadPool();
        Callable<JSONObject> callable = new Thread(reader, func);               //set the thread contain milestone3 function
        Future<JSONObject> future = null;

        try {
            if (func == null) throw new Exception();

            future = executor_service.submit(callable);     // future is the asynchronous computation result
            executor_service.shutdown();
        } catch (Exception e) {
            consumer.accept(e);
        }
        return future;
    }

    /***************   SWE 262 P MileStone 5  Our Code Ends Here ****************/

    /**
     * Convert a JSONObject into a well-formed, element-normal XML string.
     *
     * @param object A JSONObject.
     * @return A string.
     * @throws JSONException Thrown if there is an error parsing the string
     */
    public static String toString(Object object) throws JSONException {
        return toString(object, null, XMLParserConfiguration.ORIGINAL);
    }

    /**
     * Convert a JSONObject into a well-formed, element-normal XML string.
     *
     * @param object  A JSONObject.
     * @param tagName The optional name of the enclosing tag.
     * @return A string.
     * @throws JSONException Thrown if there is an error parsing the string
     */
    public static String toString(final Object object, final String tagName) {
        return toString(object, tagName, XMLParserConfiguration.ORIGINAL);
    }

    /**
     * Convert a JSONObject into a well-formed, element-normal XML string.
     *
     * @param object  A JSONObject.
     * @param tagName The optional name of the enclosing tag.
     * @param config  Configuration that can control output to XML.
     * @return A string.
     * @throws JSONException Thrown if there is an error parsing the string
     */
    public static String toString(final Object object, final String tagName, final XMLParserConfiguration config)
            throws JSONException {
        return toString(object, tagName, config, 0, 0);
    }

    /**
     * Convert a JSONObject into a well-formed, element-normal XML string,
     * either pretty print or single-lined depending on indent factor.
     *
     * @param object       A JSONObject.
     * @param tagName      The optional name of the enclosing tag.
     * @param config       Configuration that can control output to XML.
     * @param indentFactor The number of spaces to add to each level of indentation.
     * @param indent       The current ident level in spaces.
     * @return
     * @throws JSONException
     */
    private static String toString(final Object object, final String tagName, final XMLParserConfiguration config, int indentFactor, int indent)
            throws JSONException {
        StringBuilder sb = new StringBuilder();
        JSONArray ja;
        JSONObject jo;
        String string;

        if (object instanceof JSONObject) {

            // Emit <tagName>
            if (tagName != null) {
                sb.append(indent(indent));
                sb.append('<');
                sb.append(tagName);
                sb.append('>');
                if (indentFactor > 0) {
                    sb.append("\n");
                    indent += indentFactor;
                }
            }

            // Loop thru the keys.
            // don't use the new entrySet accessor to maintain Android Support
            jo = (JSONObject) object;
            for (final String key : jo.keySet()) {
                Object value = jo.opt(key);
                if (value == null) {
                    value = "";
                } else if (value.getClass().isArray()) {
                    value = new JSONArray(value);
                }

                // Emit content in body
                if (key.equals(config.getcDataTagName())) {
                    if (value instanceof JSONArray) {
                        ja = (JSONArray) value;
                        int jaLength = ja.length();
                        // don't use the new iterator API to maintain support for Android
                        for (int i = 0; i < jaLength; i++) {
                            if (i > 0) {
                                sb.append('\n');
                            }
                            Object val = ja.opt(i);
                            sb.append(escape(val.toString()));
                        }
                    } else {
                        sb.append(escape(value.toString()));
                    }

                    // Emit an array of similar keys

                } else if (value instanceof JSONArray) {
                    ja = (JSONArray) value;
                    int jaLength = ja.length();
                    // don't use the new iterator API to maintain support for Android
                    for (int i = 0; i < jaLength; i++) {
                        Object val = ja.opt(i);
                        if (val instanceof JSONArray) {
                            sb.append('<');
                            sb.append(key);
                            sb.append('>');
                            sb.append(toString(val, null, config, indentFactor, indent));
                            sb.append("</");
                            sb.append(key);
                            sb.append('>');
                        } else {
                            sb.append(toString(val, key, config, indentFactor, indent));
                        }
                    }
                } else if ("".equals(value)) {
                    sb.append(indent(indent));
                    sb.append('<');
                    sb.append(key);
                    sb.append("/>");
                    if (indentFactor > 0) {
                        sb.append("\n");
                    }

                    // Emit a new tag <k>

                } else {
                    sb.append(toString(value, key, config, indentFactor, indent));
                }
            }
            if (tagName != null) {

                // Emit the </tagName> close tag
                sb.append(indent(indent - indentFactor));
                sb.append("</");
                sb.append(tagName);
                sb.append('>');
                if (indentFactor > 0) {
                    sb.append("\n");
                }
            }
            return sb.toString();
        }

        if (object != null && (object instanceof JSONArray || object.getClass().isArray())) {
            if (object.getClass().isArray()) {
                ja = new JSONArray(object);
            } else {
                ja = (JSONArray) object;
            }
            int jaLength = ja.length();
            // don't use the new iterator API to maintain support for Android
            for (int i = 0; i < jaLength; i++) {
                Object val = ja.opt(i);
                // XML does not have good support for arrays. If an array
                // appears in a place where XML is lacking, synthesize an
                // <array> element.
                sb.append(toString(val, tagName == null ? "array" : tagName, config, indentFactor, indent));
            }
            return sb.toString();
        }

        string = (object == null) ? "null" : escape(object.toString());

        if (tagName == null) {
            return indent(indent) + "\"" + string + "\"" + ((indentFactor > 0) ? "\n" : "");
        } else if (string.length() == 0) {
            return indent(indent) + "<" + tagName + "/>" + ((indentFactor > 0) ? "\n" : "");
        } else {
            return indent(indent) + "<" + tagName
                    + ">" + string + "</" + tagName + ">" + ((indentFactor > 0) ? "\n" : "");
        }
    }

    /**
     * Convert a JSONObject into a well-formed, pretty printed element-normal XML string.
     *
     * @param object       A JSONObject.
     * @param indentFactor The number of spaces to add to each level of indentation.
     * @return A string.
     * @throws JSONException Thrown if there is an error parsing the string
     */
    public static String toString(Object object, int indentFactor) {
        return toString(object, null, XMLParserConfiguration.ORIGINAL, indentFactor);
    }

    /**
     * Convert a JSONObject into a well-formed, pretty printed element-normal XML string.
     *
     * @param object       A JSONObject.
     * @param tagName      The optional name of the enclosing tag.
     * @param indentFactor The number of spaces to add to each level of indentation.
     * @return A string.
     * @throws JSONException Thrown if there is an error parsing the string
     */
    public static String toString(final Object object, final String tagName, int indentFactor) {
        return toString(object, tagName, XMLParserConfiguration.ORIGINAL, indentFactor);
    }

    /**
     * Convert a JSONObject into a well-formed, pretty printed element-normal XML string.
     *
     * @param object       A JSONObject.
     * @param tagName      The optional name of the enclosing tag.
     * @param config       Configuration that can control output to XML.
     * @param indentFactor The number of spaces to add to each level of indentation.
     * @return A string.
     * @throws JSONException Thrown if there is an error parsing the string
     */
    public static String toString(final Object object, final String tagName, final XMLParserConfiguration config, int indentFactor) throws JSONException {
        return toString(object, tagName, config, indentFactor, 0);
    }

    /**
     * Return a String consisting of a number of space characters specified by indent
     *
     * @param indent The number of spaces to be appended to the String.
     * @return
     */
    private static final String indent(int indent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indent; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Validate key path and process it by removing '#' and '/' from it.
     *
     * @param pointer The XML source reader.
     * @return A String[] of key path.
     */
    private static String[] get_uri(JSONPointer pointer) {
        String stringUri = pointer.toURIFragment();        //toURIFragment()可以把pointer的路徑轉成String,並且在開頭加上'#'	ex. pointer="/contact/address/0"	stringUri="#/contact/address/0"

        // According to the behavior of JSONPointer class, JSON pointer should not end with '/'
        // So, we are aligned to it and return an empty when we get an invalid path format
        if (stringUri.endsWith("/")) {
            return new String[0];        //return空的String[](ex. string={})
        }

        List<String> uri = new ArrayList<String>();
        // Filter out the empty string that split() returns
        for (String s : stringUri.split("[/#]"))        //split("[/#]")代表可以用'/'或'#'將string分開
        {
            if (s.trim().length() > 0) uri.add(s);
        }
        return uri.toArray(new String[0]);
    }

    /**
     * Validate a string contains only number
     *
     * @param str The string to validate
     * @return true string contains only number, otherwise false
     */
    private static boolean is_Num(String str) {
        try {
            Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    /**
     * Get an absolute path of current location
     *
     * @param path        A path that where target is
     * @param currentPath Current path where we are currently at
     * @return absolute path if current path is found, otherwise empty string
     */
    private static String whole_path(String[] path, String currentPath) {
        StringBuilder sb = new StringBuilder();
        for (String s : path) {
            if (!s.equals(currentPath)) {
                sb.append(s).append("/");
            } else {
                sb.append(s);
                return sb.toString();
            }
        }
        return "";
    }

    /**
     * Determine the value that are going to replace the original value
     *
     * @param replacement JSONObject that does have/have not a specific key that we are looking for
     * @param key         Key that we are looking
     * @return specific value in replacement JSONObject if key is found in replacement,
     * otherwise replacement JSONObject if key doesn't found in replacement
     */
    private static Object replacement_value(JSONObject replacement, String key) {
        return replacement.has(key) ? replacement.get(key) : replacement;
    }

    /**
     * Get a specific sub-path from whole path if index is valid
     *
     * @param path  Complete key path
     * @param index Index of sub-path we are looking for
     * @return specific sub-path that index points to, otherwise return empty string
     */
    private static String sub_path(String[] path, int index) {
        return index < path.length ? path[index] : "-1";
    }

    /**
     * A modified version of existing parse().
     * It replaces / finds the value of a specific key path with replacement while scanning through XML and form a JSONObject
     * If key path exists, value of a specific key path with be replaced with replacement object.
     * If key path doesn't exist, an unmodified JSON object will be returned.
     * <p>
     * Replacement value might vary based on following condition when a key path exist:
     * 1. If target path's value is an JSONObject, it replaces with the replacement object.
     * 2. If target path's value is an element in an object, it replaces with
     * a) the specific value in replacement JSONObject if key is found in replacement, or
     * b) replacement JSONObject if key doesn't found in replacement
     *
     * @param x             The XMLTokener containing the source string.
     * @param context       The JSONObject that will include the new material.
     * @param name          The tag name
     * @param config        XMLParserConfiguration that need to set
     * @param path          Path that replacement need to be placed in
     * @param replacement   A JSONObject that going to replace the original value
     * @param index         Index which points to path that currently looking for
     * @param map           A map that use to keep track on object in an JSONArray
     * @param return_target A boolean that determine if recursion should break when target is found
     * @return true if the close tag is processed
     * @throws JSONException             when malformation XML is detected
     * @throws ParseTerminationException when target is found if shouldReturnTarget is true
     */
    private static boolean parse(XMLTokener x, JSONObject context, String name, XMLParserConfiguration config,
                                 String[] path, JSONObject replacement, int index, Map<String, Integer> map,
                                 boolean return_target) throws JSONException, ParseTerminationException {
        JSONObject jsonObject = null;
        String string;
        String tagName;
        Object token;
        XMLXsiTypeConverter<?> xmlXsiTypeConverter;
        int path_index = index;

        // Test for and skip past these forms:
        // <!-- ... -->
        // <! ... >
        // <![ ... ]]>
        // <? ... ?>
        // Report errors for these forms:
        // <>
        // <=
        // <<

        token = x.nextToken();            //nextToken()可讀取"<>"裡面的第一個單字或符號			ex.</name>會抓到"/",<contact>會抓到"contact"
        //當目標在<>外面時，nextContent()用來指向下一個元素		ex.<name>Crista Lopes</name>	指令分別為nextToken(name)->nextToken('>')->nextContent("Crista Lopes")->nextContent('<')->nextToken('/')->nextToken("name")->nextToken('>')
        // <!

        if (token == BANG) {
            return handle_BANG(x, context, config);
        } else if (token == QUEST) {
            // <?
            x.skipPast("?>");
            return false;
        } else if (token == SLASH) {
            // Close tag </
            return handle_SLASH(x, name);
        } else if (token instanceof Character) {
            throw x.syntaxError("Misshaped tag");
            // Open tag <
        } else {
            tagName = (String) token;

            // Get the current path we are currently looking for
            String cur_path = sub_path(path, path_index);
            String absolutePath = "";
            // When path is found, we increase the index
            if (tagName.equals(cur_path)) {
                path_index++;
                // When currentPath is an array, we keep track on the item that it is processing
                // Increase the path index when we found our target
                String nextPath = sub_path(path, path_index);
                if (!nextPath.equals("-1") && is_Num(nextPath))          // next path = -1 if out of bound		//if nextPath=number, it means that it has JSONArray, so use hashmap to count whicj array did we wanted
                {
                    // Absolute path is used instead of sub-path in map to solve the problem have same sub-key within a path
                    absolutePath = whole_path(path, cur_path);

                    if (map.containsKey(absolutePath)) {
                        map.put(absolutePath, map.get(absolutePath) + 1);
                    } else {
                        map.put(absolutePath, 0);
                    }

                    // When target index is found, increase index
                    if (map.get(absolutePath) == Integer.parseInt(sub_path(path, path_index))) {
                        path_index++;
                    }
                }
            }

            token = null;
            jsonObject = new JSONObject();
            boolean nilAttributeFound = false;
            xmlXsiTypeConverter = null;
            for (; ; ) {
                if (token == null) {
                    token = x.nextToken();
                }
                // attribute = value
                if (token instanceof String) {
                    string = (String) token;
                    token = x.nextToken();
                    if (token == EQ) {
                        token = x.nextToken();
                        if (!(token instanceof String)) {
                            throw x.syntaxError("Missing value");
                        }

                        if (config.isConvertNilAttributeToNull() && NULL_ATTR.equals(string) && Boolean.parseBoolean((String) token)) {
                            nilAttributeFound = true;
                        } else if (config.getXsiTypeMap() != null && !config.getXsiTypeMap().isEmpty() && TYPE_ATTR.equals(string)) {
                            xmlXsiTypeConverter = config.getXsiTypeMap().get(token);
                        } else if (!nilAttributeFound)        //book id="bk102"
                        {
                            // If target exists in attr, replace it with replacement value
                            if (path_index == path.length - 1 && string.equals(sub_path(path, path_index)) && map.containsKey(absolutePath)) {
                                jsonObject.accumulate(string, replacement_value(replacement, string));
                                if (return_target) {
                                    JSONObject target = new JSONObject();
                                    target.put(string, config.isKeepStrings() ? token.toString() : stringToValue(token.toString()));
                                    throw new ParseTerminationException(target.toString());
                                }
                            } else {
                                jsonObject.accumulate(string, config.isKeepStrings() ? token.toString() : stringToValue(token.toString()));
                            }
                        }
                        token = null;
                    } else {
                        jsonObject.accumulate(string, "");
                    }
                } else if (token == SLASH) {
                    // Empty tag <.../>
                    return handle_EMPTY(x, context, config, jsonObject, tagName, nilAttributeFound);
                } else if (token == GT) {
                    // Content, between <...> and </...>
                    for (; ; ) {
                        token = x.nextContent();    //nextContent()用來指向下一個元素
                        if (token == null) {
                            if (tagName != null) {
                                throw x.syntaxError("Unclosed tag " + tagName);
                            }
                            return false;
                        } else if (token instanceof String) {
                            accumulate_value(config, jsonObject, (String) token, xmlXsiTypeConverter);
                        } else if (token == LT) {
                            // Nested element
                            if (parse(x, jsonObject, tagName, config, path, replacement, path_index, map, return_target)) {
                                if (config.getForceList().contains(tagName)) {
                                    // Force the value to be an array
                                    JSON_value_to_Array(context, config, jsonObject, tagName);
                                } else {
                                    if (jsonObject.length() == 0) {
                                        context.accumulate(tagName, "");
                                    } else if (jsonObject.length() == 1 && jsonObject.opt(config.getcDataTagName()) != null) {
                                        Object value = jsonObject.opt(config.getcDataTagName());
                                        // Target is an element in a JSONObject, replace with the replacement value
                                        if (path_index == path.length && !is_Num(cur_path)) {
                                            if (return_target) {
                                                JSONObject target = new JSONObject();
                                                target.put(tagName, value);
                                                throw new ParseTerminationException(target.toString());
                                            }
                                            value = replacement_value(replacement, cur_path);
                                        }
                                        context.accumulate(tagName, value);
                                    } else {
                                        // Replace it with replacement in two cases here stated below.
                                        //  1. When target is an object in an array
                                        //  2. When target is an array
                                        if (path_index == path.length && is_Num(sub_path(path, path_index - 1)) && map.containsKey(absolutePath) && map.get(absolutePath) == Integer.parseInt(sub_path(path, path_index - 1))) {
                                            context.accumulate(tagName, replacement);
                                            if (return_target) {
                                                JSONObject target = new JSONObject();
                                                target.put(tagName, jsonObject);
                                                throw new ParseTerminationException(target.toString());
                                            }
                                        } else if (path_index == path.length && !is_Num(cur_path))        //第2題 replace整個JSONArray
                                        {
                                            if (return_target) {
                                                context.accumulate(tagName, jsonObject);
                                                if (path.length == 1 && context.has(sub_path(path, 0))) {
//                                                    System.out.println("1337");
                                                    JSONObject target = new JSONObject();
                                                    target.put(tagName, context.get(tagName));
                                                    throw new ParseTerminationException(target.toString());
                                                }
                                            } else {
                                                context.put(tagName, replacement);
                                            }
                                        } else {
                                            context.accumulate(tagName, jsonObject);
                                            if (return_target && path_index == path.length - 1 && !is_Num(sub_path(path, path_index)) && jsonObject.has(sub_path(path, path_index))) {
                                                throw new ParseTerminationException(jsonObject.toString());
                                            }
                                        }
                                    }
                                }
                                return false;
                            }
                        }
                    }
                } else {
                    throw x.syntaxError("Misshaped tag");
                }
            }
        }
    }

    /**
     * A subroutine that accumulate value while forming sub-JSONObject.
     * It is extracted out from parse() to avoid duplication.
     *
     * @param config              XMLParserConfiguration that need to set.
     * @param jsonObject          The JSONObject that will include the new material.
     * @param token               The value is going to be stored in jsonObject
     * @param xmlXsiTypeConverter Type conversion configuration
     * @throws JSONException when accumulate fails
     */
    private static void accumulate_value(XMLParserConfiguration config, JSONObject jsonObject, String token, XMLXsiTypeConverter<?> xmlXsiTypeConverter) throws JSONException {
        if (token.length() > 0) {
            if (xmlXsiTypeConverter != null) {
                jsonObject.accumulate(config.getcDataTagName(), stringToValue(token, xmlXsiTypeConverter));
            } else {
                jsonObject.accumulate(config.getcDataTagName(), config.isKeepStrings() ? token : stringToValue(token));
            }
        }
    }

    /**
     * A subroutine that append value while forming sub-JSONObject.
     * It is extracted out from parse() to avoid duplication.
     *
     * @param context    The JSONObject that will include the new material
     * @param config     XMLParserConfiguration that need to set
     * @param jsonObject The value that is going to be stored in context
     * @param tagName    The key of the jsonObject which is going to be stored in context
     * @throws JSONException when append fails
     */
    private static void JSON_value_to_Array(JSONObject context, XMLParserConfiguration config, JSONObject jsonObject, String tagName) throws JSONException {
        if (jsonObject.length() == 0) {
            context.put(tagName, new JSONArray());
        } else if (jsonObject.length() == 1 && jsonObject.opt(config.getcDataTagName()) != null) {
            context.append(tagName, jsonObject.opt(config.getcDataTagName()));
        } else {
            context.append(tagName, jsonObject);
        }
    }

    /**
     * A subroutine that handle empty tag while parsing XML.
     * It is extracted out from parse() to avoid duplication.
     *
     * @param x                 XMLTokener which is having info about XML we are parsing
     * @param context           The JSONObject that will include the new material
     * @param config            XMLParserConfiguration that need to set
     * @param jsonObject        The value of the jsonObject which is going to be stored in context
     * @param tagName           The key of the jsonObject which is going to be stored in context
     * @param nilAttributeFound The boolean indicates if a tagName is found inside open tag
     * @return true if the close tag is processed
     * @throws JSONException when any operation on context fails
     */
    private static boolean handle_EMPTY(XMLTokener x, JSONObject context, XMLParserConfiguration config, JSONObject jsonObject, String tagName, boolean nilAttributeFound) throws JSONException {
        if (x.nextToken() != GT) {
            throw x.syntaxError("Misshaped tag");
        }
        if (config.getForceList().contains(tagName)) {
            // Force the value to be an array
            if (nilAttributeFound) {
                context.append(tagName, JSONObject.NULL);
            } else if (jsonObject.length() > 0) {
                context.append(tagName, jsonObject);
            } else {
                context.put(tagName, new JSONArray());
            }
        } else {
            if (nilAttributeFound) {
                context.accumulate(tagName, JSONObject.NULL);
            } else if (jsonObject.length() > 0) {
                context.accumulate(tagName, jsonObject);
            } else {
                context.accumulate(tagName, "");
            }
        }
        return false;
    }

    /**
     * A subroutine that handle close tag while parsing XML.
     * It is extracted out from parse() to avoid duplication.
     *
     * @param x    XMLTokener which has info about XML we are parsing
     * @param name The name of an open tag
     * @return true if the close tag is processed
     * @throws JSONException when malformation XML is detected
     */
    private static boolean handle_SLASH(XMLTokener x, String name) throws JSONException {
        Object token = x.nextToken();
        if (name == null) {
            throw x.syntaxError("Mismatched close tag " + token);
        }
        if (!token.equals(name)) {
            throw x.syntaxError("Mismatched " + name + " and " + token);
        }
        if (x.nextToken() != GT) {
            throw x.syntaxError("Misshaped close tag");
        }
        return true;
    }

    /**
     * A subroutine that handle BANG character(!) while parsing XML.
     * It is extracted out from parse() to avoid duplication.
     *
     * @param x       XMLTokener which has info about XML we are parsing
     * @param context The JSONObject that currently forming
     * @param config  XMLParserConfiguration that need to set
     * @return true if the close tag is processed
     * @throws JSONException when operation performs on context fails or malformation XML is detected
     */
    private static boolean handle_BANG(XMLTokener x, JSONObject context, XMLParserConfiguration config) throws JSONException {
        char c = x.next();
        Object token;
        String string;
        int i;
        if (c == '-') {
            if (x.next() == '-') {
                x.skipPast("-->");
                return false;
            }
            x.back();
        } else if (c == '[') {
            token = x.nextToken();
            if ("CDATA".equals(token)) {
                if (x.next() == '[') {
                    string = x.nextCDATA();
                    if (string.length() > 0) {
                        context.accumulate(config.getcDataTagName(), string);
                    }
                    return false;
                }
            }
            throw x.syntaxError("Expected 'CDATA['");
        }
        i = 1;
        do {
            token = x.nextMeta();
            if (token == null) {
                throw x.syntaxError("Missing '>' after '<!'.");
            } else if (token == LT) {
                i += 1;
            } else if (token == GT) {
                i -= 1;
            }
        } while (i > 0);
        return false;
    }
}

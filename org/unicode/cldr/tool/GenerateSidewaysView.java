/*
 **********************************************************************
 * Copyright (c) 2002-2004, International Business Machines
 * Corporation and others.  All Rights Reserved.
 **********************************************************************
 * Author: Mark Davis
 **********************************************************************
 */
package org.unicode.cldr.tool;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.unicode.cldr.tool.ShowData.DataShower;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.CLDRFile.Status;
import org.unicode.cldr.util.CldrUtility;
import org.unicode.cldr.util.Factory;
import org.unicode.cldr.util.LanguageTagParser;
import org.unicode.cldr.util.LanguageTagParser.Fields;
import org.unicode.cldr.util.PathHeader;
import org.unicode.cldr.util.PathHeader.SurveyToolStatus;
import org.unicode.cldr.util.StringId;
import org.unicode.cldr.util.XPathParts;
import org.xml.sax.SAXException;

import com.ibm.icu.dev.test.util.BagFormatter;
import com.ibm.icu.dev.test.util.TransliteratorUtilities;
import com.ibm.icu.dev.test.util.UnicodeMap;
import com.ibm.icu.dev.tool.UOption;
import com.ibm.icu.impl.Utility;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UScript;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.Normalizer;
import com.ibm.icu.text.RuleBasedCollator;
import com.ibm.icu.text.RuleBasedNumberFormat;
import com.ibm.icu.text.Transliterator;
import com.ibm.icu.text.UTF16;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.text.UnicodeSetIterator;
import com.ibm.icu.util.ULocale;

/**
 * This is a simple class that walks through the CLDR hierarchy.
 * It gathers together all the items from all the locales that share the
 * same element chain, and thus presents a "sideways" view of the data, in files called
 * by_type/X.html, where X is a type. X may be the concatenation of more than more than
 * one element, where the file would otherwise be too large.
 * @author medavis
 */
/*
 Notes:
 http://xml.apache.org/xerces2-j/faq-grammars.html#faq-3
 http://developers.sun.com/dev/coolstuff/xml/readme.html
 http://lists.xml.org/archives/xml-dev/200007/msg00284.html
 http://java.sun.com/j2se/1.4.2/docs/api/org/xml/sax/DTDHandler.html
 */
public class GenerateSidewaysView {
    // debug flags
    static final boolean DEBUG = false;
    static final boolean DEBUG2 = false;
    static final boolean DEBUG_SHOW_ADD = false;
    static final boolean DEBUG_ELEMENT = false;
    static final boolean DEBUG_SHOW_BAT = false;

    static final boolean FIX_ZONE_ALIASES = true;

    private static final int
    HELP1 = 0,
    HELP2 = 1,
    SOURCEDIR = 2,
    DESTDIR = 3,
    MATCH = 4,
    SKIP = 5,
    TZADIR = 6,
    NONVALIDATING = 7,
    SHOW_DTD = 8,
    TRANSLIT = 9,
    PATH = 10;

    private static final UOption[] options = {
        UOption.HELP_H(),
        UOption.HELP_QUESTION_MARK(),
        UOption.SOURCEDIR().setDefault(CldrUtility.MAIN_DIRECTORY),
        UOption.DESTDIR().setDefault(CldrUtility.CHART_DIRECTORY + "../beta-charts/by_type/"), // C:/cvsdata/unicode/cldr/diff/by_type/
        UOption.create("match", 'm', UOption.REQUIRES_ARG).setDefault(".*"),
        UOption.create("skip", 'z', UOption.REQUIRES_ARG).setDefault("zh_(C|S|HK|M).*"),
        UOption.create("tzadir", 't', UOption.REQUIRES_ARG).setDefault("C:\\ICU4J\\icu4j\\src\\com\\ibm\\icu\\dev\\tool\\cldr\\"),
        UOption.create("nonvalidating", 'n', UOption.NO_ARG),
        UOption.create("dtd", 'w', UOption.NO_ARG),
        UOption.create("transliterate", 'y', UOption.NO_ARG),
        UOption.create("path", 'p', UOption.REQUIRES_ARG),
    };

    private static final Matcher altProposedMatcher = CLDRFile.ALT_PROPOSED_PATTERN.matcher("");
    //private static final UnicodeSet ALL_CHARS = new UnicodeSet(0, 0x10FFFF);
    protected static final UnicodeSet COMBINING = (UnicodeSet) new UnicodeSet("[[:m:]]").freeze();

    static int getFirstScript(UnicodeSet exemplars) {
        for (UnicodeSetIterator it = new UnicodeSetIterator(exemplars); it.next();) {
            int script = UScript.getScript(it.codepoint);
            if (script == UScript.COMMON || script == UScript.INHERITED) {
                continue;
            }
            return script;
        }
        return UScript.COMMON;
    }

    static Comparator<Object> UCA;
    static {
        RuleBasedCollator UCA2 = (RuleBasedCollator) Collator.getInstance(ULocale.ROOT);
        UCA2.setNumericCollation(true);
        UCA2.setStrength(Collator.IDENTICAL);
        UCA = new com.ibm.icu.impl.MultiComparator(UCA2, new UTF16.StringComparator(true, false, 0) );
    }

    private static Map<PathHeader,Map<String,Set<String>>> path_value_locales = new TreeMap<PathHeader,Map<String,Set<String>>>();
    private static XPathParts parts = new XPathParts(null, null);
    private static long startTime = System.currentTimeMillis();

    static RuleBasedCollator standardCollation = (RuleBasedCollator) Collator.getInstance(ULocale.ENGLISH);
    static {
        standardCollation.setStrength(Collator.IDENTICAL);
        standardCollation.setNumericCollation(true);
    }

    private static CLDRFile english;
    //private static  DataShower dataShower = new DataShower();
    private static  Matcher pathMatcher;

    public static void main(String[] args) throws SAXException, IOException {
        startTime = System.currentTimeMillis();
        CldrUtility.registerExtraTransliterators();
        UOption.parseArgs(args, options);

        pathMatcher = options[PATH].value == null ? null : Pattern.compile(options[PATH].value).matcher("");

        Factory cldrFactory = Factory.make(options[SOURCEDIR].value, options[MATCH].value);
        english = cldrFactory.make("en", true);
        pathHeaderFactory = PathHeader.getFactory(english);

        // now get the info

        loadInformation(cldrFactory);
        String oldMain = "";
        PrintWriter out = null;

        System.out.println("Getting types " + path_value_locales.size());
        //        Set<String> types = new TreeSet<String>();
        //        for (PathHeader path : path_value_locales.keySet()) {       	
        //            String main = getFileName2(path);
        //            if (!main.equals(oldMain)) {
        //                oldMain = main;
        //                types.add(main);
        //            }
        //        }
        String headerString = getHeader(path_value_locales.keySet());

        System.out.println("Printing files in " + new File(options[DESTDIR].value).getAbsolutePath());
        //Transliterator toLatin = Transliterator.getInstance("any-latin");
        toHTML = TransliteratorUtilities.toHTML;
        //UnicodeSet BIDI_R = new UnicodeSet("[[:Bidi_Class=R:][:Bidi_Class=AL:]]");

        String oldHeader = "";
        for (PathHeader path : path_value_locales.keySet()) {       	
            String main = getFileName2(path);
            if (!main.equals(oldMain)) {
                oldMain = main;
                out = start(out, main, headerString);
                oldHeader = "";
            }
            String key = path.getCode();
            String anchor = toHTML.transliterate(key);

            String originalPath = path.getOriginalPath(); // prettyPath.getOriginal(path);
            String englishValue = english.getStringValue (originalPath);
            if (englishValue != null) {
                englishValue = "English: ‹" + englishValue + "›";
            } else {
                englishValue = "";
            }

            String header = path.getHeader();
            if (!header.equals(oldHeader) && !header.equals("null")) {
                final String htmlHeader = toHTML.transliterate(header).replace(" ", "_");
                out.println("<tr><th colSpan='2' class='pathHeader'><a " +
                    "name=\"" + htmlHeader + "\"" +
                    "href=\"#" + htmlHeader + "\"" +
                				">" + htmlHeader.replace("_", " ") + "</a></th><tr>");
                oldHeader = header;
            }
            String anchorId = Long.toHexString(StringId.getId(path.getOriginalPath()));
            out.println("<tr>" +
                    "<th class='path'><a " +
                    "name=\"" + anchorId + "\"" +
                    "href=\"#" + anchorId + "\"" +
                    		">Code: ‹" + anchor + "›</a></th>" +
                    "<th class='path'>" + toHTML.transliterate(englishValue) + "</a></th>" +
            "<tr>");
            Map<String, Set<String>> value_locales =  path_value_locales.get(path);
            for (String value : value_locales.keySet()) {
                //        String outValue = toHTML.transliterate(value);
                //        String transValue = value;
                //        try {
                //          transValue = toLatin.transliterate(value);
                //        } catch (RuntimeException e) {
                //        }
                //        if (!transValue.equals(value)) {
                //          outValue = "<span title='" + toHTML.transliterate(transValue) + "'>" + outValue + "</span>";
                //        }
                String valueClass = " class='value'";
                if (DataShower.getBidiStyle(value).length() != 0) {
                    valueClass = " class='rtl_value'";
                }
                out.println("<tr><th" + valueClass + ">" + DataShower.getPrettyValue(value) + "</th><td class='td'>");
                Set<String> locales = value_locales.get(value);
                boolean first = true;
                boolean containsRoot = locales.contains("root");
                for (String locale : locales) {
                    if (first) first = false;
                    else out.print(" ");
                    if (locale.endsWith("*")) {
                        locale = locale.substring(0,locale.length()-1);
                        out.print("<i>\u00B7" + locale + "\u00B7</i>");
                    } else if (!containsRoot) {
                        out.print("\u00B7" + locale + "\u00B7");         
                    }
                }
                if (containsRoot) {
                    out.print("<b>\u00B7others\u00B7</b>");
                }
                out.println("</td><tr>");
            }
        }
        out = showExemplars(out, headerString);
        finish(out);
        System.out.println("Done in " + new RuleBasedNumberFormat(new ULocale("en"), RuleBasedNumberFormat.DURATION)
        .format((System.currentTimeMillis()-startTime)/1000.0));
    }

    //  static Comparator UCA;
    //  static {
    //    RuleBasedCollator UCA2 = (RuleBasedCollator) Collator.getInstance(ULocale.ROOT);
    //    UCA2.setNumericCollation(true);
    //    UCA2.setStrength(UCA2.IDENTICAL);
    //    UCA = new CollectionUtilities.MultiComparator(UCA2, new UTF16.StringComparator(true, false, 0) );
    //  }

    private static PrintWriter showExemplars(PrintWriter out, String headerString) throws IOException {
        showExemplars2(out, headerString, "misc.exemplarCharacters", "");
        //showExemplars2(out, types, "misc.exemplarCharacters-index", "[@type=\"index\"]");
        //showExemplars2(out, types, "misc.exemplarCharacters-auxiliary", "[@type=\"auxiliary\"]");
        //showExemplars2(out, types, "misc.exemplarCharacters-currencySymbol", "[@type=\"currencySymbol\"]");
        return out;
    }

    private static PrintWriter showExemplars2(PrintWriter out, String headerString, String filename, String variant) throws IOException {
        finish(out);
        out = start(out,filename, headerString);
        out.println("<table>");
        PathHeader cleanPath = fixPath("//ldml/characters/exemplarCharacters" + variant, null);
        Map<String, Set<String>> value_locales = path_value_locales.get(cleanPath);

        // TODO change logic so that aux characters and currencySymbol characters work well.

        Map<String,UnicodeMap<Set<String>>> script_UnicodeMap = new TreeMap<String,UnicodeMap<Set<String>>>();
        //UnicodeMap mapping = new UnicodeMap();
        UnicodeSet stuffToSkip = new UnicodeSet("[:Han:]");

        // get the locale information
        UnicodeSet totalExemplars = new UnicodeSet();
        for (String value : value_locales.keySet()) {
            // flatten out UnicodeSet
            UnicodeSet exemplars = new UnicodeSet(value);
            UnicodeSet extras = new UnicodeSet();
            for (String item : exemplars) {
                extras.addAll(Normalizer.normalize(item, Normalizer.NFD));
            }
            exemplars.addAll(extras);
            totalExemplars.addAll(exemplars);
            exemplars.removeAll(stuffToSkip);

            Set<String> locales = value_locales.get(value);
            String script = UScript.getName(getFirstScript(exemplars));
            UnicodeMap<Set<String>> mapping = script_UnicodeMap.get(script);
            if (mapping == null) script_UnicodeMap.put(script, mapping = new UnicodeMap<Set<String>>());
            mapping.composeWith(exemplars, locales, setComposer);
        }
        System.out.println("@@@TOTAL:\t" + variant + "\t" + totalExemplars.toPattern(false));
        for (String script : script_UnicodeMap.keySet()) {
            UnicodeMap<Set<String>> mapping = script_UnicodeMap.get(script);
            writeCharToLocaleMapping(out, script, mapping);
        }
        return out;
    }

    private static void writeCharToLocaleMapping(PrintWriter out, String script, UnicodeMap<Set<String>> mapping) {
        BreakIterator charBreaks = BreakIterator.getCharacterInstance(ULocale.ROOT); // TODO, make default language for script
        System.out.println("@@Exemplars for\t" + script + "\t" + mapping.keySet());
        if (script.equals("Hangul") || script.equals("Common")) {
            return; // skip these
        }
        // find out all the locales and all the characters
        Set<String> allLocales = new TreeSet<String>(UCA);
        Set<String> allChars = new TreeSet<String>(UCA);
        Set<String> allStrings = new TreeSet<String>(UCA);
        for (Set<String> locales : mapping.getAvailableValues()) {
            allLocales.addAll(locales);
            UnicodeSet unicodeSet = mapping.keySet(locales);
            for (String item : unicodeSet) {
                charBreaks.setText(item);
                int endFirst = charBreaks.next();
                if (endFirst == item.length()) {
                    allChars.add(item);
                } else {
                    allStrings.add(item);
                }
            }
        }
        // get the columns, and show them
        out.println("</table>");
        out.println("<table class='table'  style='width:1%'>");
        out.println("<caption>" + script + "</caption>");
        exemplarHeader(out, allChars);

        for (String locale : allLocales) {
            out.println("<tr>");
            out.println("<th class='head'>" + cleanLocale(locale, false) + "</th><td class='head nowrap left'>" + cleanLocale(locale, true) + "</td>");
            for (String item : allChars) {
                //String exemplarsWithoutBrackets = displayExemplars(item);
                if (mapping.get(item).contains(locale)) {
                    out.println("<td class='cell'" +
                            ">" + displayCharacter(item) + "</td>");
                } else {
                    out.println("<td class='empty'>\u00a0</td>");
                }
            }
            // now strings, if any
            StringBuilder strings = new StringBuilder();
            int lastLineStart = 0;
            for (String item : allStrings) {
                //String exemplarsWithoutBrackets = displayExemplars(item);
                if (mapping.get(item).contains(locale)) {
                    int str_len = strings.length();
                    if (str_len != 0) {
                        if (str_len - lastLineStart > 20) {
                            strings.append('\n');
                            lastLineStart = str_len;
                        } else {
                            strings.append(' ');
                        }
                    }
                    strings.append(displayCharacter(item));
                }
            }
            if (strings.length() == 0) {
                out.println("<td class='empty'>\u00a0</td>");
            } else {
                out.println("<td class='cell nowrap'>" + displayCharacter(strings.toString()).replace("\n", "<br>") + "</td>");
            }
            out.println("<th class='head'>" + cleanLocale(locale, false) + "</th><td class='head nowrap left'>" + cleanLocale(locale, true) + "</td>");

            out.println("</tr>");
        }
        exemplarHeader(out, allChars);
        out.flush();
    }

    private static String characterTitle(String item) {
        return ("title='U+" + 
                toHTML.transform(
                        Utility.hex(item, 4, ", U+", true, new StringBuilder())
                        + " " + UCharacter.getName(item, ", ")) +
        "'");
    }

    private static void exemplarHeader(PrintWriter out, Set<String> allChars) {
        out.println("<tr>");
        out.println("<th class='head nowrap' colSpan='2'>Locale \\\u00a0Chars</td>");
        for (String item : allChars) {
            out.println("<th class='head' " + characterTitle(item) + ">" + displayCharacter(item) + "</th>");
        }
        out.println("<th class='head'>Clusters</td>");
        out.println("<th class='head nowrap' colSpan='2'>Locale \\\u00a0Chars</td>");
        out.println("</tr>");
    }

    static final UnicodeSet NONSPACING = new UnicodeSet("[[:Mn:][:Me:][:default_ignorable_code_point:]]").freeze();

    public static String displayCharacter(String item) {
        if (item.length() == 0) return "<i>none</i>";
        int ch = item.codePointAt(0);
        if (NONSPACING.contains(ch)) {
            item = "\u00a0" + item + "\u00a0";
        }
        String result = toHTML.transform(item);
        return result;
    }

    static LanguageTagParser cleanLocaleParser = new LanguageTagParser();
    static Set<Fields> allButScripts = EnumSet.allOf(Fields.class);
    static {
        allButScripts.remove(Fields.SCRIPT);
    }

    private static String cleanLocale(String item, boolean name) {
        if (item == null) {
            return "<i>null</i>";
        }
        boolean draft = item.endsWith("*");
        if (draft) {
            item = item.substring(0,item.length()-1);
        }
        cleanLocaleParser.set(item);
        item = cleanLocaleParser.toString(allButScripts);
        String core = item;
        item = toHTML.transform(item);
        if (name) {
            item = english.getName(core);
            item = item == null ? "<i>null</i>" : toHTML.transform(item);
        }
        if (draft) {
            item = "<i>" + item + "</i>";
        }
        return item;
    }

    //    private static void showExemplarRow(PrintWriter out, Set<String> allLocales, UnicodeSet lastChars, Set locales) {
    //        String exemplarsWithoutBrackets = displayExemplars(lastChars);
    //        out.println("<tr><th class='head'>" + exemplarsWithoutBrackets + "</th>");
    //        for (String item : allLocales) {
    //            String cleanItem;
    //            if (locales.contains(item)) {
    //                cleanItem = "<th class='value'>" + cleanLocale(item, false) + "</th>";
    //            } else {
    //                cleanItem = "<td class='value'>\u00a0</td>";
    //            }
    //            out.println(cleanItem);
    //        }
    //        out.println("</tr>");
    //    }

    //    private static final StringTransform MyTransform = new StringTransform() {
    //
    //        public String transform(String source) {
    //            StringBuilder builder = new StringBuilder();
    //            int cp = 0;
    //            builder.append("<span title='");
    //            String prefix = "";
    //            for (int i = 0; i < source.length(); i += UTF16.getCharCount(cp)) {
    //                cp = UTF16.charAt(source, i);
    //                if (i == 0) {
    //                    if (COMBINING.contains(cp)) {
    //                        prefix = "\u25CC";
    //                    }
    //                } else {
    //                    builder.append(" + ");
    //                }
    //                builder.append("U+").append(com.ibm.icu.impl.Utility.hex(cp,4)).append(' ').append(UCharacter.getExtendedName(cp));
    //            }
    //            builder.append("'>").append(prefix).append(source).append("</span>");
    //            return builder.toString();
    //        }
    //
    //    };

    //    private static String displayExemplars(UnicodeSet lastChars) {
    //        String exemplarsWithoutBrackets = new PrettyPrinter()
    //        .setOrdering(UCA != null ? UCA : Collator.getInstance(ULocale.ROOT))
    //        .setSpaceComparator(UCA != null ? UCA : Collator.getInstance(ULocale.ROOT)
    //                .setStrength2(Collator.PRIMARY))
    //                .setCompressRanges(true)
    //                .setToQuote(ALL_CHARS)
    //                .setQuoter(MyTransform)
    //                .format(lastChars);
    //        exemplarsWithoutBrackets = exemplarsWithoutBrackets.substring(1, exemplarsWithoutBrackets.length() - 1);
    //        return exemplarsWithoutBrackets;
    //    }

    //  private static boolean isNextCharacter(String last, String value) {
    //    if (UTF16.hasMoreCodePointsThan(last, 1)) return false;
    //    if (UTF16.hasMoreCodePointsThan(value, 1)) return false;
    //    int lastChar = UTF16.charAt(last,0);
    //    int valueChar = UTF16.charAt(value,0);
    //    return lastChar + 1 == valueChar;
    //  }

    static UnicodeMap.Composer<Set<String>> setComposer = new UnicodeMap.Composer<Set<String>>() {
        public Set<String> compose(int codepoint, String string, Set<String> a, Set<String> b) {
            if (a == null) {
                return b;
            } else if (b == null) {
                return a;
            } else {
                TreeSet<String> result = new TreeSet<String>(a);
                result.addAll(b);
                return result;
            }
        }
    };

    private static void loadInformation(Factory cldrFactory) {
        Set<String> alllocales = cldrFactory.getAvailable();
        String[] postFix = new String[]{""};
        // gather all information
        // TODO tweek for value-laden attributes
        for (String localeID : alllocales) {
            System.out.println("Loading: " + localeID);
            System.out.flush();

            CLDRFile cldrFile;
            try {
                cldrFile = cldrFactory.make(localeID, localeID.equals("root"));
            } catch (IllegalArgumentException e) {
                System.err.println("Couldn't open " + localeID);
                continue;
            }
            if (cldrFile.isNonInheriting()) continue;
            for (String path : cldrFile) {
                if (pathMatcher != null && !pathMatcher.reset(path).matches()) {
                    continue;
                }
                if (altProposedMatcher.reset(path).matches()) {
                    continue;
                }
                if (path.indexOf("/alias") >= 0) continue;
                if (path.indexOf("/identity") >= 0) continue;
                if (path.indexOf("/references") >= 0) continue;
                PathHeader cleanPath = fixPath(path, postFix);
                final SurveyToolStatus surveyToolStatus = cleanPath.getSurveyToolStatus();
                if (surveyToolStatus == SurveyToolStatus.DEPRECATED || surveyToolStatus == SurveyToolStatus.HIDE) {
                    //System.out.println("Skipping " + path);
                    continue;
                }
                String fullPath = cldrFile.getFullXPath(path);
                String value = getValue(cldrFile, path, fullPath);
                if (value == null) {
                    continue;
                }
                if (fullPath.indexOf("[@draft=\"unconfirmed\"]") >= 0
                        || fullPath.indexOf("[@draft=\"provisional\"]") >= 0) {
                    postFix[0] = "*";
                }
                Map<String, Set<String>> value_locales = path_value_locales.get(cleanPath);
                if (value_locales == null ) {
                    path_value_locales.put(cleanPath, value_locales = new TreeMap<String, Set<String>>(standardCollation));
                }
                Set<String> locales = value_locales.get(value);
                if (locales == null) {
                    value_locales.put(value, locales = new TreeSet<String>());
                }
                locales.add(localeID + postFix[0]);
            }
        }
    }

    static PathHeader.Factory pathHeaderFactory;

    // static org.unicode.cldr.util.PrettyPath prettyPath = new org.unicode.cldr.util.PrettyPath();
    /**
     * 
     */
    private static PathHeader fixPath(String path, String[] localePrefix) {
        if (localePrefix != null) localePrefix[0] = "";
        if (path.indexOf("[@alt=") >= 0 || path.indexOf("[@draft=") >= 0) {
            if (localePrefix != null) localePrefix[0] = "*";
            path = removeAttributes(path, skipSet);
        }
        //if (usePrettyPath) path = prettyPath.getPrettyPath(path);
        return pathHeaderFactory.fromPath(path);
    }

    private static String removeAttributes(String xpath, Set<String> skipAttributes) {
        XPathParts parts = new XPathParts(null,null).set(xpath);
        removeAttributes(parts, skipAttributes);
        return parts.toString();
    }

    /**
     * 
     */
    private static void removeAttributes(XPathParts parts, Set<String> skipAttributes) {
        for (int i = 0; i < parts.size(); ++i) {
            //String element = parts.getElement(i);
            Map<String, String> attributes = parts.getAttributes(i);
            for (Iterator<String> it = attributes.keySet().iterator(); it.hasNext();) {
                String attribute = (String) it.next();
                if (skipAttributes.contains(attribute)) it.remove();
            }
        }
    }

    static Set<String> skipSet = new HashSet<String>(Arrays.asList("draft", "alt"));

    static Status status = new Status();

    /**
     * 
     */
    private static String getValue(CLDRFile cldrFile, String path, String fullPath) {
        String value = cldrFile.getStringValue(path);
        if (value == null) {
            System.out.println("Null value for " + path);
            return value;
        }
        cldrFile.getSourceLocaleID(path, status);
        if (!path.equals(status.pathWhereFound)) {
            //value = "[" + prettyPath.getPrettyPath(status.pathWhereFound, false) + "]";
            value = null;
            return value;
        }
        if (value.length() == 0) {
            parts.set(fullPath);
            removeAttributes(parts, skipSet);
            int limit = parts.size();
            value = parts.toString(limit-1, limit);
            return value;
        }
        return value;
    }

    private static String getFileName2(PathHeader header) {
        String result = (header.getSection() + "." + header.getPage()).replace(" ", "_").toLowerCase(Locale.ENGLISH);
        return result;
    }

    static String[] headerAndFooter = new String[2];
    private static Transliterator toHTML;

    /**
     * 
     */
    private static PrintWriter start(PrintWriter out, String main, String headerString) throws IOException {
        finish(out);
        out = writeHeader(main);
        out.println(headerString);
        out.println("</p></blockquote><table class='table'>");
        return out;
    }

    public static String getHeader(Set<PathHeader> set) {
        StringBuffer out = new StringBuffer("<p>");
        String lastMain = "";
        String lastSub = "";
        for (PathHeader pathHeader : set) {
            String mainName = pathHeader.getSection();
            String subName = pathHeader.getPage();
            if (!mainName.equals(lastMain)) {
                if (lastMain.length() != 0) {
                    out.append("</p><p>");
                }
                out.append("<b>" + mainName + "</b>: ");
                lastMain = mainName;
                lastSub = subName;
            } else if (!subName.equals(lastSub)){
                out.append(" | ");
                lastSub = subName;
            } else {
                continue; // identical, skip
            }
            out.append("<a href='" + getFileName2(pathHeader) + ".html'>" + subName + "</a>");
            continue;
        }
        return out.append("</p>").toString();
    }

    private static PrintWriter writeHeader(String main) throws IOException {
        PrintWriter out;
        out = BagFormatter.openUTF8Writer(options[DESTDIR].value, main + ".html");


        ShowData.getChartTemplate("By-Type Chart: " + main,
                ShowLanguages.CHART_DISPLAY_VERSION, 
                "",
                //"<link rel='stylesheet' type='text/css' href='by_type.css'>" +
                //    "<style type='text/css'>" + Utility.LINE_SEPARATOR +
                //    "h1 {margin-bottom:1em}" + Utility.LINE_SEPARATOR +
                //    "</style>" + Utility.LINE_SEPARATOR,
                headerAndFooter);
        out.println(headerAndFooter[0]);
        out.println("<blockquote><p>");
        return out;
    }

    /**
     * 
     */
    private static void finish(PrintWriter out) {
        if (out == null) return;
        out.println("</table>");
        out.println(headerAndFooter[1]);
        out.close();
    }

}

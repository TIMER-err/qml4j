package io.github.timer_err.qml4j.render.text;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;

/** UAX #14 strict line opportunities and UAX #29 extended grapheme boundaries. */
public final class UnicodeBreaks {
    // ICU's compiled line rules tag mandatory breaks with status 100.
    private static final int HARD_LINE_STATUS = 100;
    private static final ThreadLocal<Iterators> ITERATORS = ThreadLocal.withInitial(Iterators::new);

    private UnicodeBreaks() { }

    /** Arrays use original UTF-16 offsets; callers own the result and must not alter its boundaries. */
    public static final class Analysis {
        public final int[] graphemes;
        public final boolean[] line;
        public final boolean[] emergency;
        public final boolean[] mandatory;

        private Analysis(int[] graphemes, boolean[] line, boolean[] emergency, boolean[] mandatory) {
            this.graphemes = graphemes;
            this.line = line;
            this.emergency = emergency;
            this.mandatory = mandatory;
        }
    }

    private static final class Iterators {
        final BreakIterator character = BreakIterator.getCharacterInstance(ULocale.ROOT);
        final BreakIterator line = BreakIterator.getLineInstance(
                ULocale.ROOT.setKeywordValue("lb", "strict"));
    }

    public static Analysis analyze(String text) {
        boolean[] emergency = new boolean[text.length() + 1];
        boolean[] line = new boolean[emergency.length];
        boolean[] mandatory = new boolean[emergency.length];
        Iterators iterators = ITERATORS.get();
        try {
            int count = markGraphemes(iterators.character, text, emergency);
            markLines(iterators.line, text, emergency, line, mandatory);
            int[] graphemes = collectGraphemes(text, emergency, line, count);
            line[0] = line[text.length()] = true;
            return new Analysis(graphemes, line, emergency, mandatory);
        } finally {
            // Render threads are long-lived; cached iterators must not retain a song or document.
            iterators.character.setText("");
            iterators.line.setText("");
        }
    }

    private static int markGraphemes(BreakIterator iterator, String text, boolean[] boundaries) {
        iterator.setText(text);
        int count = 0;
        for (int offset = iterator.first(); offset != BreakIterator.DONE; offset = iterator.next()) {
            boundaries[offset] = true;
            count++;
        }
        return count;
    }

    private static void markLines(BreakIterator iterator, String text, boolean[] graphemes,
                                  boolean[] line, boolean[] mandatory) {
        iterator.setText(text);
        for (int offset = iterator.first(); offset != BreakIterator.DONE; offset = iterator.next()) {
            line[offset] = graphemes[offset];
            mandatory[offset] = iterator.getRuleStatus() >= HARD_LINE_STATUS;
        }
    }

    private static int[] collectGraphemes(String text, boolean[] emergency, boolean[] line, int count) {
        int[] result = new int[count];
        int index = 0;
        for (int offset = 0; offset < emergency.length; offset++) {
            if (!emergency[offset]) continue;
            result[index++] = offset;
            emergency[offset] = offset == 0 || offset == text.length()
                    || line[offset] || canBreakLongWord(text, offset);
        }
        return result;
    }

    // Long-word fallback relaxes alphabetic word boundaries, not punctuation, glue, or graphemes.
    private static boolean canBreakLongWord(String text, int offset) {
        int before = text.codePointBefore(offset);
        int after = text.codePointAt(offset);
        int beforeClass = UCharacter.getIntPropertyValue(before, UProperty.LINE_BREAK);
        int afterClass = UCharacter.getIntPropertyValue(after, UProperty.LINE_BREAK);
        return !joinsAcross(beforeClass) && !joinsAcross(afterClass)
                && !continuesConjunct(before)
                && beforeClass != UCharacter.LineBreak.OPEN_PUNCTUATION
                && beforeClass != UCharacter.LineBreak.PREFIX_NUMERIC
                && UCharacter.getType(before) != UCharacter.INITIAL_PUNCTUATION
                && UCharacter.getType(after) != UCharacter.FINAL_PUNCTUATION
                && !prohibitsStart(afterClass);
    }

    // Some scripts' orthographic syllables span more than one extended grapheme.
    private static boolean continuesConjunct(int codePoint) {
        int category = UCharacter.getIntPropertyValue(codePoint, UProperty.INDIC_SYLLABIC_CATEGORY);
        return category == UCharacter.IndicSyllabicCategory.VIRAMA
                || category == UCharacter.IndicSyllabicCategory.INVISIBLE_STACKER;
    }

    private static boolean joinsAcross(int lineClass) {
        return lineClass == UCharacter.LineBreak.WORD_JOINER
                || lineClass == UCharacter.LineBreak.GLUE;
    }

    private static boolean prohibitsStart(int lineClass) {
        switch (lineClass) {
            case UCharacter.LineBreak.CLOSE_PUNCTUATION:
            case UCharacter.LineBreak.CLOSE_PARENTHESIS:
            case UCharacter.LineBreak.EXCLAMATION:
            case UCharacter.LineBreak.INFIX_NUMERIC:
            case UCharacter.LineBreak.BREAK_SYMBOLS:
            case UCharacter.LineBreak.NONSTARTER:
            case UCharacter.LineBreak.INSEPARABLE:
            case UCharacter.LineBreak.CONDITIONAL_JAPANESE_STARTER:
            case UCharacter.LineBreak.POSTFIX_NUMERIC:
            case UCharacter.LineBreak.SPACE:
            case UCharacter.LineBreak.ZWSPACE:
            case UCharacter.LineBreak.MANDATORY_BREAK:
            case UCharacter.LineBreak.CARRIAGE_RETURN:
            case UCharacter.LineBreak.LINE_FEED:
            case UCharacter.LineBreak.NEXT_LINE:
                return true;
            default:
                return false;
        }
    }
}

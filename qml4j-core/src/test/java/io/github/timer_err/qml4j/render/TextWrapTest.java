package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.render.items.core.TextWrap;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TextWrapTest {

    private static final TextWrap.Measure FIXED = s -> s.length() * 10f;

    @Test
    void noWrapKeepsHardBreaks() {
        TextWrap.Result r = TextWrap.wrap("hello\nworld", "NoWrap", 1000, FIXED);
        assertEquals(2, r.lines.size());
        assertEquals("hello", r.lines.get(0));
        assertEquals("world", r.lines.get(1));
        assertArrayEquals(new int[]{0, 6}, r.starts);
    }

    @Test
    void wordWrapBreaksAtSpaces() {
        TextWrap.Result r = TextWrap.wrap("the quick brown fox jumps", "Wrap", 100, FIXED);
        assertEquals(3, r.lines.size());
        assertEquals("the quick ", r.lines.get(0));
        assertEquals("brown fox ", r.lines.get(1));
        assertEquals("jumps", r.lines.get(2));
    }

    // WordWrap breaks only at word boundaries; a word wider than the box stays whole and
    // overflows (Qt), unlike Wrap which falls back to a mid-word break.
    @Test
    void wordWrapKeepsLongWordWhole() {
        TextWrap.Result r = TextWrap.wrap("hi enormousword", "WordWrap", 50, FIXED);
        assertEquals(2, r.lines.size());
        assertEquals("hi ", r.lines.get(0));
        assertEquals("enormousword", r.lines.get(1));
    }

    @Test
    void wrapFallsBackToMidWordForLongWord() {
        TextWrap.Result r = TextWrap.wrap("enormousword", "Wrap", 50, FIXED);
        assertEquals(3, r.lines.size());
        assertEquals("enorm", r.lines.get(0));
        assertEquals("ouswo", r.lines.get(1));
        assertEquals("rd", r.lines.get(2));
    }

    @Test
    void wrapAnywhereBreaksMidWord() {
        TextWrap.Result r = TextWrap.wrap("abcdefghij", "WrapAnywhere", 50, FIXED);
        assertEquals(2, r.lines.size());
        assertEquals("abcde", r.lines.get(0));
        assertEquals("fghij", r.lines.get(1));
    }

    @Test
    void lineForCaretMapsCorrectly() {
        TextWrap.Result r = TextWrap.wrap("aa\nbb\ncc", "NoWrap", 1000, FIXED);
        assertEquals(0, TextWrap.lineForCaret(r, 0));
        assertEquals(0, TextWrap.lineForCaret(r, 2));
        assertEquals(1, TextWrap.lineForCaret(r, 3));
        assertEquals(2, TextWrap.lineForCaret(r, 6));
    }

    @Test
    void verticalMovePreservesColumn() {
        TextWrap.Result r = TextWrap.wrap("hello\nworldwide\nbye", "NoWrap", 1000, FIXED);
        int down = TextWrap.moveCaretVertical(r, 2, 1, FIXED);
        assertEquals(8, down);
        int up = TextWrap.moveCaretVertical(r, down, -1, FIXED);
        assertEquals(2, up);
    }

    @Test
    void verticalMoveClampsAtEdges() {
        TextWrap.Result r = TextWrap.wrap("only one line", "NoWrap", 1000, FIXED);
        assertEquals(3, TextWrap.moveCaretVertical(r, 3, -1, FIXED));
        assertEquals(3, TextWrap.moveCaretVertical(r, 3, 1, FIXED));
    }

    @Test
    void caretInLineHitsMidpoint() {
        String line = "abcd";
        assertEquals(0, TextWrap.caretInLine(line, 0, FIXED));
        assertEquals(1, TextWrap.caretInLine(line, 6, FIXED));
        assertEquals(2, TextWrap.caretInLine(line, 15, FIXED));
        assertEquals(4, TextWrap.caretInLine(line, 100, FIXED));
    }

    @Test
    void hardBreakWithoutWrapPreserved() {
        TextWrap.Result r = TextWrap.wrap("a\n\nb", "NoWrap", 1000, FIXED);
        assertEquals(3, r.lines.size());
        assertEquals("a", r.lines.get(0));
        assertEquals("", r.lines.get(1));
        assertEquals("b", r.lines.get(2));
    }

    @Test
    void closingPunctuationMovesWithItsPrecedingText() {
        TextWrap.Result r = TextWrap.wrap("界面文案。", "Wrap", 40, FIXED);
        assertEquals(Arrays.asList("界面文", "案。"), r.lines);
        assertArrayEquals(new int[]{0, 3}, r.starts);
    }

    @Test
    void openingAndClosingBracketsStayWithTheirContents() {
        TextWrap.Result r = TextWrap.wrap("甲（乙丙）丁", "WordWrap", 20, FIXED);
        assertEquals(Arrays.asList("甲", "（乙", "丙）", "丁"), r.lines);
    }

    @Test
    void longWordFallbackDoesNotStrandItsFinalPunctuation() {
        TextWrap.Result r = TextWrap.wrap("hello.", "Wrap", 50, FIXED);
        assertEquals(Arrays.asList("hell", "o."), r.lines);
    }

    @Test
    void anywhereWrapStillKeepsExtendedGraphemesWhole() {
        TextWrap.Result r = TextWrap.wrap("a\uD83D\uDC69\u200D\uD83D\uDE80\uD83C\uDDE8\uD83C\uDDF3e\u0301",
                "WrapAnywhere", 10, FIXED);
        assertEquals(Arrays.asList("a", "\uD83D\uDC69\u200D\uD83D\uDE80",
                "\uD83C\uDDE8\uD83C\uDDF3", "e\u0301"), r.lines);
        assertArrayEquals(new int[]{0, 1, 6, 10}, r.starts);
    }

    @Test
    void hardSeparatorsPreserveEmptyLinesAndOriginalOffsets() {
        TextWrap.Result r = TextWrap.wrap("a\r\nb\u0085\u2028c\u2029", "NoWrap", 10, FIXED);
        assertEquals(Arrays.asList("a", "b", "", "c", ""), r.lines);
        assertArrayEquals(new int[]{0, 3, 5, 6, 8}, r.starts);
    }

    @Test
    void smallestIndivisiblePunctuationUnitCanOverflowANarrowBox() {
        assertEquals(Arrays.asList("好。"), TextWrap.wrap("好。", "Wrap", 10, FIXED).lines);
    }

    @Test
    void emergencyWrappingHonorsNonBreakingGlue() {
        TextWrap.Result r = TextWrap.wrap("a\u00A0b c", "Wrap", 10, FIXED);
        assertEquals(Arrays.asList("a\u00A0b ", "c"), r.lines);
    }
}

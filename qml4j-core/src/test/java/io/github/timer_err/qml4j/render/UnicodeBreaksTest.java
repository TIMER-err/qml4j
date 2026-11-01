package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.render.text.UnicodeBreaks;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnicodeBreaksTest {
    @Test
    void extendedGraphemesKeepEmojiFlagsAndCombiningMarksWhole() {
        String text = "\uD83D\uDC69\u200D\uD83D\uDE80\uD83C\uDDE8\uD83C\uDDF3e\u0301";
        UnicodeBreaks.Analysis result = UnicodeBreaks.analyze(text);
        assertArrayEquals(new int[]{0, 5, 9, 11}, result.graphemes);
        for (int offset = 1; offset < text.length(); offset++) {
            if (offset == 5 || offset == 9) continue;
            assertFalse(result.line[offset]);
            assertFalse(result.emergency[offset]);
        }
    }

    @Test
    void strictPunctuationConstraintsSurviveLongWordFallback() {
        UnicodeBreaks.Analysis result = UnicodeBreaks.analyze("甲（乙丙）丁。");
        for (int offset : new int[]{2, 4, 6}) {
            assertFalse(result.line[offset]);
            assertFalse(result.emergency[offset]);
        }
        assertTrue(result.line[3]);
    }

    @Test
    void glueCannotBeSplitEvenByEmergencyWrapping() {
        for (String text : new String[]{"a\u00A0b", "a\u202Fb", "a\u2060b"}) {
            UnicodeBreaks.Analysis result = UnicodeBreaks.analyze(text);
            assertFalse(result.line[1]);
            assertFalse(result.line[2]);
            assertFalse(result.emergency[1]);
            assertFalse(result.emergency[2]);
        }
    }

    @Test
    void hardBreaksIdentifyCrLfAsOneSeparatorAndKeepOriginalOffsets() {
        UnicodeBreaks.Analysis result = UnicodeBreaks.analyze("a\r\nb\u0085\u2028c\u2029");
        for (int offset = 0; offset < result.mandatory.length; offset++) {
            assertEquals(offset == 3 || offset == 5 || offset == 6 || offset == 8,
                    result.mandatory[offset]);
        }
        assertFalse(result.emergency[2]);
    }

    @Test
    void emergencyWrappingDoesNotStrandAnOrthographicConjunct() {
        UnicodeBreaks.Analysis result = UnicodeBreaks.analyze("\u1781\u17D2\u1798");
        assertFalse(result.line[2]);
        assertFalse(result.emergency[2]);
    }

    @Test
    void concurrentDocumentsDoNotShareIteratorState() {
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> checkRepeated("界面文字。", 4));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> checkRepeated("a\u0301\uD83C\uDDE8\uD83C\uDDF3", 3));
        CompletableFuture.allOf(first, second).join();
    }

    private static void checkRepeated(String text, int internalBoundary) {
        for (int i = 0; i < 100; i++) {
            UnicodeBreaks.Analysis result = UnicodeBreaks.analyze(text);
            assertEquals(text.length(), result.graphemes[result.graphemes.length - 1]);
            assertTrue(result.emergency[text.length()]);
            assertFalse(result.line[internalBoundary]);
        }
    }
}

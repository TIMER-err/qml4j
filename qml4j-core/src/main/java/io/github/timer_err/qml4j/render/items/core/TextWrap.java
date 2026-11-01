package io.github.timer_err.qml4j.render.items.core;

import io.github.timer_err.qml4j.render.text.UnicodeBreaks;

import java.util.ArrayList;
import java.util.List;

public final class TextWrap {

    private TextWrap() {}

    public interface Measure {
        float widthOf(String s);
    }

    public static final class Result {
        public final List<String> lines;
        public final int[] starts;
        public Result(List<String> lines, int[] starts) {
            this.lines = lines;
            this.starts = starts;
        }
    }

    public static Result wrap(String text, String mode, float maxWidth, Measure m) {
        List<String> lines = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        if (text == null) text = "";
        boolean wrapAny = "WrapAnywhere".equals(mode);
        // WordWrap breaks at word boundaries only; a word wider than the box overflows
        // rather than being split (Qt). Wrap falls back to a mid-word break when a single
        // word does not fit.
        boolean wordOnly = "WordWrap".equals(mode);
        boolean wrap = "Wrap".equals(mode) || wordOnly || wrapAny;
        int start = 0;
        int n = text.length();
        while (start <= n) {
            int hardBreak = nextHardBreak(text, start);
            int segEnd = hardBreak < 0 ? n : hardBreak;
            String seg = text.substring(start, segEnd);
            if (!wrap || maxWidth <= 0 || m.widthOf(seg) <= maxWidth) {
                starts.add(start);
                lines.add(seg);
            } else {
                wrapSegment(seg, start, maxWidth, m, wrapAny, wordOnly, lines, starts);
            }
            if (hardBreak < 0) break;
            start = hardBreak + 1;
            if (text.charAt(hardBreak) == '\r' && start < n && text.charAt(start) == '\n') start++;
            if (start == n) {
                starts.add(n);
                lines.add("");
                break;
            }
        }
        if (lines.isEmpty()) {
            starts.add(0);
            lines.add("");
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = starts.get(i);
        return new Result(lines, arr);
    }

    private static void wrapSegment(String seg, int origin, float maxWidth, Measure m,
                                    boolean wrapAny, boolean wordOnly, List<String> outLines,
                                    List<Integer> outStarts) {
        UnicodeBreaks.Analysis breaks = UnicodeBreaks.analyze(seg);
        int from = 0;
        while (from + 1 < breaks.graphemes.length) {
            int fitted = fittingGrapheme(seg, breaks.graphemes, from, maxWidth, m);
            int next = chooseBoundary(breaks, from, fitted, wrapAny, wordOnly);
            outStarts.add(origin + breaks.graphemes[from]);
            outLines.add(seg.substring(breaks.graphemes[from], breaks.graphemes[next]));
            from = next;
        }
    }

    private static int fittingGrapheme(String text, int[] graphemes, int from,
                                       float maxWidth, Measure measure) {
        int low = from + 1, high = graphemes.length - 1, best = from;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (measure.widthOf(text.substring(graphemes[from], graphemes[mid])) <= maxWidth) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    private static int chooseBoundary(UnicodeBreaks.Analysis breaks, int from, int fitted,
                                       boolean anywhere, boolean wordOnly) {
        if (fitted == breaks.graphemes.length - 1) return fitted;
        if (anywhere) return Math.max(from + 1, fitted);
        int preferred = precedingBoundary(breaks.graphemes, breaks.line, from, fitted);
        if (preferred > from) return preferred;
        if (wordOnly) return followingBoundary(breaks.graphemes, breaks.line, from);
        int emergency = precedingBoundary(breaks.graphemes, breaks.emergency, from, fitted);
        return emergency > from ? emergency
                : followingBoundary(breaks.graphemes, breaks.emergency, from);
    }

    private static int precedingBoundary(int[] graphemes, boolean[] allowed, int from, int end) {
        for (int i = end; i > from; i--) if (allowed[graphemes[i]]) return i;
        return from;
    }

    private static int followingBoundary(int[] graphemes, boolean[] allowed, int from) {
        int i = from + 1;
        while (!allowed[graphemes[i]]) i++;
        return i;
    }

    private static int nextHardBreak(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            // UAX #14 BK, CR, LF and NL separators; CRLF is consumed as one break.
            if (c == '\n' || c == '\r' || c == '\013' || c == '\f'
                    || c == '\u0085' || c == '\u2028' || c == '\u2029') return i;
        }
        return -1;
    }

    public static int caretInLine(String line, float localX, Measure m) {
        if (line == null || line.isEmpty() || localX <= 0) return 0;
        float prev = 0f;
        int n = line.length();
        for (int i = 1; i <= n; i++) {
            float w = m.widthOf(line.substring(0, i));
            if (w >= localX) {
                float mid = (prev + w) / 2f;
                return localX < mid ? i - 1 : i;
            }
            prev = w;
        }
        return n;
    }

    public static int moveCaretVertical(Result r, int caret, int delta, Measure m) {
        int line = lineForCaret(r, caret);
        int target = line + delta;
        if (target < 0 || target >= r.lines.size()) return caret;
        int col = caret - r.starts[line];
        String src = r.lines.get(line);
        String dst = r.lines.get(target);
        float x = col <= 0 ? 0 : m.widthOf(src.substring(0, Math.min(col, src.length())));
        int newCol = caretInLine(dst, x, m);
        return r.starts[target] + newCol;
    }

    public static int lineForCaret(Result r, int caret) {
        int idx = 0;
        for (int i = 0; i < r.starts.length; i++) {
            if (r.starts[i] <= caret) idx = i;
            else break;
        }
        int end = idx + 1 < r.starts.length
            ? r.starts[idx + 1]
            : r.starts[idx] + r.lines.get(idx).length() + 1;
        if (caret >= end && idx + 1 < r.starts.length) idx++;
        return idx;
    }
}

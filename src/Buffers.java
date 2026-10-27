import java.io.IOException;
import java.io.Writer;

/** Which of the two buffers a piece points into. */
enum Source { ORIGINAL, ADD }

/** Read-only view of a character buffer. Pieces refer to ranges of a TextBuffer. */
interface TextBuffer {
    int length();
    char charAt(int index);
    String substring(int start, int len);
    void appendTo(StringBuilder sb, int start, int len);
    void writeTo(Writer w, int start, int len) throws IOException;
    int count(char c, int start, int len);
}

final class BufferRange {
    private BufferRange() {}

    static void check(int start, int len, int bufferLength) {
        if (start < 0 || len < 0 || start > bufferLength || len > bufferLength - start) {
            throw new IndexOutOfBoundsException(
                "range [" + start + ",+" + len + ") outside buffer of length " + bufferLength);
        }
    }
}

/**
 * The Original Buffer: the text loaded from the file. Backed by an immutable String,
 * so it can never be modified or overwritten while editing.
 */
final class OriginalBuffer implements TextBuffer {
    private final String text;

    public OriginalBuffer(String text) {
        if (text == null) throw new IllegalArgumentException("text is null");
        this.text = text;
    }

    @Override public int length() { return text.length(); }

    @Override public char charAt(int index) { return text.charAt(index); }

    @Override public String substring(int start, int len) {
        BufferRange.check(start, len, text.length());
        return text.substring(start, start + len);
    }

    @Override public void appendTo(StringBuilder sb, int start, int len) {
        BufferRange.check(start, len, text.length());
        sb.append(text, start, start + len);
    }

    @Override public void writeTo(Writer w, int start, int len) throws IOException {
        BufferRange.check(start, len, text.length());
        w.write(text, start, len);
    }

    @Override public int count(char c, int start, int len) {
        BufferRange.check(start, len, text.length());
        int n = 0;
        for (int i = start; i < start + len; i++) if (text.charAt(i) == c) n++;
        return n;
    }
}

/**
 * The Add Buffer: append-only storage for every character the user inserts.
 * Existing contents are never overwritten or shifted, so offsets handed out by
 * {@link #append(String)} remain valid forever (this is what makes pieces and undo safe).
 * Growth is amortised O(1) per character (capacity doubling copies only this buffer).
 */
final class AddBuffer implements TextBuffer {
    private char[] data = new char[256];
    private int length;

    /** Appends text and returns the start offset of the appended range. */
    public int append(String s) {
        int start = length;
        int need = length + s.length();
        if (need < 0) throw new IllegalStateException("Add buffer too large");
        if (need > data.length) {
            long cap = Math.max((long) data.length * 2, need);
            data = java.util.Arrays.copyOf(data, (int) Math.min(cap, Integer.MAX_VALUE - 8));
        }
        s.getChars(0, s.length(), data, length);
        length = need;
        return start;
    }

    @Override public int length() { return length; }

    @Override public char charAt(int index) {
        if (index < 0 || index >= length) throw new IndexOutOfBoundsException("add buffer index " + index);
        return data[index];
    }

    @Override public String substring(int start, int len) {
        BufferRange.check(start, len, length);
        return new String(data, start, len);
    }

    @Override public void appendTo(StringBuilder sb, int start, int len) {
        BufferRange.check(start, len, length);
        sb.append(data, start, len);
    }

    @Override public void writeTo(Writer w, int start, int len) throws IOException {
        BufferRange.check(start, len, length);
        w.write(data, start, len);
    }

    @Override public int count(char c, int start, int len) {
        BufferRange.check(start, len, length);
        int n = 0;
        for (int i = start; i < start + len; i++) if (data[i] == c) n++;
        return n;
    }
}

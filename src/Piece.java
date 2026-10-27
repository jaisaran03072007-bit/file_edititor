/**
 * A piece: (buffer source, start offset in that buffer, length). Immutable.
 * It represents the text buffer[start, start+length). Pieces never hold characters themselves.
 */
final class Piece {
    private final Source source;
    private final int start;
    private final int length;

    public Piece(Source source, int start, int length) {
        if (source == null) throw new IllegalArgumentException("source is null");
        if (start < 0) throw new IllegalArgumentException("negative start: " + start);
        if (length <= 0) throw new IllegalArgumentException("piece length must be positive: " + length);
        this.source = source;
        this.start = start;
        this.length = length;
    }

    public Source source() { return source; }
    public int start() { return start; }
    public int length() { return length; }
    public int end() { return start + length; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Piece)) return false;
        Piece p = (Piece) o;
        return p.source == source && p.start == start && p.length == length;
    }

    @Override public int hashCode() { return (source.ordinal() * 31 + start) * 31 + length; }

    @Override public String toString() { return source + "[" + start + ",+" + length + ")"; }
}

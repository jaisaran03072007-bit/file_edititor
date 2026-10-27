import java.util.Collections;
import java.util.List;

/**
 * A reversible edit record. It stores piece references, not copies of the text:
 * the buffers never overwrite old characters, so the pieces always still describe the same text.
 * <ul>
 *   <li>INSERT at position: undo = delete [position, position+length); redo = re-insert the pieces.</li>
 *   <li>DELETE at position: the detached pieces are kept; undo = re-insert them at position.</li>
 * </ul>
 * Edits that were produced by one user action (e.g. typing over a selection) share a group id
 * and are undone/redone together.
 */
final class Edit {
    public enum Type { INSERT, DELETE }

    private final long id;
    private final Type type;
    private final int position;
    private final int length;
    private final List<Piece> pieces;
    private final int group;

    Edit(long id, Type type, int position, int length, List<Piece> pieces, int group) {
        this.id = id;
        this.type = type;
        this.position = position;
        this.length = length;
        this.pieces = Collections.unmodifiableList(pieces);
        this.group = group;
    }

    public long id() { return id; }
    public Type type() { return type; }
    public int position() { return position; }
    /** Number of characters inserted or deleted. */
    public int length() { return length; }
    public List<Piece> pieces() { return pieces; }
    /** 0 = ungrouped; otherwise edits with the same value form one undo step. */
    public int group() { return group; }

    @Override public String toString() {
        return type + "@" + position + " len=" + length + " pieces=" + pieces;
    }
}

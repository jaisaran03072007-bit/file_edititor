import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The Editing Controller: GUI -> EditingController -> PieceTree -> buffers.
 *
 * It owns the PieceTree and the undo/redo history. The GUI only calls the methods below and
 * renders {@link #getText()}; it never holds document state of its own.
 *
 * Invalid requests throw (IndexOutOfBoundsException / IllegalArgumentException) BEFORE anything is
 * changed, so a rejected edit leaves both the tree and the history untouched.
 */
final class EditingController {
    private PieceTree tree = new PieceTree("");
    private Path file;
    private final Deque<Edit> undoStack = new ArrayDeque<>();
    private final Deque<Edit> redoStack = new ArrayDeque<>();
    private long nextEditId = 1;
    private int nextGroup = 1;
    private long savedStateId = 0;     // id of the top undo edit at the time of the last save/open
    private int caretHint = 0;
    private long version = 0;           // bumped whenever the document text changes
    private final List<Runnable> listeners = new ArrayList<>();

    // ------------------------------------------------------------------ document lifecycle

    /** Starts a brand-new document state from text (fresh tree, fresh buffers, empty history). */
    public void newDocument(String text, Path file) {
        this.tree = new PieceTree(text);
        this.file = file;
        undoStack.clear();
        redoStack.clear();
        savedStateId = 0;
        caretHint = 0;
        version++;
        fire();
    }

    /**
     * Opens a UTF-8 file: its text becomes the immutable Original Buffer of a NEW PieceTree.
     * Nothing from the previously opened document (pieces, add buffer, history) is kept.
     * If reading fails the current document is left unchanged.
     */
    public void load(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("File is not valid UTF-8: " + path, e);
        }
        newDocument(text, path.toAbsolutePath());
    }

    /** Saves to the current file (only when the user asks for it). */
    public void save() throws IOException {
        if (file == null) throw new IllegalStateException("No file name yet - use Save As");
        writeFile(file);
        savedStateId = currentStateId();
        fire();
    }

    public void saveAs(Path target) throws IOException {
        Path abs = target.toAbsolutePath();
        writeFile(abs);
        file = abs;
        savedStateId = currentStateId();
        fire();
    }

    private void writeFile(Path target) throws IOException {
        Path dir = target.getParent();
        Path tmp = Files.createTempFile(dir, ".piecetree-", ".tmp");
        try {
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                tree.writeTo(w);   // streams piece by piece, O(N + p)
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ------------------------------------------------------------------ validation helpers

    private static void requireWellFormed(String text) {
        if (text == null) throw new IllegalArgumentException("text is null");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException("text contains an unpaired surrogate at index " + i);
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("text contains an unpaired surrogate at index " + i);
            }
        }
    }

    /** Rejects positions that would cut a surrogate pair (an emoji etc.) in half. O(log p). */
    private void requireBoundary(int pos) {
        if (pos > 0 && pos < tree.length()
            && Character.isHighSurrogate(tree.charAt(pos - 1))
            && Character.isLowSurrogate(tree.charAt(pos))) {
            throw new IllegalArgumentException("position " + pos + " is inside a surrogate pair");
        }
    }

    private void checkInsert(int pos, String text) {
        if (pos < 0 || pos > tree.length()) {
            throw new IndexOutOfBoundsException("insert position " + pos + " outside [0," + tree.length() + "]");
        }
        requireWellFormed(text);
        requireBoundary(pos);
    }

    private void checkDelete(int pos, int len) {
        if (pos < 0 || len < 0 || pos > tree.length() || len > tree.length() - pos) {
            throw new IndexOutOfBoundsException(
                "delete range [" + pos + ",+" + len + ") outside document of length " + tree.length());
        }
        requireBoundary(pos);
        requireBoundary(pos + len);
    }

    // ------------------------------------------------------------------ editing operations

    /** Inserts text at pos. Empty text is a no-op (no history entry). */
    public void insert(int pos, String text) {
        checkInsert(pos, text);
        if (text.isEmpty()) return;
        applyInsert(pos, text, 0);
        fire();
    }

    /** Deletes len characters starting at pos. len == 0 is a no-op. */
    public void delete(int pos, int len) {
        checkDelete(pos, len);
        if (len == 0) return;
        applyDelete(pos, len, 0);
        fire();
    }

    /** Replaces [pos, pos+len) with text as ONE undo step (delete + insert share a group). */
    public void replace(int pos, int len, String text) {
        checkDelete(pos, len);
        requireWellFormed(text);
        if (len == 0 && text.isEmpty()) return;
        int group = (len > 0 && !text.isEmpty()) ? nextGroup++ : 0;
        if (len > 0) applyDelete(pos, len, group);
        if (!text.isEmpty()) applyInsert(pos, text, group);
        fire();
    }

    private void applyInsert(int pos, String text, int group) {
        List<Piece> pieces = tree.insert(pos, text);
        undoStack.push(new Edit(nextEditId++, Edit.Type.INSERT, pos, text.length(), pieces, group));
        redoStack.clear();                       // a new edit invalidates the redo history
        caretHint = pos + text.length();
        version++;
    }

    private void applyDelete(int pos, int len, int group) {
        List<Piece> removed = tree.delete(pos, len);
        undoStack.push(new Edit(nextEditId++, Edit.Type.DELETE, pos, len, removed, group));
        redoStack.clear();
        caretHint = pos;
        version++;
    }

    // ------------------------------------------------------------------ undo / redo

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
    public int undoDepth() { return undoStack.size(); }
    public int redoDepth() { return redoStack.size(); }

    /** Reverses the most recent user action through the PieceTree. Returns false if nothing to undo. */
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        int group = undoStack.peek().group();
        do {
            Edit e = undoStack.pop();
            if (e.type() == Edit.Type.INSERT) {
                tree.delete(e.position(), e.length());             // remove what was inserted
                caretHint = e.position();
            } else {
                tree.insertPieces(e.position(), e.pieces());       // restore the detached pieces
                caretHint = e.position() + e.length();
            }
            redoStack.push(e);
            version++;
        } while (group != 0 && !undoStack.isEmpty() && undoStack.peek().group() == group);
        fire();
        return true;
    }

    /** Re-applies the most recently undone action through the PieceTree. */
    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        int group = redoStack.peek().group();
        do {
            Edit e = redoStack.pop();
            if (e.type() == Edit.Type.INSERT) {
                tree.insertPieces(e.position(), e.pieces());
                caretHint = e.position() + e.length();
            } else {
                tree.delete(e.position(), e.length());
                caretHint = e.position();
            }
            undoStack.push(e);
            version++;
        } while (group != 0 && !redoStack.isEmpty() && redoStack.peek().group() == group);
        fire();
        return true;
    }

    // ------------------------------------------------------------------ read access (what the GUI renders)

    public String getText() { return tree.getText(); }
    public int length() { return tree.length(); }
    public int lineCount() { return tree.countChar('\n') + 1; }
    public Path getFile() { return file; }
    /** Changes whenever the document text changes (not on save). Lets the GUI skip needless re-rendering. */
    public long version() { return version; }
    public int caretHint() { return Math.min(caretHint, tree.length()); }

    /** Read access for visualisation and tests. Callers must not use it to modify the tree. */
    public PieceTree tree() { return tree; }

    private long currentStateId() { return undoStack.isEmpty() ? 0 : undoStack.peek().id(); }

    /** True when the document differs (in edit history terms) from the last saved/opened state. */
    public boolean isDirty() { return currentStateId() != savedStateId; }

    public Edit lastEdit() { return undoStack.peek(); }

    // ------------------------------------------------------------------ change notification

    public void addChangeListener(Runnable r) { listeners.add(r); }

    private int batchDepth;
    private boolean pendingFire;

    /**
     * Runs many edits and notifies listeners only ONCE at the end. Used for bulk operations so the
     * GUI does not regenerate the whole displayed text after every single edit.
     */
    public void runBatch(Runnable edits) {
        batchDepth++;
        try {
            edits.run();
        } finally {
            batchDepth--;
            if (batchDepth == 0 && pendingFire) {
                pendingFire = false;
                fire();
            }
        }
    }

    private void fire() {
        if (batchDepth > 0) {
            pendingFire = true;
            return;
        }
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }
}

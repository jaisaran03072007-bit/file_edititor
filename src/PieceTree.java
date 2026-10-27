import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Piece Tree: a piece table whose pieces are stored in an AVL tree.
 *
 * <ul>
 *   <li>The in-order sequence of nodes is the document order (NOT sorted by buffer offset).</li>
 *   <li>Every node stores its subtree character count, so a document position is resolved by
 *       walking one root-to-node path: O(log p).</li>
 *   <li>Edits are done with <b>split</b> and <b>join</b> (Adams / Blelloch et al. AVL join),
 *       never by rebuilding the tree.</li>
 *   <li>The Original Buffer is immutable; the Add Buffer is append-only.</li>
 * </ul>
 *
 * Positions are UTF-16 code-unit offsets (Java {@code char} indices), the same unit used by Swing.
 *
 * Notation in complexity comments: N = characters in document, p = pieces (nodes),
 * k = characters inserted/deleted, m = pieces touched by an edit.
 */
public final class PieceTree {
    private final OriginalBuffer original;
    private final AddBuffer add = new AddBuffer();
    private Node root;

    // Instrumentation used by tests/GUI to prove no whole-tree rebuilding happens per edit.
    private long nodesAllocated;
    private long fullTextBuilds;

    // ------------------------------------------------------------------ construction

    public PieceTree(String originalText) {
        this.original = new OriginalBuffer(originalText);
        if (original.length() > 0) {
            root = newNode(new Piece(Source.ORIGINAL, 0, original.length()));
        }
    }

    private Node newNode(Piece p) {
        nodesAllocated++;
        return new Node(p);
    }

    // ------------------------------------------------------------------ simple accessors

    /** Number of characters in the document. O(1). */
    public int length() { return Avl.size(root); }

    /** Number of pieces (nodes). O(1). */
    public int pieceCount() { return Avl.count(root); }

    /** Height of the AVL tree (0 for an empty tree). O(1). */
    public int height() { return Avl.height(root); }

    public Node root() { return root; }

    public int originalLength() { return original.length(); }
    public int addLength() { return add.length(); }

    /** Total nodes ever allocated; used to show edits allocate O(1) nodes, not O(p). */
    public long nodesAllocated() { return nodesAllocated; }

    /** How many times the whole document was materialised (display/save); edits never do this. */
    public long fullTextBuilds() { return fullTextBuilds; }

    public TextBuffer buffer(Source s) { return s == Source.ORIGINAL ? original : add; }

    /**
     * split(t, pos): returns {L, R} where L holds exactly the first pos characters and R the rest.
     * A piece that straddles pos is cut into two piece records (no characters are copied).
     * Cost O(log p): the joins performed on the way up telescope, because each join costs
     * O(height difference) and the sum of those differences along one path is O(log p).
     */
    private Node[] split(Node t, int pos) {
        if (t == null) return new Node[] {null, null};
        if (pos <= 0) return new Node[] {null, t};
        if (pos >= t.size) return new Node[] {t, null};

        Node l = t.left, r = t.right;
        int ls = Avl.size(l);
        int len = t.piece.length();

        if (pos < ls) {
            Node[] s = split(l, pos);
            return new Node[] {s[0], Avl.join(s[1], t, r)};
        }
        if (pos == ls) {
            return new Node[] {l, Avl.join(null, t, r)};
        }
        if (pos < ls + len) {                       // cut inside this piece
            int inside = pos - ls;
            Piece p = t.piece;
            Piece leftPiece = new Piece(p.source(), p.start(), inside);
            Piece rightPiece = new Piece(p.source(), p.start() + inside, len - inside);
            t.piece = leftPiece;                    // reuse t for the left half
            Node rightNode = newNode(rightPiece);
            return new Node[] {Avl.join(l, t, null), Avl.join(null, rightNode, r)};
        }
        Node[] s = split(r, pos - ls - len);
        return new Node[] {Avl.join(l, t, s[0]), s[1]};
    }

    /** Builds a perfectly balanced (hence AVL) tree from pieces[lo,hi) in O(m). */
    private Node build(List<Piece> pieces, int lo, int hi) {
        if (lo >= hi) return null;
        int mid = (lo + hi) >>> 1;
        Node n = newNode(pieces.get(mid));
        n.left = build(pieces, lo, mid);
        n.right = build(pieces, mid + 1, hi);
        Avl.update(n);
        return n;
    }

    // ------------------------------------------------------------------ editing API

    private void checkPos(int pos) {
        if (pos < 0 || pos > length()) {
            throw new IndexOutOfBoundsException("position " + pos + " outside [0," + length() + "]");
        }
    }

    private void checkRange(int pos, int len) {
        if (pos < 0 || len < 0 || pos > length() || len > length() - pos) {
            throw new IndexOutOfBoundsException(
                "range [" + pos + ",+" + len + ") outside document of length " + length());
        }
    }

    /**
     * Inserts text at document position pos. The characters are appended to the Add Buffer and a
     * new piece pointing at them is joined into the tree.
     * Cost: O(k) to append to the add buffer (amortised) + O(log p) split/join = O(k + log p).
     *
     * @return the piece(s) that were inserted (an empty list if text is empty)
     */
    public List<Piece> insert(int pos, String text) {
        checkPos(pos);
        if (text == null) throw new IllegalArgumentException("text is null");
        if (text.isEmpty()) return Collections.emptyList();
        int start = add.append(text);
        List<Piece> pieces = Collections.singletonList(new Piece(Source.ADD, start, text.length()));
        insertPiecesUnchecked(pos, pieces);
        return pieces;
    }

    /**
     * Re-inserts existing piece records (used by undo/redo, which must NOT copy text again).
     * Cost: O(m) to build a balanced subtree of the m pieces + O(log p) split/join.
     */
    public void insertPieces(int pos, List<Piece> pieces) {
        checkPos(pos);
        for (Piece p : pieces) {
            TextBuffer b = buffer(p.source());
            if (p.end() > b.length()) {
                throw new IllegalArgumentException("piece " + p + " outside its buffer (length " + b.length() + ")");
            }
        }
        if (pieces.isEmpty()) return;
        insertPiecesUnchecked(pos, pieces);
    }

    private void insertPiecesUnchecked(int pos, List<Piece> pieces) {
        Node[] s = split(root, pos);
        if (pieces.size() == 1) {
            root = Avl.join(s[0], newNode(pieces.get(0)), s[1]);
        } else {
            Node mid = build(pieces, 0, pieces.size());
            root = Avl.join2(Avl.join2(s[0], mid), s[1]);
        }
    }

    /**
     * Deletes [pos, pos+len). Characters are not erased from any buffer; whole pieces are detached
     * and boundary pieces are trimmed by splitting.
     * Cost: O(log p) for the two splits and the join, + O(m) to list the m detached pieces
     * so that undo can restore them. No text is copied.
     *
     * @return the detached pieces in document order
     */
    public List<Piece> delete(int pos, int len) {
        checkRange(pos, len);
        if (len == 0) return Collections.emptyList();
        Node[] a = split(root, pos);
        Node[] b = split(a[1], len);
        List<Piece> removed = new ArrayList<>(Math.min(Avl.count(b[0]), 1024));
        collectPieces(b[0], removed);
        root = Avl.join2(a[0], b[1]);
        return removed;
    }

    // ------------------------------------------------------------------ queries

    /**
     * Resolves the character at document position pos (0 <= pos < length) by walking down the tree
     * using subtree character counts. O(log p). Never scans the document.
     */
    public Location locate(int pos) {
        if (pos < 0 || pos >= length()) {
            throw new IndexOutOfBoundsException("position " + pos + " outside [0," + length() + ")");
        }
        Node n = root;
        int base = 0;
        int depth = 1;
        while (true) {
            int ls = Avl.size(n.left);
            int len = n.piece.length();
            if (pos < ls) {
                n = n.left;
            } else if (pos < ls + len) {
                return new Location(n, pos - ls, base + ls, depth);
            } else {
                pos -= ls + len;
                base += ls + len;
                n = n.right;
            }
            depth++;
        }
    }

    /** Character at pos. O(log p). */
    public char charAt(int pos) {
        Location loc = locate(pos);
        Piece p = loc.piece();
        return buffer(p.source()).charAt(p.start() + loc.offsetInPiece());
    }

    /** Text in [start, end). O(log p + output size + pieces overlapped). */
    public String substring(int start, int end) {
        if (start < 0 || end < start || end > length()) {
            throw new IndexOutOfBoundsException("range [" + start + "," + end + ") outside [0," + length() + "]");
        }
        StringBuilder sb = new StringBuilder(end - start);
        collectText(root, start, end, sb);
        return sb.toString();
    }

    private void collectText(Node n, int from, int to, StringBuilder sb) {
        if (n == null || from >= to) return;
        int ls = Avl.size(n.left);
        int len = n.piece.length();
        if (from < ls) collectText(n.left, from, Math.min(to, ls), sb);
        int ps = ls, pe = ls + len;
        if (to > ps && from < pe) {
            int a = Math.max(from, ps) - ps;
            int b = Math.min(to, pe) - ps;
            buffer(n.piece.source()).appendTo(sb, n.piece.start() + a, b - a);
        }
        if (to > pe) collectText(n.right, Math.max(from, pe) - pe, to - pe, sb);
    }

    /** The whole document (in-order concatenation of pieces). O(N + p) = O(N). Display/test use only. */
    public String getText() {
        fullTextBuilds++;
        return substring(0, length());
    }

    /** Streams the document to a writer without building one big string. O(N + p). */
    public void writeTo(Writer w) throws IOException {
        fullTextBuilds++;
        writeNode(root, w);
    }

    private void writeNode(Node n, Writer w) throws IOException {
        if (n == null) return;
        writeNode(n.left, w);
        buffer(n.piece.source()).writeTo(w, n.piece.start(), n.piece.length());
        writeNode(n.right, w);
    }

    /** Counts occurrences of c in the document without building the text. O(N + p). */
    public int countChar(char c) {
        return countNode(root, c);
    }

    private int countNode(Node n, char c) {
        if (n == null) return 0;
        return countNode(n.left, c)
            + buffer(n.piece.source()).count(c, n.piece.start(), n.piece.length())
            + countNode(n.right, c);
    }

    /** Pieces in document (in-order) order. O(p). */
    public List<Piece> pieces() {
        List<Piece> out = new ArrayList<>(pieceCount());
        collectPieces(root, out);
        return out;
    }

    static void collectPieces(Node n, List<Piece> out) {
        if (n == null) return;
        collectPieces(n.left, out);
        out.add(n.piece);
        collectPieces(n.right, out);
    }

    // ------------------------------------------------------------------ invariant checks (used by tests and GUI "Verify")

    /** Every piece is non-empty and lies inside the buffer it names. */
    public boolean piecesValid() { return piecesValid(root); }

    private boolean piecesValid(Node n) {
        if (n == null) return true;
        Piece p = n.piece;
        if (p == null || p.length() <= 0 || p.start() < 0) return false;
        if (p.end() > buffer(p.source()).length()) return false;
        return piecesValid(n.left) && piecesValid(n.right);
    }

    /** Every stored height equals 1 + max(child heights), recomputed from scratch. */
    public boolean heightsCorrect() { return trueHeight(root, false) >= 0; }

    /** Every balance factor (recomputed from real heights) lies in [-1, +1]. */
    public boolean balanced() { return trueHeight(root, true) >= 0; }

    /** Real height of a subtree, or -1 if a stored height (checkBalance=false) or a balance factor (true) is wrong. */
    static int trueHeight(Node n, boolean checkBalance) {
        if (n == null) return 0;
        int l = trueHeight(n.left, checkBalance);
        int r = trueHeight(n.right, checkBalance);
        if (l < 0 || r < 0) return -1;
        int h = 1 + Math.max(l, r);
        if (checkBalance) return Math.abs(l - r) <= 1 ? h : -1;
        return h == n.height ? h : -1;
    }

    /** Every stored subtree character count and piece count equals the recomputed value. */
    public boolean countsCorrect() { return trueSize(root) >= 0; }

    /** Real character count of a subtree, or -1 if a stored size/count is wrong. */
    static long trueSize(Node n) {
        if (n == null) return 0;
        long l = trueSize(n.left);
        long r = trueSize(n.right);
        if (l < 0 || r < 0) return -1;
        long s = l + r + n.piece.length();
        long c = Avl.count(n.left) + Avl.count(n.right) + 1L;
        return (s == n.size && c == n.count) ? s : -1;
    }

    /** Runs every check; returns an empty list when the tree is fully consistent. */
    public List<String> findViolations() {
        List<String> v = new ArrayList<>();
        if (!piecesValid()) v.add("a piece is empty or points outside its buffer");
        if (!heightsCorrect()) v.add("a stored AVL height is wrong");
        if (!balanced()) v.add("a balance factor is outside [-1,+1]");
        if (!countsCorrect()) v.add("a subtree character/piece count is wrong");
        return v;
    }

    /** Throws IllegalStateException describing the first problem found. */
    public void validate() {
        List<String> v = findViolations();
        if (!v.isEmpty()) throw new IllegalStateException("Piece tree invariant violated: " + v);
    }

    // ------------------------------------------------------------------ debugging / visualisation

    /** Indented outline of the tree (pre-order) for the GUI and README. */
    public String dump(int maxLines) {
        StringBuilder sb = new StringBuilder();
        int[] lines = {0};
        dumpNode(root, "", "root", sb, lines, maxLines);
        if (lines[0] >= maxLines) sb.append("... (truncated; ").append(pieceCount()).append(" pieces in total)\n");
        if (root == null) sb.append("(empty tree)\n");
        return sb.toString();
    }

    private void dumpNode(Node n, String indent, String label, StringBuilder sb, int[] lines, int max) {
        if (n == null || lines[0] >= max) return;
        lines[0]++;
        sb.append(indent).append(label).append(": ").append(n.piece)
          .append("  h=").append(n.height)
          .append(" bf=").append(Avl.balanceFactor(n))
          .append(" subtreeChars=").append(n.size)
          .append(" subtreePieces=").append(n.count).append('\n');
        dumpNode(n.left, indent + "    ", "L", sb, lines, max);
        dumpNode(n.right, indent + "    ", "R", sb, lines, max);
    }
}

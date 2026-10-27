/** Result of resolving a document position through the tree. */
final class Location {
    private final Node node;
    private final int offsetInPiece;
    private final int pieceStart;
    private final int depth;

    Location(Node node, int offsetInPiece, int pieceStart, int depth) {
        this.node = node;
        this.offsetInPiece = offsetInPiece;
        this.pieceStart = pieceStart;
        this.depth = depth;
    }

    public Node node() { return node; }
    public Piece piece() { return node.piece(); }
    /** Index of the character inside the piece. */
    public int offsetInPiece() { return offsetInPiece; }
    /** Document offset of the first character of the piece. */
    public int pieceStart() { return pieceStart; }
    /** Number of nodes visited on the root-to-node path (at most the tree height). */
    public int depth() { return depth; }
}

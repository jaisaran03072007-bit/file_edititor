/**
 * AVL tree node. In-order position of a node = position of its piece in the document.
 * Fields are package-private: only {@link PieceTree} mutates them.
 */
final class Node {
    Piece piece;
    Node left, right;
    int height;  // AVL height; null child has height 0, a leaf has height 1
    int size;    // number of document characters in this subtree
    int count;   // number of pieces (nodes) in this subtree

    Node(Piece piece) {
        this.piece = piece;
        this.height = 1;
        this.size = piece.length();
        this.count = 1;
    }

    public Piece piece() { return piece; }
    public Node left() { return left; }
    public Node right() { return right; }
    public int height() { return height; }
    /** Subtree character count (characters in left subtree + this piece + right subtree). */
    public int subtreeChars() { return size; }
    public int subtreePieces() { return count; }
}

/**
 * AVL balancing toolkit for the piece tree: height/size bookkeeping, rotations, rebalance,
 * and the join / join2 operations used by split. All methods are static and O(1) or O(log p).
 */
final class Avl {
    private Avl() {}


    public static int height(Node n) { return n == null ? 0 : n.height; }
    public static int size(Node n) { return n == null ? 0 : n.size; }
    public static int count(Node n) { return n == null ? 0 : n.count; }

    /** Balance factor = height(left) - height(right). */
    public static int balanceFactor(Node n) { return n == null ? 0 : height(n.left) - height(n.right); }

    /** Recomputes height, subtree character count and subtree piece count from the children. O(1). */
    public static void update(Node n) {
        n.height = 1 + Math.max(height(n.left), height(n.right));
        n.size = size(n.left) + size(n.right) + n.piece.length();
        n.count = count(n.left) + count(n.right) + 1;
    }

    /** Right rotation (fixes a left-left heavy node). Returns the new subtree root. O(1). */
    public static Node rotateRight(Node y) {
        Node x = y.left;
        y.left = x.right;
        x.right = y;
        update(y);
        update(x);
        return x;
    }

    /** Left rotation (fixes a right-right heavy node). Returns the new subtree root. O(1). */
    public static Node rotateLeft(Node x) {
        Node y = x.right;
        x.right = y.left;
        y.left = x;
        update(x);
        update(y);
        return y;
    }

    /**
     * Restores the AVL property at n assuming the children are valid AVL trees whose heights
     * differ by at most 2. Handles LL (right rotation), RR (left rotation),
     * LR (left-right double rotation) and RL (right-left double rotation). O(1).
     */
    public static Node rebalance(Node n) {
        update(n);
        int bf = balanceFactor(n);
        if (bf > 1) {
            if (balanceFactor(n.left) < 0) n.left = rotateLeft(n.left);   // LR case
            return rotateRight(n);                                         // LL case
        }
        if (bf < -1) {
            if (balanceFactor(n.right) > 0) n.right = rotateRight(n.right); // RL case
            return rotateLeft(n);                                           // RR case
        }
        return n;
    }

    /**
     * join(l, k, r): all of l, then node k, then all of r, as one AVL tree. k's old children are
     * discarded. Cost O(|height(l) - height(r)| + 1) = O(log p).
     */
    public static Node join(Node l, Node k, Node r) {
        int hl = height(l), hr = height(r);
        if (hl > hr + 1) return joinRight(l, k, r);
        if (hr > hl + 1) return joinLeft(l, k, r);
        k.left = l;
        k.right = r;
        update(k);
        return k;
    }

    /** l is taller than r by more than 1: descend l's right spine until heights match. */
    private static Node joinRight(Node tl, Node k, Node tr) {
        Node l = tl.left;
        Node c = tl.right;
        if (height(c) <= height(tr) + 1) {
            k.left = c;
            k.right = tr;
            update(k);
            if (height(k) <= height(l) + 1) {
                tl.right = k;
                update(tl);
                return tl;
            }
            tl.right = rotateRight(k);
            update(tl);
            return rotateLeft(tl);
        }
        Node t2 = joinRight(c, k, tr);
        tl.right = t2;
        update(tl);
        if (height(t2) <= height(l) + 1) return tl;
        return rotateLeft(tl);
    }

    /** Mirror image of joinRight: r is taller than l by more than 1. */
    private static Node joinLeft(Node tl, Node k, Node tr) {
        Node c = tr.left;
        Node r = tr.right;
        if (height(c) <= height(tl) + 1) {
            k.right = c;
            k.left = tl;
            update(k);
            if (height(k) <= height(r) + 1) {
                tr.left = k;
                update(tr);
                return tr;
            }
            tr.left = rotateLeft(k);
            update(tr);
            return rotateRight(tr);
        }
        Node t2 = joinLeft(tl, k, c);
        tr.left = t2;
        update(tr);
        if (height(t2) <= height(r) + 1) return tr;
        return rotateRight(tr);
    }

    /** Removes the leftmost node of n (AVL deletion of the minimum). out[0] receives it. O(log p). */
    private static Node removeMin(Node n, Node[] out) {
        if (n.left == null) {
            out[0] = n;
            Node r = n.right;
            n.right = null;
            update(n);
            return r;
        }
        n.left = removeMin(n.left, out);
        return rebalance(n);
    }

    /** join2(l, r): concatenates two AVL trees (all of l before all of r). O(log p). */
    public static Node join2(Node l, Node r) {
        if (l == null) return r;
        if (r == null) return l;
        Node[] min = new Node[1];
        Node r2 = removeMin(r, min);
        return join(l, min[0], r2);
    }
}

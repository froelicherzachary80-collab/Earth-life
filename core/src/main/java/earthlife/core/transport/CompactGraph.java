package earthlife.core.transport;

import earthlife.core.world.Features;

import java.util.*;

/**
 * A memory-compact road graph for long routes across the Northeast map (millions of vertices): coordinates and
 * adjacency in primitive arrays. Vertices of different ways within 1 m are joined (also across 1.5 m hash buckets),
 * so motorways from the global major-roads file connect to the detailed local streets of the cells around the start
 * and the destination. A* on travel time.
 */
public final class CompactGraph {
    private double[] xs = new double[1024], zs = new double[1024];
    private int n;
    private final HashMap<Long, int[]> buckets = new HashMap<>();
    // edges (built in lists, then frozen into CSR)
    private int[] ea = new int[2048], eb = new int[2048];
    private float[] ecost = new float[2048], elen = new float[2048];
    private int m;
    private int[] head, next;           // adjacency lists over directed edge indices (2 per undirected edge)

    static long key(double x, double z) { return (Math.round(x / 1.5) << 32) ^ (Math.round(z / 1.5) & 0xffffffffL); }

    private int node(double x, double z) {
        long bx = Math.round(x / 1.5), bz = Math.round(z / 1.5);
        for (long dx = -1; dx <= 1; dx++) for (long dz = -1; dz <= 1; dz++) {
            int[] b = buckets.get(((bx + dx) << 32) ^ ((bz + dz) & 0xffffffffL));
            if (b == null) continue;
            for (int k = 1; k <= b[0]; k++) { int v = b[k]; if (Math.abs(xs[v] - x) < 1.0 && Math.abs(zs[v] - z) < 1.0) return v; }
        }
        if (n == xs.length) { xs = Arrays.copyOf(xs, n * 2); zs = Arrays.copyOf(zs, n * 2); }
        xs[n] = x; zs[n] = z;
        long k = (bx << 32) ^ (bz & 0xffffffffL);
        int[] b = buckets.get(k);
        if (b == null) b = new int[3];
        else if (b[0] + 1 >= b.length) b = Arrays.copyOf(b, b.length * 2);
        b[++b[0]] = n;
        buckets.put(k, b);
        head = null;
        return n++;
    }

    /** Adds a way with a travel speed (m/s). */
    public void addWay(List<Features.Pt> line, double speed) {
        head = null;
        int prev = -1;
        for (Features.Pt p : line) {
            int v = node(p.x(), p.z());
            if (prev >= 0 && v != prev) {
                if (m == ea.length) { ea = Arrays.copyOf(ea, m * 2); eb = Arrays.copyOf(eb, m * 2); ecost = Arrays.copyOf(ecost, m * 2); elen = Arrays.copyOf(elen, m * 2); }
                float len = (float) Math.hypot(xs[v] - xs[prev], zs[v] - zs[prev]);
                ea[m] = prev; eb[m] = v; elen[m] = len; ecost[m] = (float) (len / speed); m++;
            }
            prev = v;
        }
    }

    public int nodes() { return n; }
    public int edges() { return m; }

    private void freeze() {
        if (head != null) return;
        head = new int[n]; Arrays.fill(head, -1);
        next = new int[2 * m];
        for (int e = 0; e < 2 * m; e++) {
            int from = e < m ? ea[e] : eb[e - m];
            next[e] = head[from]; head[from] = e;
        }
    }

    int nearest(double x, double z) {
        int best = -1; double bd = Double.MAX_VALUE;
        for (int v = 0; v < n; v++) { double d = (xs[v] - x) * (xs[v] - x) + (zs[v] - z) * (zs[v] - z); if (d < bd) { bd = d; best = v; } }
        return best;
    }

    /** Fastest path between the nodes nearest to the two points, or empty if they aren't connected. */
    public List<Features.Pt> route(double fx, double fz, double tx, double tz) {
        if (n == 0) return List.of();
        freeze();
        int s = nearest(fx, fz), t = nearest(tx, tz);
        double vmax = 31;
        double[] g = new double[n]; int[] prev = new int[n];
        Arrays.fill(g, Double.MAX_VALUE); Arrays.fill(prev, -1);
        boolean[] closed = new boolean[n];
        g[s] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1]));
        open.add(new double[]{s, Math.hypot(xs[s] - xs[t], zs[s] - zs[t]) / vmax});
        while (!open.isEmpty()) {
            int u = (int) open.poll()[0];
            if (closed[u]) continue;
            closed[u] = true;
            if (u == t) break;
            for (int e = head[u]; e >= 0; e = next[e]) {
                int to = e < m ? eb[e] : ea[e - m];
                double ng = g[u] + ecost[e < m ? e : e - m];
                if (ng < g[to]) { g[to] = ng; prev[to] = u; open.add(new double[]{to, ng + Math.hypot(xs[to] - xs[t], zs[to] - zs[t]) / vmax}); }
            }
        }
        if (s != t && prev[t] < 0) return List.of();
        ArrayDeque<Features.Pt> path = new ArrayDeque<>();
        for (int v = t; v >= 0; v = prev[v]) path.addFirst(new Features.Pt(xs[v], zs[v]));
        return new ArrayList<>(path);
    }
}

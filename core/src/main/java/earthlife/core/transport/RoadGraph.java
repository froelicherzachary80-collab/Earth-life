package earthlife.core.transport;

import earthlife.core.world.Features;

import java.util.*;

/**
 * Routable network built from map ways (roads or rails). Vertices shared by ways (OSM junctions) become graph nodes;
 * near-coincident endpoints within 1.5 m are merged. A* with travel-time cost (length / class speed).
 */
public final class RoadGraph {
    public record Node(int id, double x, double z) {}
    record Edge(int to, double length, double cost) {}

    private final List<Node> nodes = new ArrayList<>();
    private final List<List<Edge>> adj = new ArrayList<>();
    private final Map<Long, Integer> index = new HashMap<>();

    public static RoadGraph roads(Features f) {
        RoadGraph g = new RoadGraph();
        for (var r : f.roads) if (r.cls().drivable()) g.addWay(r.line(), speedFor(r.cls()));
        return g;
    }

    public static RoadGraph rails(Features f) {
        RoadGraph g = new RoadGraph();
        for (var r : f.roads) if (r.cls() == Features.RoadClass.RAIL) g.addWay(r.line(), 30);
        return g;
    }

    public static double speedFor(Features.RoadClass c) {
        return switch (c) { case MOTORWAY -> 29; case PRIMARY -> 22; case SECONDARY -> 18; case RESIDENTIAL -> 11; case SERVICE -> 7; default -> 5; };
    }

    private int node(double x, double z) {
        long k = (Math.round(x / 1.5) << 32) ^ (Math.round(z / 1.5) & 0xffffffffL);
        return index.computeIfAbsent(k, q -> { nodes.add(new Node(nodes.size(), x, z)); adj.add(new ArrayList<>()); return nodes.size() - 1; });
    }

    public void addWay(List<Features.Pt> line, double speed) {
        for (int i = 1; i < line.size(); i++) {
            var a = line.get(i - 1); var b = line.get(i);
            int na = node(a.x(), a.z()), nb = node(b.x(), b.z());
            if (na == nb) continue;
            double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
            adj.get(na).add(new Edge(nb, len, len / speed));
            adj.get(nb).add(new Edge(na, len, len / speed));
        }
    }

    public int size() { return nodes.size(); }
    public Node node(int id) { return nodes.get(id); }

    public int nearest(double x, double z) {
        int best = -1; double bd = Double.MAX_VALUE;
        for (Node n : nodes) { double d = Math.hypot(n.x() - x, n.z() - z); if (d < bd) { bd = d; best = n.id(); } }
        return best;
    }

    /** Fastest path as a polyline of node positions, or empty if unreachable. */
    public List<Features.Pt> route(double fx, double fz, double tx, double tz) {
        int s = nearest(fx, fz), t = nearest(tx, tz);
        if (s < 0 || t < 0) return List.of();
        double maxSpeed = 29;
        double[] g = new double[nodes.size()];
        int[] prev = new int[nodes.size()];
        Arrays.fill(g, Double.MAX_VALUE); Arrays.fill(prev, -1);
        g[s] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1]));
        open.add(new double[]{s, h(s, t, maxSpeed)});
        boolean[] closed = new boolean[nodes.size()];
        while (!open.isEmpty()) {
            int u = (int) open.poll()[0];
            if (closed[u]) continue;
            closed[u] = true;
            if (u == t) break;
            for (Edge e : adj.get(u)) {
                double ng = g[u] + e.cost();
                if (ng < g[e.to()]) { g[e.to()] = ng; prev[e.to()] = u; open.add(new double[]{e.to(), ng + h(e.to(), t, maxSpeed)}); }
            }
        }
        if (s != t && prev[t] < 0) return List.of();
        LinkedList<Features.Pt> path = new LinkedList<>();
        for (int v = t; v >= 0; v = prev[v]) path.addFirst(new Features.Pt(nodes.get(v).x(), nodes.get(v).z()));
        return path;
    }

    private double h(int a, int b, double vmax) { return Math.hypot(nodes.get(a).x() - nodes.get(b).x(), nodes.get(a).z() - nodes.get(b).z()) / vmax; }

    public static double length(List<Features.Pt> p) {
        double s = 0;
        for (int i = 1; i < p.size(); i++) s += Math.hypot(p.get(i).x() - p.get(i - 1).x(), p.get(i).z() - p.get(i - 1).z());
        return s;
    }
}

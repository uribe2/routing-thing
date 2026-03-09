package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.*;

public class LinkStateDatabase {

  // linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given
   * IP address using Dijkstra's algorithm with link weights (not hop count).
   *
   * format: "Path found: IP (W) -> IP (W) -> ... -> IP"
   *
   * @param destinationIP the simulated IP address of the destination router
   * @return the formatted path string, or "Path not found" if unreachable
   */
  String getShortestPath(String destinationIP) {
    WeightedGraph g = buildGraph();
    int srcIdx = g.indexOf(rd.simulatedIPAddress);
    int dstIdx = g.indexOf(destinationIP);

    if (srcIdx == -1 || dstIdx == -1) return "Path not found";

    int[] prev = computePrev(g, srcIdx);

    if (prev[dstIdx] == -1 && dstIdx != srcIdx) return "Path not found";

    // Reconstruct path as list of indices
    List<Integer> path = new ArrayList<>();
    for (int at = dstIdx; at != -1; at = prev[at]) {
      path.add(0, at);
    }

    // Format as "Path found: IP (W) -> IP (W) -> ... -> IP"
    StringBuilder sb = new StringBuilder("Path found: ");
    for (int i = 0; i < path.size() - 1; i++) {
      int u = path.get(i);
      int v = path.get(i + 1);
      sb.append(g.nodeIDs[u]).append(" (").append(g.edges[u][v]).append(") -> ");
    }
    sb.append(g.nodeIDs[path.get(path.size() - 1)]);

    return sb.toString();
  }

  /**
   * Get the next hop on the shortest path to the destination.
   * Used for forwarding application messages.
   *
   * @param destinationIP the simulated IP address of the destination router
   * @return the IP address of the next hop, or null if no path exists
   */
  String getNextHop(String destinationIP) {
    WeightedGraph g = buildGraph();
    int srcIdx = g.indexOf(rd.simulatedIPAddress);
    int dstIdx = g.indexOf(destinationIP);

    if (srcIdx == -1 || dstIdx == -1) return null;

    int[] prev = computePrev(g, srcIdx);

    if (prev[dstIdx] == -1 && dstIdx != srcIdx) return null;

    // Backtrack from destination until we find the node whose prev is the source
    int at = dstIdx;
    while (prev[at] != srcIdx) {
      at = prev[at];
      if (at == -1) return null;
    }

    return g.nodeIDs[at];
  }

  /**
   * Run Dijkstra from srcIdx and return the prev[] array for path reconstruction.
   * prev[i] = index of the node before i on the shortest path from srcIdx, or -1.
   */
  private int[] computePrev(WeightedGraph g, int srcIdx) {
    int n = g.nodeIDs.length;
    int[] dist = new int[n];
    int[] prev = new int[n];
    boolean[] visited = new boolean[n];

    Arrays.fill(dist, Integer.MAX_VALUE);
    Arrays.fill(prev, -1);
    dist[srcIdx] = 0;

    // int[] entries: [nodeIndex, distance]
    PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[1]));
    pq.offer(new int[]{srcIdx, 0});

    while (!pq.isEmpty()) {
      int[] curr = pq.poll();
      int u = curr[0];

      if (visited[u]) continue;
      visited[u] = true;

      for (int v = 0; v < n; v++) {
        if (g.edges[u][v] > 0 && !visited[v]) {
          int newDist = dist[u] + g.edges[u][v];
          if (newDist < dist[v]) {
            dist[v] = newDist;
            prev[v] = u;
            pq.offer(new int[]{v, newDist});
          }
        }
      }
    }

    return prev;
  }

  /**
   * Build a WeightedGraph from the Link State Database.
   * Each LSA router becomes a node; each linkDescription defines a directed edge.
   */
  private WeightedGraph buildGraph() {
    // Collect all node IDs (preserves insertion order for stable indexing)
    Set<String> nodeSet = new LinkedHashSet<>();
    for (LSA lsa : _store.values()) {
      nodeSet.add(lsa.linkStateID);
      for (LinkDescription ld : lsa.links) {
        nodeSet.add(ld.linkID);
      }
    }

    String[] nodeIDs = nodeSet.toArray(new String[0]);
    WeightedGraph g = new WeightedGraph(nodeIDs.length);
    g.nodeIDs = nodeIDs;

    // Fill edge matrix; skip self-links (weight 0 means no edge)
    for (LSA lsa : _store.values()) {
      int i = g.indexOf(lsa.linkStateID);
      for (LinkDescription ld : lsa.links) {
        if (!ld.linkID.equals(lsa.linkStateID)) {
          int j = g.indexOf(ld.linkID);
          g.edges[i][j] = (short) ld.weight;
        }
      }
    }

    return g;
  }

  // initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    ld.tosMetrics = 0;
    ld.weight = 0; // self-link has weight 0
    lsa.links.add(ld);
    return lsa;
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa : _store.values()) {
      sb.append(lsa.linkStateID).append("(" + lsa.lsaSeqNumber + ")").append(":\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append(",").append(ld.weight).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }
}

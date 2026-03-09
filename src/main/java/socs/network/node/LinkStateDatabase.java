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
   * IP address
   * using Dijkstra's algorithm with link weights (not hop count).
   *
   * format: source ip address -> ip address -> ... -> destination ip
   *
   * @param destinationIP the simulated IP address of the destination router
   * @return the shortest path as a string, or null if no path exists
   */
  String getShortestPath(String destinationIP) {
    // Build the graph from LSA database
    Map<String, Map<String, Integer>> graph = buildGraph();

    // Run Dijkstra's algorithm
    Map<String, Integer> distances = new HashMap<>();
    Map<String, String> previous = new HashMap<>();
    PriorityQueue<NodeDistance> pq = new PriorityQueue<>();
    Set<String> visited = new HashSet<>();

    String source = rd.simulatedIPAddress;

    // Initialize distances
    for (String node : graph.keySet()) {
      distances.put(node, Integer.MAX_VALUE);
    }
    distances.put(source, 0);
    pq.offer(new NodeDistance(source, 0));

    // Dijkstra's algorithm
    while (!pq.isEmpty()) {
      NodeDistance current = pq.poll();
      String currentNode = current.node;

      if (visited.contains(currentNode)) {
        continue;
      }
      visited.add(currentNode);

      if (currentNode.equals(destinationIP)) {
        break;
      }

      Map<String, Integer> neighbors = graph.get(currentNode);
      if (neighbors == null) {
        continue;
      }

      for (Map.Entry<String, Integer> neighbor : neighbors.entrySet()) {
        String neighborNode = neighbor.getKey();
        int weight = neighbor.getValue();

        if (visited.contains(neighborNode)) {
          continue;
        }

        int newDistance = distances.get(currentNode) + weight;
        if (newDistance < distances.get(neighborNode)) {
          distances.put(neighborNode, newDistance);
          previous.put(neighborNode, currentNode);
          pq.offer(new NodeDistance(neighborNode, newDistance));
        }
      }
    }

    // Reconstruct path
    if (!distances.containsKey(destinationIP) || distances.get(destinationIP) == Integer.MAX_VALUE) {
      return null; // No path exists
    }

    List<String> path = new ArrayList<>();
    String current = destinationIP;
    while (current != null) {
      path.add(0, current);
      current = previous.get(current);
    }

    // Format as string
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < path.size(); i++) {
      sb.append(path.get(i));
      if (i < path.size() - 1) {
        sb.append(" -> ");
      }
    }

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
    // Build the graph from LSA database
    Map<String, Map<String, Integer>> graph = buildGraph();

    // Run Dijkstra's algorithm
    Map<String, Integer> distances = new HashMap<>();
    Map<String, String> previous = new HashMap<>();
    PriorityQueue<NodeDistance> pq = new PriorityQueue<>();
    Set<String> visited = new HashSet<>();

    String source = rd.simulatedIPAddress;

    // Initialize distances
    for (String node : graph.keySet()) {
      distances.put(node, Integer.MAX_VALUE);
    }
    distances.put(source, 0);
    pq.offer(new NodeDistance(source, 0));

    // Dijkstra's algorithm
    while (!pq.isEmpty()) {
      NodeDistance current = pq.poll();
      String currentNode = current.node;

      if (visited.contains(currentNode)) {
        continue;
      }
      visited.add(currentNode);

      if (currentNode.equals(destinationIP)) {
        break;
      }

      Map<String, Integer> neighbors = graph.get(currentNode);
      if (neighbors == null) {
        continue;
      }

      for (Map.Entry<String, Integer> neighbor : neighbors.entrySet()) {
        String neighborNode = neighbor.getKey();
        int weight = neighbor.getValue();

        if (visited.contains(neighborNode)) {
          continue;
        }

        int newDistance = distances.get(currentNode) + weight;
        if (newDistance < distances.get(neighborNode)) {
          distances.put(neighborNode, newDistance);
          previous.put(neighborNode, currentNode);
          pq.offer(new NodeDistance(neighborNode, newDistance));
        }
      }
    }

    // Find next hop by backtracking from destination
    if (!distances.containsKey(destinationIP) || distances.get(destinationIP) == Integer.MAX_VALUE) {
      return null; // No path exists
    }

    String current = destinationIP;
    String nextHop = current;

    while (current != null && !current.equals(source)) {
      nextHop = current;
      current = previous.get(current);
    }

    return nextHop;
  }

  /**
   * Build a weighted graph from the Link State Database.
   * 
   * @return a map where each node maps to its neighbors and their weights
   */
  private Map<String, Map<String, Integer>> buildGraph() {
    Map<String, Map<String, Integer>> graph = new HashMap<>();

    for (LSA lsa : _store.values()) {
      String nodeID = lsa.linkStateID;
      Map<String, Integer> neighbors = new HashMap<>();

      for (LinkDescription ld : lsa.links) {
        if (!ld.linkID.equals(nodeID)) {
          // This is a link to a neighbor (not self-link)
          neighbors.put(ld.linkID, ld.weight);
        }
      }

      graph.put(nodeID, neighbors);
    }

    return graph;
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

  /**
   * Helper class for Dijkstra's algorithm priority queue
   */
  private static class NodeDistance implements Comparable<NodeDistance> {
    String node;
    int distance;

    NodeDistance(String node, int distance) {
      this.node = node;
      this.distance = distance;
    }

    @Override
    public int compareTo(NodeDistance other) {
      return Integer.compare(this.distance, other.distance);
    }
  }
}
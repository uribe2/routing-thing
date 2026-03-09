package socs.network.node;

public class WeightedGraph {
  public short[][] edges;   // edges[i][j] = weight; 0 means no edge
  public String[] nodeIDs;  // simulated router IPs, indexed to match edges

  public WeightedGraph(int size) {
    nodeIDs = new String[size];
    edges = new short[size][size];
  }

  public int indexOf(String nodeID) {
    for (int i = 0; i < nodeIDs.length; i++) {
      if (nodeID.equals(nodeIDs[i])) return i;
    }
    return -1;
  }
}

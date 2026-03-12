package socs.network.node;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

public class Router {

  protected LinkStateDatabase lsd;
  RouterDescription rd = new RouterDescription();
  Link[] ports = new Link[4];

  private ServerSocket serverSocket;
  private volatile boolean running = true;

  // Queue to hold pending attach requests
  private BlockingQueue<PendingAttachRequest> pendingAttachRequests = new LinkedBlockingQueue<>();

  // Class to hold attach request info
  private static class PendingAttachRequest {
    SOSPFPacket packet;
    ObjectOutputStream out;
    Socket socket;

    PendingAttachRequest(SOSPFPacket packet, ObjectOutputStream out, Socket socket) {
      this.packet = packet;
      this.out = out;
      this.socket = socket;
    }
  }

  public Router(Configuration config) {
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    rd.processPortNumber = config.getShort("socs.network.router.port");

    try {
      rd.processIPAddress = java.net.InetAddress.getLocalHost().getHostAddress();
    } catch (Exception e) {
      rd.processIPAddress = "127.0.0.1";
    }

    lsd = new LinkStateDatabase(rd);
    startServerThread();
    printStartupInfo();
  }

  private void printStartupInfo() {
    System.out.println("========================================");
    System.out.println("Process IP : " + rd.processIPAddress);
    System.out.println("Process Port : " + rd.processPortNumber);
    System.out.println("Simulated IP : " + rd.simulatedIPAddress);
    System.out.println("========================================");
  }

  private void startServerThread() {
    Thread serverThread = new Thread(() -> {
      try {
        serverSocket = new ServerSocket(rd.processPortNumber);
        while (running) {
          try {
            Socket clientSocket = serverSocket.accept();
            Thread handlerThread = new Thread(() -> requestHandler(clientSocket));
            handlerThread.start();
          } catch (IOException e) {
            if (running) {
              System.err.println("Error accepting connection: " + e.getMessage());
            }
          }
        }
      } catch (IOException e) {
        System.err.println("Could not start server on port " + rd.processPortNumber);
        e.printStackTrace();
      }
    });
    serverThread.setDaemon(true);
    serverThread.start();
  }

  private void requestHandler(Socket socket) {
    try {
      ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
      out.flush();
      ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

      // Read first packet
      SOSPFPacket packet = (SOSPFPacket) in.readObject();

      if (packet.sospfType == 0) {
        // HELLO message
        if (packet.neighborID != null && !packet.neighborID.isEmpty()) {
          // This is an attach request
          handleAttachRequest(packet, out, socket);
          return; // Socket handling is done in handleAttachRequest
        } else {
          // This is a HELLO handshake
          handleHelloHandshake(packet, out);

          // Check if we should expect a second HELLO
          Link link = findLinkBySimulatedIP(packet.srcIP);
          if (link != null) {
            RouterDescription remoteRouter = getRemoteRouter(link);
            // Wait for second HELLO if we're in INIT or TWO_WAY (both cases send response)
            if (remoteRouter.status == RouterStatus.INIT || remoteRouter.status == RouterStatus.TWO_WAY) {
              try {
                SOSPFPacket secondPacket = (SOSPFPacket) in.readObject();
                if (secondPacket.sospfType == 0) {
                  handleHelloHandshake(secondPacket, out);
                }
              } catch (Exception e) {
                // Connection closed or second HELLO didn't come - that's expected
              }
            }
          }
        }
      } else if (packet.sospfType == 1) {
        // LSAUPDATE message
        handleLSAUpdate(packet);
      } else if (packet.sospfType == 4) {
        // Application message
        handleApplicationMessage(packet);
        out.writeObject("ACK");
        out.flush();
      }

      socket.close();
    } catch (Exception e) {
      // Suppress expected connection errors during HELLO handshake
      String msg = e.getMessage();
      if (msg == null || (!msg.contains("Connection reset") &&
          !msg.contains("Connection aborted") &&
          !msg.contains("connection was aborted") &&
          !msg.contains("Broken pipe"))) {
        System.err.println("Error handling connection: " + msg);
      }
    }
  }

  /**
   * Handle attach request from remote router.
   * Queue the request and let the main terminal thread handle Y/N.
   */
  private void handleAttachRequest(SOSPFPacket packet, ObjectOutputStream out, Socket socket) throws IOException {
    System.out.println("received HELLO from " + packet.neighborID + ";");

    Link existingLink = findLinkBySimulatedIP(packet.neighborID);
    if (existingLink != null) {
      SOSPFPacket response = new SOSPFPacket();
      response.sospfType = 0;
      response.routerID = "ACCEPT";
      response.srcIP = rd.simulatedIPAddress;
      out.writeObject(response);
      out.flush();
      socket.close();
      return;
    }

    int freePort = findFreePort();
    if (freePort == -1) {
      System.out.println("No free ports available;");
      SOSPFPacket reject = new SOSPFPacket();
      reject.sospfType = 0;
      reject.routerID = "REJECT";
      out.writeObject(reject);
      out.flush();
      socket.close();
      return;
    }

    // Queue this request and DON'T close the socket
    PendingAttachRequest request = new PendingAttachRequest(packet, out, socket);
    pendingAttachRequests.offer(request);

    System.out.println("Do you accept this request? (Y/N)");
    System.out.flush();

    // DON'T close socket - keep it open for response
  }

  private void handleHelloHandshake(SOSPFPacket packet, ObjectOutputStream out) throws IOException {
    Link link = findLinkBySimulatedIP(packet.srcIP);
    if (link == null) {
      return;
    }

    RouterDescription remoteRouter = getRemoteRouter(link);

    System.out.println("received HELLO from " + packet.srcIP + ";");

    if (remoteRouter.status == null) {
      // First HELLO received, set to INIT
      remoteRouter.status = RouterStatus.INIT;
      System.out.println("set " + packet.srcIP + " STATE to INIT;");

      // Send HELLO back
      SOSPFPacket response = createHelloPacket(packet.srcIP);
      out.writeObject(response);
      out.flush();

    } else if (remoteRouter.status == RouterStatus.INIT) {
      // Second HELLO received, set to TWO_WAY
      remoteRouter.status = RouterStatus.TWO_WAY;
      System.out.println("set " + packet.srcIP + " STATE to TWO_WAY;");

      // IMPORTANT: advertise the new adjacency
      updateOwnLSA();
      broadcastAllLSAs();

    } else if (remoteRouter.status == RouterStatus.TWO_WAY) {
      // Already in TWO_WAY state
      SOSPFPacket response = createHelloPacket(packet.srcIP);
      out.writeObject(response);
      out.flush();
    }
  }

  private SOSPFPacket createHelloPacket(String destinationIP) {
    SOSPFPacket packet = new SOSPFPacket();
    packet.sospfType = 0;
    packet.srcIP = rd.simulatedIPAddress;
    packet.dstIP = destinationIP;
    packet.srcProcessIP = rd.processIPAddress;
    packet.srcProcessPort = rd.processPortNumber;
    packet.routerID = rd.simulatedIPAddress;
    return packet;
  }

  private void handleLSAUpdate(SOSPFPacket packet) {
    if (packet.lsaArray == null || packet.lsaArray.isEmpty()) {
      return;
    }

    System.out.println("Received LSAUpdate from " + packet.srcIP);

    boolean updated = false;

    for (LSA receivedLSA : packet.lsaArray) {
      // Never overwrite our own LSA from an incoming update
      if (receivedLSA.linkStateID.equals(rd.simulatedIPAddress)) {
        continue;
      }

      LSA existingLSA = lsd._store.get(receivedLSA.linkStateID);

      if (existingLSA == null || receivedLSA.lsaSeqNumber > existingLSA.lsaSeqNumber) {
        lsd._store.put(receivedLSA.linkStateID, copyLSA(receivedLSA));
        updated = true;
      }
    }

    if (updated) {
      // Forward full current LSDB (including own updated LSA) so all neighbors
      // converge
      Vector<LSA> toForward = new Vector<>();
      for (LSA lsa : lsd._store.values()) {
        toForward.add(copyLSA(lsa));
      }
      forwardLSAUpdate(toForward, packet.srcIP);
    }
  }

  private LSA copyLSA(LSA src) {
    LSA copy = new LSA();
    copy.linkStateID = src.linkStateID;
    copy.lsaSeqNumber = src.lsaSeqNumber;
    for (LinkDescription ld : src.links) {
      LinkDescription c = new LinkDescription();
      c.linkID = ld.linkID;
      c.portNum = ld.portNum;
      c.tosMetrics = ld.tosMetrics;
      c.weight = ld.weight;
      copy.links.add(c);
    }
    return copy;
  }

  private void forwardLSAUpdate(Vector<LSA> lsaArray, String excludeIP) {
    StringBuilder log = new StringBuilder("Multicasting LSAUpdate to:");
    for (Link link : ports) {
      if (link != null) {
        RouterDescription neighbor = getRemoteRouter(link);
        if (neighbor.status == RouterStatus.TWO_WAY &&
            !neighbor.simulatedIPAddress.equals(excludeIP)) {
          log.append(" ").append(neighbor.simulatedIPAddress);
          sendLSAUpdate(neighbor, lsaArray);
        }
      }
    }
    System.out.println(log);
  }

  private void sendLSAUpdate(RouterDescription destination, Vector<LSA> lsaArray) {
    try {
      Socket socket = new Socket(destination.processIPAddress, destination.processPortNumber);
      ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
      out.flush();

      SOSPFPacket packet = new SOSPFPacket();
      packet.sospfType = 1;
      packet.srcIP = rd.simulatedIPAddress;
      packet.dstIP = destination.simulatedIPAddress;
      packet.srcProcessIP = rd.processIPAddress;
      packet.srcProcessPort = rd.processPortNumber;
      packet.lsaArray = lsaArray;

      out.writeObject(packet);
      out.flush();
      socket.close();
    } catch (IOException e) {
      // Fail silently
    }
  }

  private void processAttach(String processIP, short processPort, String simulatedIP, short weight) {
    int freePort = findFreePort();
    if (freePort == -1) {
      System.out.println("Error: No free ports available");
      return;
    }

    try {
      Socket socket = new Socket(processIP, processPort);
      socket.setSoTimeout(60000); // Wait up to 60 seconds for Y/N response

      ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
      out.flush();
      ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

      SOSPFPacket packet = new SOSPFPacket();
      packet.sospfType = 0;
      packet.srcIP = rd.simulatedIPAddress;
      packet.neighborID = rd.simulatedIPAddress;
      packet.srcProcessIP = rd.processIPAddress;
      packet.srcProcessPort = rd.processPortNumber;
      packet.weight = weight;

      out.writeObject(packet);
      out.flush();

      SOSPFPacket response = (SOSPFPacket) in.readObject();

      if (response.routerID != null && response.routerID.equals("ACCEPT")) {
        String confirmedIP = response.srcIP;

        if (!confirmedIP.equals(simulatedIP)) {
          System.out.println("Error: Expected " + simulatedIP + " but connected to " + confirmedIP);
          socket.close();
          return;
        }

        if (simulatedIP.equals(rd.simulatedIPAddress)) {
          System.out.println("Error: Cannot attach to self");
          socket.close();
          return;
        }

        if (findLinkBySimulatedIP(simulatedIP) != null) {
          System.out.println("Error: Already attached to " + simulatedIP);
          socket.close();
          return;
        }

        RouterDescription remoteRouter = new RouterDescription();
        remoteRouter.simulatedIPAddress = confirmedIP;
        remoteRouter.processIPAddress = processIP;
        remoteRouter.processPortNumber = processPort;
        remoteRouter.status = null;

        Link link = new Link(rd, remoteRouter, weight);
        ports[freePort] = link;

        System.out.println("Successfully formed connection with " + simulatedIP);
      } else {
        System.out.println("Your attach request has been rejected;");
      }

      socket.close();
    } catch (java.net.SocketTimeoutException e) {
      System.out.println("Error: Timeout waiting for response from router");
      System.out.println("The remote router may not have responded to the attach request");
    } catch (Exception e) {
      System.out.println("Error: Cannot connect to router at " + processIP + ":" + processPort);
      System.out.println("Details: " + e.getMessage());
    }
  }

  /**
   * Process a pending attach request with Y/N response.
   */
  private void processPendingAttachRequest(String response) {
    PendingAttachRequest request = pendingAttachRequests.poll();
    if (request == null) {
      return;
    }

    try {
      if (response.equalsIgnoreCase("Y")) {
        // Accept
        int freePort = findFreePort();
        if (freePort != -1) {
          RouterDescription remoteRouter = new RouterDescription();
          remoteRouter.simulatedIPAddress = request.packet.neighborID;
          remoteRouter.processIPAddress = request.packet.srcProcessIP;
          remoteRouter.processPortNumber = request.packet.srcProcessPort;
          remoteRouter.status = null;

          Link link = new Link(rd, remoteRouter, request.packet.weight);
          ports[freePort] = link;

          SOSPFPacket acceptPacket = new SOSPFPacket();
          acceptPacket.sospfType = 0;
          acceptPacket.routerID = "ACCEPT";
          acceptPacket.srcIP = rd.simulatedIPAddress;
          request.out.writeObject(acceptPacket);
          request.out.flush();
        }
      } else {
        // Reject
        System.out.println("You rejected the attach request;");
        SOSPFPacket reject = new SOSPFPacket();
        reject.sospfType = 0;
        reject.routerID = "REJECT";
        request.out.writeObject(reject);
        request.out.flush();
      }
    } catch (IOException e) {
      // Socket was closed by remote router - this is OK, just print a message
      System.out.println("Warning: Connection closed before response could be sent");
      System.out.println("The remote router may have timed out");
    } finally {
      try {
        request.socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  private void processStart() {
    for (Link link : ports) {
      if (link != null) {
        RouterDescription neighbor = getRemoteRouter(link);
        sendHelloToNeighbor(neighbor);
      }
    }

    updateOwnLSA();
    broadcastAllLSAs();
  }

  private void sendHelloToNeighbor(RouterDescription neighbor) {
    try {
      Socket socket = new Socket(neighbor.processIPAddress, neighbor.processPortNumber);
      ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
      out.flush();
      ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

      // Send first HELLO
      SOSPFPacket helloPacket = createHelloPacket(neighbor.simulatedIPAddress);
      out.writeObject(helloPacket);
      out.flush();

      // Receive HELLO back (neighbor is now in INIT and sends back HELLO)
      SOSPFPacket response = (SOSPFPacket) in.readObject();

      System.out.println("received HELLO from " + response.srcIP + ";");

      // We set neighbor to TWO_WAY after receiving their HELLO
      neighbor.status = RouterStatus.TWO_WAY;
      System.out.println("set " + neighbor.simulatedIPAddress + " STATE to TWO_WAY;");

      // Send second HELLO to confirm TWO_WAY
      SOSPFPacket secondHello = createHelloPacket(neighbor.simulatedIPAddress);
      out.writeObject(secondHello);
      out.flush();

      // Wait a moment for server to process second HELLO
      // Then close gracefully
      try {
        Thread.sleep(50);
      } catch (InterruptedException ie) {
        // Ignore
      }

      socket.close();
    } catch (Exception e) {
      // Don't print stack trace for expected disconnections
      String msg = e.getMessage();
      if (msg == null || (!msg.contains("Connection reset") &&
          !msg.contains("Broken pipe"))) {
        System.err.println("Error sending HELLO to " + neighbor.simulatedIPAddress + ": " + msg);
      }
    }
  }

  private void updateOwnLSA() {
    LSA myLSA = lsd._store.get(rd.simulatedIPAddress);
    if (myLSA == null) {
      return;
    }

    myLSA.lsaSeqNumber++;
    myLSA.links.clear();

    // Always keep the self-link as the first entry
    LinkDescription self = new LinkDescription();
    self.linkID = rd.simulatedIPAddress;
    self.portNum = -1;
    self.tosMetrics = 0;
    self.weight = 0;
    myLSA.links.add(self);

    for (int i = 0; i < ports.length; i++) {
      if (ports[i] != null) {
        RouterDescription neighbor = getRemoteRouter(ports[i]);
        if (neighbor.status == RouterStatus.TWO_WAY) {
          LinkDescription ld = new LinkDescription();
          ld.linkID = neighbor.simulatedIPAddress;
          ld.portNum = i;
          ld.tosMetrics = ports[i].weight;
          ld.weight = ports[i].weight;
          myLSA.links.add(ld);
        }
      }
    }
  }

  private void broadcastAllLSAs() {
    Vector<LSA> lsaArray = new Vector<>();

    for (LSA lsa : lsd._store.values()) {
      lsaArray.add(copyLSA(lsa));
    }

    StringBuilder log = new StringBuilder("Multicasting LSAUpdate to:");
    for (Link link : ports) {
      if (link != null) {
        RouterDescription neighbor = getRemoteRouter(link);
        if (neighbor.status == RouterStatus.TWO_WAY) {
          log.append(" ").append(neighbor.simulatedIPAddress);
          sendLSAUpdate(neighbor, lsaArray);
        }
      }
    }
    System.out.println(log);
  }

  private void processNeighbors() {
    for (Link link : ports) {
      if (link != null) {
        RouterDescription neighbor = getRemoteRouter(link);
        if (neighbor.status == RouterStatus.TWO_WAY) {
          System.out.println(neighbor.simulatedIPAddress);
        }
      }
    }
  }

  private void processDetect(String destinationIP) {
    System.out.println(lsd.getShortestPath(destinationIP));
  }

  private void processConnect(String processIP, short processPort, String simulatedIP, short weight) {
    int portsBefore = countLinks();
    processAttach(processIP, processPort, simulatedIP, weight);

    // Find the newly added link by detecting which port is new
    for (Link link : ports) {
      if (link != null) {
        RouterDescription neighbor = getRemoteRouter(link);
        if (neighbor.status == null) {
          sendHelloToNeighbor(neighbor);
        }
      }
    }

    if (countLinks() > portsBefore) {
      updateOwnLSA();
      broadcastAllLSAs();
    }
  }

  private int countLinks() {
    int count = 0;
    for (Link link : ports) {
      if (link != null)
        count++;
    }
    return count;
  }

  private void processDisconnect(short portNumber) {
    if (portNumber < 0 || portNumber >= ports.length || ports[portNumber] == null) {
      System.out.println("Error: Invalid port number");
      return;
    }

    ports[portNumber] = null;
    updateOwnLSA();
    broadcastAllLSAs();
  }

  private void processUpdate(short portNumber, short newWeight) {
    if (portNumber < 0 || portNumber >= ports.length || ports[portNumber] == null) {
      System.out.println("Error: Invalid port number");
      return;
    }

    ports[portNumber].weight = newWeight;
    updateOwnLSA();
    broadcastAllLSAs();
  }

  private void processSend(String destinationIP, String message) {
    if (destinationIP.equals(rd.simulatedIPAddress)) {
      System.out.println("Received message from " + rd.simulatedIPAddress + ";");
      System.out.println("Message: " + message);
      return;
    }

    SOSPFPacket packet = new SOSPFPacket();
    packet.sospfType = 4;
    packet.srcIP = rd.simulatedIPAddress;
    packet.dstIP = destinationIP;
    packet.message = message;

    String nextHop = lsd.getNextHop(destinationIP);
    if (nextHop == null) {
      System.out.println("No path to " + destinationIP);
      return;
    }

    System.out.println("Sending message to " + destinationIP + " via " + nextHop);
    forwardApplicationMessage(packet, nextHop);
  }

  private void forwardApplicationMessage(SOSPFPacket packet, String nextHopIP) {
    Link link = findLinkBySimulatedIP(nextHopIP);
    if (link == null) {
      System.out.println("Error: no direct link to next hop " + nextHopIP);
      return;
    }
    RouterDescription nextHop = getRemoteRouter(link);
    try {
      Socket socket = new Socket(nextHop.processIPAddress, nextHop.processPortNumber);
      ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
      out.flush();
      ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
      out.writeObject(packet);
      out.flush();
      in.readObject(); // wait for ACK before closing
      socket.close();
    } catch (Exception e) {
      System.err.println("Error forwarding message: " + e.getMessage());
    }
  }

  private void handleApplicationMessage(SOSPFPacket packet) {
    if (packet.dstIP.equals(rd.simulatedIPAddress)) {
      System.out.println("Received message from " + packet.srcIP + ";");
      System.out.println("Message: " + packet.message);
    } else {
      String nextHop = lsd.getNextHop(packet.dstIP);
      if (nextHop == null) {
        System.out.println("No path to " + packet.dstIP + "; dropping message from " + packet.srcIP);
        return;
      }
      System.out.println("Forwarding message from " + packet.srcIP + " to " + packet.dstIP);
      System.out.println("Next hop: " + nextHop);
      forwardApplicationMessage(packet, nextHop);
    }
  }

  private void processQuit() {
    updateOwnLSA();
    broadcastAllLSAs();

    running = false;
    try {
      if (serverSocket != null) {
        serverSocket.close();
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    System.exit(0);
  }

  private int findFreePort() {
    for (int i = 0; i < ports.length; i++) {
      if (ports[i] == null) {
        return i;
      }
    }
    return -1;
  }

  private Link findLinkBySimulatedIP(String simulatedIP) {
    for (Link link : ports) {
      if (link != null) {
        RouterDescription remote = getRemoteRouter(link);
        if (remote.simulatedIPAddress.equals(simulatedIP)) {
          return link;
        }
      }
    }
    return null;
  }

  private RouterDescription getRemoteRouter(Link link) {
    if (link.router1.simulatedIPAddress.equals(rd.simulatedIPAddress)) {
      return link.router2;
    } else {
      return link.router1;
    }
  }

  public void terminal() {
    try {
      InputStreamReader isReader = new InputStreamReader(System.in);
      BufferedReader br = new BufferedReader(isReader);

      while (true) {
        // Show prompt
        System.out.print(">> ");
        String command = br.readLine();

        // CRITICAL: Check if there's a pending attach request first
        if (!pendingAttachRequests.isEmpty() &&
            (command.equalsIgnoreCase("Y") || command.equalsIgnoreCase("N"))) {
          processPendingAttachRequest(command);
          continue; // Go back to top and show >> again
        }

        // Normal command processing
        if (command.startsWith("detect ")) {
          String[] cmdLine = command.split(" ");
          processDetect(cmdLine[1]);
        } else if (command.startsWith("disconnect ")) {
          String[] cmdLine = command.split(" ");
          processDisconnect(Short.parseShort(cmdLine[1]));
        } else if (command.startsWith("quit")) {
          processQuit();
          break;
        } else if (command.startsWith("attach ")) {
          String[] cmdLine = command.split(" ");
          processAttach(cmdLine[1], Short.parseShort(cmdLine[2]),
              cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("start")) {
          processStart();
        } else if (command.startsWith("connect ")) {
          String[] cmdLine = command.split(" ");
          processConnect(cmdLine[1], Short.parseShort(cmdLine[2]),
              cmdLine[3], Short.parseShort(cmdLine[4]));
        } else if (command.equals("neighbors")) {
          processNeighbors();
        } else if (command.startsWith("send ")) {
          String[] cmdLine = command.split(" ", 3);
          if (cmdLine.length >= 3) {
            processSend(cmdLine[1], cmdLine[2]);
          }
        } else if (command.startsWith("update ")) {
          String[] cmdLine = command.split(" ");
          if (cmdLine.length >= 3) {
            processUpdate(Short.parseShort(cmdLine[1]), Short.parseShort(cmdLine[2]));
          }
        } else {
          System.out.println("Invalid command");
        }
      }
      isReader.close();
      br.close();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}

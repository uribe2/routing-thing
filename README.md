# PA-1

## Running the Program

Compile the program into a jar file.

```
mvn clean package assembly:single
```

**Step 1: Start 4 routers in separate terminals**

Run each config file in a different terminal

Terminal 1:

```

java -jar target/router.jar conf/router1.conf

```

Terminal 2:

```

java -jar target/router.jar conf/router2.conf

```

Terminal 3:

```

java -jar target/router.jar conf/router3.conf

```

Terminal 4:

```

java -jar target/router.jar conf/router4.conf

```

**Step 2: Build topology (attach)**

Terminal 2:

```

attach 127.0.0.1 32001 192.168.1.1 1

```

Terminal 1: Type `Y`

Terminal 2:

```

attach 127.0.0.1 32003 192.168.1.3 1

```

Terminal 3: Type `Y`

Terminal 3:

```

attach 127.0.0.1 32004 192.168.1.4 1

```

Terminal 4: Type `Y`

Terminal 4:

```

attach 127.0.0.1 32001 192.168.1.1 1

```

Terminal 1: Type `Y`

**Step 3: Initialize routing (start)**

Run `start` in each terminal (one at a time):

```

start

```

**Step 4: Verify neighbors**

Run `neighbors` in each terminal:

```

neighbors

```

Expected output:

- R1: 192.168.1.2, 192.168.1.4
- R2: 192.168.1.1, 192.168.1.3
- R3: 192.168.1.2, 192.168.1.4
- R4: 192.168.1.3, 192.168.1.1

## Supported Commands

### attach

```

attach [Process IP] [Process Port] [Simulated IP] [Weight]

```

Establish a link to a remote router. The remote router will be prompted to accept (Y) or reject (N).

### start

```

start

```

Initialize database synchronization by exchanging HELLO messages with all attached neighbors.

### neighbors

```

neighbors

```

Display the simulated IP addresses of all neighboring routers.

### quit

```

quit

```

Exit the router program.

## Configuration Files

Each router requires a config file with:

```

socs.network.router.ip="192.168.1.X"
socs.network.router.port="3200X"

```

Example config files are in `conf/router1.conf` through `conf/router7.conf`.

## Important

- Each router runs as a separate Java process
- Routers communicate via TCP sockets
- Multi-threaded server handles concurrent connections
- HELLO handshake establishes TWO_WAY state between neighbors
- Links are bidirectional after attach is accepted

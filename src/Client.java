import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Scanner;
import java.net.InetAddress;
import java.io.*;

public class Client extends UnicastRemoteObject implements ClientI {

    @Serial
    private static final long serialVersionUID = 1L;

    private HostI host;
    private Player me;
    private int logicalClock;
    private Scanner sc;
    private volatile boolean gameStateChanged = false;
    private volatile boolean gameEnded = false;  // NEW: Track if game has ended
    private volatile boolean isDead = false;     // NEW: Track if this player is dead

    protected Client() throws Exception {
        super();
        sc = new Scanner(System.in);
        logicalClock = 0;
    }

    public static void main(String[] args) {
        try {
            Client client = new Client();
            client.start();
        } catch (Exception e) {
            System.err.println("Failed to start the client: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void start() throws Exception {
        System.out.println("\n************************************");
        System.out.println("*   WELCOME TO THE SHOOTER GAME    *");
        System.out.println("************************************");
        System.out.println("* Please choose an option:         *");
        System.out.println("*----------------------------------*");
        System.out.println("* 1. Create a game (Be the host)   *");
        System.out.println("* 2. Join an existing game         *");
        System.out.println("************************************");
        System.out.print("Your choice (1 or 2): ");

        String choice = sc.nextLine().trim();
        String hostIP = "localhost";

        if ("1".equals(choice)) {
            Host myHost = new Host();
            try {
                LocateRegistry.createRegistry(1099);
                System.out.println("RMI registry created successfully.");
            } catch (RemoteException e) {
                System.out.println("RMI registry already running or could not be started on port 1099. Attempting to use existing.");
            }
            Registry registry = LocateRegistry.getRegistry(1099);
            registry.rebind("HostService", myHost);

            InetAddress ip = InetAddress.getLocalHost();
            System.out.println("\n====================================");
            System.out.println(" YOU ARE THE HOST");
            System.out.println(" Your IP Address is: " + ip.getHostAddress());
            System.out.println(" Share this IP with other players.");
            System.out.println("====================================");
            host = myHost;

            myHost.registerClientStub("hostInternalClient", this);

        } else if ("2".equals(choice)) {
            System.out.print("Enter the Host's IP address to join (or press Enter for localhost): ");
            hostIP = sc.nextLine().trim();
            if (hostIP.isEmpty()) {
                hostIP = "localhost";
            }

            try {
                Registry registry = LocateRegistry.getRegistry(hostIP, 1099);
                host = (HostI) registry.lookup("HostService");
                System.out.println("Successfully connected to host at " + hostIP);
            } catch (Exception e) {
                System.err.println("Could not connect to the host at " + hostIP + ". Please ensure the IP is correct and the host is running.");
                System.err.println("Error details: " + e.getMessage());
                return;
            }
        } else {
            System.out.println("Invalid choice. Exiting game.");
            return;
        }

        String id;
        while (true) {
            System.out.print("Enter your player ID (e.g., your name): ");
            id = sc.nextLine().trim();
            if (id.isEmpty()) {
                System.out.println("Player ID cannot be empty. Please try again.");
            } else if ("hostInternalClient".equalsIgnoreCase(id)) {
                System.out.println("The ID 'hostInternalClient' is reserved. Please choose a different ID.");
            } else {
                break;
            }
        }

        me = new Player(id, 0, 0, 100);
        me = host.registerPlayer(me);

        // Check if registration failed (game might have ended)
        if (me == null) {
            System.out.println("Failed to join the game. The game may have already ended.");
            return;
        }

        host.registerClientStub(me.id, this);

        System.out.println("\n------------------------------------");
        if (me.isHost) {
            System.out.println("You are the HOST! Your symbol is '" + me.symbol + "'.");
        } else {
            System.out.println("You have joined the game as Player: " + me.id + " (Symbol: '" + me.symbol + "')");
        }
        System.out.println("Initial Position: (" + me.x + "," + me.y + "), Health: " + me.health);
        System.out.println("------------------------------------");

        // Initial map display after joining
        try {
            List<Player> initialPlayers = host.getPlayers();
            drawMapForClient(initialPlayers, me.id);
        } catch (RemoteException e) {
            System.err.println("Could not fetch initial game state: " + e.getMessage());
        }

        // Game loop
        while (!gameEnded) {
            // Check if game state changed and redraw if needed
            if (gameStateChanged) {
                try {
                    List<Player> players = host.getPlayers();
                    if (players != null) {
                        drawMapForClient(players, me.id);
                        gameStateChanged = false;
                    }
                } catch (RemoteException e) {
                    System.err.println("Error fetching updated players: " + e.getMessage());
                }
            }

            List<Player> players;
            try {
                players = host.getPlayers();
            } catch (RemoteException e) {
                System.err.println("Error fetching players from host: " + e.getMessage() + ". The host might have disconnected.");
                break;
            }

            if (players == null) {
                System.err.println("Received null player list from host. Exiting.");
                break;
            }

            // Update local player state
            for (Player p : players) {
                if (p.id.equals(me.id)) {
                    isDead = p.isDead || p.health <= 0;
                    break;
                }
            }

            // Show different prompts based on player status
            if (isDead) {
                System.out.println("\n[YOU ARE DEAD] You can no longer perform actions. Waiting for game to end...");
                System.out.println("Press Enter to continue watching or type 'exit' to leave:");
                String input = sc.nextLine().trim();
                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }
                continue; // Skip action processing
            }

            System.out.println("\nAvailable actions: move <up|down|left|right>, shoot <playerID>, heal <playerID>, exit");
            System.out.print("Enter action for " + me.id + " (Clock: " + logicalClock + "): ");
            String fullCommand = sc.nextLine().trim().toLowerCase();

            if (fullCommand.isEmpty()) {
                System.out.println("Please enter a command.");
                continue;
            }

            if ("exit".equalsIgnoreCase(fullCommand)) {
                System.out.println("Exiting game...");
                break;
            }

            logicalClock++;
            me.logicalClock = logicalClock;

            String[] commandParts = fullCommand.split("\\s+", 2);
            String action = commandParts[0];
            String argument = commandParts.length > 1 ? commandParts[1] : null;

            String direction = null;
            String targetId = null;
            boolean validCommand = false;

            switch (action) {
                case "move":
                    if (argument != null && (argument.equals("up") || argument.equals("down") || argument.equals("left") || argument.equals("right"))) {
                        direction = argument;
                        validCommand = true;
                    } else {
                        System.out.println("Invalid move command. Usage: move <up|down|left|right>");
                    }
                    break;
                case "shoot":
                case "heal":
                    if (argument != null && !argument.isEmpty()) {
                        boolean targetExists = false;
                        String actualTargetId = null;
                        for(Player p : players) {
                            if(p.id.equalsIgnoreCase(argument)) {
                                actualTargetId = p.id;
                                targetExists = true;
                                break;
                            }
                        }
                        if (targetExists) {
                            if (actualTargetId.equals(me.id) && action.equals("shoot")) {
                                System.out.println("You cannot shoot yourself.");
                            }else {
                                // NEW: Check if target is dead
                                String finalActualTargetId = actualTargetId;
                                Player targetPlayer = players.stream()
                                        .filter(p -> p.id.equals(finalActualTargetId))
                                        .findFirst()
                                        .orElse(null);

                                if (targetPlayer != null && targetPlayer.isDead && action.equals("heal")) {
                                    System.out.println("Cannot heal dead player: " + actualTargetId);
                                } else if (targetPlayer != null && targetPlayer.isDead && action.equals("shoot")) {
                                    System.out.println("Cannot shoot dead player: " + actualTargetId);
                                } else {
                                    targetId = actualTargetId;
                                    validCommand = true;
                                }
                            }
                        } else {
                            System.out.println("Invalid target player ID: '" + argument + "'. Player not found.");
                        }
                    } else {
                        System.out.println("Invalid " + action + " command. Usage: " + action + " <playerID>");
                    }
                    break;
                default:
                    System.out.println("Unknown action: '" + action + "'. Type 'exit' to quit.");
                    break;
            }

            if (validCommand) {
                Message msg = new Message(me.id, logicalClock, action, direction, targetId, System.currentTimeMillis());

                try {
                    byte[] serializedMsg = serialize(msg);
                    byte[] encryptedMsg = EncryptionUtils.xorEncryptDecrypt(serializedMsg);
                    host.multicastMessage(encryptedMsg);
                } catch (RemoteException e) {
                    System.err.println("RemoteException sending message: " + e.getMessage() + ". Host might be down.");
                    break;
                } catch (IOException e) {
                    System.err.println("Error serializing or sending message: " + e.getMessage());
                }
            }

            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Cleanup
        if (sc != null) {
            sc.close();
        }
        System.out.println("Game exited. You are the winner!!");
        try {
            UnicastRemoteObject.unexportObject(this, true);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
        System.exit(0);
    }

    private byte[] serialize(Message msg) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(msg);
            oos.flush();
        }
        return bos.toByteArray();
    }

    private Message deserialize(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        try (ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (Message) ois.readObject();
        }
    }

    @Override
    public void receiveMulticast(byte[] encryptedMessage) throws RemoteException {
        try {
            byte[] decrypted = EncryptionUtils.xorEncryptDecrypt(encryptedMessage);
            Message msg = deserialize(decrypted);

            logicalClock = Math.max(logicalClock, msg.logicalClock) + 1;
            gameStateChanged = true;

            System.out.println("\n[Network] Player " + msg.playerId + " performed " + msg.action +
                    (msg.direction != null ? " " + msg.direction : "") +
                    (msg.targetId != null ? " on " + msg.targetId : "") +
                    ". Local clock: " + logicalClock);

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error processing received multicast message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static void drawMapForClient(List<Player> players, String currentPlayerId) {
        System.out.println("\n================ MAP (10x10) ================");
        char[][] grid = new char[10][10];

        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                grid[y][x] = '.';
            }
        }

        for (Player p : players) {
            if (p.x >= 0 && p.x < 10 && p.y >= 0 && p.y < 10) {
                // NEW: Different symbol for dead players
                if (p.isDead || p.health <= 0) {
                    grid[p.y][p.x] = 'X'; // Dead players marked with X
                } else {
                    grid[p.y][p.x] = p.symbol;
                }
            }
        }

        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                System.out.print("[" + grid[y][x] + "]");
            }
            System.out.println();
        }
        System.out.println("============================================");

        System.out.println("\n-------------- PLAYER STATS --------------");
        for (Player p : players) {
            String hostTag = p.isHost ? " (HOST)" : "";
            String selfTag = p.id.equals(currentPlayerId) ? " (YOU)" : "";
            String statusTag = (p.isDead || p.health <= 0) ? " [DEAD]" : " [ALIVE]";

            System.out.printf("ID: %-15s (Symbol '%c') | Pos: (%d,%d) | Health: %-3d%s%s%s\n",
                    p.id,
                    (p.isDead || p.health <= 0) ? 'X' : p.symbol,
                    p.x, p.y, p.health,
                    hostTag, selfTag, statusTag);
        }
        System.out.println("------------------------------------------");
    }

    @Override
    public void updateGameState(List<Player> players) throws RemoteException {
        System.out.println("\n[Game State Update] Game state changed!");
        if (me != null) {
            drawMapForClient(players, me.id);
            gameStateChanged = false;
        }
    }

    // NEW: Handle death notifications
    @Override
    public void notifyPlayerDeath(String deadPlayerId) throws RemoteException {
        if (deadPlayerId.equals(me.id)) {
            isDead = true;
            System.out.println("\n" + "=".repeat(50));
            System.out.println("💀 YOU HAVE BEEN KILLED! 💀");
            System.out.println("You can no longer perform actions but can continue watching the game.");
            System.out.println("=".repeat(50));
        } else {
            System.out.println("\n💀 Player " + deadPlayerId + " has been killed!");
        }
        gameStateChanged = true;
    }

    // NEW: Handle game end notifications
    @Override
    public void notifyGameEnd(String winnerId) throws RemoteException {
        gameEnded = true;
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🎮 GAME OVER! 🎮");

        if (winnerId != null) {
            if (winnerId.equals(me.id)) {
                System.out.println("🏆 CONGRATULATIONS! YOU WON! 🏆");
            } else {
                System.out.println("🏆 Winner: " + winnerId + " 🏆");
            }
        } else {
            System.out.println("💀 No survivors! Everyone died! 💀");
        }

        System.out.println("=".repeat(60));
        System.out.println("Press Enter to exit the game...");
    }
}
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.io.*;

public class Host extends UnicastRemoteObject implements HostI {

    private static final long serialVersionUID = 1L;
    private final List<Player> players;
    private final Map<String, ClientI> clientStubs;
    private int playerCount;
    private boolean gameEnded = false;  // NEW: Track if game has ended

    private final PriorityQueue<Message> eventQueue;

    protected Host() throws RemoteException {
        super();
        players = new ArrayList<>();
        clientStubs = new HashMap<>();
        playerCount = 0;
        eventQueue = new PriorityQueue<>((m1, m2) -> {
            if (m1.logicalClock != m2.logicalClock)
                return Integer.compare(m1.logicalClock, m2.logicalClock);
            int cmp = Long.compare(m1.timestamp, m2.timestamp);
            if (cmp != 0) return cmp;
            return m1.playerId.compareTo(m2.playerId);
        });
    }


    @Override
    public synchronized Player registerPlayer(Player p) throws RemoteException {
        // Don't allow new players to join if game has ended
        if (gameEnded) {
            System.out.println("Game has ended. New players cannot join.");
            return null;
        }

        boolean hostExists = false;
        for (Player pl : players) {
            if (pl.isHost) {
                hostExists = true;
                break;
            }
        }

        if (!hostExists) {
            p.isHost = true;
            p.x = 5;
            p.y = 5;
        } else {
            p.isHost = false;
            p.x = (int) (Math.random() * 10);
            p.y = (int) (Math.random() * 10);
        }

        playerCount++;
        p.symbol = generateSymbol(playerCount);
        players.add(p);

        broadcastGameStateUpdate();
        return p;
    }


    private char generateSymbol(int count) {
        if (count <= 26) return (char) ('a' + count - 1);
        else if (count <= 52) return (char) ('A' + count - 27);
        else return (char) ('a' + (count - 1) % 26);
    }

    @Override
    public synchronized List<Player> getPlayers() throws RemoteException {
        return new ArrayList<>(players);
    }

    public synchronized void registerClientStub(String playerId, ClientI stub) {
        clientStubs.put(playerId, stub);
        broadcastGameStateUpdate();
    }

    private void broadcastGameStateUpdate() {
        List<Player> currentPlayers = new ArrayList<>(players);
        List<String> disconnectedClients = new ArrayList<>();

        for (Map.Entry<String, ClientI> entry : clientStubs.entrySet()) {
            try {
                entry.getValue().updateGameState(currentPlayers);
            } catch (RemoteException e) {
                System.out.println("Failed to send game state update to " + entry.getKey() +
                        ": " + e.getMessage());
                disconnectedClients.add(entry.getKey());
            }
        }

        for (String clientId : disconnectedClients) {
            clientStubs.remove(clientId);
            players.removeIf(player -> player.id.equals(clientId));
            System.out.println("Removed disconnected player: " + clientId);
        }
    }

    @Override
    public synchronized void multicastMessage(byte[] encryptedMessage) throws RemoteException {
        try {
            byte[] decrypted = EncryptionUtils.xorEncryptDecrypt(encryptedMessage);

            ByteArrayInputStream bis = new ByteArrayInputStream(decrypted);
            ObjectInputStream ois = new ObjectInputStream(bis);
            Message msg = (Message) ois.readObject();

            Player sender = null;
            for (Player p : players) {
                if (p.id.equals(msg.playerId)) {
                    sender = p;
                    break;
                }
            }

            if (sender == null) return;

            if (sender.isDead || sender.health <= 0) {
                System.out.println("Action rejected: Player " + sender.id + " is dead and cannot perform actions.");
                broadcastDeathNotification(sender.id);
                return;
            }

            sender.logicalClock = Math.max(sender.logicalClock, msg.logicalClock)+1;

            eventQueue.offer(msg);

            List<String> disconnectedClients = new ArrayList<>();
            for (Map.Entry<String, ClientI> entry : clientStubs.entrySet()) {
                try {
                    entry.getValue().receiveMulticast(encryptedMessage);
                } catch (RemoteException e) {
                    disconnectedClients.add(entry.getKey());
                }
            }

            for (String clientId : disconnectedClients) {
                clientStubs.remove(clientId);
                players.removeIf(player -> player.id.equals(clientId));
                System.out.println("Removed disconnected player during multicast: " + clientId);
            }

            executeEvents();
            broadcastGameStateUpdate();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void executeEvents() {
        while (!eventQueue.isEmpty()) {
            Message msg = eventQueue.peek();
            Player player = null;
            for (Player p : players) {
                if (p.id.equals(msg.playerId)) {
                    player = p;
                    break;
                }
            }

            if (player == null) {
                eventQueue.poll();
                continue;
            }

            if (player.isDead || player.health <= 0) {
                System.out.println("Skipping action from dead player: " + player.id);
                eventQueue.poll();
                continue;
            }

            switch (msg.action.toLowerCase()) {
                case "move":
                    switch (msg.direction.toLowerCase()) {
                        case "up": if (player.y > 0) player.y--; break;
                        case "down": if (player.y < 9) player.y++; break;
                        case "left": if (player.x > 0) player.x--; break;
                        case "right": if (player.x < 9) player.x++; break;
                    }
                    break;

                case "shoot":
                case "heal":
                    if (msg.targetId != null) {
                        Player target = null;
                        for (Player p : players) {
                            if (p.id.equals(msg.targetId)) {
                                target = p;
                                break;
                            }
                        }

                        if (target != null && !target.isDead && euclideanDistance(player, target) <= 3) {
                            if (msg.action.equalsIgnoreCase("shoot")) {
                                target.health -= 10;
                                if (target.health <= 0) {
                                    target.markAsDead();  // NEW: Mark player as dead
                                    System.out.println("Player " + target.id + " has been killed by " + player.id + "!");
                                    broadcastDeathNotification(target.id);
                                    checkGameEnd();  // NEW: Check if game should end
                                }
                            } else { // heal
                                if (!target.isDead) {
                                    target.health += 10;
                                    if (target.health > 100) target.health = 100;
                                } else {
                                    System.out.println("Cannot heal dead player: " + target.id);
                                }
                            }
                        } else if (target != null && target.isDead) {
                            System.out.println("Cannot target dead player: " + target.id);
                        }
                    }
                    break;
            }

            eventQueue.poll();
        }
    }


    // NEW: Method to broadcast death notifications
    private void broadcastDeathNotification(String deadPlayerId) {
        List<String> disconnectedClients = new ArrayList<>();

        for (Map.Entry<String, ClientI> entry : clientStubs.entrySet()) {
            try {
                entry.getValue().notifyPlayerDeath(deadPlayerId);
            } catch (RemoteException e) {
                disconnectedClients.add(entry.getKey());
            }
        }

        for (String clientId : disconnectedClients) {
            clientStubs.remove(clientId);
            players.removeIf(player -> player.id.equals(clientId));
        }
    }

    // NEW: Method to check if game should end
    private void checkGameEnd() {
        List<Player> alivePlayers = new ArrayList<>();

        for (Player p : players) {
            if (!p.isDead && p.health > 0) {
                alivePlayers.add(p);
            }
        }

        if (alivePlayers.size() <= 1) {
            gameEnded = true;

            if (alivePlayers.size() == 1) {
                Player winner = alivePlayers.get(0);
                System.out.println("GAME OVER! Winner: " + winner.id);
                broadcastGameEnd(winner.id);
            } else {
                System.out.println("GAME OVER! No survivors!");
                broadcastGameEnd(null);
            }
        }
    }


    // NEW: Method to broadcast game end
    private void broadcastGameEnd(String winnerId) {
        List<String> disconnectedClients = new ArrayList<>();

        for (Map.Entry<String, ClientI> entry : clientStubs.entrySet()) {
            try {
                entry.getValue().notifyGameEnd(winnerId);
            } catch (RemoteException e) {
                disconnectedClients.add(entry.getKey());
            }
        }

        for (String clientId : disconnectedClients) {
            clientStubs.remove(clientId);
        }
    }

    private double euclideanDistance(Player a, Player b) {
        return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2));
    }

}
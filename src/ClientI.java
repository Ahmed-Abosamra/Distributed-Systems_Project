import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface ClientI extends Remote {
    void receiveMulticast(byte[] encryptedMessage) throws RemoteException;
    void updateGameState(List<Player> players) throws RemoteException;
    void notifyPlayerDeath(String deadPlayerId) throws RemoteException;  // NEW: Death notification
    void notifyGameEnd(String winnerId) throws RemoteException;           // NEW: Game end notification
}
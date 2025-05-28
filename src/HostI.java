import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface HostI extends Remote {
    Player registerPlayer(Player p) throws RemoteException;
    List<Player> getPlayers() throws RemoteException;
    void multicastMessage(byte[] encryptedMessage) throws RemoteException;
    void registerClientStub(String playerId, ClientI stub) throws RemoteException;
}

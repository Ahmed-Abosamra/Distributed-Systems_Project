import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public String playerId;
    public int logicalClock;
    public String action;
    public String direction;
    public String targetId;
    public long timestamp;

    public Message(String playerId, int logicalClock, String action, String direction, String targetId, long timestamp) {
        this.playerId = playerId;
        this.logicalClock = logicalClock;
        this.action = action;
        this.direction = direction;
        this.targetId = targetId;
        this.timestamp = timestamp;
    }
}

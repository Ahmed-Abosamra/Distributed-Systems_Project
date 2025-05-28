import java.io.Serializable;

public class Player implements Serializable {
    private static final long serialVersionUID = 1L;

    public String id;
    public int x, y;
    public int health;
    public boolean isHost;
    public char symbol;
    public boolean isDead;  // NEW: Track if player is dead
    public long deathTime;  // NEW: Track when player died

    public int logicalClock;

    public Player(String id, int x, int y, int health) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.health = health;
        this.isHost = false;
        this.symbol = id.length() > 0 ? id.charAt(0) : 'P';
        this.logicalClock = 0;
        this.isDead = false;  // NEW: Initialize as alive
        this.deathTime = 0;   // NEW: Initialize death time
    }

    /**
     * Marks the player as dead and records the death time
     */
    public void markAsDead() {
        this.isDead = true;
        this.health = 0;
        this.deathTime = System.currentTimeMillis();
    }

    /**
     * Checks if the player is alive and can perform actions
     */
    public boolean isAlive() {
        return !isDead && health > 0;
    }
}
public class EncryptionUtils {
    private static final byte KEY = 0x5A;

    public static byte[] xorEncryptDecrypt(byte[] data) {
        byte[] result = new byte[data.length];
        for(int i=0; i<data.length; i++) {
            result[i] = (byte)(data[i] ^ KEY);
        }
        return result;
    }
}

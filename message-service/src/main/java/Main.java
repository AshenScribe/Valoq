import server.MessageServer;

public final class Main {

    private Main() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    public static void main() throws InterruptedException {
        MessageServer server = new MessageServer(8000);
        try {
            server.start();
        } catch (InterruptedException e) {
            server.stop();
            throw e;
        }
    }
}

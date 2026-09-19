import authenticator.jwt.JwtUtil;
import config.ConfigLoader;
import config.ServerConfig;
import database.DatabaseManager;
import server.AuthServer;

public final class Main {

    private Main() {}

    static void main() throws InterruptedException {
        ServerConfig appConfig = new ConfigLoader().appConfig();
        DatabaseManager.init(appConfig);
        JwtUtil.getInstance(appConfig.jwtExpirationTime());
        AuthServer server = new AuthServer(appConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            DatabaseManager.close();
        }));
        server.start();
    }
}

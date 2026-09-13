import authenticator.jwt.JwtUtil;
import config.ConfigLoader;
import config.ServerConfig;
import server.AuthServer;

public final class Main {

    private Main() {}

    static void main() throws InterruptedException {
        ServerConfig appConfig = new ConfigLoader().appConfig();
        JwtUtil.getInstance(appConfig.jwtExpirationTime());
        AuthServer server = new AuthServer(appConfig);
        server.start();
    }
}

package server.handler;

import authenticator.Authenticator;
import authenticator.BasicAuthenticator;
import authenticator.RegisterAuthenticator;
import authenticator.TokenAuthenticator;
import server.command.AuthCommand;
import server.command.BasicCommand;
import server.command.RegisterCommand;
import server.command.TokenCommand;

public final class AuthenticationHandlerFactory {

    private static final Authenticator TOKEN_AUTHENTICATOR = new TokenAuthenticator();
    private static final Authenticator BASIC_AUTHENTICATOR = new BasicAuthenticator();
    private static final Authenticator REGISTER_AUTHENTICATOR = new RegisterAuthenticator();

    private AuthenticationHandlerFactory() {}

    public static Authenticator getAuthenticationHandler(AuthCommand authCommand) {
        return switch (authCommand) {
            case TokenCommand ignored -> TOKEN_AUTHENTICATOR;
            case BasicCommand ignored -> BASIC_AUTHENTICATOR;
            case RegisterCommand ignored -> REGISTER_AUTHENTICATOR;
            default -> throw new IllegalStateException("Unexpected value: " + authCommand);
        };
    }
}

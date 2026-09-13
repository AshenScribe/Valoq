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

    private AuthenticationHandlerFactory() {}

    public static Authenticator getAuthenticationHandler(AuthCommand authCommand) {
        return switch (authCommand) {
            case TokenCommand ignored -> new TokenAuthenticator();
            case BasicCommand ignored -> new BasicAuthenticator();
            case RegisterCommand ignored -> new RegisterAuthenticator();
            default -> throw new IllegalStateException("Unexpected value: " + authCommand);
        };
    }
}

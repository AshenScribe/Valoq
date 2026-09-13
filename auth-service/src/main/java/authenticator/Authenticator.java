package authenticator;

import server.command.AuthCommand;

public interface Authenticator {
    /**
     * @param authCommand - The authentication mechanism used for authenticating
     * @return - Basic on type of authCommand:
     *  * BASIC, REGISTER - returns userId that will be decoded by {@link authenticator.jwt.JwtUtil}
     *  * TOKEN - refreshes the token if expired
     */
    String login(AuthCommand authCommand);
}

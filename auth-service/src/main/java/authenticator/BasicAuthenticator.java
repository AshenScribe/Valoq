package authenticator;

import server.command.AuthCommand;
import server.command.BasicCommand;

public class BasicAuthenticator implements Authenticator {
    @Override
    public String login(AuthCommand authCommand) {
        BasicCommand basicCommand = (BasicCommand) authCommand;
        // todo: database check
        return null;
    }
}

package authenticator;

import authenticator.jwt.JwtUtil;
import database.UserRepository;
import exception.UserNotFoundException;
import java.util.Map;
import server.command.AuthCommand;
import server.command.BasicCommand;
import server.mapper.UserMapper;
import server.model.User;

public class BasicAuthenticator implements Authenticator {

    private final UserRepository userRepository;

    public BasicAuthenticator() {
        userRepository = new UserRepository();
    }

    public BasicAuthenticator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public String login(AuthCommand authCommand) {
        BasicCommand basicCommand = (BasicCommand) authCommand;
        User user = UserMapper.mapToUser(userRepository.getUser(basicCommand.username()));
        if (user == null)
            throw new UserNotFoundException(String.format("User with username %s not found", basicCommand.username()));
        if (user.passwordHash().equals(basicCommand.password())) {
            return JwtUtil.getInstance().generateJwt(user.userId(), Map.of());
        }
        throw new UserNotFoundException(
                String.format("User with username %s not found or password does not match", basicCommand.username()));
    }
}

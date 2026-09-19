package authenticator;

import authenticator.jwt.JwtUtil;
import authenticator.validator.PasswordValidationResult;
import authenticator.validator.UsernameValidationResult;
import authenticator.validator.Validator;
import database.UserRepository;
import exception.ValidationException;
import java.util.Map;
import server.command.AuthCommand;
import server.command.RegisterCommand;

public class RegisterAuthenticator implements Authenticator {

    private final UserRepository userRepository;

    public RegisterAuthenticator() {
        userRepository = new UserRepository();
    }

    public RegisterAuthenticator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public String login(AuthCommand authCommand) {
        RegisterCommand registerCommand = (RegisterCommand) authCommand;

        UsernameValidationResult result = Validator.validateUsername(registerCommand.username());
        if (!result.isValid()) throw new ValidationException(result.getMessage());
        PasswordValidationResult passwordResult = Validator.validatePassword(registerCommand.password());
        if (!passwordResult.isValid()) throw new ValidationException(passwordResult.getMessage());
        String userId =
                userRepository.saveUser(registerCommand.username(), registerCommand.password(), registerCommand.salt());
        return JwtUtil.getInstance().generateJwt(userId, Map.of());
    }
}

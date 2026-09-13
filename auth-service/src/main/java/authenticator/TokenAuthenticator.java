package authenticator;

import authenticator.jwt.JwtUtil;
import io.jsonwebtoken.Claims;
import java.util.Date;
import server.command.AuthCommand;
import server.command.TokenCommand;

public class TokenAuthenticator implements Authenticator {
    @Override
    public String login(AuthCommand authCommand) {
        TokenCommand tokenCommand = (TokenCommand) authCommand;
        JwtUtil jwtUtil = JwtUtil.getInstance();

        Claims claims = jwtUtil.parseJwt(tokenCommand.token());
        if (claims.getExpiration().before(new Date())) return jwtUtil.generateJwt(claims.getSubject(), claims);
        return tokenCommand.token();
    }
}

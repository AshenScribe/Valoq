/*
 * MIT License
 *
 * Copyright (c) 2026 Valoq
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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
            throw new UserNotFoundException(
                    String.format("User with username %s not found", basicCommand.username()));
        if (user.passwordHash().equals(basicCommand.password())) {
            return JwtUtil.getInstance().generateJwt(user.userId(), Map.of());
        }
        throw new UserNotFoundException(
                String.format(
                        "User with username %s not found or password does not match",
                        basicCommand.username()));
    }
}

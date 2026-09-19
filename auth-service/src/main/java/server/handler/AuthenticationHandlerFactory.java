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

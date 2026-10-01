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
package server.codec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.Base64;
import java.util.List;
import server.command.AuthCommand;
import server.command.BasicCommand;
import server.command.RegisterCommand;
import server.command.TokenCommand;

public class CommandDecoder extends MessageToMessageDecoder<String> {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    protected void decode(ChannelHandlerContext ctx, String msg, List<Object> out) {
        String trimmed = msg.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        if (!trimmed.startsWith("AUTH ") && !trimmed.startsWith("auth ")) {
            throw new DecoderException(
                    "Invalid command format. Expected: AUTH <TOKEN|BASIC|REGISTER> <PAYLOAD>");
        }

        int firstSpace = trimmed.indexOf(' ');
        int secondSpace = trimmed.indexOf(' ', firstSpace + 1);

        while (secondSpace != -1 && secondSpace == firstSpace + 1) {
            firstSpace = secondSpace;
            secondSpace = trimmed.indexOf(' ', firstSpace + 1);
        }

        if (secondSpace == -1) {
            throw new DecoderException(
                    "Invalid command format. Expected: AUTH <TOKEN|BASIC|REGISTER> <PAYLOAD>");
        }

        String commandType = trimmed.substring(firstSpace + 1, secondSpace).trim().toUpperCase();
        String payload = trimmed.substring(secondSpace + 1).trim();

        if (payload.isEmpty()) {
            throw new DecoderException(
                    "Invalid command format. Expected: AUTH <TOKEN|BASIC|REGISTER> <PAYLOAD>");
        }

        AuthCommand authCommand =
                switch (commandType) {
                    case "TOKEN" -> decodeToken(payload);
                    case "BASIC" -> decodeBasic(payload);
                    case "REGISTER" -> decodeRegister(payload);
                    default ->
                            throw new DecoderException(
                                    "Unsupported command type: "
                                            + commandType
                                            + ". Expected: TOKEN, BASIC, or REGISTER");
                };

        out.add(authCommand);
    }

    private TokenCommand decodeToken(String payload) {
        if (payload.isBlank()) {
            throw new DecoderException("AUTH TOKEN requires a non-empty token payload");
        }
        return new TokenCommand(payload);
    }

    private BasicCommand decodeBasic(String payload) {
        int firstColon = payload.indexOf(':');
        int lastColon = payload.lastIndexOf(':');
        if (firstColon == -1 || lastColon == -1 || firstColon == lastColon) {
            throw new DecoderException(
                    "Invalid BASIC payload. Expected format: username:password:salt");
        }

        String username = payload.substring(0, firstColon);
        String password = payload.substring(firstColon + 1, lastColon);
        String salt = payload.substring(lastColon + 1);

        return new BasicCommand(username, password, salt);
    }

    private RegisterCommand decodeRegister(String payload) {
        byte[] jsonBytes;
        try {
            jsonBytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new DecoderException("Invalid Base64 payload for REGISTER command", e);
        }

        try {
            RegisterCommand cmd = OBJECT_MAPPER.readValue(jsonBytes, RegisterCommand.class);
            if (cmd.username() == null || cmd.password() == null) {
                throw new DecoderException(
                        "REGISTER payload missing mandatory fields (username/password)");
            }
            return cmd;
        } catch (Exception e) {
            throw new DecoderException("Invalid JSON payload for REGISTER command", e);
        }
    }
}

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import server.command.AuthCommand;
import server.command.BasicCommand;
import server.command.RegisterCommand;
import server.command.TokenCommand;

/**
 * AUTH TOKEN/BASIC/REGISTER DATA
 * DATA will be base64 encoded string of username:password for BASIC and for REGISTER, it will be base64 encoded JSON
 */
public class CommandDecoder extends MessageToMessageDecoder<String> {

    final Pattern AUTH_PATTERN = Pattern.compile("AUTH\\s(\\S+)\\s(\\S+)$");
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    protected void decode(ChannelHandlerContext ctx, String msg, List<Object> out) {
        final Matcher matcher = AUTH_PATTERN.matcher(msg);
        if (!matcher.matches())
            throw new DecoderException(String.format(
                    "invalid command format: %s\nExpected AUTH TOKEN/BASIC/REGISTER DATA custom_payload", msg));
        AuthCommand authCommand =
                switch (matcher.group(1)) {
                    case "TOKEN" -> new TokenCommand(matcher.group(2));
                    case "BASIC" -> {
                        String[] parts = matcher.group(2).split(":");
                        yield new BasicCommand(parts[0], parts[1], parts[2]);
                    }
                    case "REGISTER" -> {
                        try {
                            byte[] jsonBytes = Base64.getDecoder().decode(matcher.group(2));
                            yield OBJECT_MAPPER.readValue(jsonBytes, RegisterCommand.class);
                        } catch (Exception e) {
                            throw new DecoderException("Invalid JSON payload for REGISTER command", e);
                        }
                    }
                    default -> throw new IllegalStateException(
                            String.format("invalid command type: %s\nExpected TOKEN/BASIC/REGISTER", matcher.group(1)));
                };
        out.add(authCommand);
    }
}

package server.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
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
                    case "REGISTER" -> new RegisterCommand(matcher.group(2));
                    default -> throw new IllegalStateException(
                            String.format("invalid command type: %s\nExpected TOKEN/BASIC/REGISTER", matcher.group(1)));
                };
        out.add(authCommand);
    }
}

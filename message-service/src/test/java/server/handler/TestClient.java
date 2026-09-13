package server.handler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class TestClient implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;

    public TestClient(Socket socket) throws IOException {
        this.socket = socket;

        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public void send(String message) throws IOException {
        writer.write(message);
        writer.flush();
    }

    public boolean serverClosedConnection() throws IOException {
        socket.setSoTimeout(2000);
        return reader.read() == -1;
    }

    public String readLine() throws IOException {
        return readLine(Duration.ofSeconds(2));
    }

    public String readLine(Duration timeout) throws IOException {

        socket.setSoTimeout((int) timeout.toMillis());

        try {
            String line = reader.readLine();

            if (line == null) {
                return null;
            }

            return line + "\n";
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}

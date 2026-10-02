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
package config;

import org.aeonbits.owner.ConfigFactory;

public class ConfigLoader {

    public ServerConfig appConfig() {
        AppConfig config = ConfigFactory.create(AppConfig.class);
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.server().setHost(config.serverHost());
        serverConfig.server().setPort(config.serverPort());
        serverConfig.server().ssl().setEnabled(config.serverSslEnabled());

        serverConfig.database().setHost(config.databaseHost());
        serverConfig.database().setPort(config.databasePort());
        serverConfig.database().setName(config.databaseName());
        serverConfig.database().setUsername(config.databaseUsername());
        serverConfig.database().setPassword(config.databasePassword());
        serverConfig.database().ssl().setMode(config.databaseSslMode());
        serverConfig.jwt().setExpirationSeconds(config.jwtExpirationSeconds());

        return serverConfig;
    }
}

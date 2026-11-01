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

import org.aeonbits.owner.Config;
import org.aeonbits.owner.Config.Sources;

@Config.LoadPolicy(Config.LoadType.MERGE)
@Sources({
    "system:properties",
    "system:env",
    "classpath:auth-config.properties",
})
public interface AppConfig extends Config {

    @Key("SERVER_HOST")
    @DefaultValue("localhost")
    String serverHost();

    @Key("PORT")
    int serverPort();

    @Key("SERVER_SSL_ENABLED")
    @DefaultValue("false")
    boolean serverSslEnabled();

    @Key("DB_HOST")
    String databaseHost();

    @Key("DB_PORT")
    int databasePort();

    @Key("DB_NAME")
    String databaseName();

    @Key("DB_USER")
    String databaseUsername();

    @Key("DB_PASSWORD")
    String databasePassword();

    @Key("DB_SSL_MODE")
    @DefaultValue("require")
    String databaseSslMode();

    @Key("JWT_EXPIRATION_SECONDS")
    @DefaultValue("3600")
    long jwtExpirationSeconds();
}

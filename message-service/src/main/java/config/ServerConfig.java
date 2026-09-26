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

@Config.Sources({"system:env"})
public interface ServerConfig extends Config {

    @Key("PORT")
    @DefaultValue("8002")
    int serverPort();

    @Key("AUTH_HOST")
    @DefaultValue("localhost")
    String authHost();

    @Key("AUTH_PORT")
    @DefaultValue("8081")
    int authPort();

    @Key("DB_HOST")
    @DefaultValue("localhost")
    String databaseHost();

    @Key("DB_PORT")
    @DefaultValue("9042")
    int databasePort();

    @Key("DB_KEYSPACE")
    @DefaultValue("valoq_messages")
    String databaseKeyspace();

    @Key("DB_LOCAL_DATACENTER")
    @DefaultValue("datacenter1")
    String databaseLocalDatacenter();

    @Key("DB_USER")
    @DefaultValue("valoq_message_service")
    String databaseUsername();

    @Key("DB_PASSWORD")
    @DefaultValue("message_service_password")
    String databasePassword();

    @Key("DB_SSL_ENABLED")
    @DefaultValue("true")
    boolean databaseSslEnabled();
}

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
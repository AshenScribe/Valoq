package config;

import java.io.InputStream;
import org.yaml.snakeyaml.Yaml;

public class ConfigLoader {

    public ServerConfig appConfig() {
        Yaml yaml = new Yaml();

        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.yaml");

        if (inputStream == null) throw new IllegalStateException("config.yaml not found");
        return yaml.loadAs(inputStream, ServerConfig.class);
    }
}

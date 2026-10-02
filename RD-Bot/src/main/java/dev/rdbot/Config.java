package dev.rdbot;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** config.yml with environment-variable overrides for secrets. */
@SuppressWarnings("unchecked")
public final class Config {

    private final Map<String, Object> root;

    public Config(Path file) {
        Map<String, Object> loaded;
        try (InputStream in = Files.newInputStream(file)) {
            loaded = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file.toAbsolutePath() + " (copy config.example.yml)", e);
        }
        this.root = loaded == null ? new LinkedHashMap<>() : loaded;
    }

    public Object get(String path, Object def) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map) || !map.containsKey(part)) return def;
            cur = map.get(part);
        }
        return cur == null ? def : cur;
    }

    public String str(String path, String def) {
        String env = envOverride(path);
        if (env != null) return env;
        return String.valueOf(get(path, def));
    }

    public int intOf(String path, int def) {
        Object v = get(path, def);
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }

    public double dbl(String path, double def) {
        Object v = get(path, def);
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
    }

    public boolean bool(String path, boolean def) {
        Object v = get(path, def);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    public List<String> strings(String path) {
        Object v = get(path, List.of());
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) out.add(String.valueOf(o));
        }
        return out;
    }

    public Map<String, Object> map(String path) {
        Object v = get(path, Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, val) -> out.put(String.valueOf(k), val));
        }
        return out;
    }

    /** Groq key pool: file list plus RDBOT_GROQ_KEY_1..N environment entries. */
    public List<String> groqKeys() {
        List<String> keys = new ArrayList<>(strings("ai.keys"));
        for (int i = 1; i <= 32; i++) {
            String env = System.getenv("RDBOT_GROQ_KEY_" + i);
            if (env != null && !env.isBlank()) keys.add(env.trim());
        }
        return keys;
    }

    private String envOverride(String path) {
        String name = switch (path) {
            case "discord.token" -> "RDBOT_BOT_TOKEN";
            case "discord.owner-id" -> "RDBOT_OWNER_ID";
            default -> null;
        };
        String v = name == null ? null : System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }

    public static void copyDefaultsIfMissing(Path file) {
        if (Files.exists(file)) return;
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream("config.example.yml")) {
            if (in == null) return;
            Files.copy(in, file);
        } catch (IOException ignored) {
        }
    }
}

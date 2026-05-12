package com.musio.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MusioConfigService {
    private static final Pattern ENV_REFERENCE = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}$");

    private final MusioConfig config;

    public MusioConfigService(Environment environment) {
        this.config = load(environment);
    }

    public MusioConfig config() {
        return config;
    }

    private MusioConfig load(Environment environment) {
        Path storageHome = expandHome(environment.getProperty("musio.storage.home", "~/.musio"));
        Path configPath = expandHome(environment.getProperty("musio.config.path", storageHome.resolve("config.toml").toString()));

        Map<String, String> fileValues = readTomlValues(configPath);

        MusioConfig.Ai ai = new MusioConfig.Ai(
                value(fileValues, "ai.provider", environment.getProperty("musio.ai.provider", "openai-compatible")),
                value(fileValues, "ai.base_url", environment.getProperty("musio.ai.base-url", "http://127.0.0.1:11434/v1")),
                valueAllowBlank(fileValues, "ai.api_key", environment.getProperty("musio.ai.api-key", "")),
                value(fileValues, "ai.model", environment.getProperty("musio.ai.model", "qwen2.5:7b")),
                doubleValue(value(fileValues, "ai.temperature", environment.getProperty("musio.ai.temperature", "0.7")), 0.7),
                intValue(value(fileValues, "ai.max_tokens", environment.getProperty("musio.ai.max-tokens", "2048")), 2048)
        );

        String qqMusicSidecarHost = value(fileValues, "providers.qqmusic.sidecar_host", "127.0.0.1");
        String qqMusicSidecarPort = value(fileValues, "providers.qqmusic.sidecar_port", "18767");
        String derivedQQMusicSidecarBaseUrl = "http://" + qqMusicSidecarHost + ":" + qqMusicSidecarPort;
        String qqMusicSidecarBaseUrl = hasValue(fileValues, "providers.qqmusic.sidecar_host")
                || hasValue(fileValues, "providers.qqmusic.sidecar_port")
                ? derivedQQMusicSidecarBaseUrl
                : value(
                        fileValues,
                        "providers.qqmusic.sidecar_base_url",
                        environment.getProperty("musio.providers.qqmusic.sidecar-base-url", derivedQQMusicSidecarBaseUrl)
                );

        boolean allowStaticManifestFallback = booleanValue(value(
                fileValues,
                "providers.qqmusic.allow_static_manifest_fallback",
                environment.getProperty("musio.providers.qqmusic.allow-static-manifest-fallback", "false")
        ), false);
        MusioConfig.QQMusic qqMusic = new MusioConfig.QQMusic(qqMusicSidecarBaseUrl, allowStaticManifestFallback);

        MusioConfig.Storage storage = new MusioConfig.Storage(
                expandHome(value(fileValues, "storage.home", storageHome.toString()))
        );

        return new MusioConfig(configPath, ai, new MusioConfig.Providers(qqMusic), storage);
    }

    private Map<String, String> readTomlValues(Path configPath) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(configPath)) {
            return values;
        }

        String section = "";
        try {
            for (String rawLine : Files.readAllLines(configPath)) {
                String line = stripComment(rawLine).trim();
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                String value = unquote(line.substring(equals + 1).trim());
                String fullKey = section.isBlank() ? key : section + "." + key;
                values.put(fullKey, resolveEnvironmentReference(value));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Musio config file: " + configPath, e);
        }
        return values;
    }

    private static String value(Map<String, String> values, String key, String defaultValue) {
        String value = values.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String valueAllowBlank(Map<String, String> values, String key, String defaultValue) {
        return values.containsKey(key) ? values.get(key) : defaultValue;
    }

    private static boolean hasValue(Map<String, String> values, String key) {
        String value = values.get(key);
        return value != null && !value.isBlank();
    }

    private static String stripComment(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                inQuotes = !inQuotes;
            }
            if (current == '#' && !inQuotes) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String resolveEnvironmentReference(String value) {
        Matcher matcher = ENV_REFERENCE.matcher(value);
        if (!matcher.matches()) {
            return value;
        }

        String envName = matcher.group(1);
        String fallback = matcher.group(2) == null ? "" : matcher.group(2);
        String envValue = System.getenv(envName);
        return envValue == null || envValue.isBlank() ? fallback : envValue;
    }

    private static Path expandHome(String value) {
        if (value == null || value.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".musio").toAbsolutePath().normalize();
        }
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toAbsolutePath().normalize();
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static int intValue(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleValue(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean booleanValue(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> fallback;
        };
    }
}

package com.musio.cli.commands;

import picocli.CommandLine.IVersionProvider;

public class MusioVersionProvider implements IVersionProvider {
    private static final String DEFAULT_VERSION = "0.1.0";

    @Override
    public String[] getVersion() {
        return new String[]{"musio " + resolveVersion()};
    }

    private String resolveVersion() {
        String configured = firstNonBlank(
                System.getProperty("musio.version"),
                System.getenv("MUSIO_VERSION"),
                MusioVersionProvider.class.getPackage().getImplementationVersion(),
                DEFAULT_VERSION
        );
        if (configured.endsWith("-SNAPSHOT")) {
            return configured.substring(0, configured.length() - "-SNAPSHOT".length());
        }
        return configured;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return DEFAULT_VERSION;
    }
}

package ru.raskolnpc.integration.towny;

public final class TownyIntegrationException extends RuntimeException {
    public TownyIntegrationException(String message) {
        super(message);
    }

    public TownyIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package ru.raskolnpc.npc.lifecycle;

public final class NpcLifecycleException extends RuntimeException {

    public NpcLifecycleException(String message) {
        super(message);
    }

    public NpcLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}

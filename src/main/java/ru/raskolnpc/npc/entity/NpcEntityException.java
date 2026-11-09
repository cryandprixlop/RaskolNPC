package ru.raskolnpc.npc.entity;

public final class NpcEntityException extends RuntimeException {
    public NpcEntityException(String message) {
        super(message);
    }

    public NpcEntityException(String message, Throwable cause) {
        super(message, cause);
    }
}

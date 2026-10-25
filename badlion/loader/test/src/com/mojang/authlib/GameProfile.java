package com.mojang.authlib;

/** Stand-in for the authlib type; the loader only reads {@link #getName()}. */
public final class GameProfile {

    private final String name;

    public GameProfile(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

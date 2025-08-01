package net.minestom.server.registry;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface StaticProtocolObject<T> extends RegistryKey<T> {

    @Contract(pure = true)
    default @NotNull String name() {
        return key().asString();
    }

    @Override
    @Contract(pure = true)
    @NotNull Key key();

    @Contract(pure = true)
    int id();

    default @Nullable Object registry() {
        return null;
    }
}

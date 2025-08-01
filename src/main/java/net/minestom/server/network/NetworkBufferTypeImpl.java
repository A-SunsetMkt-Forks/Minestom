package net.minestom.server.network;

import com.google.gson.JsonElement;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Result;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryTranscoder;
import net.minestom.server.utils.Either;
import net.minestom.server.utils.Unit;
import net.minestom.server.utils.json.JsonUtil;
import net.minestom.server.utils.nbt.BinaryTagReader;
import net.minestom.server.utils.nbt.BinaryTagWriter;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static net.minestom.server.network.NetworkBuffer.*;
import static net.minestom.server.network.NetworkBufferImpl.impl;

interface NetworkBufferTypeImpl<T> extends NetworkBuffer.Type<T> {
    int SEGMENT_BITS = 0x7F;
    int CONTINUE_BIT = 0x80;

    record UnitType() implements NetworkBufferTypeImpl<Unit> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Unit value) {
        }

        @Override
        public Unit read(@NotNull NetworkBuffer buffer) {
            return Unit.INSTANCE;
        }
    }

    record BooleanType() implements NetworkBufferTypeImpl<Boolean> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Boolean value) {
            buffer.ensureWritable(1);
            impl(buffer)._putByte(buffer.writeIndex(), value ? (byte) 1 : (byte) 0);
            buffer.advanceWrite(1);
        }

        @Override
        public Boolean read(@NotNull NetworkBuffer buffer) {
            final byte value = impl(buffer)._getByte(buffer.readIndex());
            buffer.advanceRead(1);
            return value == 1;
        }
    }

    record ByteType() implements NetworkBufferTypeImpl<Byte> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Byte value) {
            buffer.ensureWritable(1);
            impl(buffer)._putByte(buffer.writeIndex(), value);
            buffer.advanceWrite(1);
        }

        @Override
        public Byte read(@NotNull NetworkBuffer buffer) {
            final byte value = impl(buffer)._getByte(buffer.readIndex());
            buffer.advanceRead(1);
            return value;
        }
    }

    record ShortType() implements NetworkBufferTypeImpl<Short> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Short value) {
            buffer.ensureWritable(2);
            impl(buffer)._putShort(buffer.writeIndex(), value);
            buffer.advanceWrite(2);
        }

        @Override
        public Short read(@NotNull NetworkBuffer buffer) {
            final short value = impl(buffer)._getShort(buffer.readIndex());
            buffer.advanceRead(2);
            return value;
        }
    }

    record UnsignedShortType() implements NetworkBufferTypeImpl<Integer> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Integer value) {
            buffer.ensureWritable(2);
            impl(buffer)._putShort(buffer.writeIndex(), (short) (value & 0xFFFF));
            buffer.advanceWrite(2);
        }

        @Override
        public Integer read(@NotNull NetworkBuffer buffer) {
            final short value = impl(buffer)._getShort(buffer.readIndex());
            buffer.advanceRead(2);
            return value & 0xFFFF;
        }
    }

    record IntType() implements NetworkBufferTypeImpl<Integer> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Integer value) {
            buffer.ensureWritable(4);
            impl(buffer)._putInt(buffer.writeIndex(), value);
            buffer.advanceWrite(4);
        }

        @Override
        public Integer read(@NotNull NetworkBuffer buffer) {
            final int value = impl(buffer)._getInt(buffer.readIndex());
            buffer.advanceRead(4);
            return value;
        }
    }

    record LongType() implements NetworkBufferTypeImpl<Long> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Long value) {
            buffer.ensureWritable(8);
            impl(buffer)._putLong(buffer.writeIndex(), value);
            buffer.advanceWrite(8);
        }

        @Override
        public Long read(@NotNull NetworkBuffer buffer) {
            final long value = impl(buffer)._getLong(buffer.readIndex());
            buffer.advanceRead(8);
            return value;
        }
    }

    record FloatType() implements NetworkBufferTypeImpl<Float> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Float value) {
            buffer.ensureWritable(4);
            impl(buffer)._putFloat(buffer.writeIndex(), value);
            buffer.advanceWrite(4);
        }

        @Override
        public Float read(@NotNull NetworkBuffer buffer) {
            final float value = impl(buffer)._getFloat(buffer.readIndex());
            buffer.advanceRead(4);
            return value;
        }
    }

    record DoubleType() implements NetworkBufferTypeImpl<Double> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Double value) {
            buffer.ensureWritable(8);
            impl(buffer)._putDouble(buffer.writeIndex(), value);
            buffer.advanceWrite(8);
        }

        @Override
        public Double read(@NotNull NetworkBuffer buffer) {
            final double value = impl(buffer)._getDouble(buffer.readIndex());
            buffer.advanceRead(8);
            return value;
        }
    }

    record VarIntType() implements NetworkBufferTypeImpl<Integer> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Integer boxed) {
            buffer.ensureWritable(5);
            long index = buffer.writeIndex();
            int value = boxed;
            var nio = impl(buffer);
            while (true) {
                if ((value & ~SEGMENT_BITS) == 0) {
                    nio._putByte(index++, (byte) value);
                    buffer.advanceWrite(index - buffer.writeIndex());
                    return;
                }
                nio._putByte(index++, (byte) ((byte) (value & SEGMENT_BITS) | CONTINUE_BIT));
                // Note: >>> means that the sign bit is shifted with the rest of the number rather than being left alone
                value >>>= 7;
            }
        }

        @Override
        public Integer read(@NotNull NetworkBuffer buffer) {
            long index = buffer.readIndex();
            // https://github.com/jvm-profiling-tools/async-profiler/blob/a38a375dc62b31a8109f3af97366a307abb0fe6f/src/converter/one/jfr/JfrReader.java#L393
            int result = 0;
            for (int shift = 0; ; shift += 7) {
                byte b = impl(buffer)._getByte(index++);
                result |= (b & 0x7f) << shift;
                if (b >= 0) {
                    buffer.advanceRead(index - buffer.readIndex());
                    return result;
                }
            }
        }
    }

    record OptionalVarIntType() implements NetworkBufferTypeImpl<@Nullable Integer> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, @Nullable Integer value) {
            buffer.write(VAR_INT, value == null ? 0 : value + 1);
        }

        @Override
        public @Nullable Integer read(@NotNull NetworkBuffer buffer) {
            final int value = buffer.read(VAR_INT);
            return value == 0 ? null : value - 1;
        }
    }

    record VarInt3Type() implements NetworkBufferTypeImpl<Integer> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Integer boxed) {
            final int value = boxed;
            // Value must be between 0 and 2^21
            Check.argCondition(value < 0 || value >= (1 << 21), "VarInt3 out of bounds: {0}", value);
            buffer.ensureWritable(3);
            final long startIndex = buffer.writeIndex();
            var impl = impl(buffer);
            impl._putByte(startIndex, (byte) (value & 0x7F | 0x80));
            impl._putByte(startIndex + 1, (byte) ((value >>> 7) & 0x7F | 0x80));
            impl._putByte(startIndex + 2, (byte) (value >>> 14));
            buffer.advanceWrite(3);
        }

        @Override
        public Integer read(@NotNull NetworkBuffer buffer) {
            // Ensure that the buffer can read other var-int sizes
            // The optimization is mostly relevant for writing
            return buffer.read(VAR_INT);
        }
    }

    record VarLongType() implements NetworkBufferTypeImpl<Long> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Long value) {
            buffer.ensureWritable(10);
            int size = 0;
            while (true) {
                if ((value & ~((long) SEGMENT_BITS)) == 0) {
                    impl(buffer)._putByte(buffer.writeIndex() + size, (byte) value.intValue());
                    buffer.advanceWrite(size + 1);
                    return;
                }
                impl(buffer)._putByte(buffer.writeIndex() + size, (byte) (value & SEGMENT_BITS | CONTINUE_BIT));
                size++;
                // Note: >>> means that the sign bit is shifted with the rest of the number rather than being left alone
                value >>>= 7;
            }
        }

        @Override
        public Long read(@NotNull NetworkBuffer buffer) {
            int length = 0;
            long value = 0;
            int position = 0;
            byte currentByte;
            while (true) {
                currentByte = impl(buffer)._getByte(buffer.readIndex() + length);
                length++;
                value |= (long) (currentByte & SEGMENT_BITS) << position;
                if ((currentByte & CONTINUE_BIT) == 0) break;
                position += 7;
                if (position >= 64) throw new RuntimeException("VarLong is too big");
            }
            buffer.advanceRead(length);
            return value;
        }
    }

    record RawBytesType(int length) implements NetworkBufferTypeImpl<byte[]> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, byte[] value) {
            if (length != -1 && value.length != length) {
                throw new IllegalArgumentException("Invalid length: " + value.length + " != " + length);
            }
            final int length = value.length;
            if (length == 0) return;
            buffer.ensureWritable(length);
            impl(buffer)._putBytes(buffer.writeIndex(), value);
            buffer.advanceWrite(length);
        }

        @Override
        public byte[] read(@NotNull NetworkBuffer buffer) {
            long length = buffer.readableBytes();
            if (this.length != -1) {
                length = Math.min(length, this.length);
            }
            if (length == 0) return new byte[0];
            assert length > 0 : "Invalid remaining: " + length;

            final int arrayLength = Math.toIntExact(length);
            final byte[] bytes = new byte[arrayLength];
            impl(buffer)._getBytes(buffer.readIndex(), bytes);
            buffer.advanceRead(arrayLength);
            return bytes;
        }
    }

    record StringType() implements NetworkBufferTypeImpl<String> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, String value) {
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            buffer.write(BYTE_ARRAY, bytes);
        }

        @Override
        public String read(@NotNull NetworkBuffer buffer) {
            final byte[] bytes = buffer.read(BYTE_ARRAY);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    record StringTerminatedType() implements NetworkBufferTypeImpl<String> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, String value) {
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            byte[] terminated = new byte[bytes.length + 1];
            System.arraycopy(bytes, 0, terminated, 0, bytes.length);
            terminated[terminated.length - 1] = 0;
            buffer.write(RAW_BYTES, terminated);
        }

        @Override
        public String read(@NotNull NetworkBuffer buffer) {
            ByteArrayList bytes = new ByteArrayList();
            byte b;
            while ((b = buffer.read(BYTE)) != 0) {
                bytes.add(b);
            }
            return new String(bytes.elements(), StandardCharsets.UTF_8);
        }
    }

    record NbtType() implements NetworkBufferTypeImpl<BinaryTag> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, BinaryTag value) {
            BinaryTagWriter nbtWriter = impl(buffer).nbtWriter();
            try {
                nbtWriter.writeNameless(value);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public BinaryTag read(@NotNull NetworkBuffer buffer) {
            BinaryTagReader nbtReader = impl(buffer).nbtReader();
            try {
                return nbtReader.readNameless();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    record BlockPositionType() implements NetworkBufferTypeImpl<Point> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Point value) {
            final int blockX = value.blockX();
            final int blockY = value.blockY();
            final int blockZ = value.blockZ();
            final long longPos = (((long) blockX & 0x3FFFFFF) << 38) |
                    (((long) blockZ & 0x3FFFFFF) << 12) |
                    ((long) blockY & 0xFFF);
            buffer.write(LONG, longPos);
        }

        @Override
        public Point read(@NotNull NetworkBuffer buffer) {
            final long value = buffer.read(LONG);
            final int x = (int) (value >> 38);
            final int y = (int) (value << 52 >> 52);
            final int z = (int) (value << 26 >> 38);
            return new Vec(x, y, z);
        }
    }

    record JsonComponentType() implements NetworkBufferTypeImpl<Component> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Component value) {
            final Transcoder<JsonElement> coder = buffer.registries() != null
                    ? new RegistryTranscoder<>(Transcoder.JSON, buffer.registries())
                    : Transcoder.JSON;
            final String json = JsonUtil.toJson(Codec.COMPONENT.encode(coder, value).orElseThrow());
            buffer.write(STRING, json);
        }

        @Override
        public Component read(@NotNull NetworkBuffer buffer) {
            final Transcoder<JsonElement> coder = buffer.registries() != null
                    ? new RegistryTranscoder<>(Transcoder.JSON, buffer.registries())
                    : Transcoder.JSON;
            final JsonElement json = JsonUtil.fromJson(buffer.read(STRING));
            return Codec.COMPONENT.decode(coder, json).orElseThrow();
        }
    }

    record UUIDType() implements NetworkBufferTypeImpl<UUID> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, java.util.UUID value) {
            buffer.write(LONG, value.getMostSignificantBits());
            buffer.write(LONG, value.getLeastSignificantBits());
        }

        @Override
        public java.util.UUID read(@NotNull NetworkBuffer buffer) {
            final long mostSignificantBits = buffer.read(LONG);
            final long leastSignificantBits = buffer.read(LONG);
            return new UUID(mostSignificantBits, leastSignificantBits);
        }
    }

    record PosType() implements NetworkBufferTypeImpl<Pos> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Pos value) {
            buffer.write(DOUBLE, value.x());
            buffer.write(DOUBLE, value.y());
            buffer.write(DOUBLE, value.z());
            buffer.write(FLOAT, value.yaw());
            buffer.write(FLOAT, value.pitch());
        }

        @Override
        public Pos read(@NotNull NetworkBuffer buffer) {
            final double x = buffer.read(DOUBLE);
            final double y = buffer.read(DOUBLE);
            final double z = buffer.read(DOUBLE);
            final float yaw = buffer.read(FLOAT);
            final float pitch = buffer.read(FLOAT);
            return new Pos(x, y, z, yaw, pitch);
        }
    }

    record ByteArrayType() implements NetworkBufferTypeImpl<byte[]> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, byte[] value) {
            buffer.write(VAR_INT, value.length);
            buffer.write(RAW_BYTES, value);
        }

        @Override
        public byte[] read(@NotNull NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            if (length == 0) return new byte[0];
            final long remaining = buffer.readableBytes();
            Check.argCondition(length > remaining, "String is too long (length: {0}, readable: {1})", length, remaining);
            return buffer.read(FixedRawBytes(length));
        }
    }

    record LongArrayType() implements NetworkBufferTypeImpl<long[]> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, long[] value) {
            buffer.write(VAR_INT, value.length);
            for (long l : value) buffer.write(LONG, l);
        }

        @Override
        public long[] read(@NotNull NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            final long[] longs = new long[length];
            for (int i = 0; i < length; i++) longs[i] = buffer.read(LONG);
            return longs;
        }
    }

    record VarIntArrayType() implements NetworkBufferTypeImpl<int[]> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, int[] value) {
            buffer.write(VAR_INT, value.length);
            for (int i : value) buffer.write(VAR_INT, i);
        }

        @Override
        public int[] read(@NotNull NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            final int[] ints = new int[length];
            for (int i = 0; i < length; i++) ints[i] = buffer.read(VAR_INT);
            return ints;
        }
    }

    record VarLongArrayType() implements NetworkBufferTypeImpl<long[]> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, long[] value) {
            buffer.write(VAR_INT, value.length);
            for (long l : value) buffer.write(VAR_LONG, l);
        }

        @Override
        public long[] read(@NotNull NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            final long[] longs = new long[length];
            for (int i = 0; i < length; i++) longs[i] = buffer.read(VAR_LONG);
            return longs;
        }
    }

    record Vector3Type() implements NetworkBufferTypeImpl<Point> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Point value) {
            buffer.write(FLOAT, (float) value.x());
            buffer.write(FLOAT, (float) value.y());
            buffer.write(FLOAT, (float) value.z());
        }

        @Override
        public Point read(@NotNull NetworkBuffer buffer) {
            final float x = buffer.read(FLOAT);
            final float y = buffer.read(FLOAT);
            final float z = buffer.read(FLOAT);
            return new Vec(x, y, z);
        }
    }

    record Vector3DType() implements NetworkBufferTypeImpl<Point> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Point value) {
            buffer.write(DOUBLE, value.x());
            buffer.write(DOUBLE, value.y());
            buffer.write(DOUBLE, value.z());
        }

        @Override
        public Point read(@NotNull NetworkBuffer buffer) {
            final double x = buffer.read(DOUBLE);
            final double y = buffer.read(DOUBLE);
            final double z = buffer.read(DOUBLE);
            return new Vec(x, y, z);
        }
    }

    record Vector3IType() implements NetworkBufferTypeImpl<Point> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Point value) {
            buffer.write(VAR_INT, (int) value.x());
            buffer.write(VAR_INT, (int) value.y());
            buffer.write(VAR_INT, (int) value.z());
        }

        @Override
        public Point read(@NotNull NetworkBuffer buffer) {
            final int x = buffer.read(VAR_INT);
            final int y = buffer.read(VAR_INT);
            final int z = buffer.read(VAR_INT);
            return new Vec(x, y, z);
        }
    }

    record Vector3BType() implements NetworkBufferTypeImpl<Point> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Point value) {
            buffer.write(BYTE, (byte) value.x());
            buffer.write(BYTE, (byte) value.y());
            buffer.write(BYTE, (byte) value.z());
        }

        @Override
        public Point read(@NotNull NetworkBuffer buffer) {
            final byte x = buffer.read(BYTE);
            final byte y = buffer.read(BYTE);
            final byte z = buffer.read(BYTE);
            return new Vec(x, y, z);
        }
    }

    record QuaternionType() implements NetworkBufferTypeImpl<float[]> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, float[] value) {
            buffer.write(FLOAT, value[0]);
            buffer.write(FLOAT, value[1]);
            buffer.write(FLOAT, value[2]);
            buffer.write(FLOAT, value[3]);
        }

        @Override
        public float[] read(@NotNull NetworkBuffer buffer) {
            final float x = buffer.read(FLOAT);
            final float y = buffer.read(FLOAT);
            final float z = buffer.read(FLOAT);
            final float w = buffer.read(FLOAT);
            return new float[]{x, y, z, w};
        }
    }

    // Combinators

    record EnumSetType<E extends Enum<E>>(@NotNull Class<E> enumType,
                                          E[] values) implements NetworkBufferTypeImpl<EnumSet<E>> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, EnumSet<E> value) {
            BitSet bitSet = new BitSet(values.length);
            for (int i = 0; i < values.length; ++i) {
                bitSet.set(i, value.contains(values[i]));
            }
            final byte[] array = bitSet.toByteArray();
            buffer.write(RAW_BYTES, array);
        }

        @Override
        public EnumSet<E> read(@NotNull NetworkBuffer buffer) {
            final byte[] array = buffer.read(FixedRawBytes((values.length + 7) / 8));
            BitSet bitSet = BitSet.valueOf(array);
            EnumSet<E> enumSet = EnumSet.noneOf(enumType);
            for (int i = 0; i < values.length; ++i) {
                if (bitSet.get(i)) {
                    enumSet.add(values[i]);
                }
            }
            return enumSet;
        }
    }

    record FixedBitSetType(int length) implements NetworkBufferTypeImpl<BitSet> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, BitSet value) {
            final int setLength = value.length();
            if (setLength > length) {
                throw new IllegalArgumentException("BitSet is larger than expected size (" + setLength + ">" + length + ")");
            } else {
                final byte[] array = value.toByteArray();
                buffer.write(RAW_BYTES, array);
            }
        }

        @Override
        public BitSet read(@NotNull NetworkBuffer buffer) {
            final byte[] array = buffer.read(FixedRawBytes((length + 7) / 8));
            return BitSet.valueOf(array);
        }
    }

    record OptionalType<T>(@NotNull Type<T> parent) implements NetworkBufferTypeImpl<@Nullable T> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, T value) {
            buffer.write(BOOLEAN, value != null);
            if (value != null) buffer.write(parent, value);
        }

        @Override
        public T read(@NotNull NetworkBuffer buffer) {
            return buffer.read(BOOLEAN) ? buffer.read(parent) : null;
        }
    }

    record LengthPrefixedType<T>(@NotNull Type<T> parent, int maxLength) implements NetworkBufferTypeImpl<T> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, T value) {
            // Write to another buffer and copy (kinda inefficient, but currently unused serverside so its ok for now)
            final byte[] componentData = NetworkBuffer.makeArray(b -> parent.write(b, value), buffer.registries());
            buffer.write(NetworkBuffer.BYTE_ARRAY, componentData);
        }

        @Override
        public T read(@NotNull NetworkBuffer buffer) {
            final int length = buffer.read(VAR_INT);
            Check.argCondition(length > maxLength, "Value is too long (length: {0}, max: {1})", length, maxLength);

            final long availableBytes = buffer.readableBytes();
            Check.argCondition(length > availableBytes, "Value is too long (length: {0}, available: {1})", length, availableBytes);
            final T value = parent.read(buffer);
            Check.argCondition(buffer.readableBytes() != availableBytes - length, "Value is too short (length: {0}, available: {1})", length, availableBytes);

            return value;
        }
    }

    final class LazyType<T> implements NetworkBufferTypeImpl<T> {
        private final @NotNull Supplier<NetworkBuffer.@NotNull Type<T>> supplier;
        private Type<T> type;

        public LazyType(@NotNull Supplier<NetworkBuffer.@NotNull Type<T>> supplier) {
            this.supplier = supplier;
        }

        @Override
        public void write(@NotNull NetworkBuffer buffer, T value) {
            if (type == null) type = supplier.get();
            type.write(buffer, value);
        }

        @Override
        public T read(@NotNull NetworkBuffer buffer) {
            if (type == null) type = supplier.get();
            return null;
        }
    }

    record TypedNbtType<T>(@NotNull Codec<T> nbtType) implements NetworkBufferTypeImpl<T> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, T value) {
            final Registries registries = impl(buffer).registries;
            Check.stateCondition(registries == null, "Buffer does not have registries");
            final Result<BinaryTag> result = nbtType.encode(new RegistryTranscoder<>(Transcoder.NBT, registries), value);
            switch (result) {
                case Result.Ok(BinaryTag tag) -> buffer.write(NBT, tag);
                case Result.Error(String message) -> throw new IllegalArgumentException("Invalid NBT tag: " + message);
            }
        }

        @Override
        public T read(@NotNull NetworkBuffer buffer) {
            final Registries registries = impl(buffer).registries;
            Check.stateCondition(registries == null, "Buffer does not have registries");
            final Result<T> result = nbtType.decode(new RegistryTranscoder<>(Transcoder.NBT, registries), buffer.read(NBT));
            return switch (result) {
                case Result.Ok(T value) -> value;
                case Result.Error(String message) -> throw new IllegalArgumentException("Invalid NBT tag: " + message);
            };
        }
    }

    record EitherType<L, R>(
            @NotNull NetworkBuffer.Type<L> left,
            @NotNull NetworkBuffer.Type<R> right
    ) implements NetworkBuffer.Type<Either<L, R>> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Either<L, R> value) {
            switch (value) {
                case Either.Left(L leftValue) -> {
                    buffer.write(BOOLEAN, true);
                    buffer.write(left, leftValue);
                }
                case Either.Right(R rightValue) -> {
                    buffer.write(BOOLEAN, false);
                    buffer.write(right, rightValue);
                }
            }
        }

        @Override
        public Either<L, R> read(@NotNull NetworkBuffer buffer) {
            if (buffer.read(BOOLEAN))
                return Either.left(buffer.read(left));
            return Either.right(buffer.read(right));
        }
    }

    record TransformType<T, S>(@NotNull Type<T> parent, @NotNull Function<T, S> to,
                               @NotNull Function<S, T> from) implements NetworkBufferTypeImpl<S> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, S value) {
            parent.write(buffer, from.apply(value));
        }

        @Override
        public S read(@NotNull NetworkBuffer buffer) {
            return to.apply(parent.read(buffer));
        }
    }

    record MapType<K, V>(@NotNull Type<K> parent, @NotNull NetworkBuffer.Type<V> valueType,
                         int maxSize) implements NetworkBufferTypeImpl<Map<K, V>> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Map<K, V> map) {
            buffer.write(VAR_INT, map.size());
            for (Map.Entry<K, V> entry : map.entrySet()) {
                buffer.write(parent, entry.getKey());
                buffer.write(valueType, entry.getValue());
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public Map<K, V> read(@NotNull NetworkBuffer buffer) {
            final int size = buffer.read(VAR_INT);
            Check.argCondition(size > maxSize, "Map size ({0}) is higher than the maximum allowed size ({1})", size, maxSize);
            K[] keys = (K[]) new Object[size];
            V[] values = (V[]) new Object[size];
            for (int i = 0; i < size; i++) {
                keys[i] = buffer.read(parent);
                values[i] = buffer.read(valueType);
            }
            return Map.copyOf(new Object2ObjectArrayMap<>(keys, values, size));
        }
    }

    record ListType<T>(@NotNull Type<T> parent, int maxSize) implements NetworkBufferTypeImpl<List<T>> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, List<T> values) {
            if (values == null) {
                buffer.write(BYTE, (byte) 0);
                return;
            }
            buffer.write(VAR_INT, values.size());
            for (T value : values) buffer.write(parent, value);
        }

        @SuppressWarnings("unchecked")
        @Override
        public List<T> read(@NotNull NetworkBuffer buffer) {
            final int size = buffer.read(VAR_INT);
            Check.argCondition(size > maxSize, "Collection size ({0}) is higher than the maximum allowed size ({1})", size, maxSize);
            T[] values = (T[]) new Object[size];
            for (int i = 0; i < size; i++) values[i] = buffer.read(parent);
            return List.of(values);
        }
    }

    record SetType<T>(@NotNull Type<T> parent, int maxSize) implements NetworkBufferTypeImpl<Set<T>> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, Set<T> values) {
            if (values == null) {
                buffer.write(BYTE, (byte) 0);
                return;
            }
            buffer.write(VAR_INT, values.size());
            for (T value : values) buffer.write(parent, value);
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<T> read(@NotNull NetworkBuffer buffer) {
            final int size = buffer.read(VAR_INT);
            Check.argCondition(size > maxSize, "Collection size ({0}) is higher than the maximum allowed size ({1})", size, maxSize);
            T[] values = (T[]) new Object[size];
            for (int i = 0; i < size; i++) values[i] = buffer.read(parent);
            return Set.of(values);
        }
    }

    record UnionType<T, K, TR extends T>(
            @NotNull Type<K> keyType, @NotNull Function<T, ? extends K> keyFunc,
            @NotNull Function<K, NetworkBuffer.Type<TR>> serializers
    ) implements NetworkBufferTypeImpl<T> {

        @SuppressWarnings("unchecked") // Much nicer than using the correct wildcard type for returns, pretty much ensuring T has subtypes already.
        @Override
        public void write(@NotNull NetworkBuffer buffer, T value) {
            final K key = keyFunc.apply(value);
            buffer.write(keyType, key);
            var serializer = serializers.apply(key);
            if (serializer == null)
                throw new UnsupportedOperationException("Unrecognized type: " + key);
            serializer.write(buffer, (TR) value);
        }

        @Override
        public T read(@NotNull NetworkBuffer buffer) {
            final K key = buffer.read(keyType);
            var serializer = serializers.apply(key);
            if (serializer == null) throw new UnsupportedOperationException("Unrecognized type: " + key);
            return serializer.read(buffer);
        }
    }

    /**
     * This is a very gross version of {@link java.io.DataOutputStream#writeUTF(String)} & ${@link DataInputStream#readUTF()}. We need the data in the java
     * modified utf-8 format for Component, and I couldnt find a method without creating a new buffer for it.
     */
    record IOUTF8StringType() implements NetworkBufferTypeImpl<String> {
        @Override
        public void write(@NotNull NetworkBuffer buffer, String value) {
            final int strlen = value.length();
            int utflen = strlen; // optimized for ASCII

            for (int i = 0; i < strlen; i++) {
                int c = value.charAt(i);
                if (c >= 0x80 || c == 0)
                    utflen += (c >= 0x800) ? 2 : 1;
            }

            if (utflen > 65535 || /* overflow */ utflen < strlen)
                throw new RuntimeException("UTF-8 string too long");

            buffer.write(SHORT, (short) utflen);
            buffer.ensureWritable(utflen);
            var impl = (NetworkBufferImpl) buffer;
            int i;
            for (i = 0; i < strlen; i++) { // optimized for initial run of ASCII
                int c = value.charAt(i);
                if (c >= 0x80 || c == 0) break;
                impl._putByte(buffer.writeIndex(), (byte) c);
                impl.advanceWrite(1);
            }

            for (; i < strlen; i++) {
                int c = value.charAt(i);
                if (c < 0x80 && c != 0) {
                    impl._putByte(buffer.writeIndex(), (byte) c);
                    impl.advanceWrite(1);
                } else if (c >= 0x800) {
                    impl._putByte(buffer.writeIndex(), (byte) (0xE0 | ((c >> 12) & 0x0F)));
                    impl._putByte(buffer.writeIndex() + 1, (byte) (0x80 | ((c >> 6) & 0x3F)));
                    impl._putByte(buffer.writeIndex() + 2, (byte) (0x80 | ((c >> 0) & 0x3F)));
                    impl.advanceWrite(3);
                } else {
                    impl._putByte(buffer.writeIndex(), (byte) (0xC0 | ((c >> 6) & 0x1F)));
                    impl._putByte(buffer.writeIndex() + 1, (byte) (0x80 | ((c >> 0) & 0x3F)));
                    impl.advanceWrite(2);
                }
            }
        }

        @Override
        public String read(@NotNull NetworkBuffer buffer) {
            int utflen = buffer.read(UNSIGNED_SHORT);
            if (buffer.readableBytes() < utflen) throw new IllegalArgumentException("Invalid String size.");
            byte[] bytearr = buffer.read(FixedRawBytes(utflen));
            final char[] chararr = new char[utflen];

            int c, char2, char3;
            int count = 0;
            int chararr_count = 0;

            while (count < utflen) {
                c = (int) bytearr[count] & 0xff;
                if (c > 127) break;
                count++;
                chararr[chararr_count++] = (char) c;
            }

            while (count < utflen) {
                c = (int) bytearr[count] & 0xff;
                try { // Surround in try catch to throw a runtime exception instead of a checked one
                    switch (c >> 4) {
                        case 0, 1, 2, 3, 4, 5, 6, 7 -> {
                            /* 0xxxxxxx*/
                            count++;
                            chararr[chararr_count++] = (char) c;
                        }
                        case 12, 13 -> {
                            /* 110x xxxx   10xx xxxx*/
                            count += 2;
                            if (count > utflen)
                                throw new UTFDataFormatException(
                                        "malformed input: partial character at end");
                            char2 = bytearr[count - 1];
                            if ((char2 & 0xC0) != 0x80)
                                throw new UTFDataFormatException(
                                        "malformed input around byte " + count);
                            chararr[chararr_count++] = (char) (((c & 0x1F) << 6) |
                                    (char2 & 0x3F));
                        }
                        case 14 -> {
                            /* 1110 xxxx  10xx xxxx  10xx xxxx */
                            count += 3;
                            if (count > utflen)
                                throw new UTFDataFormatException(
                                        "malformed input: partial character at end");
                            char2 = bytearr[count - 2];
                            char3 = bytearr[count - 1];
                            if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                                throw new UTFDataFormatException(
                                        "malformed input around byte " + (count - 1));
                            chararr[chararr_count++] = (char) (((c & 0x0F) << 12) |
                                    ((char2 & 0x3F) << 6) |
                                    ((char3 & 0x3F) << 0));
                        }
                        default ->
                            /* 10xx xxxx,  1111 xxxx */
                                throw new UTFDataFormatException(
                                        "malformed input around byte " + count);
                    }
                } catch (UTFDataFormatException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            // The number of chars produced may be less than utflen
            return new String(chararr, 0, chararr_count);
        }
    }

    static <T> long sizeOf(Type<T> type, T value, Registries registries) {
        NetworkBuffer buffer = NetworkBufferImpl.dummy(registries);
        type.write(buffer, value);
        return buffer.writeIndex();
    }
}

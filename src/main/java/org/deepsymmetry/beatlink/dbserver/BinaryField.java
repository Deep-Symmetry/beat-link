package org.deepsymmetry.beatlink.dbserver;

import org.deepsymmetry.beatlink.Util;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A binary field holds an arbitrary sequence of bytes whose length is determined by the 4-byte big-endian
 * integer that follows the type tag.
 *
 * @author James Elliott
 */
public class BinaryField extends Field {

    /**
     * The byte which identifies the specific type of number field that is coming next in a network stream.
     */
    private final byte typeTag = (byte)0x14;

    /**
     * The byte which identifies this type of field within a message's argument list, which for some reason is
     * different than the type tag itself.
     */
    private final byte argumentTag = (byte)0x03;

    /**
     * The number of bytes making up the network representation of the value of this field, excluding the type tag.
     */
    private final int size;

    /**
     * Holds the value represented by this field.
     */
    private final ByteBuffer value;

    /**
     * Holds the actual bytes used to transmit this field, including the type tag.
     */
    private final ByteBuffer buffer;

    /**
     * Pulls out the portion of our full buffer that represents just the binary value held by the field.
     * Shared by both constructors to initialize the {@link #value} field.
     *
     * @return the portion of our byte stream that follows the tag and size.
     */
    private ByteBuffer extractValue() {
        buffer.rewind();
        buffer.get();    // Move past the tag
        buffer.getInt(); // Move past the size
        return buffer.slice();
    }

    /**
     * Constructor for reading from the network.
     *
     * @param is the stream on which the field value is to be read.
     *
     * @throws IllegalArgumentException if tag is not a valid number field tag.
     * @throws IOException if there is a problem reading the value.
     */
    public BinaryField(final DataInputStream is) throws IOException {
        byte[] sizeBytes = new byte[4];
        is.readFully(sizeBytes);
        size = (int)Util.bytesToNumber(sizeBytes, 0, 4);
        byte[] bufBytes = new byte[size + 5];
        bufBytes[0] = typeTag;
        System.arraycopy(sizeBytes, 0, bufBytes, 1, 4);
        is.readFully(bufBytes, 5, size);
        buffer = ByteBuffer.wrap(bufBytes).asReadOnlyBuffer();
        value = extractValue();
    }

    /**
     * Constructor for code.
     *
     * @param bytes the value that this field will convey.
     */
    public BinaryField(final byte[] bytes) {
        size = bytes.length;
        ByteBuffer scratch = ByteBuffer.allocate(size + 5);
        scratch.put(typeTag);
        scratch.putInt(size);
        scratch.put(bytes);
        buffer = scratch.asReadOnlyBuffer();
        value = extractValue();
    }

    @Override
    public byte getTypeTag() {
        return typeTag;
    }

    @Override
    public byte getArgumentTag() {
        return argumentTag;
    }

    @Override
    public ByteBuffer getBytes() {
        buffer.rewind();
        return buffer;
    }

    @Override
    public long getSize() {
        return size;
    }
}

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
     * Shared by both constructors to initialize the {@link #value} field. Handles the special case of
     * a zero-length blob, for which no bytes at all are sent. (Really! The protocol requires this!)
     *
     * @return the portion of our byte stream that follows the tag and size.
     */
    private ByteBuffer extractValue() {
        buffer.rewind();
        if (buffer.capacity() > 0) {
            buffer.get();    // Move past the tag
            buffer.getInt(); // Move past the size
        }
        return buffer.slice();
    }

    /**
     * Get the bytes which represent the payload of this field, without the leading type tag and length header.
     *
     * @return the bytes whose purpose this field exists to convey.
     */
    public ByteBuffer getValue() {
        value.rewind();
        return value.slice();
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
        final ByteBuffer scratch;
        size = bytes.length;
        if (size > 0) {
            scratch = ByteBuffer.allocate(size + 5);
            scratch.put(typeTag);
            scratch.putInt(size);
            scratch.put(bytes);
        } else {
            scratch = ByteBuffer.allocate(0);
        }
        buffer = scratch.asReadOnlyBuffer();
        value = extractValue();
    }

    @Override
    public byte getTypeTag() {
        return typeTag;
    }

    @Override
    public byte getArgumentTag() {
        return (byte) 0x03;
    }

    @Override
    public ByteBuffer getBytes() {
        buffer.rewind();
        return buffer.slice();
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public String toString() {
        return "BinaryField[ size: " + size + ", bytes: " + getHexString() + "]";
    }
}

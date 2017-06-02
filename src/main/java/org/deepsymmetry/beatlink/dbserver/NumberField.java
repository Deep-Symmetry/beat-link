package org.deepsymmetry.beatlink.dbserver;

import org.deepsymmetry.beatlink.Util;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A number field represents an integer, and can take up 1, 2, or 4 bytes, depending on the tag which
 * introduces it.
 *
 * @author James Elliott
 */
public class NumberField extends Field {

    /**
     * The byte which identifies the specific type of number field that is coming next in a network stream.
     */
    private final byte typeTag;

    /**
     * The number of bytes making up the network representation of the value of this field, excluding the type tag.
     */
    private final int size;

    /**
     * Holds the value represented by this field. We use a long so we don't have to worry about sign issues
     * when the full four byte version is used.
     */
    private final long value;

    /**
     * Holds the actual bytes used to transmit this field, including the type tag.
     */
    private final ByteBuffer buffer;

    /**
     * Constructor for reading from the network.
     *
     * @param typeTag the tag which identified this field as a NumberField, and which allows us to determine the
     *                proper size.
     * @param is the stream on which the field value is to be read.
     *
     * @throws IllegalArgumentException if tag is not a valid number field tag.
     * @throws IOException if there is a problem reading the value.
     */
    public NumberField(final byte typeTag, final DataInputStream is) throws IOException {
        this.typeTag = typeTag;
        switch (typeTag) {
            case 0x0f:
                size = 1;
                break;
            case 0x10:
                size = 2;
                break;
            case 0x11:
                size = 4;
                break;
            default:
                throw new IllegalArgumentException("NumberField cannot have tag " + typeTag);
        }
        byte[] bufBytes = new byte[size + 1];
        bufBytes[0] = typeTag;
        is.readFully(bufBytes, 1, size);
        buffer = ByteBuffer.wrap(bufBytes).asReadOnlyBuffer();
        value = Util.bytesToNumber(bufBytes, 1, size);
    }

    /**
     * Constructor from code.
     *
     * @param value the desired value to be represented by this field.
     * @param size the number of bytes to be used to hold the value: 1, 2, or 4.
     *
     * @throws IllegalArgumentException if the specified size is not a supported number field size.
     */
    public NumberField(final long value, final int size) {
        this.value = value & 0xffffffffL;
        this.size = size;
        byte[] bufBytes = new byte[size + 1];
        switch (size) {
            case 1:
                typeTag = (byte)0x0f;
                bufBytes[1] = (byte)(value & 0xff);
                break;
            case 2:
                typeTag = (byte)0x10;
                bufBytes[1] = (byte)((value & 0xff00) >> 8);
                bufBytes[2] = (byte)(value & 0xff);
                break;
            case 4:
                typeTag = (byte)0x11;
                bufBytes[1] = (byte)((value & 0xff000000) >> 24);
                bufBytes[2] = (byte)((value & 0xff0000) >> 16);
                bufBytes[3] = (byte)((value & 0xff00) >> 8);
                bufBytes[4] = (byte)(value & 0xff);
                break;
            default:
                throw new IllegalArgumentException("NumberField cannot have size " + size);
        }
        bufBytes[0] = typeTag;
        buffer = ByteBuffer.wrap(bufBytes).asReadOnlyBuffer();
    }

    /**
     * Convenience constructor from code, for the most common case of wanting a 4-byte number field.
     *
     * @param value the desired value to be represented by this field.
     */
    public NumberField(final long value) {
        this(value, 4);
    }

    /**
     * Get the numeric value this field represents.
     *
     * @return the number which this field contains.
     */
    public long getValue() {
        return value;
    }

    @Override
    public byte getTypeTag() {
        return typeTag;
    }

    @Override
    public byte getArgumentTag() {
        return (byte) 0x06;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public ByteBuffer getBytes() {
        buffer.rewind();
        return buffer.slice();
    }

    @Override
    public String toString() {
        return "NumberField[ size: " + size + ", value: " + value + ", bytes: " + getHexString() + "]";
    }
}

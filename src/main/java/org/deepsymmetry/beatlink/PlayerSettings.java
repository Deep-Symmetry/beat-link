package org.deepsymmetry.beatlink;

/**
 * Encapsulates the “My Settings” configuration parameters that can be applied to a player over the network.
 * Not all players support all features. A simple mutable class (and therefore not thread-safe).
 * 
 * @author James Elliott
 * @see VirtualCdj#sendLoadSettingsCommand(DeviceUpdate, PlayerSettings) 
 */
public class PlayerSettings {

    /**
     * Create an instance with default settings values.
     */
    public PlayerSettings() {
        // Nothing to do.
    }

    /**
     * A standard on/off choice which is used in many settings.
     */
    public enum Toggle {
        OFF((byte)0x80),
        ON((byte)0x81);

        /**
         * The value in the Load Settings packet which corresponds to the chosen state.
         */
        public final byte protocolValue;

        Toggle(byte protocolValue) {
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls whether the player displays whether it is currently on the air (when paired with a mixer that
     * supports this feature).
     */
    public Toggle onAirDisplay = Toggle.ON;

    /**
     * The brightness options for the player's LCD screen.
     */
    public enum LcdBrightness {
        DIMMEST(1, (byte)0x81),
        DIM(2, (byte)0x82),
        MEDIUM(3, (byte)0x83),
        BRIGHT(4, (byte)0x84),
        BRIGHTEST(5, (byte)0x85);

        /**
         * The value displayed in the rekordbox interface for each brightness setting.
         */
        public final int displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each brightness setting.
         */
        public final byte protocolValue;

        LcdBrightness(int displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls the brightness of the main LCD display.
     */
    public LcdBrightness lcdBrightness = LcdBrightness.MEDIUM;

    /**
     * Controls whether quantization is enabled.
     */
    public Toggle quantize = Toggle.ON;

    /**
     * The options for establishing an automatic cue point when loading a track.
     */
    public enum AutoCueLevel {
        MINUS_36("-36 dB", (byte)0x80),
        MINUS_42("-42 dB", (byte)0x81),
        MINUS_48("-48 dB", (byte)0x82),
        MINUS_54("-54 dB", (byte)0x83),
        MINUS_60("-60 dB", (byte)0x84),
        MINUS_66("-66 dB", (byte)0x85),
        MINUS_72("-72 dB", (byte)0x86),
        MINUS_78("-78 dB", (byte)0x87),
        MEMORY("Memory", (byte)0x88);

        /**
         * The value displayed in the rekordbox interface for each auto cue level setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each auto cue level setting.
         */
        public final byte protocolValue;

        AutoCueLevel(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls where the initial cue point is established when loading a track.
     */
    public AutoCueLevel autoCueLevel = AutoCueLevel.MEMORY;

}

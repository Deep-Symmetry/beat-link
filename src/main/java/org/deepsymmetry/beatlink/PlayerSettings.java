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
        OFF("Off", (byte)0x80),
        ON ("On", (byte)0x81);

        /**
         * The value displayed in the rekordbox interface for each brightness setting.
         */
        public final String displayValue;

        /**
         * The value in the Load Settings packet which corresponds to the chosen state.
         */
        public final byte protocolValue;

        Toggle(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
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

    /**
     * The options for the player user interface language.
     */
    public enum Language {
        ENGLISH("English", (byte)0x81),
        FRENCH("French", (byte)0x82),
        GERMAN("German", (byte)0x83),
        ITALIAN("Italian", (byte)0x84),
        DUTCH("Dutch", (byte)0x85),
        SPANISH("Spanish", (byte)0x86),
        RUSSIAN("Russian", (byte)0x87),
        KOREAN("Korean", (byte)0x88),
        CHINESE_SIMPLIFIED("Chinese (simplified)", (byte)0x89),
        CHINESE_TRADITIONAL("Chinese (traditional)", (byte)0x8a),
        JAPANESE("Japanese", (byte)0x8b),
        PORTUGUESE("Portuguese", (byte)0x8c),
        SWEDISH("Swedish", (byte)0x8d),
        CZECH("Czech", (byte)0x8e),
        MAGYAR("Magyar", (byte)0x8f),
        DANISH("Danish", (byte)0x90),
        GREEK("Greek", (byte)0x91),
        TURKISH("Turkish", (byte)0x92);

        /**
         * The value displayed in the rekordbox interface for each language setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each language setting.
         */
        public final byte protocolValue;

        Language(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls the language displayed in the player user interface.
     */
    public Language language = Language.ENGLISH;

    /**
     * The brightness choices for optional illumination (for example of the jog ring or disc slot).
     */
    public enum Illumination {
        OFF("0 (Off)", (byte)0x80),
        DARK("1 (Dark)", (byte)0x81),
        BRIGHT("2 (Bright)", (byte)0x82);

        /**
         * The value displayed in the rekordbox interface for each illumination setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each illumination setting.
         */
        public final byte protocolValue;

        Illumination(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls the illumination of the player's jog ring.
     */
    public Illumination jogRingIllumination = Illumination.BRIGHT;

    /**
     * Controls the jog ring indicator feature.
     */
    public Toggle jogRingIndicator = Toggle.ON;

    /**
     * Controls the Slip Flashing feature.
     */
    public Toggle slipFlashing = Toggle.ON;

    /**
     * Controls the illumination of the player's jog ring.
     */
    public Illumination discSlotIllumination = Illumination.DARK;

    /**
     * Controls the eject/load lock feature.
     */
    public Toggle ejectLoadLock = Toggle.ON;

    /**
     * Controls the Sync feature.
     */
    public Toggle sync = Toggle.ON;

    /**
     * The options for whether to keep playing when a track ends.
     */
    public enum PlayMode {
        CONTINUE("Continue", (byte)0x80),
        SINGLE("Single", (byte)0x81);
        /**
         * The value displayed in the rekordbox interface for each play mode setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each play mode setting.
         */
        public final byte protocolValue;

        PlayMode(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls what to do when a track ends.
     */
    public PlayMode autoPlayMode = PlayMode.SINGLE;

    /**
     * The options the quantization size.
     */
    public enum QuantizeMode {
        BEAT("Beat", (byte)0x80),
        HALF("Half Beat", (byte)0x81),
        QUARTER("Quarter Beat", (byte)0x82),
        EIGHTH("Eighth Beat", (byte)0x83);

        /**
         * The value displayed in the rekordbox interface for each quantize mode setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each quantize mode setting.
         */
        public final byte protocolValue;

        QuantizeMode(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls the scale at which quantization is applied, when it is active.
     */
    public QuantizeMode quantizeBeatValue = QuantizeMode.BEAT;

    /**
     * The options for whether to auto-load hot cues when loading a track.
     */
    public enum AutoLoadMode {
        OFF("Off", (byte)0x80),
        ON("On", (byte)0x81),
        REKORDBOX("rekordbox", (byte)0x82);
        /**
         * The value displayed in the rekordbox interface for each auto load setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each auto load setting.
         */
        public final byte protocolValue;

        AutoLoadMode(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls whether hot cues are auto-loaded when loading tracks.
     */
    public AutoLoadMode autoLoadMode = AutoLoadMode.REKORDBOX;

    /**
     * Controls whether hot cue colors are displayed.
     */
    public Toggle hotCueColor = Toggle.ON;

    /**
     * Controls the Needle Lock feature.
     */
    public Toggle needleLock = Toggle.ON;

    /**
     * The options for how time is displayed on the player.
     */
    public enum TimeDisplayMode {
        ELAPSED("Elapsed", (byte)0x80),
        REMAINING("Remaining", (byte)0x81);
        /**
         * The value displayed in the rekordbox interface for each time display setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each time display setting.
         */
        public final byte protocolValue;

        TimeDisplayMode(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls how time is displayed on the player.
     */
    public TimeDisplayMode timeDisplayMode = TimeDisplayMode.REMAINING;

    /**
     * The options for how the jog wheel affects playback.
     */
    public enum JogMode {
        CDJ("CDJ", (byte)0x80),
        VINYL("Vinyl", (byte)0x81);
        /**
         * The value displayed in the rekordbox interface for each jog mode setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each jog mode setting.
         */
        public final byte protocolValue;

        JogMode(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls how the jog wheel affects playback.
     */
    public JogMode jogMode = JogMode.VINYL;

    /**
     * Controls the Auto Cue feature.
     */
    public Toggle autoCue = Toggle.ON;

    /**
     * Controls the Master Tempo feature.
     */
    public Toggle masterTempo = Toggle.ON;

    /**
     * The options for tempo range limits.
     */
    public enum TempoRange {
        PLUS_MINUS_6("±6", (byte)0x80),
        PLUS_MINUS_10("±10", (byte)0x81),
        PLUS_MINUS_16("±16", (byte)0x82),
        WIDE("Wide", (byte)0x83);
        /**
         * The value displayed in the rekordbox interface for each tempo range setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each tempo range setting.
         */
        public final byte protocolValue;

        TempoRange(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls the range of the tempo fader.
     */
    public TempoRange tempoRange = TempoRange.PLUS_MINUS_6;

    /**
     * The options for the phase meter type.
     */
    public enum PhaseMeterType {
        TYPE_1("Type 1", (byte)0x80),
        TYPE_2("Type 2", (byte)0x81);
        /**
         * The value displayed in the rekordbox interface for each phase meter setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each phase meter setting.
         */
        public final byte protocolValue;

        PhaseMeterType(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls the phase meter type displayed.
     */
    public PhaseMeterType phaseMeterType = PhaseMeterType.TYPE_1;

    /**
     * The options for the phase meter type.
     */
    public enum VinylSpeedAdjust {
        TOUCH_RELEASE("Touch and Release", (byte)0x80),
        TOUCH("Touch", (byte)0x81),
        RELEASE("Release", (byte)0x82);
        /**
         * The value displayed in the rekordbox interface for each vinyl speed adjust setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each vinyl speed adjust setting.
         */
        public final byte protocolValue;

        VinylSpeedAdjust(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls when vinyl speed adjustment is used.
     */
    public VinylSpeedAdjust vinylSpeedAdjust = VinylSpeedAdjust.TOUCH_RELEASE;

    /**
     * The options for what to display in the jog wheel display screens.
     */
    public enum JogWheelDisplay {
        AUTO("Auto", (byte)0x80),
        INFO("Info", (byte)0x81),
        SIMPLE("Simple", (byte)0x82),
        ARTWORK("Artwork", (byte)0x83);
        /**
         * The value displayed in the rekordbox interface for each jog wheel display setting.
         */
        public final String displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each jog wheel display setting.
         */
        public final byte protocolValue;

        JogWheelDisplay(String displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls what is shown in the jog wheel display screens.
     */
    public JogWheelDisplay jogWheelDisplay = JogWheelDisplay.AUTO;

    /**
     * The brightness options for the player's performance pads and buttons.
     */
    public enum PadButtonBrightness {
        DIMMEST(1, (byte)0x81),
        DIM(2, (byte)0x82),
        BRIGHT(3, (byte)0x83),
        BRIGHTEST(4, (byte)0x84);

        /**
         * The value displayed in the rekordbox interface for each brightness setting.
         */
        public final int displayValue;

        /**
         * The value sent in the Load Settings packet which establishes each brightness setting.
         */
        public final byte protocolValue;

        PadButtonBrightness(int displayValue, byte protocolValue) {
            this.displayValue = displayValue;
            this.protocolValue = protocolValue;
        }
    }

    /**
     * Controls the brightness of the performance pads and buttons.
     */
    public PadButtonBrightness padButtonBrightness = PadButtonBrightness.BRIGHT;

    /**
     * Controls the brightness of the jog wheel LCD display.
     */
    public LcdBrightness jogWheelLcdBrightness = LcdBrightness.MEDIUM;
}

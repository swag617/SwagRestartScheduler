package com.swag617.restartsched.warning;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable data class representing a single warning threshold.
 *
 * <p>When the restart countdown reaches {@code secondsRemaining}, the warning
 * manager fires a broadcast message and optional title/subtitle/sound.</p>
 */
public final class WarningDefinition {

    private final int secondsRemaining;
    private final String message;       // MiniMessage string, nullable
    private final String title;         // MiniMessage string, nullable
    private final String subtitle;      // MiniMessage string, nullable
    private final String sound;         // Bukkit Sound enum name, nullable

    public WarningDefinition(int secondsRemaining, String message, String title,
                             String subtitle, String sound) {
        if (secondsRemaining < 0) {
            throw new IllegalArgumentException("secondsRemaining must be >= 0, got: " + secondsRemaining);
        }
        this.secondsRemaining = secondsRemaining;
        this.message  = message;
        this.title    = title;
        this.subtitle = subtitle;
        this.sound    = sound;
    }

    /**
     * Parses a {@link WarningDefinition} from a config section that has the shape:
     * <pre>
     * seconds: 300
     * message: "..."
     * title: "..."     # may be null / absent
     * subtitle: "..."  # may be null / absent
     * sound: "..."     # may be null / absent
     * </pre>
     *
     * @param section the config section to parse
     * @return parsed definition, or {@code null} if the section is invalid
     */
    public static WarningDefinition fromSection(ConfigurationSection section) {
        if (section == null) return null;

        int seconds = section.getInt("seconds", -1);
        if (seconds < 0) return null;

        String message  = section.isString("message")  ? section.getString("message")  : null;
        String title    = section.isString("title")    ? section.getString("title")    : null;
        String subtitle = section.isString("subtitle") ? section.getString("subtitle") : null;
        String sound    = section.isString("sound")    ? section.getString("sound")    : null;

        return new WarningDefinition(seconds, message, title, subtitle, sound);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** The countdown value (seconds remaining) at which this warning fires. */
    public int getSecondsRemaining() {
        return secondsRemaining;
    }

    /** MiniMessage-formatted chat broadcast. May be {@code null}. */
    public String getMessage() {
        return message;
    }

    /** MiniMessage-formatted title text. May be {@code null}. */
    public String getTitle() {
        return title;
    }

    /** MiniMessage-formatted subtitle text. May be {@code null}. */
    public String getSubtitle() {
        return subtitle;
    }

    /**
     * Bukkit {@code Sound} enum name (e.g. {@code "ENTITY_ENDER_DRAGON_GROWL"}).
     * May be {@code null}.
     */
    public String getSound() {
        return sound;
    }

    @Override
    public String toString() {
        return "WarningDefinition{seconds=" + secondsRemaining
                + ", message='" + message + '\''
                + ", title='" + title + '\''
                + ", subtitle='" + subtitle + '\''
                + ", sound='" + sound + '\''
                + '}';
    }
}

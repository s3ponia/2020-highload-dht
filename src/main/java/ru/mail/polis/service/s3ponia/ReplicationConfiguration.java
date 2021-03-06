package ru.mail.polis.service.s3ponia;

import com.google.common.base.Splitter;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class ReplicationConfiguration {
    public static final List<ReplicationConfiguration> DEFAULT_CONFIGURATIONS = Arrays.asList(
            new ReplicationConfiguration(1, 1),
            new ReplicationConfiguration(2, 2),
            new ReplicationConfiguration(2, 3),
            new ReplicationConfiguration(3, 4),
            new ReplicationConfiguration(3, 5)
    );
    public final int acks;
    public final int replicas;

    public ReplicationConfiguration(final int ack, final int from) {
        this.acks = ack;
        this.replicas = from;
    }

    /**
     * Parses ReplicationConfiguration from String.
     *
     * @param s String for parsing
     * @return ReplicationConfiguration on null
     */
    @NotNull
    public static ReplicationConfiguration parse(@NotNull final String s) {
        final var splitStrings = Splitter.on('/').splitToList(s);
        if (splitStrings.size() != 2) {
            throw new IllegalArgumentException("Bad replica string");
        }

        final var temp = new ReplicationConfiguration(Integer.parseInt(splitStrings.get(0)),
                Integer.parseInt(splitStrings.get(1)));
        if (temp.acks > temp.replicas || temp.acks == 0) {
            throw new IllegalArgumentException("Bad replica string");
        }
        return temp;
    }

    /**
     * Returning default configuration for passed nodes' count if null passed or parse string.
     * @param s replica's string config
     * @param sz node's count
     * @return ReplicationConfiguration
     */
    public static ReplicationConfiguration parseOrDefault(final String s, final int sz) {
        if (s == null) {
            return DEFAULT_CONFIGURATIONS.get(sz - 1);
        } else {
            return parse(s);
        }
    }

    @Override
    public String toString() {
        return acks + "/" + replicas;
    }
}

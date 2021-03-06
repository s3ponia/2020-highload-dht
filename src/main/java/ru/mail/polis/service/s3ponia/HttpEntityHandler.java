package ru.mail.polis.service.s3ponia;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;

public interface HttpEntityHandler extends Closeable {

    void entity(final String id,
                final String replicas,
                @NotNull final Request request,
                @NotNull final HttpSession session) throws IOException;
}

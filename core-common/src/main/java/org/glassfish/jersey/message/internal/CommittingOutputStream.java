/*
 * Copyright (c) 2010, 2024 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.message.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.jersey.innate.VirtualThreadSupport;
import org.glassfish.jersey.internal.LocalizationMessages;
import org.glassfish.jersey.internal.guava.Preconditions;

/**
 * A committing output stream with optional serialized entity buffering functionality
 * which allows measuring of the entity size.
 * <p>
 * When buffering functionality is enabled the output stream buffers
 * the written bytes into an internal buffer of a configurable size. After the last
 * written byte the {@link #commit()} method is expected to be called to notify
 * a {@link org.glassfish.jersey.message.internal.OutboundMessageContext.StreamProvider#getOutputStream(int) callback}
 * with an actual measured entity size. If the entity is too large to
 * fit into the internal buffer and the buffer exceeds before the {@link #commit()}
 * is called then the stream is automatically committed and the callback is called
 * with parameter {@code size} value of {@code -1}.
 * </p>
 * <p>
 * Callback method also returns the output stream in which the output will be written. The committing output stream
 * must be initialized with the callback using
 * {@link #setStreamProvider(org.glassfish.jersey.message.internal.OutboundMessageContext.StreamProvider)}
 * before first byte is written.
 * </p>
 * The buffering is by default disabled and can be enabled by calling {@link #enableBuffering()}
 * or {@link #enableBuffering(int)} before writing the first byte into this output stream. The former
 * method enables buffering with the default size
 * <tt>{@value CommittingOutputStream#DEFAULT_BUFFER_SIZE}</tt> bytes specified in {@link #DEFAULT_BUFFER_SIZE}.
 * </p>
 *
 * @author Paul Sandoz
 * @author Marek Potociar
 * @author Miroslav Fuksa
 */
public final class CommittingOutputStream extends OutputStream {

    private static final Logger LOGGER = Logger.getLogger(CommittingOutputStream.class.getName());
    private final boolean isVirtualThread = VirtualThreadSupport.isVirtualThread();

    /**
     * Null stream provider.
     */
    private static final OutboundMessageContext.StreamProvider NULL_STREAM_PROVIDER =
            contentLength -> new NullOutputStream();
    /**
     * Default size of the buffer which will be used if no user defined size is specified.
     */
    public static final int DEFAULT_BUFFER_SIZE = 8192;
    /**
     * Adapted output stream.
     */
    private OutputStream adaptedOutput;
    /**
     * Buffering stream provider.
     */
    private OutboundMessageContext.StreamProvider streamProvider;
    /**
     * Internal buffer size.
     */
    private int bufferSize = 0;
    /**
     * Entity buffer.
     */
    private ByteArrayOutputStream buffer;
    /**
     * When {@code true}, the data are written directly to output stream and not to the buffer.
     */
    private boolean directWrite = true;
    /**
     * When {@code true}, the stream is already committed (redirected to adaptedOutput).
     */
    private boolean isCommitted;
    /**
     * When {@code true}, the stream is already closed.
     */
    private boolean isClosed;

    private static final String STREAM_PROVIDER_NULL = LocalizationMessages.STREAM_PROVIDER_NULL();
    private static final String COMMITTING_STREAM_BUFFERING_ILLEGAL_STATE = LocalizationMessages
            .COMMITTING_STREAM_BUFFERING_ILLEGAL_STATE();

    /**
     * Creates new committing output stream. The returned stream instance still needs to be initialized before
     * writing first bytes.
     */
    public CommittingOutputStream() {
    }

    /**
     * Set the buffering output stream provider. If the committing output stream works in buffering mode
     * this method must be called before first bytes are written into this stream.
     *
     * @param streamProvider non-null stream provider callback.
     */
    public void setStreamProvider(OutboundMessageContext.StreamProvider streamProvider) {
        if (isClosed) {
            throw new IllegalStateException(LocalizationMessages.OUTPUT_STREAM_CLOSED());
        }
        Objects.nonNull(streamProvider);

        if (this.streamProvider != null) {
            LOGGER.log(Level.WARNING, LocalizationMessages.COMMITTING_STREAM_ALREADY_INITIALIZED());
        }
        this.streamProvider = streamProvider;
    }

    /**
     * Enable buffering of the serialized entity.
     *
     * @param bufferSize size of the buffer. When the value is less or equal to zero the buffering will be disabled and {@code -1}
     *                   will be passed to the
     *                   {@link org.glassfish.jersey.message.internal.OutboundMessageContext.StreamProvider#getOutputStream(int) callback}.
     */
    public void enableBuffering(int bufferSize) {
        Preconditions.checkState(!isCommitted && (this.buffer == null || this.buffer.size() == 0),
                                 COMMITTING_STREAM_BUFFERING_ILLEGAL_STATE);
        this.bufferSize = bufferSize;
        if (bufferSize <= 0) {
            this.directWrite = true;
            this.buffer = null;
        } else {
            directWrite = false;
            buffer = new ByteArrayOutputStream(bufferSize);
        }
    }

    /**
     * Enable buffering of the serialized entity with the {@link #DEFAULT_BUFFER_SIZE default buffer size }.
     */
    void enableBuffering() {
        enableBuffering(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Determine whether the stream was already committed or not.
     *
     * @return {@code true} if this stream was already committed, {@code false} otherwise.
     */
    public boolean isCommitted() {
        return isCommitted;
    }

    private void commitStream() throws IOException {
        commitStream(-1);
    }

    private void commitStream(int currentSize) throws IOException {
        if (!isCommitted) {
            Preconditions.checkState(streamProvider != null, STREAM_PROVIDER_NULL);
            adaptedOutput = streamProvider.getOutputStream(currentSize);
            if (adaptedOutput == null) {
                adaptedOutput = new NullOutputStream();
            }

            directWrite = true;
            isCommitted = true;
        }
    }

    @Override
    public void write(byte b[]) throws IOException {
        if (directWrite) {
            commitStream();
            adaptedOutput.write(b);
        } else {
            if (b.length + buffer.size() > bufferSize) {
                flushBuffer(false);
                adaptedOutput.write(b);
            } else {
                buffer.write(b);
            }
        }
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
        if (directWrite) {
            commitStream();
            adaptedOutput.write(b, off, len);
        } else {
            if (len + buffer.size() > bufferSize) {
                flushBuffer(false);
                adaptedOutput.write(b, off, len);
            } else {
                buffer.write(b, off, len);
            }
        }
    }

    @Override
    public void write(int b) throws IOException {
        if (directWrite) {
            commitStream();
            adaptedOutput.write(b);
        } else {
            if (buffer.size() + 1 > bufferSize) {
                flushBuffer(false);
                adaptedOutput.write(b);
            } else {
                buffer.write(b);
            }
        }
    }

    /**
     * Commit the output stream.
     *
     * @throws IOException when underlying stream returned from the callback method throws the io exception.
     */
    public void commit() throws IOException {
        flushBuffer(true);
        commitStream();
    }

    @Override
    public void close() throws IOException {
        if (isClosed) {
            return;
        }

        isClosed = true;

        if (streamProvider == null) {
            streamProvider = NULL_STREAM_PROVIDER;
        }
        commit();
        adaptedOutput.close();
    }

    /**
     * Check if the committing output stream has been closed already.
     *
     * @return {@code true} if the stream has been closed, {@code false} otherwise.
     */
    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public void flush() throws IOException {
        if (isCommitted()) {
            adaptedOutput.flush();
        }
    }

    private void flushBuffer(boolean endOfStream) throws IOException {
        if (!directWrite) {
            int currentSize;
            if (endOfStream) {
                currentSize = buffer == null ? 0 : buffer.size();
            } else {
                currentSize = -1;
            }

            commitStream(currentSize);
            if (buffer != null) {
                if (isVirtualThread && adaptedOutput != null) {
                    adaptedOutput.write(buffer.toByteArray());
                } else {
                    // Virtual thread in JDK 21 are blocked by synchronized writeTo
                    // but about 10% faster than ^ without virtual threads.
                    buffer.writeTo(adaptedOutput);
                }
            }
        }
    }

}

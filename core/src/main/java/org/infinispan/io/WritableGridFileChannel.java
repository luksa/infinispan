/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.infinispan.io;

import org.infinispan.Cache;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;

/**
 * @author Marko Luksa
 */
public class WritableGridFileChannel implements WritableByteChannel {

    private static final Log log = LogFactory.getLog(WritableGridFileChannel.class);

    private GridFile file;
    private String filePath;
    private Cache<String, byte[]> cache;
    private int chunkSize;

    private int position;
    private int localIndex;
    private byte[] currentBuffer;

    private boolean closed;

    WritableGridFileChannel(GridFile file, Cache<String, byte[]> cache, boolean append, int chunkSize) {
        this.file = file;
        this.cache = cache;
        this.chunkSize = chunkSize;
        this.filePath = file.getPath();
        if (append) {
            initForAppending();
        } else {
            this.currentBuffer = new byte[chunkSize];
            this.localIndex = 0;
            this.position = 0;
        }
    }

    private void initForAppending() {
        this.position = (int) file.length();

        if (lastChunkIsFull()) {
            this.currentBuffer = new byte[chunkSize];
            this.localIndex = 0;
        } else {
            this.currentBuffer = fetchLastChunk();
            this.localIndex = position % chunkSize;
        }
    }

    private byte[] fetchLastChunk() {
        String key = getChunkKey(getLastChunkNumber());
        byte[] val = cache.get(key);
        if (val == null) {
            throw new IllegalStateException("Last chunk is null");
        } else {
            byte chunk[] = new byte[chunkSize];
            System.arraycopy(val, 0, chunk, 0, val.length);
            return chunk;
        }
    }

    private int getLastChunkNumber() {
        return getChunkNumber((int) file.length()-1);
    }

    private boolean lastChunkIsFull() {
        return file.length() % chunkSize == 0;
    }

    public int write(ByteBuffer src) throws IOException {
        checkOpen();

        int bytesWritten = 0;
        while (src.remaining() > 0) {
            int bytesWrittenToChunk = writeToChunk(src);
            bytesWritten += bytesWrittenToChunk;
        }
        return bytesWritten;
    }

    private int writeToChunk(ByteBuffer src) throws IOException {
        int remainingInChunk = getBytesRemainingInChunk();
        if (remainingInChunk == 0) {
            flush();
            localIndex = 0;
            remainingInChunk = chunkSize;
        }

        int bytesToWrite = Math.min(remainingInChunk, src.remaining());
        src.get(currentBuffer, localIndex, bytesToWrite);
        localIndex += bytesToWrite;
        position += bytesToWrite;
        return bytesToWrite;
    }

    private int getBytesRemainingInChunk() {
        return currentBuffer.length - localIndex;
    }

    public void flush() throws IOException {
        storeChunkInCache();
        updateFileLength();
    }

    private void updateFileLength() {
        file.setLength(position);
    }

    private void storeChunkInCache() {
        String key = getCurrentChunkKey();
        byte[] val = createChunkValue();
        cache.put(key, val);
        if (log.isTraceEnabled())
            log.trace("put(): position=" + position + ", key=" + key + ": " + val.length + " bytes");
    }

    private String getCurrentChunkKey() {
        int chunkNumber = getChunkNumberOfPreviousByte();
        return getChunkKey(chunkNumber);
    }

    private int getChunkNumberOfPreviousByte() {
        return getChunkNumber(position - 1);
    }

    private int getChunkNumber(int position) {
        return position / chunkSize;
    }

    protected String getChunkKey(int chunkNumber) {
        return filePath + ".#" + chunkNumber;
    }

    private byte[] createChunkValue() {
        byte[] val = new byte[localIndex];
        System.arraycopy(currentBuffer, 0, val, 0, localIndex);
        return val;
    }

    public boolean isOpen() {
        return !closed;
    }

    public void close() throws IOException {
        flush();
        reset();
        closed = true;
    }

    protected void checkOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    public long size() throws IOException {
        return file.length();
    }

    private void reset() {
        position = localIndex = 0;
    }


}

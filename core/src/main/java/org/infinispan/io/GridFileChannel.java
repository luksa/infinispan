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
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;

/**
 * @author Marko Luksa
 */
public class GridFileChannel implements ByteChannel {

    private static final Log log = LogFactory.getLog(GridInputStream.class);

    private GridFile file;
    private String filePath;
    private Cache<String, byte[]> cache;
    private int chunkSize;

    private int position = 0;
    private int localIndex = 0;
    private byte[] currentBuffer;

    private boolean closed;

    GridFileChannel(GridFile file, Cache<String, byte[]> cache, int chunkSize) {
        this.file = file;
        this.cache = cache;
        this.chunkSize = chunkSize;
        this.filePath = file.getPath();
    }

    public int read(ByteBuffer dst) throws IOException {
        int bytesRead = 0;
        int len = Math.min(dst.remaining(), getTotalBytesRemaining());
        while (len > 0) {
            int bytesReadFromChunk = readFromChunk(dst, len);
            len -= bytesReadFromChunk;
            bytesRead += bytesReadFromChunk;
        }

        return bytesRead;
    }

    private int readFromChunk(ByteBuffer dst, int len) {
        int bytesRemaining = getBytesRemainingInChunk();
        if (bytesRemaining == 0) {
            fetchNextChunk();
            bytesRemaining = getBytesRemainingInChunk();
        }
        int bytesToRead = Math.min(len, bytesRemaining);
        dst.put(currentBuffer, localIndex, bytesToRead);

        position += bytesToRead;
        localIndex += bytesToRead;
        return bytesToRead;
    }

    private void fetchNextChunk() {
        int chunkNumber = getChunkNumber(position);
        currentBuffer = fetchChunk(chunkNumber);
        localIndex = 0;
    }

    private int getTotalBytesRemaining() {
        return (int)file.length() - position;
    }

    private byte[] fetchChunk(int chunkNumber) {
        if (chunkNumber <= getLastChunkNumber()) {
            String key = getChunkKey(chunkNumber);
            byte[] val = cache.get(key);
            if (log.isTraceEnabled())
                log.trace("fetching position=" + position + ", key=" + key + ": " + (val != null ? val.length + " bytes" : "null"));

            if (val == null) {
                return new byte[chunkSize];
            } else if (val.length < chunkSize) {
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(val, 0, chunk, 0, val.length);
                return chunk;
            } else {
                return val;
            }
        } else {
            return new byte[chunkSize];
        }
    }

    private int getLastChunkNumber() {
        return getChunkNumber((int) file.length() - 1);
    }


    public int write(ByteBuffer src) throws IOException {
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
            fetchNextChunk();
            remainingInChunk = chunkSize;
        }

        int bytesToWrite = Math.min(remainingInChunk, src.remaining());
        src.get(currentBuffer, localIndex, bytesToWrite);
        localIndex += bytesToWrite;
        position += bytesToWrite;
        return bytesToWrite;
    }

    public void flush() throws IOException {
        if (currentBuffer != null) {
            storeChunkInCache();
            updateFileLength();
        }
    }

    private void updateFileLength() {
        if (position > file.length()) {
            file.setLength(position);
        }
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

    public long position() throws IOException {
        checkOpen();
        return position;
    }

    public void position(long newPosition) throws IOException {
        if (newPosition < 0) {
            throw new IllegalArgumentException("newPosition may not be negative");
        }
        checkOpen();

        int newPos = (int) newPosition;
        if (getChunkNumberOfPreviousByte() == getChunkNumber(newPos)) {
            position = newPos;
        } else {
            flush();
            currentBuffer = fetchChunk(getChunkNumber(newPos));
        }
        localIndex = newPos % chunkSize;
    }

    protected void checkOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    public long size() throws IOException {
        return file.length();
    }

    public void truncate(long size) {
        file.setLength((int) size);
    }

    private int getChunkNumberOfPreviousByte() {
        return getChunkNumber(position - 1);
    }

    private int getChunkNumber(int position) {
        return position / chunkSize;
    }

    private void reset() {
        position = localIndex = 0;
    }

    private int getBytesRemainingInChunk() {
        return currentBuffer == null ? 0 : currentBuffer.length - localIndex;
    }


}

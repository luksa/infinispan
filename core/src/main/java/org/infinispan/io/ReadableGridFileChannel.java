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
import java.nio.channels.ReadableByteChannel;

/**
 * @author Marko Luksa
 */
public class ReadableGridFileChannel implements ReadableByteChannel {

    private static final Log log = LogFactory.getLog(GridInputStream.class);

    private GridFile file;
    private String filePath;
    private Cache<String, byte[]> cache;
    private int chunkSize;

    private int position = 0;
    private int localIndex = 0;
    private byte[] currentBuffer;

    private boolean closed;

    ReadableGridFileChannel(GridFile file, Cache<String, byte[]> cache) {
        this.file = file;
        this.cache = cache;
        this.chunkSize = file.getChunkSize();
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
        return (int) file.length() - position;
    }

    private byte[] fetchChunk(int chunkNumber) {
        String key = getChunkKey(chunkNumber);
        byte[] val = cache.get(key);
        if (log.isTraceEnabled())
            log.trace("fetching position=" + position + ", key=" + key + ": " + (val != null ? val.length + " bytes" : "null"));

        if (val == null) {
            return new byte[chunkSize];
        } else {
            return val;
        }
    }

    protected String getChunkKey(int chunkNumber) {
        return filePath + ".#" + chunkNumber;
    }

    public boolean isOpen() {
        return !closed;
    }

    public void close() throws IOException {
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
        int chunkNumberOfNewPosition = getChunkNumber(newPos);
        if (getChunkNumberOfPreviousByte() == chunkNumberOfNewPosition) {
            position = newPos;
        } else {
            currentBuffer = fetchChunk(chunkNumberOfNewPosition);
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

    private int getChunkNumberOfPreviousByte() {
        return getChunkNumber(position - 1);
    }

    private int getChunkNumber(int position) {
        return position < 0 ? -1 : (position / chunkSize);
    }

    private void reset() {
        position = localIndex = 0;
    }

    private int getBytesRemainingInChunk() {
        return currentBuffer == null ? 0 : currentBuffer.length - localIndex;
    }


}

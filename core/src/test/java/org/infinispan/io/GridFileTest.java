/*
 * Copyright 2011 Red Hat, Inc. and/or its affiliates.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

package org.infinispan.io;

import org.infinispan.Cache;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.test.SingleCacheManagerTest;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import static org.testng.Assert.*;

@Test(testName = "io.GridFileTest", groups = "functional")
public class GridFileTest extends SingleCacheManagerTest {

    public static final int CHUNK_SIZE = 10;

    private Cache<String,byte[]> dataCache;
    private Cache<String,GridFile.Metadata> metadataCache;
    private GridFilesystem fs;

    @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      return new DefaultCacheManager();
   }

    @BeforeMethod
    protected void setUp() throws Exception {
        dataCache = cacheManager.getCache("data");
        metadataCache = cacheManager.getCache("metadata");
        fs = new GridFilesystem(dataCache, metadataCache, CHUNK_SIZE);
    }

   public void testGridFS() throws IOException {
      File gridDir = fs.getFile("/test");
      assert gridDir.mkdirs();
      File gridFile = fs.getFile("/test/myfile.txt");
      assert gridFile.createNewFile();
   }

    public void testStreams() throws Exception {
        OutputStream out = fs.getOutput("testfile.txt");
        out.write("Hello world".getBytes());
        out.close();

        InputStream in = fs.getInput("testfile.txt");
        byte buf[] = new byte[100];
        int bytesRead = in.read(buf);
        in.close();

        String readString = new String(buf, 0, bytesRead);
        assert "Hello world".equals(readString);
    }

    public void testCreateEmptyFile() throws Exception {
        GridFileChannel channel = fs.getChannel("emptyFile.txt");
        channel.close();

        File file = fs.getFile("emptyFile.txt");
        assert file.length() == 0;
    }

    public void testSinglePartiallyFullChunk() throws Exception {
        File file = fs.getFile("singleChunkNotFull.txt");

        String text = "Hello";
        GridFileChannel channel = fs.getChannel(file);
        byte[] helloBytes = text.getBytes();
        int bytesWritten = channel.write(ByteBuffer.wrap(helloBytes));
        assert bytesWritten == 5;
        channel.close();

        assert file.length() == 5;

        byte[] chunk0 = dataCache.get(channel.getChunkKey(0));
        assertEquals(chunk0.length, 5);
        assertEquals(chunk0, "Hello".getBytes());

        assertEquals(getFileContents(file), "Hello");
    }

    private String getFileContents(File file) throws IOException {
        GridFileChannel channel2 = fs.getChannel(file);
        ByteBuffer buffer = ByteBuffer.allocate(100);
        channel2.read(buffer);
        channel2.close();
        return getStringFrom(buffer);
    }

    private String getStringFrom(ByteBuffer buffer) {
        buffer.flip();
        byte[] buf = new byte[buffer.remaining()];
        buffer.get(buf);
        return new String(buf);
    }

    public void testSingleFullChunk() throws Exception {
        GridFileChannel channel = fs.getChannel("singleChunkFull.txt");
        byte[] helloWorldBytes = "HelloWorld".getBytes();
        int bytesWritten = channel.write(ByteBuffer.wrap(helloWorldBytes));
        assert bytesWritten == 10;
        channel.close();

        File file = fs.getFile("singleChunkFull.txt");
        assert file.length() == 10;

        byte[] chunk0 = dataCache.get(channel.getChunkKey(0));
        assertEquals(chunk0.length, 10);
        assertEquals(chunk0, "HelloWorld".getBytes());
    }

    public void testTwoFullChunks() throws Exception {
        GridFileChannel channel = fs.getChannel("twoFullChunks.txt");
        byte[] helloWorldBytes = "HelloWorld1234567890".getBytes();
        int bytesWritten = channel.write(ByteBuffer.wrap(helloWorldBytes));
        assert bytesWritten == 20;
        channel.close();

        File file = fs.getFile("twoFullChunks.txt");
        assert file.length() == 20;

        byte[] chunk0 = dataCache.get(channel.getChunkKey(0));
        assertEquals(chunk0.length, 10);
        assertEquals(chunk0, "HelloWorld".getBytes());

        byte[] chunk1 = dataCache.get(channel.getChunkKey(1));
        assertEquals(chunk1.length, 10);
        assertEquals(chunk1, "1234567890".getBytes());
    }


//    public void testOverwrite() throws Exception {
//        File file = fs.getFile("overwrite.txt");
//        GridFileChannel channel = fs.getChannel(file);
//        channel.write(ByteBuffer.wrap("1234567890".getBytes()));
//        channel.close();
//
//        channel = fs.getChannel(file);
//        channel.write(ByteBuffer.wrap("Hello".getBytes()));
//        channel.close();
//
//        assertEquals(file.length(), 10);
//        assertEquals(getFileContents(file), "Hello67890");
//    }

    public void testAppend() throws Exception {
        File file = fs.getFile("append.txt");

        WritableGridFileChannel channel = fs.getWritableChannel(file.getPath());
        channel.write(ByteBuffer.wrap("Hello".getBytes()));
        channel.close();

        assertEquals(file.length(), "Hello".getBytes().length);
        assertEquals(getFileContents(file), "Hello");

        channel = fs.getWritableChannel(file.getPath(), true);
        channel.write(ByteBuffer.wrap("World".getBytes()));
        channel.close();

        assertEquals(file.length(), "HelloWorld".getBytes().length);
        assertEquals(getFileContents(file), "HelloWorld");
    }



}

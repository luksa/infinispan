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
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.testng.Assert.*;
import static org.testng.Assert.assertEquals;

@Test(testName = "io.GridFileTest", groups = "functional")
public class GridFileTest extends SingleCacheManagerTest {

   private Cache<String, byte[]> dataCache;
   private Cache<String, GridFile.Metadata> metadataCache;
   private GridFilesystem fs;

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      return new DefaultCacheManager();
   }

   @BeforeMethod
   protected void setUp() throws Exception {
      dataCache = cacheManager.getCache("data");
      metadataCache = cacheManager.getCache("metadata");
      fs = new GridFilesystem(dataCache, metadataCache);
   }

   public void testGridFS() throws IOException {
      File gridDir = fs.getFile("/test");
      assert gridDir.mkdirs();
      File gridFile = fs.getFile("/test/myfile.txt");
      assert gridFile.createNewFile();
   }

   public void testNonExistentFileIsNeitherFileNorDirectory() throws IOException {
      File file = fs.getFile("nonExistentFile.txt");
      assertFalse(file.exists());
      assertFalse(file.isFile());
      assertFalse(file.isDirectory());
   }

   public void testGetParent() throws IOException {
      File file = fs.getFile("file.txt");
      assertEquals(file.getParent(), null);

      file = fs.getFile("/parentdir/file.txt");
      assertEquals(file.getParent(), "/parentdir");

      file = fs.getFile("/parentdir/subdir/file.txt");
      assertEquals(file.getParent(), "/parentdir/subdir");

   }

   public void testGetParentFile() throws IOException {
      File file = fs.getFile("file.txt");
      assertNull(file.getParentFile());

      file = fs.getFile("/parentdir/file.txt");
      File parentDir = file.getParentFile();
      assertTrue(parentDir instanceof GridFile);
      assertEquals(parentDir.getPath(), "/parentdir");
   }

   @Test(expectedExceptions = FileNotFoundException.class)
   public void testWritingToDirectoryThrowsException1() throws IOException {
      GridFile dir = (GridFile) createDir();
      fs.getOutput(dir);  // should throw exception
   }

   @Test(expectedExceptions = FileNotFoundException.class)
   public void testWritingToDirectoryThrowsException2() throws IOException {
      File dir = createDir();
      fs.getOutput(dir.getPath());  // should throw exception
   }

   @Test(expectedExceptions = FileNotFoundException.class)
   public void testReadingFromDirectoryThrowsException1() throws IOException {
      File dir = createDir();
      fs.getInput(dir);  // should throw exception
   }

   @Test(expectedExceptions = FileNotFoundException.class)
   public void testReadingFromDirectoryThrowsException2() throws IOException {
      File dir = createDir();
      fs.getInput(dir.getPath());  // should throw exception
   }

   private File createDir() {
      File dir = fs.getFile("mydir");
      boolean created = dir.mkdir();
      assert created;
      return dir;
   }

   public void testWriteOverMultipleChunksWithNonDefaultChunkSize() throws Exception {
      OutputStream out = fs.getOutput("multipleChunks.txt", false, 10);  // chunkSize = 10
      try {
         out.write("This text spans multiple chunks, because each chunk is only 10 bytes long.".getBytes());
      } finally {
         out.close();
      }

      String text = getContents("multipleChunks.txt");
      assertEquals(text, "This text spans multiple chunks, because each chunk is only 10 bytes long.");
   }

   public void testWriteOverMultipleChunksWithNonDefaultChunkSizeAfterFileIsExplicitlyCreated() throws Exception {
      GridFile file = (GridFile) fs.getFile("multipleChunks.txt", 20);  // chunkSize = 20
      file.createNewFile();
      
      OutputStream out = fs.getOutput(file.getPath(), false, 10); // chunkSize = 10 (but it is ignored, because the
                                                                  // file was already created with chunkSize = 20)
      try {
         out.write("This text spans multiple chunks, because each chunk is only 20 bytes long.".getBytes());
      } finally {
         out.close();
      }

      String text = getContents("multipleChunks.txt");
      assertEquals(text, "This text spans multiple chunks, because each chunk is only 20 bytes long.");
   }

   public void testAppend() throws Exception {
      appendToFile("append.txt", "Hello");
      appendToFile("append.txt", "World");
      assertEquals(getContents("append.txt"), "HelloWorld");
   }

   public void testAppendWithDifferentChunkSize() throws Exception {
      appendToFile("append.txt", "Hello", 2);   // chunkSize = 2
      appendToFile("append.txt", "World", 5);        // chunkSize = 5
      assertEquals(getContents("append.txt"), "HelloWorld");
   }

   public void testAppendToEmptyFile() throws Exception {
      appendToFile("empty.txt", "Hello");
      assertEquals(getContents("empty.txt"), "Hello");
   }

   private void appendToFile(String filePath, String text) throws IOException {
      appendToFile(filePath, text, null);
   }

   private void appendToFile(String filePath, String text, Integer chunkSize) throws IOException {
      OutputStream out = chunkSize == null
         ? fs.getOutput(filePath, true)
         : fs.getOutput(filePath, true, chunkSize);
      try {
         out.write(text.getBytes());
      } finally {
         out.close();
      }
   }

   private String getContents(String filePath) throws IOException {
      InputStream in = fs.getInput(filePath);
      try {
         byte[] buf = new byte[1000];
         int bytesRead = in.read(buf);
         return new String(buf, 0, bytesRead);
      } finally {
         in.close();
      }
   }

}

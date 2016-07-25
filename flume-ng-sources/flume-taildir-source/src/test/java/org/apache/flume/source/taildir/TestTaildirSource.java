/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.flume.source.taildir;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.apache.flume.Channel;
import org.apache.flume.ChannelSelector;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.Transaction;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.channel.MemoryChannel;
import org.apache.flume.channel.ReplicatingChannelSelector;
import org.apache.flume.conf.Configurables;
import org.apache.flume.lifecycle.LifecycleController;
import org.apache.flume.lifecycle.LifecycleState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.apache.flume.source.taildir.TaildirSourceConfigurationConstants.FILE_GROUPS;
import static org.apache.flume.source.taildir.TaildirSourceConfigurationConstants.FILE_GROUPS_PREFIX;
import static org.apache.flume.source.taildir.TaildirSourceConfigurationConstants.HEADERS_PREFIX;
import static org.apache.flume.source.taildir.TaildirSourceConfigurationConstants.POSITION_FILE;
import static org.apache.flume.source.taildir.TaildirSourceConfigurationConstants.FILENAME_HEADER;
import static org.apache.flume.source.taildir.TaildirSourceConfigurationConstants.FILENAME_HEADER_KEY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestTaildirSource {
  static TaildirSource source;
  static MemoryChannel channel;
  private File tmpDir;
  private String posFilePath;

  @Before
  public void setUp() {
    source = new TaildirSource();
    channel = new MemoryChannel();

    Configurables.configure(channel, new Context());

    List<Channel> channels = new ArrayList<Channel>();
    channels.add(channel);

    ChannelSelector rcs = new ReplicatingChannelSelector();
    rcs.setChannels(channels);

    source.setChannelProcessor(new ChannelProcessor(rcs));
    tmpDir = Files.createTempDir();
    posFilePath = tmpDir.getAbsolutePath() + "/taildir_position_test.json";
  }

  @After
  public void tearDown() {
    deleteFiles(tmpDir);
    tmpDir.delete();
  }

  /**
   * Helper method to recursively clean up testing directory
   * @param directory the directory to clean up
   */
  private void deleteFiles(File directory) {
    for (File f : directory.listFiles()) {
      if (f.isDirectory()) {
        deleteFiles(f);
        f.delete();
      } else {
        f.delete();
      }
    }
  }

  @Test
  public void testRegexFileNameFilteringEndToEnd() throws IOException {
    File f1 = new File(tmpDir, "a.log");
    File f2 = new File(tmpDir, "a.log.1");
    File f3 = new File(tmpDir, "b.log");
    File f4 = new File(tmpDir, "c.log.yyyy-MM-01");
    File f5 = new File(tmpDir, "c.log.yyyy-MM-02");
    Files.write("a.log\n", f1, Charsets.UTF_8);
    Files.write("a.log.1\n", f2, Charsets.UTF_8);
    Files.write("b.log\n", f3, Charsets.UTF_8);
    Files.write("c.log.yyyy-MM-01\n", f4, Charsets.UTF_8);
    Files.write("c.log.yyyy-MM-02\n", f5, Charsets.UTF_8);

    Context context = new Context();
    context.put(POSITION_FILE, posFilePath);
    context.put(FILE_GROUPS, "ab c");
    // Tail a.log and b.log
    context.put(FILE_GROUPS_PREFIX + "ab", tmpDir.getAbsolutePath() + "/[ab].log");
    // Tail files that starts with c.log
    context.put(FILE_GROUPS_PREFIX + "c", tmpDir.getAbsolutePath() + "/c.log.*");

    Configurables.configure(source, context);
    source.start();
    source.process();
    Transaction txn = channel.getTransaction();
    txn.begin();
    List<String> out = Lists.newArrayList();
    for (int i = 0; i < 5; i++) {
      Event e = channel.take();
      if (e != null) {
        out.add(TestTaildirEventReader.bodyAsString(e));
      }
    }
    txn.commit();
    txn.close();

    assertEquals(4, out.size());
    // Make sure we got every file
    assertTrue(out.contains("a.log"));
    assertFalse(out.contains("a.log.1"));
    assertTrue(out.contains("b.log"));
    assertTrue(out.contains("c.log.yyyy-MM-01"));
    assertTrue(out.contains("c.log.yyyy-MM-02"));
  }

  @Test
  public void testWildcardsDirFiltering() throws IOException {
    File f1 = new File(tmpDir.getAbsolutePath()+"/fg1/dir1/subdir/file1.txt");
    Files.createParentDirs(f1);
    Files.write("file1\n", f1, Charsets.UTF_8);
    File f2 = new File(tmpDir.getAbsolutePath()+"/fg1/dir2/subdir/file2.txt");
    Files.createParentDirs(f2);
    Files.write("file2\n", f2, Charsets.UTF_8);
    File f3 = new File(tmpDir.getAbsolutePath()+"/fg1/dir3/file3.txt");
    Files.createParentDirs(f3);
    Files.write("file3\n", f3, Charsets.UTF_8);

    File f4 = new File(tmpDir.getAbsolutePath()+"/fg2/dir4/file4.txt");
    Files.createParentDirs(f4);
    Files.write("file4\n", f4, Charsets.UTF_8);
    File f5 = new File(tmpDir.getAbsolutePath()+"/fg2/dir5/file5.txt");
    Files.createParentDirs(f5);
    Files.write("file5\n", f5, Charsets.UTF_8);
    File f6 = new File(tmpDir.getAbsolutePath()+"/fg2/dir66/file66.txt");
    Files.createParentDirs(f6);
    Files.write("file66\n", f6, Charsets.UTF_8);

    File f7 = new File(tmpDir.getAbsolutePath()+"/fg3/dir7/file7.txt");
    Files.createParentDirs(f7);
    Files.write("file7\n", f7, Charsets.UTF_8);
    File f8 = new File(tmpDir.getAbsolutePath()+"/fg3/dir8/file8.txt");
    Files.createParentDirs(f8);
    Files.write("file8\n", f8, Charsets.UTF_8);
    File f9 = new File(tmpDir.getAbsolutePath()+"/fg3/dir9/file9.txt");
    Files.createParentDirs(f9);
    Files.write("file9\n", f9, Charsets.UTF_8);

    File f10 = new File(tmpDir.getAbsolutePath()+"/fg4/dir10/file10.txt");
    Files.createParentDirs(f10);
    Files.write("file10\n", f10, Charsets.UTF_8);
    File f11 = new File(tmpDir.getAbsolutePath()+"/fg4/dir11/file11.txt");
    Files.createParentDirs(f11);
    Files.write("file11\n", f11, Charsets.UTF_8);
    File f12 = new File(tmpDir.getAbsolutePath()+"/fg4/dir12/file12.txt");
    Files.createParentDirs(f12);
    Files.write("file12\n", f12, Charsets.UTF_8);

    File f13 = new File(tmpDir.getAbsolutePath()+"/fg5/dir13/file13.txt");
    Files.createParentDirs(f13);
    Files.write("file13\n", f13, Charsets.UTF_8);
    File f14 = new File(tmpDir.getAbsolutePath()+"/fg5/dir14/file14.txt");
    Files.createParentDirs(f14);
    Files.write("file14\n", f14, Charsets.UTF_8);
    File f15 = new File(tmpDir.getAbsolutePath()+"/fg5/dir15/subdir15/file15.txt");
    Files.createParentDirs(f15);
    Files.write("file15\n", f15, Charsets.UTF_8);

    Context context = new Context();
    context.put(POSITION_FILE, posFilePath);
    context.put(FILE_GROUPS, "fg1 fg2 fg3 fg4 fg5");
    context.put(FILE_GROUPS_PREFIX + "fg1", tmpDir.getAbsolutePath() + "/fg1/*/subdir/file.*");
    context.put(FILE_GROUPS_PREFIX + "fg2", tmpDir.getAbsolutePath() + "/fg2/dir?/file.*");
    context.put(FILE_GROUPS_PREFIX + "fg3", tmpDir.getAbsolutePath() + "/fg3/dir[78]/file.*");
    context.put(FILE_GROUPS_PREFIX + "fg4", tmpDir.getAbsolutePath() + "/fg4/dir{10,12}/file.*");
    context.put(FILE_GROUPS_PREFIX + "fg5", tmpDir.getAbsolutePath() + "/fg5/**/file.*");

    Configurables.configure(source, context);
    source.start();
    source.process();
    Transaction txn = channel.getTransaction();
    txn.begin();
    List<String> out = Lists.newArrayList();
    for (int i = 0; i < 15; i++) {
      Event e = channel.take();
      if (e != null) {
        out.add(TestTaildirEventReader.bodyAsString(e));
      }
    }
    txn.commit();
    txn.close();

    assertEquals(11, out.size());
    assertTrue(out.contains("file1"));
    assertTrue(out.contains("file2"));
    assertFalse(out.contains("file3"));
    assertTrue(out.contains("file4"));
    assertTrue(out.contains("file5"));
    assertFalse(out.contains("file66"));
    assertTrue(out.contains("file7"));
    assertTrue(out.contains("file8"));
    assertFalse(out.contains("file9"));
    assertTrue(out.contains("file10"));
    assertFalse(out.contains("file11"));
    assertTrue(out.contains("file12"));
    assertTrue(out.contains("file13"));
    assertTrue(out.contains("file14"));
    assertTrue(out.contains("file15"));
  }

  @Test
  public void testHeaderMapping() throws IOException {
    File f1 = new File(tmpDir, "file1");
    File f2 = new File(tmpDir, "file2");
    File f3 = new File(tmpDir, "file3");
    Files.write("file1line1\nfile1line2\n", f1, Charsets.UTF_8);
    Files.write("file2line1\nfile2line2\n", f2, Charsets.UTF_8);
    Files.write("file3line1\nfile3line2\n", f3, Charsets.UTF_8);

    Context context = new Context();
    context.put(POSITION_FILE, posFilePath);
    context.put(FILE_GROUPS, "f1 f2 f3");
    context.put(FILE_GROUPS_PREFIX + "f1", tmpDir.getAbsolutePath() + "/file1$");
    context.put(FILE_GROUPS_PREFIX + "f2", tmpDir.getAbsolutePath() + "/file2$");
    context.put(FILE_GROUPS_PREFIX + "f3", tmpDir.getAbsolutePath() + "/file3$");
    context.put(HEADERS_PREFIX + "f1.headerKeyTest", "value1");
    context.put(HEADERS_PREFIX + "f2.headerKeyTest", "value2");
    context.put(HEADERS_PREFIX + "f2.headerKeyTest2", "value2-2");

    Configurables.configure(source, context);
    source.start();
    source.process();
    Transaction txn = channel.getTransaction();
    txn.begin();
    for (int i = 0; i < 6; i++) {
      Event e = channel.take();
      String body = new String(e.getBody(), Charsets.UTF_8);
      String headerValue = e.getHeaders().get("headerKeyTest");
      String headerValue2 = e.getHeaders().get("headerKeyTest2");
      if (body.startsWith("file1")) {
        assertEquals("value1", headerValue);
        assertNull(headerValue2);
      } else if (body.startsWith("file2")) {
        assertEquals("value2", headerValue);
        assertEquals("value2-2", headerValue2);
      } else if (body.startsWith("file3")) {
        // No header
        assertNull(headerValue);
        assertNull(headerValue2);
      }
    }
    txn.commit();
    txn.close();
  }

  @Test
  public void testLifecycle() throws IOException, InterruptedException {
    File f1 = new File(tmpDir, "file1");
    Files.write("file1line1\nfile1line2\n", f1, Charsets.UTF_8);

    Context context = new Context();
    context.put(POSITION_FILE, posFilePath);
    context.put(FILE_GROUPS, "f1");
    context.put(FILE_GROUPS_PREFIX + "f1", tmpDir.getAbsolutePath() + "/file1$");
    Configurables.configure(source, context);

    for (int i = 0; i < 3; i++) {
      source.start();
      source.process();
      assertTrue("Reached start or error", LifecycleController.waitForOneOf(
          source, LifecycleState.START_OR_ERROR));
      assertEquals("Server is started", LifecycleState.START,
          source.getLifecycleState());

      source.stop();
      assertTrue("Reached stop or error",
          LifecycleController.waitForOneOf(source, LifecycleState.STOP_OR_ERROR));
      assertEquals("Server is stopped", LifecycleState.STOP,
          source.getLifecycleState());
    }
  }

  @Test
  public void testFileConsumeOrder() throws IOException {
    System.out.println(tmpDir.toString());
    // 1) Create 1st file
    File f1 = new File(tmpDir, "file1");
    String line1 = "file1line1\n";
    String line2 = "file1line2\n";
    String line3 = "file1line3\n";
    Files.write(line1 + line2 + line3, f1, Charsets.UTF_8);
    try {
      Thread.sleep(1000); // wait before creating a new file
    } catch (InterruptedException e) {
    }

    // 1) Create 2nd file
    String line1b = "file2line1\n";
    String line2b = "file2line2\n";
    String line3b = "file2line3\n";
    File f2 = new File(tmpDir, "file2");
    Files.write(line1b + line2b + line3b, f2, Charsets.UTF_8);
    try {
      Thread.sleep(1000); // wait before creating next file
    } catch (InterruptedException e) {
    }

    // 3) Create 3rd file
    String line1c = "file3line1\n";
    String line2c = "file3line2\n";
    String line3c = "file3line3\n";
    File f3 = new File(tmpDir, "file3");
    Files.write(line1c + line2c + line3c, f3, Charsets.UTF_8);

    try {
      Thread.sleep(1000); // wait before creating a new file
    } catch (InterruptedException e) {
    }


    // 4) Create 4th file
    String line1d = "file4line1\n";
    String line2d = "file4line2\n";
    String line3d = "file4line3\n";
    File f4 = new File(tmpDir, "file4");
    Files.write(line1d + line2d + line3d, f4, Charsets.UTF_8);

    try {
      Thread.sleep(1000); // wait before creating a new file
    } catch (InterruptedException e) {
    }


    // 5) Now update the 3rd file so that its the latest file and gets consumed last
    f3.setLastModified(System.currentTimeMillis());

    // 4) Consume the files
    ArrayList<String> consumedOrder = Lists.newArrayList();
    Context context = new Context();
    context.put(POSITION_FILE, posFilePath);
    context.put(FILE_GROUPS, "g1");
    context.put(FILE_GROUPS_PREFIX + "g1", tmpDir.getAbsolutePath() + "/.*");

    Configurables.configure(source, context);
    source.start();
    source.process();
    Transaction txn = channel.getTransaction();
    txn.begin();
    for (int i = 0; i < 12; i++) {
      Event e = channel.take();
      String body = new String(e.getBody(), Charsets.UTF_8);
      consumedOrder.add(body);
    }
    txn.commit();
    txn.close();

    System.out.println(consumedOrder);

    // 6) Ensure consumption order is in order of last update time
    ArrayList<String> expected = Lists.newArrayList(line1, line2, line3,    // file1
                                                    line1b, line2b, line3b, // file2
                                                    line1d, line2d, line3d, // file4
                                                    line1c, line2c, line3c  // file3
                                                   );
    for (int i = 0; i != expected.size(); ++i) {
      expected.set(i, expected.get(i).trim());
    }
    assertArrayEquals("Files not consumed in expected order", expected.toArray(),
                      consumedOrder.toArray());
  }

  @Test
  public void testPutFilenameHeader() throws IOException {
    File f1 = new File(tmpDir, "file1");
    Files.write("f1\n", f1, Charsets.UTF_8);

    Context context = new Context();
    context.put(POSITION_FILE, posFilePath);
    context.put(FILE_GROUPS, "fg");
    context.put(FILE_GROUPS_PREFIX + "fg", tmpDir.getAbsolutePath() + "/file.*");
    context.put(FILENAME_HEADER, "true");
    context.put(FILENAME_HEADER_KEY, "path");

    Configurables.configure(source, context);
    source.start();
    source.process();
    Transaction txn = channel.getTransaction();
    txn.begin();
    Event e = channel.take();
    txn.commit();
    txn.close();

    assertNotNull(e.getHeaders().get("path"));
    assertEquals(f1.getAbsolutePath(),
            e.getHeaders().get("path"));
  }

  @Test
  public void testWildcardsDirFilteringCache() throws IOException, InterruptedException {
    //first iteration everything is working as expected
    File f1 = new File(tmpDir.getAbsolutePath() + "/fg1/dir1/file1.txt");
    Files.createParentDirs(f1);
    Files.write("file1\n", f1, Charsets.UTF_8);

    Context context = new Context();
    context.put(POSITION_FILE, posFilePath);
    context.put(FILE_GROUPS, "fg1");
    context.put(FILE_GROUPS_PREFIX + "fg1", tmpDir.getAbsolutePath() + "/fg1/*/file.*");

    Configurables.configure(source, context);
    source.start();
    source.process();
    Transaction txn = channel.getTransaction();
    txn.begin();
    List<String> out = Lists.newArrayList();
    for (int i = 0; i < 2; i++) {
      Event e = channel.take();
      if (e != null) {
        out.add(TestTaildirEventReader.bodyAsString(e));
      }
    }
    txn.commit();
    txn.close();

    // empty iterations simulating that time is passing by
    Thread.sleep(1000);
    source.process();
    Thread.sleep(1000);

    //file was created after a while it should be picked up as well
    File f2 = new File(tmpDir.getAbsolutePath() + "/fg1/dir1/file2.txt");
    Files.write("file2\n", f2, Charsets.UTF_8);

    source.process();
    txn = channel.getTransaction();
    txn.begin();
    for (int i = 0; i < 2; i++) {
      Event e = channel.take();
      if (e != null) {
        out.add(TestTaildirEventReader.bodyAsString(e));
      }
    }
    txn.commit();
    txn.close();

    assertEquals(2, out.size()); //fails as file2.txt won't appear in the channel ever
    assertTrue(out.contains("file1"));
    assertTrue(out.contains("file2"));
  }
}

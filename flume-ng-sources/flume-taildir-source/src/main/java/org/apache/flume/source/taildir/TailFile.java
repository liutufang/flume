/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flume.source.taildir;

import com.google.common.collect.Lists;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.interceptor.TimestampInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.flume.source.taildir.TaildirSourceConfigurationConstants.BYTE_OFFSET_HEADER_KEY;

public class TailFile {
  private static final Logger logger = LoggerFactory.getLogger(TailFile.class);

  private static final byte BYTE_NL = (byte) 10;
  private static final byte BYTE_CR = (byte) 13;

  private static final int BUFFER_SIZE = 8192;
  private static final int NEED_READING = -1;

  private RandomAccessFile raf;
  private final String path;
  private final long inode;
  private long pos;
  private long lastUpdated;
  private boolean needTail;
  private final Map<String, String> headers;
  private byte[] buffer;
  private byte[] oldBuffer;
  private int bufferPos;
  private long lineReadPos;
  private boolean multiline;
  private String multilinePattern;
  private String multilinePatternBelong;
  private boolean multilinePatternMatched;
  private long multilineEventTimeoutSecs;
  private int multilineMaxBytes;
  private int multilineMaxLines;
  private Event bufferEvent;

  public TailFile(File file, Map<String, String> headers, long inode, long pos)
      throws IOException {
    this.raf = new RandomAccessFile(file, "r");
    if (pos > 0) {
      raf.seek(pos);
      lineReadPos = pos;
    }
    this.path = file.getAbsolutePath();
    this.inode = inode;
    this.pos = pos;
    this.lastUpdated = 0L;
    this.needTail = true;
    this.headers = headers;
    this.oldBuffer = new byte[0];
    this.bufferPos = NEED_READING;
    this.bufferEvent = null;
  }

  public RandomAccessFile getRaf() {
    return raf;
  }

  public String getPath() {
    return path;
  }

  public long getInode() {
    return inode;
  }

  public long getPos() {
    return pos;
  }

  public long getLastUpdated() {
    return lastUpdated;
  }

  public boolean isNeedTail() {
    return needTail;
  }

  public boolean isNeedFlushBufferEvent() {
    if (bufferEvent != null) {
      long now = System.currentTimeMillis();
      long eventTime = Long.parseLong(
              bufferEvent.getHeaders().get(TimestampInterceptor.Constants.TIMESTAMP));
      if (multilineEventTimeoutSecs > 0 && (now - eventTime) > multilineEventTimeoutSecs * 1000) {
        return true;
      }
    }
    return false;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public long getLineReadPos() {
    return lineReadPos;
  }

  public void setPos(long pos) {
    this.pos = pos;
  }

  public void setLastUpdated(long lastUpdated) {
    this.lastUpdated = lastUpdated;
  }

  public void setNeedTail(boolean needTail) {
    this.needTail = needTail;
  }

  public void setLineReadPos(long lineReadPos) {
    this.lineReadPos = lineReadPos;
  }

  public void setMultiline(boolean multiline) {
    this.multiline = multiline;
  }

  public void setMultilinePattern(String multilinePattern) {
    this.multilinePattern = multilinePattern;
  }

  public void setMultilinePatternBelong(String multilinePatternBelong) {
    this.multilinePatternBelong = multilinePatternBelong;
  }

  public void setMultilinePatternMatched(boolean multilinePatternMatched) {
    this.multilinePatternMatched = multilinePatternMatched;
  }

  public void setMultilineEventTimeoutSecs(long multilineEventTimeoutSecs) {
    this.multilineEventTimeoutSecs = multilineEventTimeoutSecs;
  }

  public void setMultilineMaxBytes(int multilineMaxBytes) {
    this.multilineMaxBytes = multilineMaxBytes;
  }

  public void setMultilineMaxLines(int multilineMaxLines) {
    this.multilineMaxLines = multilineMaxLines;
  }

  public boolean updatePos(String path, long inode, long pos) throws IOException {
    if (this.inode == inode && this.path.equals(path)) {
      setPos(pos);
      updateFilePos(pos);
      logger.info("Updated position, file: " + path + ", inode: " + inode + ", pos: " + pos);
      return true;
    }
    return false;
  }
  public void updateFilePos(long pos) throws IOException {
    raf.seek(pos);
    lineReadPos = pos;
    bufferPos = NEED_READING;
    oldBuffer = new byte[0];
  }


  public List<Event> readEvents(int numEvents, boolean backoffWithoutNL,
      boolean addByteOffset) throws IOException {
    List<Event> events = Lists.newLinkedList();
    if (this.multiline) {
      boolean match = this.multilinePatternMatched;
      Pattern pattern = Pattern.compile(this.multilinePattern);
      while (events.size() < numEvents) {
        LineResult line = readLine();
        if (line == null) {
          break;
        }
        Event event = null;
        switch (this.multilinePatternBelong) {
          case "next":
            event = readMultilineEventNext(line, match, pattern);
            break;
          case "previous":
            event = readMultilineEventPre(line, match, pattern);
            break;
          default:
            break;
        }
        if (event != null) {
          events.add(event);
        }
        if (bufferEvent != null && (bufferEvent.getBody().length >= multilineMaxBytes
                || countNewLine(bufferEvent.getBody()) == multilineMaxLines)) {
          flushBufferEvent(events);
        }
      }
      if (isNeedFlushBufferEvent()) {
        flushBufferEvent(events);
      }
    } else {
      for (int i = 0; i < numEvents; i++) {
        Event event = readEvent(backoffWithoutNL, addByteOffset);
        if (event == null) {
          break;
        }
        events.add(event);
      }
    }
    return events;
  }

  private Event readMultilineEventPre(LineResult line, boolean match, Pattern pattern)
          throws IOException {
    Event event = null;
    Matcher m = pattern.matcher(new String(line.line));
    boolean find = m.find();
    match = (find && match) || (!find && !match);
    if (match) {
      /** If matched, merge it to the buffer event. */
      byte[] mergedBytes = mergeEvent(line);
      bufferEvent = EventBuilder.withBody(mergedBytes);
      long now = System.currentTimeMillis();
      bufferEvent.getHeaders().put(TimestampInterceptor.Constants.TIMESTAMP, Long.toString(now));
      bufferEvent.getHeaders().put("multiline", "true");
    } else {
      /**
       * If not matched, this line is not part of previous event when the buffer event is not null.
       * Then create a new event with buffer event's message and put the current line into the
       * cleared buffer event.
       */
      if (bufferEvent != null) {
        event = EventBuilder.withBody(bufferEvent.getBody());
      }
      bufferEvent = null;
      bufferEvent = EventBuilder.withBody(toOriginBytes(line));
      long now = System.currentTimeMillis();
      bufferEvent.getHeaders().put(TimestampInterceptor.Constants.TIMESTAMP, Long.toString(now));
    }

    return event;
  }

  private Event readMultilineEventNext(LineResult line, boolean match, Pattern pattern)
          throws IOException {
    Event event = null;
    Matcher m = pattern.matcher(new String(line.line));
    boolean find = m.find();
    match = (find && match) || (!find && !match);
    if (match) {
      /** If matched, merge it to the buffer event. */
      byte[] mergedBytes = mergeEvent(line);
      bufferEvent = EventBuilder.withBody(mergedBytes);
      long now = System.currentTimeMillis();
      bufferEvent.getHeaders().put(TimestampInterceptor.Constants.TIMESTAMP, Long.toString(now));
      bufferEvent.getHeaders().put("multiline", "true");
    } else {
      /**
       * If not matched, this line is not part of next event. Then merge the current line into the
       * buffer event and create a new event with the merged message.
       */
      byte[] mergedBytes = mergeEvent(line);
      event = EventBuilder.withBody(mergedBytes);
      bufferEvent = null;
    }

    return event;
  }

  private byte[] mergeEvent(LineResult line) {
    byte[] mergedBytes;
    byte[] lineBytes = toOriginBytes(line);
    if (bufferEvent != null) {
      mergedBytes = concatByteArrays(bufferEvent.getBody(), 0, bufferEvent.getBody().length,
              lineBytes, 0, lineBytes.length);
    } else {
      mergedBytes = lineBytes;
    }
    return mergedBytes;
  }

  private void flushBufferEvent(List<Event> events) {
    Event event = EventBuilder.withBody(bufferEvent.getBody());
    events.add(event);
    bufferEvent = null;
  }

  private byte[] toOriginBytes(LineResult line) {
    byte[] newByte = null;
    if (line.lineSepInclude) {
      newByte = new byte[line.line.length + 1];
      System.arraycopy(line.line, 0, newByte, 0, line.line.length);
      newByte[line.line.length] = BYTE_NL;
      return newByte;
    }
    return line.line;
  }

  private int countNewLine(byte[] bytes) {
    int count = 0;
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == BYTE_NL) {
        count++;
      }
    }
    return count;
  }

  private Event readEvent(boolean backoffWithoutNL, boolean addByteOffset) throws IOException {
    Long posTmp = getLineReadPos();
    LineResult line = readLine();
    if (line == null) {
      return null;
    }
    if (backoffWithoutNL && !line.lineSepInclude) {
      logger.info("Backing off in file without newline: "
          + path + ", inode: " + inode + ", pos: " + raf.getFilePointer());
      updateFilePos(posTmp);
      return null;
    }
    Event event = EventBuilder.withBody(line.line);
    if (addByteOffset == true) {
      event.getHeaders().put(BYTE_OFFSET_HEADER_KEY, posTmp.toString());
    }
    return event;
  }

  private void readFile() throws IOException {
    int maxBytes = BUFFER_SIZE;
    if (multilineMaxBytes < BUFFER_SIZE) {
      maxBytes = multilineMaxBytes;
    }
    if ((raf.length() - raf.getFilePointer()) < maxBytes) {
      buffer = new byte[(int) (raf.length() - raf.getFilePointer())];
    } else {
      buffer = new byte[maxBytes];
    }
    raf.read(buffer, 0, buffer.length);
    bufferPos = 0;
  }

  private byte[] concatByteArrays(byte[] a, int startIdxA, int lenA,
                                  byte[] b, int startIdxB, int lenB) {
    byte[] c = new byte[lenA + lenB];
    System.arraycopy(a, startIdxA, c, 0, lenA);
    System.arraycopy(b, startIdxB, c, lenA, lenB);
    return c;
  }

  public LineResult readLine() throws IOException {
    LineResult lineResult = null;
    while (true) {
      if (bufferPos == NEED_READING) {
        if (raf.getFilePointer() < raf.length()) {
          readFile();
        } else {
          if (oldBuffer.length > 0) {
            lineResult = new LineResult(false, oldBuffer);
            oldBuffer = new byte[0];
            setLineReadPos(lineReadPos + lineResult.line.length);
          }
          break;
        }
      }
      for (int i = bufferPos; i < buffer.length; i++) {
        if (buffer[i] == BYTE_NL) {
          int oldLen = oldBuffer.length;
          // Don't copy last byte(NEW_LINE)
          int lineLen = i - bufferPos;
          // For windows, check for CR
          if (i > 0 && buffer[i - 1] == BYTE_CR) {
            lineLen -= 1;
          } else if (oldBuffer.length > 0 && oldBuffer[oldBuffer.length - 1] == BYTE_CR) {
            oldLen -= 1;
          }
          lineResult = new LineResult(true,
              concatByteArrays(oldBuffer, 0, oldLen, buffer, bufferPos, lineLen));
          setLineReadPos(lineReadPos + (oldBuffer.length + (i - bufferPos + 1)));
          oldBuffer = new byte[0];
          if (i + 1 < buffer.length) {
            bufferPos = i + 1;
          } else {
            bufferPos = NEED_READING;
          }
          break;
        }
      }
      if (lineResult != null) {
        break;
      }
      // NEW_LINE not showed up at the end of the buffer
      oldBuffer = concatByteArrays(oldBuffer, 0, oldBuffer.length,
                                   buffer, bufferPos, buffer.length - bufferPos);
      if (oldBuffer.length >= multilineMaxBytes) {
        lineResult = new LineResult(false, oldBuffer);
        setLineReadPos(lineReadPos + oldBuffer.length);
        oldBuffer = new byte[0];
        bufferPos = NEED_READING;
        break;
      }
      bufferPos = NEED_READING;
    }
    return lineResult;
  }

  public void close() {
    try {
      raf.close();
      raf = null;
      long now = System.currentTimeMillis();
      setLastUpdated(now);
    } catch (IOException e) {
      logger.error("Failed closing file: " + path + ", inode: " + inode, e);
    }
  }

  private class LineResult {
    final boolean lineSepInclude;
    final byte[] line;

    public LineResult(boolean lineSepInclude, byte[] line) {
      super();
      this.lineSepInclude = lineSepInclude;
      this.line = line;
    }
  }
}

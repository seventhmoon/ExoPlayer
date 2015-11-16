/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.extractor.ts;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.extractor.TrackOutput;
import com.google.android.exoplayer.util.MimeTypes;
import com.google.android.exoplayer.util.NalUnitUtil;
import com.google.android.exoplayer.util.ParsableByteArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses a continuous H262 byte stream and extracts individual frames.
 */
/* package */ final class H262Reader extends ElementaryStreamReader {

  private static final int START_PICTURE = 0x00;
  private static final int START_SEQUENCE_PARAMETER = 0xB3;
  private static final int START_EXT = 0xB5;
  private static final int START_GOP = 0xB8;

  // State that should not be reset on seek.
  private boolean hasOutputFormat;

  // State that should be reset on seek.
  private final boolean[] prefixFlags;
  private final CsdBuffer csdBuffer;
  private boolean foundFirstFrameInGop;
  private long totalBytesWritten;

  // Per sample state that gets reset at the start of each frame.
  private boolean isKeyframe;
  private long framePosition;
  private long frameTimeUs;

  public H262Reader(TrackOutput output) {
    super(output);
    prefixFlags = new boolean[4];
    csdBuffer = new CsdBuffer(128);
  }

  @Override
  public void seek() {
    NalUnitUtil.clearPrefixFlags(prefixFlags);
    csdBuffer.reset();
    foundFirstFrameInGop = false;
    totalBytesWritten = 0;
  }

  @Override
  public void consume(ParsableByteArray data, long pesTimeUs, boolean startOfPacket) {
    while (data.bytesLeft() > 0) {
      int position = data.getPosition();
      int limit = data.limit();
      byte[] dataArray = data.data;

      // Append the data to the buffer.
      totalBytesWritten += data.bytesLeft();
      output.sampleData(data, data.bytesLeft());

      boolean firstStartCode = true;
      while (true) {
        int nextStartCodeSearchPosition = position + (firstStartCode ? 0 : 3);
        int nextStartCodePosition = NalUnitUtil.findNalUnit(dataArray, nextStartCodeSearchPosition,
            limit, prefixFlags);

        if (nextStartCodePosition == limit) {
          // We've scanned to the end of the data being consumed without finding another start code.
          if (!hasOutputFormat) {
            csdBuffer.onData(dataArray, position, limit);
          }
          return;
        }

        // We've found a start code with the following value.
        int startCodeValue = data.data[nextStartCodePosition + 3] & 0xFF;

        if (!hasOutputFormat) {
          // This is the number of bytes from the current position to the start of the next start
          // code. It may be negative if the start code started in previously consumed data.
          int lengthToStartCode = nextStartCodePosition - position;
          if (lengthToStartCode > 0) {
            csdBuffer.onData(dataArray, position, nextStartCodePosition);
          }
          // This is the number of bytes belonging to the next start code that have already been
          // passed to csdDataTargetBuffer.
          int bytesAlreadyPassed = lengthToStartCode < 0 ? -lengthToStartCode : 0;
          if (csdBuffer.onStartCode(startCodeValue, bytesAlreadyPassed)) {
            // The csd data is complete, so we can parse and output the media format.
            parseMediaFormat(csdBuffer);
          }
        }

        if (hasOutputFormat && (startCodeValue == START_GOP || startCodeValue == START_PICTURE)) {
          int bytesWrittenPastFrame = limit - nextStartCodePosition;
          if (foundFirstFrameInGop) {
            int flags = isKeyframe ? C.SAMPLE_FLAG_SYNC : 0;
            int size = (int) (totalBytesWritten - framePosition) - bytesWrittenPastFrame;
            output.sampleMetadata(frameTimeUs, flags, size, bytesWrittenPastFrame, null);
            isKeyframe = false;
          }
          if (startCodeValue == START_GOP) {
            foundFirstFrameInGop = false;
            isKeyframe = true;
          } else /* startCode == START_PICTURE */ {
            foundFirstFrameInGop = true;
            frameTimeUs = pesTimeUs;
            framePosition = totalBytesWritten - bytesWrittenPastFrame;
          }
        }

        position = nextStartCodePosition;
        firstStartCode = false;
      }
    }
  }

  @Override
  public void packetFinished() {
    // Do nothing.
  }

  private void parseMediaFormat(CsdBuffer csdBuffer) {
    byte[] csdData = Arrays.copyOf(csdBuffer.csdData, csdBuffer.csdLength);
    List<byte[]> initializationData = new ArrayList<>();
    initializationData.add(csdData);

    int width;
    int height;
    int firstByte = csdData[4] & 0xFF;
    int secondByte = csdData[5] & 0xFF;
    int thirdByte = csdData[6] & 0xFF;
    width = (firstByte << 4) | (secondByte >> 4);
    height = (secondByte & 0x0F) << 8 | thirdByte;

    float videoWidthHeightRatio = (float) width / height;
    float pixelWidthHeightRatio = 1f;
    int aspectRatioCode = (csdData[7] & 0xF0) >> 4;
    switch(aspectRatioCode) {
      case 2:
        pixelWidthHeightRatio = (3f / 4) * videoWidthHeightRatio;
        break;
      case 3:
        pixelWidthHeightRatio = (9f / 16) * videoWidthHeightRatio;
        break;
      case 4:
        pixelWidthHeightRatio = (1f / 1.21f) * videoWidthHeightRatio;
        break;
      default:
        // Do nothing.
        break;
    }

    // Construct and output the format.
    output.format(MediaFormat.createVideoFormat(null, MimeTypes.VIDEO_MPEG2, MediaFormat.NO_VALUE,
        MediaFormat.NO_VALUE, C.UNKNOWN_TIME_US, width, height, initializationData,
        MediaFormat.NO_VALUE, pixelWidthHeightRatio));
    hasOutputFormat = true;
  }

  private static final class CsdBuffer {

    private boolean isFilling;
    private boolean seenExtStartCode;

    public int csdLength;
    public byte[] csdData;

    public CsdBuffer(int initialCapacity) {
      csdData = new byte[initialCapacity];
    }

    /**
     * Resets the buffer, clearing any data that it holds.
     */
    public void reset() {
      isFilling = false;
      seenExtStartCode = false;
      csdLength = 0;
    }

    /**
     * Invoked when a start code is encountered in the stream.
     *
     * @param startCodeValue The start code value.
     * @param bytesAlreadyPassed The number of bytes of the start code that have already been
     *     passed to {@link #onData(byte[], int, int)}, or 0.
     * @return True if the csd data is now complete. False otherwise. If true is returned, neither
     *     this method or {@link #onData(byte[], int, int)} should be called again without an
     *     interleaving call to {@link #reset()}.
     */
    public boolean onStartCode(int startCodeValue, int bytesAlreadyPassed) {
      if (isFilling) {
        if (!seenExtStartCode && startCodeValue == START_EXT) {
          seenExtStartCode = true;
        } else {
          csdLength -= bytesAlreadyPassed;
          isFilling = false;
          return true;
        }
      } else if (startCodeValue == START_SEQUENCE_PARAMETER) {
        isFilling = true;
      }
      return false;
    }

    /**
     * Invoked to pass stream data.
     *
     * @param data Holds the data being passed.
     * @param offset The offset of the data in {@code data}.
     * @param limit The limit (exclusive) of the data in {@code data}.
     */
    public void onData(byte[] data, int offset, int limit) {
      if (!isFilling) {
        return;
      }
      int readLength = limit - offset;
      if (csdData.length < csdLength + readLength) {
        csdData = Arrays.copyOf(csdData, (csdLength + readLength) * 2);
      }
      System.arraycopy(data, offset, csdData, csdLength, readLength);
      csdLength += readLength;
    }

  }

}

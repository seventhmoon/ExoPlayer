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

import java.util.Collections;

/**
 * Parses a continuous H262 byte stream and extracts individual frames.
 */
/* package */ final class H262Reader extends ElementaryStreamReader {

  private static final int START_CODE_SLICE_MIN = 0x01;
  private static final int START_CODE_SLICE_MAX = 0xAF;
  private static final int START_CODE_SEQUENCE_PARAMETER = 0xB3;
  private static final int START_CODE_GOP_START = 0xB8;

  // State that should not be reset on seek.
  private boolean hasOutputFormat;

  // State that should be reset on seek.
  private final boolean[] prefixFlags;
  private final NalUnitTargetBuffer sequenceParameterTarget;
  private boolean foundFirstFrame;
  private long totalBytesWritten;

  // Per sample state that gets reset at the start of each frame.
  private boolean isKeyframe;
  private long framePosition;
  private long frameTimeUs;

  public H262Reader(TrackOutput output) {
    super(output);
    prefixFlags = new boolean[4];
    sequenceParameterTarget = new NalUnitTargetBuffer(START_CODE_SEQUENCE_PARAMETER, 128);
  }

  @Override
  public void seek() {
    NalUnitUtil.clearPrefixFlags(prefixFlags);
    sequenceParameterTarget.reset();
    foundFirstFrame = false;
    totalBytesWritten = 0;
  }

  @Override
  public void consume(ParsableByteArray data, long pesTimeUs, boolean startOfPacket) {
    while (data.bytesLeft() > 0) {
      int offset = data.getPosition();
      int limit = data.limit();
      byte[] dataArray = data.data;

      // Append the data to the buffer.
      totalBytesWritten += data.bytesLeft();
      output.sampleData(data, data.bytesLeft());

      while (offset < limit) {
        int nextFramePosition = NalUnitUtil.findNalUnit(dataArray, offset, limit, prefixFlags);
        if (nextFramePosition < limit) {
          int startCodeType = data.data[nextFramePosition + 3] & 0xFF;
          if (START_CODE_SLICE_MIN <= startCodeType && startCodeType <= START_CODE_SLICE_MAX) {
            // Don't split individual slices.
            offset = nextFramePosition + 3;
            continue;
          }

          if (!hasOutputFormat) {
            // This is the length to the start of the frame. It may be negative if the frame
            // actually started in previously consumed data.
            int lengthToFrame = nextFramePosition - offset;
            if (lengthToFrame > 0) {
              sequenceParameterTarget.appendToNalUnit(dataArray, offset, nextFramePosition);
            }
            // If the length to the start of the frame is negative then we wrote too many bytes to
            // target. Discard the excess bytes when notifying that the frame has ended.
            sequenceParameterTarget.endNalUnit(lengthToFrame < 0 ? -lengthToFrame : 0);
            if (sequenceParameterTarget.isCompleted()) {
              parseMediaFormat(sequenceParameterTarget);
            }
            sequenceParameterTarget.startNalUnit(startCodeType);
          }

          int bytesWrittenPastFrame = limit - nextFramePosition;
          if (foundFirstFrame) {
            int flags = isKeyframe ? C.SAMPLE_FLAG_SYNC : 0;
            int size = (int) (totalBytesWritten - framePosition) - bytesWrittenPastFrame;
            output.sampleMetadata(frameTimeUs, flags, size, bytesWrittenPastFrame, null);
          }
          foundFirstFrame = true;
          framePosition = totalBytesWritten - bytesWrittenPastFrame;
          frameTimeUs = pesTimeUs;
          isKeyframe = startCodeType == START_CODE_GOP_START;

          offset = nextFramePosition + 3;
        } else {
          offset = limit;
        }
      }
    }
  }

  @Override
  public void packetFinished() {
    // Do nothing.
  }

  private void parseMediaFormat(NalUnitTargetBuffer sequenceTarget) {
    byte[] data = sequenceTarget.nalData;

    int width;
    int height;
    int firstByte = data[4] & 0xFF;
    int secondByte = data[5] & 0xFF;
    int thirdByte = data[6] & 0xFF;
    width = (firstByte << 4) | (secondByte >> 4);
    height = (secondByte & 0x0F) << 8 | thirdByte;

    float videoWidthHeightRatio = (float) width / height;
    float pixelWidthHeightRatio = 1f;
    int aspectRatioCode = (data[7] & 0xF0) >> 4;
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
        MediaFormat.NO_VALUE, C.UNKNOWN_TIME_US, width, height, Collections.<byte[]>emptyList(),
        MediaFormat.NO_VALUE, pixelWidthHeightRatio));
    hasOutputFormat = true;
  }

}

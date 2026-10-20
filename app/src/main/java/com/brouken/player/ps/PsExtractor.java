/*
 * Copyright (C) 2016 The Android Open Source Project
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
 *
 * Modified for Syntex (from Media3 1.11.0, androidx.media3.extractor.ts.PsExtractor): MPEG-1 program
 * streams — the 12-byte MPEG-1 pack header (sniff, skipping, SCR in PsDurationReader and
 * PsBinarySearchSeeker), the MPEG-1 PES header (stuffing, STD buffer, PTS/DTS without the flags byte),
 * and the MPEG-1 pixel aspect table for video (H262Reader applies the MPEG-2 one); a packet without a
 * PTS is untimed rather than timed at zero. Replaces the Media3 extractor through replacing().
 */
package com.brouken.player.ps;

import static com.google.common.base.Preconditions.checkNotNull;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import android.net.Uri;
import androidx.media3.common.Format;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorsFactory;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.Ac3Reader;
import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.H262Reader;
import androidx.media3.extractor.ts.MpegAudioReader;
import androidx.media3.extractor.ts.TsPayloadReader;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Extracts data from the MPEG-2 PS container format. */
@UnstableApi
public final class PsExtractor implements Extractor {

  /** Factory for {@link PsExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new PsExtractor()};

  /* package */ static final int PACK_START_CODE = 0x000001BA;
  /* package */ static final int SYSTEM_HEADER_START_CODE = 0x000001BB;
  /* package */ static final int PACKET_START_CODE_PREFIX = 0x000001;
  /* package */ static final int MPEG_PROGRAM_END_CODE = 0x000001B9;
  private static final int MAX_STREAM_ID_PLUS_ONE = 0x100;

  // Max search length for first audio and video track in input data.
  private static final long MAX_SEARCH_LENGTH = 1024 * 1024;
  // Max search length for additional audio and video tracks in input data after at least one audio
  // and video track has been found.
  private static final long MAX_SEARCH_LENGTH_AFTER_AUDIO_AND_VIDEO_FOUND = 8 * 1024;

  /** Start code included; MPEG-1 has no stuffing field. */
  /* package */ static final int MPEG1_PACK_HEADER_LENGTH = 12;

  /** The byte after a pack start code: '0010' for MPEG-1, '01' for MPEG-2. */
  /* package */ static boolean isMpeg1Pack(byte firstByte) {
    return (firstByte & 0xF1) == 0x21;
  }

  /**
   * Swaps the Media3 PS extractor for this one wherever a factory makes it, in place: before the MP3
   * extractor, which otherwise takes an MPEG-1 PS for an audio file by the first MP2 frame it finds.
   */
  public static ExtractorsFactory replacing(ExtractorsFactory delegate) {
    return new ForwardingExtractorsFactory(delegate) {
      @Override
      public Extractor[] createExtractors() {
        return swap(super.createExtractors());
      }

      @Override
      public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
        return swap(super.createExtractors(uri, responseHeaders));
      }

      private Extractor[] swap(Extractor[] extractors) {
        for (int i = 0; i < extractors.length; i++) {
          if (extractors[i] instanceof androidx.media3.extractor.ts.PsExtractor) {
            extractors[i] = new PsExtractor();
          }
        }
        return extractors;
      }
    };
  }

  public static final int PRIVATE_STREAM_1 = 0xBD;
  public static final int AUDIO_STREAM = 0xC0;
  public static final int AUDIO_STREAM_MASK = 0xE0;
  public static final int VIDEO_STREAM = 0xE0;
  public static final int VIDEO_STREAM_MASK = 0xF0;

  private final TimestampAdjuster timestampAdjuster;
  private final SparseArray<PesReader> psPayloadReaders; // Indexed by pid
  private final ParsableByteArray psPacketBuffer;
  private final PsDurationReader durationReader;

  private boolean foundAllTracks;
  private boolean foundAudioTrack;
  private boolean foundVideoTrack;
  private long lastTrackPosition;

  // Accessed only by the loading thread.
  @Nullable private PsBinarySearchSeeker psBinarySearchSeeker;
  private @MonotonicNonNull ExtractorOutput output;
  private boolean hasOutputSeekMap;

  public PsExtractor() {
    this(new TimestampAdjuster(0));
  }

  public PsExtractor(TimestampAdjuster timestampAdjuster) {
    this.timestampAdjuster = timestampAdjuster;
    psPacketBuffer = new ParsableByteArray(4096);
    psPayloadReaders = new SparseArray<>();
    durationReader = new PsDurationReader();
  }

  // Extractor implementation.

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    byte[] scratch = new byte[14];
    input.peekFully(scratch, 0, 14);

    // Verify the PACK_START_CODE for the first 4 bytes
    if (PACK_START_CODE
        != (((scratch[0] & 0xFF) << 24)
            | ((scratch[1] & 0xFF) << 16)
            | ((scratch[2] & 0xFF) << 8)
            | (scratch[3] & 0xFF))) {
      return false;
    }
    // Verify the 01xxx1xx marker on the 5th byte
    if (isMpeg1Pack(scratch[4])) {
      // ISO/IEC 11172-1, 2.4.3.2: '0010', SCR with three marker bits, mux_rate between two more.
      if ((scratch[6] & 0x01) != 0x01
          || (scratch[8] & 0x01) != 0x01
          || (scratch[9] & 0x80) != 0x80
          || (scratch[11] & 0x01) != 0x01) {
        return false;
      }
      input.resetPeekPosition();
      input.advancePeekPosition(MPEG1_PACK_HEADER_LENGTH);
      input.peekFully(scratch, 0, 3);
      return (PACKET_START_CODE_PREFIX
          == (((scratch[0] & 0xFF) << 16) | ((scratch[1] & 0xFF) << 8) | (scratch[2] & 0xFF)));
    }
    if ((scratch[4] & 0xC4) != 0x44) {
      return false;
    }
    // Verify the xxxxx1xx marker on the 7th byte
    if ((scratch[6] & 0x04) != 0x04) {
      return false;
    }
    // Verify the xxxxx1xx marker on the 9th byte
    if ((scratch[8] & 0x04) != 0x04) {
      return false;
    }
    // Verify the xxxxxxx1 marker on the 10th byte
    if ((scratch[9] & 0x01) != 0x01) {
      return false;
    }
    // Verify the xxxxxx11 marker on the 13th byte
    if ((scratch[12] & 0x03) != 0x03) {
      return false;
    }
    // Read the stuffing length from the 14th byte (last 3 bits)
    int packStuffingLength = scratch[13] & 0x07;
    input.advancePeekPosition(packStuffingLength);
    // Now check that the next 3 bytes are the beginning of an MPEG start code
    input.peekFully(scratch, 0, 3);
    return (PACKET_START_CODE_PREFIX
        == (((scratch[0] & 0xFF) << 16) | ((scratch[1] & 0xFF) << 8) | (scratch[2] & 0xFF)));
  }

  @Override
  public void init(ExtractorOutput output) {
    this.output = output;
  }

  @Override
  public void seek(long position, long timeUs) {
    // If the timestamp adjuster has not yet established a timestamp offset, we need to reset its
    // expected first sample timestamp to be the new seek position. Without this, the timestamp
    // adjuster would incorrectly establish its timestamp offset assuming that the first sample
    // after this seek corresponds to the start of the stream (or a previous seek position, if there
    // was one).
    boolean resetTimestampAdjuster = timestampAdjuster.getTimestampOffsetUs() == C.TIME_UNSET;
    if (!resetTimestampAdjuster) {
      long adjusterFirstSampleTimestampUs = timestampAdjuster.getFirstSampleTimestampUs();
      // Also reset the timestamp adjuster if its offset was calculated based on a non-zero position
      // in the stream (other than the position being seeked to), since in this case the offset may
      // not be accurate.
      resetTimestampAdjuster =
          adjusterFirstSampleTimestampUs != C.TIME_UNSET
              && adjusterFirstSampleTimestampUs != 0
              && adjusterFirstSampleTimestampUs != timeUs;
    }
    if (resetTimestampAdjuster) {
      timestampAdjuster.reset(timeUs);
    }

    if (psBinarySearchSeeker != null) {
      psBinarySearchSeeker.setSeekTargetUs(timeUs);
    }
    for (int i = 0; i < psPayloadReaders.size(); i++) {
      psPayloadReaders.valueAt(i).seek();
    }
  }

  @Override
  public void release() {
    // Do nothing
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    // Asserts init has been called.
    checkNotNull(output);

    long inputLength = input.getLength();
    boolean canReadDuration = inputLength != C.LENGTH_UNSET;
    if (canReadDuration && !durationReader.isDurationReadFinished()) {
      return durationReader.readDuration(input, seekPosition);
    }
    maybeOutputSeekMap(inputLength);
    if (psBinarySearchSeeker != null && psBinarySearchSeeker.isSeeking()) {
      return psBinarySearchSeeker.handlePendingSeek(input, seekPosition);
    }

    input.resetPeekPosition();
    long peekBytesLeft =
        inputLength != C.LENGTH_UNSET ? inputLength - input.getPeekPosition() : C.LENGTH_UNSET;
    if (peekBytesLeft != C.LENGTH_UNSET && peekBytesLeft < 4) {
      onEndOfInput();
      return RESULT_END_OF_INPUT;
    }
    // First peek and check what type of start code is next.
    if (!input.peekFully(psPacketBuffer.getData(), 0, 4, true)) {
      onEndOfInput();
      return RESULT_END_OF_INPUT;
    }

    psPacketBuffer.setPosition(0);
    int nextStartCode = psPacketBuffer.readInt();
    if (nextStartCode == MPEG_PROGRAM_END_CODE) {
      onEndOfInput();
      return RESULT_END_OF_INPUT;
    } else if (nextStartCode == PACK_START_CODE) {
      // Now peek the rest of the pack_header.
      input.peekFully(psPacketBuffer.getData(), 0, 10);
      // The peek goes on from the start code: byte 0 here is the pack's first.
      if (isMpeg1Pack(psPacketBuffer.getData()[0])) {
        input.skipFully(MPEG1_PACK_HEADER_LENGTH);
        return RESULT_CONTINUE;
      }

      // We only care about the pack_stuffing_length in here, skip the first 77 bits.
      psPacketBuffer.setPosition(9);

      // Last 3 bits is the length.
      int packStuffingLength = psPacketBuffer.readUnsignedByte() & 0x07;

      // Now skip the stuffing and the pack header.
      input.skipFully(packStuffingLength + 14);
      return RESULT_CONTINUE;
    } else if (nextStartCode == SYSTEM_HEADER_START_CODE) {
      // We just skip all this, but we need to get the length first.
      input.peekFully(psPacketBuffer.getData(), 0, 2);

      // Length is the next 2 bytes.
      psPacketBuffer.setPosition(0);
      int systemHeaderLength = psPacketBuffer.readUnsignedShort();
      input.skipFully(systemHeaderLength + 6);
      return RESULT_CONTINUE;
    } else if (((nextStartCode & 0xFFFFFF00) >> 8) != PACKET_START_CODE_PREFIX) {
      input.skipFully(1); // Skip bytes until we see a valid start code again.
      return RESULT_CONTINUE;
    }

    // We're at the start of a regular PES packet now.
    // Get the stream ID off the last byte of the start code.
    int streamId = nextStartCode & 0xFF;

    // Check to see if we have this one in our map yet, and if not, then add it.
    PesReader payloadReader = psPayloadReaders.get(streamId);
    if (!foundAllTracks) {
      if (payloadReader == null) {
        @Nullable ElementaryStreamReader elementaryStreamReader = null;
        if (streamId == PRIVATE_STREAM_1) {
          // Private stream, used for AC3 audio.
          // NOTE: This may need further parsing to determine if its DTS, but that's likely only
          // valid for DVDs.
          elementaryStreamReader = new Ac3Reader(MimeTypes.VIDEO_PS);
          foundAudioTrack = true;
          lastTrackPosition = input.getPosition();
        } else if ((streamId & AUDIO_STREAM_MASK) == AUDIO_STREAM) {
          elementaryStreamReader = new MpegAudioReader(MimeTypes.VIDEO_PS);
          foundAudioTrack = true;
          lastTrackPosition = input.getPosition();
        } else if ((streamId & VIDEO_STREAM_MASK) == VIDEO_STREAM) {
          elementaryStreamReader = new H262Reader(MimeTypes.VIDEO_PS);
          foundVideoTrack = true;
          lastTrackPosition = input.getPosition();
        }
        if (elementaryStreamReader != null) {
          TrackIdGenerator idGenerator = new TrackIdGenerator(streamId, MAX_STREAM_ID_PLUS_ONE);
          elementaryStreamReader.createTracks(
              elementaryStreamReader instanceof H262Reader ? new Mpeg1Aspect(output) : output,
              idGenerator);
          payloadReader = new PesReader(elementaryStreamReader, timestampAdjuster);
          psPayloadReaders.put(streamId, payloadReader);
        }
      }
      long maxSearchPosition =
          foundAudioTrack && foundVideoTrack
              ? lastTrackPosition + MAX_SEARCH_LENGTH_AFTER_AUDIO_AND_VIDEO_FOUND
              : MAX_SEARCH_LENGTH;
      if (input.getPosition() > maxSearchPosition) {
        foundAllTracks = true;
        output.endTracks();
      }
    }

    // The next 2 bytes are the length. Once we have that we can consume the complete packet.
    input.peekFully(psPacketBuffer.getData(), 0, 2);
    psPacketBuffer.setPosition(0);
    int payloadLength = psPacketBuffer.readUnsignedShort();
    int pesLength = payloadLength + 6;

    if (payloadReader == null) {
      // Just skip this data.
      input.skipFully(pesLength);
    } else {
      psPacketBuffer.reset(pesLength);
      // Read the whole packet and the header for consumption.
      input.readFully(psPacketBuffer.getData(), 0, pesLength);
      psPacketBuffer.setPosition(6);
      payloadReader.consume(psPacketBuffer);
      psPacketBuffer.setLimit(psPacketBuffer.capacity());
    }

    return RESULT_CONTINUE;
  }

  // Internals.

  private void onEndOfInput() {
    for (int i = 0; i < psPayloadReaders.size(); i++) {
      psPayloadReaders.valueAt(i).consumeEndOfInput();
    }
  }

  @RequiresNonNull("output")
  private void maybeOutputSeekMap(long inputLength) {
    if (!hasOutputSeekMap) {
      hasOutputSeekMap = true;
      if (durationReader.getDurationUs() != C.TIME_UNSET) {
        psBinarySearchSeeker =
            new PsBinarySearchSeeker(
                durationReader.getScrTimestampAdjuster(),
                durationReader.getDurationUs(),
                inputLength);
        output.seekMap(psBinarySearchSeeker.getSeekMap());
      } else {
        output.seekMap(new SeekMap.Unseekable(durationReader.getDurationUs()));
      }
    }
  }

  /** Parses PES packet data and extracts samples. */
  private static final class PesReader {

    private static final int PES_SCRATCH_SIZE = 64;

    private final ElementaryStreamReader pesPayloadReader;
    private final TimestampAdjuster timestampAdjuster;
    private final ParsableBitArray pesScratch;

    private boolean ptsFlag;
    private boolean dtsFlag;
    private boolean seenFirstDts;
    private int extendedHeaderLength;
    private long timeUs;

    public PesReader(ElementaryStreamReader pesPayloadReader, TimestampAdjuster timestampAdjuster) {
      this.pesPayloadReader = pesPayloadReader;
      this.timestampAdjuster = timestampAdjuster;
      pesScratch = new ParsableBitArray(new byte[PES_SCRATCH_SIZE]);
    }

    /**
     * Notifies the reader that a seek has occurred.
     *
     * <p>Following a call to this method, the data passed to the next invocation of {@link
     * #consume(ParsableByteArray)} will not be a continuation of the data that was previously
     * passed. Hence the reader should reset any internal state.
     */
    public void seek() {
      seenFirstDts = false;
      pesPayloadReader.seek();
    }

    /**
     * Consumes the payload of a PS packet.
     *
     * @param data The PES packet. The position will be set to the start of the payload.
     * @throws ParserException If the payload could not be parsed.
     */
    public void consume(ParsableByteArray data) throws ParserException {
      if ((data.peekUnsignedByte() & 0xC0) != 0x80) {
        if (!parseMpeg1Header(data)) {
          return;
        }
      } else {
        data.readBytes(pesScratch.data, 0, 3);
        pesScratch.setPosition(0);
        parseHeader();
        data.readBytes(pesScratch.data, 0, extendedHeaderLength);
        pesScratch.setPosition(0);
      }
      parseHeaderExtension();
      pesPayloadReader.packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR);
      pesPayloadReader.consume(data);
      // We always have complete PES packets with program stream.
      pesPayloadReader.packetFinished();
    }

    private void consumeEndOfInput() {
      pesPayloadReader.endOfInputReached();
    }

    /**
     * ISO/IEC 11172-1, 2.4.3.3: stuffing bytes 0xFF, an optional STD buffer field ('01',
     * 2 bytes), then '0010' + PTS (5 bytes), '0011' + PTS + DTS (10 bytes) or 0x0F. Leaves the
     * timestamps in pesScratch the way parseHeaderExtension reads them, and data at the payload.
     * False for a header that is none of these: the packet is dropped.
     */
    private boolean parseMpeg1Header(ParsableByteArray data) {
      // The standard allows 16; muxers that pad packets to one size write more, and ffmpeg reads
      // any number (measured: "Король и Шут - Месть Гарри.mpg", most video packets).
      while (data.bytesLeft() > 0 && data.peekUnsignedByte() == 0xFF) {
        data.skipBytes(1);
      }
      if (data.bytesLeft() >= 2 && (data.peekUnsignedByte() & 0xC0) == 0x40) {
        data.skipBytes(2); // STD_buffer_scale and STD_buffer_size
      }
      if (data.bytesLeft() < 1) {
        return false;
      }
      int marker = data.peekUnsignedByte();
      int length;
      if ((marker & 0xF0) == 0x20) {
        ptsFlag = true;
        dtsFlag = false;
        length = 5;
      } else if ((marker & 0xF0) == 0x30) {
        ptsFlag = true;
        dtsFlag = true;
        length = 10;
      } else if (marker == 0x0F) {
        ptsFlag = false;
        dtsFlag = false;
        length = 1;
      } else {
        return false;
      }
      if (data.bytesLeft() < length) {
        return false;
      }
      data.readBytes(pesScratch.data, 0, length);
      pesScratch.setPosition(0);
      return true;
    }

    private void parseHeader() {
      // Note: see ISO/IEC 13818-1, section 2.4.3.6 for detailed information on the format of
      // the header.
      // First 8 bits are skipped: '10' (2), PES_scrambling_control (2), PES_priority (1),
      // data_alignment_indicator (1), copyright (1), original_or_copy (1)
      pesScratch.skipBits(8);
      ptsFlag = pesScratch.readBit();
      dtsFlag = pesScratch.readBit();
      // ESCR_flag (1), ES_rate_flag (1), DSM_trick_mode_flag (1),
      // additional_copy_info_flag (1), PES_CRC_flag (1), PES_extension_flag (1)
      pesScratch.skipBits(6);
      extendedHeaderLength = pesScratch.readBits(8);
    }

    private void parseHeaderExtension() {
      // Unset, not 0 as in Media3: a packet without a PTS is common (two of three video packets in
      // "Король и Шут - Месть Гарри.mpg"), and a picture starting in one was timed at zero.
      timeUs = C.TIME_UNSET;
      if (ptsFlag) {
        pesScratch.skipBits(4); // '0010' or '0011'
        long pts = (long) pesScratch.readBits(3) << 30;
        pesScratch.skipBits(1); // marker_bit
        pts |= pesScratch.readBits(15) << 15;
        pesScratch.skipBits(1); // marker_bit
        pts |= pesScratch.readBits(15);
        pesScratch.skipBits(1); // marker_bit
        if (!seenFirstDts && dtsFlag) {
          pesScratch.skipBits(4); // '0011'
          long dts = (long) pesScratch.readBits(3) << 30;
          pesScratch.skipBits(1); // marker_bit
          dts |= pesScratch.readBits(15) << 15;
          pesScratch.skipBits(1); // marker_bit
          dts |= pesScratch.readBits(15);
          pesScratch.skipBits(1); // marker_bit
          // Subsequent PES packets may have earlier presentation timestamps than this one, but they
          // should all be greater than or equal to this packet's decode timestamp. We feed the
          // decode timestamp to the adjuster here so that in the case that this is the first to be
          // fed, the adjuster will be able to compute an offset to apply such that the adjusted
          // presentation timestamps of all future packets are non-negative.
          timestampAdjuster.adjustTsTimestamp(dts);
          seenFirstDts = true;
        }
        timeUs = timestampAdjuster.adjustTsTimestamp(pts);
      }
    }
  }

  /**
   * Corrects the pixel aspect ratio of an MPEG-1 video track. H262Reader reads the 4-bit aspect code
   * against the MPEG-2 table (display aspect: 4:3, 16:9, 2.21:1); MPEG-1 has a table of pixel
   * aspects there instead (ISO/IEC 11172-2, 2.4.2.3), so a PAL 4:3 clip at 720x560 (code 8) came out
   * with square pixels. MPEG-1 is told by its sequence header having no sequence extension.
   */
  /* package */ static final class Mpeg1Aspect extends ForwardingExtractorOutput {

    /** Pixel height/width by code, 1-14; width/height is 1/that. */
    private static final double[] PEL_ASPECT = {
      1.0, 0.6735, 0.7031, 0.7615, 0.8055, 0.8437, 0.8935, 0.9157, 0.9815, 1.0255, 1.0695, 1.0950,
      1.1575, 1.2015
    };

    Mpeg1Aspect(ExtractorOutput output) {
      super(output);
    }

    @Override
    public TrackOutput track(int id, int type) {
      return new ForwardingTrackOutput(super.track(id, type)) {
        @Override
        public void format(Format format) {
          super.format(withMpeg1Aspect(format));
        }
      };
    }

    /* package */ static Format withMpeg1Aspect(Format format) {
      if (format.initializationData.isEmpty()) {
        return format;
      }
      byte[] csd = format.initializationData.get(0);
      if (csd.length < 8 || hasSequenceExtension(csd)) {
        return format;
      }
      int code = (csd[7] & 0xF0) >> 4;
      if (code < 1 || code > PEL_ASPECT.length) {
        return format;
      }
      return format.buildUpon().setPixelWidthHeightRatio((float) (1 / PEL_ASPECT[code - 1])).build();
    }

    private static boolean hasSequenceExtension(byte[] csd) {
      for (int i = 4; i + 3 < csd.length; i++) {
        if (csd[i] == 0 && csd[i + 1] == 0 && csd[i + 2] == 1 && (csd[i + 3] & 0xFF) == 0xB5) {
          return true;
        }
      }
      return false;
    }
  }
}

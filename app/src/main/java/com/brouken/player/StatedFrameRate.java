package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.ParsableNalUnitBitArray;

import java.util.List;

/**
 * The frame rate an H.264 or HEVC stream states in its own parameter sets, for a container that
 * states none.
 *
 * <p>MPEG-TS carries no frame rate, and Media3's H.264 and HEVC readers do not take one from the
 * stream, so a TS reaches the decoder without {@code KEY_FRAME_RATE}. Amlogic's decoder then assumes
 * 30 ({@code C2VdecDeviceUtil: query frame rate:30.000000 … mDurationUs = 33333}). On interlaced 25i it
 * deinterlaces to fields and times the fields between two stream timestamps by that guess, 16.7 ms
 * instead of 20: the picture runs ahead and is pulled back about once a second, a stutter with a
 * steady beat. Measured on a Dune with broadcast 1080i H.264 (24.10.2026): with the rate given as 25
 * the fields come out exactly 20 ms apart and nothing is skipped.
 *
 * <p>The stream says it outright, in the timing info of its VUI. H.264: {@code time_scale / (2 ·
 * num_units_in_tick)} is the frame rate by the standard's definition (a tick is a field period),
 * whatever the coding — field pictures, MBAFF or progressive. HEVC: {@code vui_time_scale /
 * vui_num_units_in_tick} is the picture rate, halved when {@code field_seq_flag} says the pictures
 * are fields; the VPS carries the same timing and answers when the SPS has none. Measuring the
 * samples instead would count fields on a field-coded stream and frames on an MBAFF one, with
 * nothing in the samples to tell the two apart.
 */
final class StatedFrameRate {

    private static final int H264_SPS = 7;
    private static final int H265_VPS = 32;
    private static final int H265_SPS = 33;

    private StatedFrameRate() {
    }

    /**
     * The frame rate stated in {@code initializationData} for a stream of {@code mime}, or {@link
     * Format#NO_VALUE} when it states none, is not H.264 or HEVC, or cannot be read.
     */
    static float of(@Nullable String mime, List<byte[]> initializationData) {
        final boolean hevc = MimeTypes.VIDEO_H265.equals(mime);
        if (!hevc && !MimeTypes.VIDEO_H264.equals(mime)) {
            return Format.NO_VALUE;
        }
        float fromVps = Format.NO_VALUE;
        for (byte[] data : initializationData) {
            int start = nextNal(data, 0);
            while (start >= 0 && start < data.length) {
                final int end = endOfNal(data, start);
                try {
                    if (!hevc && (data[start] & 0x1f) == H264_SPS) {
                        return h264Sps(data, start + 1, end);
                    }
                    if (hevc && start + 2 <= end) {
                        final int type = (data[start] >> 1) & 0x3f;
                        if (type == H265_SPS) {
                            final float rate = h265Sps(data, start + 2, end).rate;
                            if (rate != Format.NO_VALUE) return rate;
                        } else if (type == H265_VPS && fromVps == Format.NO_VALUE) {
                            fromVps = h265Vps(data, start + 2, end);
                        }
                    }
                } catch (RuntimeException e) {
                    // A truncated or malformed parameter set: say nothing rather than something wrong.
                    return Format.NO_VALUE;
                }
                start = nextNal(data, end);
            }
        }
        return fromVps;
    }

    /**
     * Where the NAL header after the next start code at or after {@code from} is; for an array with
     * no start code at all — one bare NAL unit — 0 when {@code from} is 0, else -1.
     */
    private static int nextNal(byte[] data, int from) {
        for (int i = from; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) return i + 3;
        }
        return from == 0 ? 0 : -1;
    }

    /** The end of the NAL unit starting at {@code start}: the next start code, or the array's end. */
    private static int endOfNal(byte[] data, int start) {
        for (int i = start; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || data[i + 2] == 0)) {
                // 00 00 00 01 and 00 00 01 both begin the next unit; a lone 00 00 00 is trailing zero bytes.
                return i;
            }
        }
        return data.length;
    }

    /** H.264 SPS payload (after the NAL header), read up to the VUI timing info. */
    static float h264Sps(byte[] data, int offset, int limit) {
        final ParsableNalUnitBitArray b = new ParsableNalUnitBitArray(data, offset, limit);
        final int profileIdc = b.readBits(8);
        b.skipBits(16); // constraint flags, reserved bits, level_idc
        b.readUnsignedExpGolombCodedInt(); // seq_parameter_set_id
        if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118
                || profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134
                || profileIdc == 135) {
            final int chromaFormatIdc = b.readUnsignedExpGolombCodedInt();
            if (chromaFormatIdc == 3) b.skipBit(); // separate_colour_plane_flag
            b.readUnsignedExpGolombCodedInt(); // bit_depth_luma_minus8
            b.readUnsignedExpGolombCodedInt(); // bit_depth_chroma_minus8
            b.skipBit(); // qpprime_y_zero_transform_bypass_flag
            if (b.readBit()) { // seq_scaling_matrix_present_flag
                final int lists = chromaFormatIdc != 3 ? 8 : 12;
                for (int i = 0; i < lists; i++) {
                    if (b.readBit()) skipH264ScalingList(b, i < 6 ? 16 : 64);
                }
            }
        }
        b.readUnsignedExpGolombCodedInt(); // log2_max_frame_num_minus4
        final int picOrderCntType = b.readUnsignedExpGolombCodedInt();
        if (picOrderCntType == 0) {
            b.readUnsignedExpGolombCodedInt(); // log2_max_pic_order_cnt_lsb_minus4
        } else if (picOrderCntType == 1) {
            b.skipBit(); // delta_pic_order_always_zero_flag
            b.readSignedExpGolombCodedInt(); // offset_for_non_ref_pic
            b.readSignedExpGolombCodedInt(); // offset_for_top_to_bottom_field
            final int cycle = b.readUnsignedExpGolombCodedInt();
            for (int i = 0; i < cycle; i++) b.readSignedExpGolombCodedInt();
        }
        b.readUnsignedExpGolombCodedInt(); // max_num_ref_frames
        b.skipBit(); // gaps_in_frame_num_value_allowed_flag
        b.readUnsignedExpGolombCodedInt(); // pic_width_in_mbs_minus1
        b.readUnsignedExpGolombCodedInt(); // pic_height_in_map_units_minus1
        if (!b.readBit()) b.skipBit(); // frame_mbs_only_flag, mb_adaptive_frame_field_flag
        b.skipBit(); // direct_8x8_inference_flag
        if (b.readBit()) { // frame_cropping_flag
            for (int i = 0; i < 4; i++) b.readUnsignedExpGolombCodedInt();
        }
        if (!b.readBit()) return Format.NO_VALUE; // vui_parameters_present_flag
        skipVuiUpToChromaLoc(b);
        if (!b.readBit()) return Format.NO_VALUE; // timing_info_present_flag
        return rate(readUnsigned32(b), readUnsigned32(b), 2);
    }

    /** What an HEVC SPS says about its VUI: whether it has one, and the frame rate it states there. */
    static final class H265Vui {
        static final H265Vui ABSENT = new H265Vui(false, Format.NO_VALUE);

        final boolean present;
        final float rate;

        H265Vui(boolean present, float rate) {
            this.present = present;
            this.rate = rate;
        }
    }

    /** HEVC SPS payload (after the two-byte NAL header), read up to the VUI timing info. */
    static H265Vui h265Sps(byte[] data, int offset, int limit) {
        final ParsableNalUnitBitArray b = new ParsableNalUnitBitArray(data, offset, limit);
        b.skipBits(4); // sps_video_parameter_set_id
        final int maxSubLayersMinus1 = b.readBits(3);
        b.skipBit(); // sps_temporal_id_nesting_flag
        skipH265ProfileTierLevel(b, maxSubLayersMinus1);
        b.readUnsignedExpGolombCodedInt(); // sps_seq_parameter_set_id
        if (b.readUnsignedExpGolombCodedInt() == 3) b.skipBit(); // chroma_format_idc, separate_colour_plane_flag
        b.readUnsignedExpGolombCodedInt(); // pic_width_in_luma_samples
        b.readUnsignedExpGolombCodedInt(); // pic_height_in_luma_samples
        if (b.readBit()) { // conformance_window_flag
            for (int i = 0; i < 4; i++) b.readUnsignedExpGolombCodedInt();
        }
        b.readUnsignedExpGolombCodedInt(); // bit_depth_luma_minus8
        b.readUnsignedExpGolombCodedInt(); // bit_depth_chroma_minus8
        final int log2MaxPicOrderCntLsb = b.readUnsignedExpGolombCodedInt() + 4;
        final boolean orderingForAll = b.readBit(); // sps_sub_layer_ordering_info_present_flag
        for (int i = orderingForAll ? 0 : maxSubLayersMinus1; i <= maxSubLayersMinus1; i++) {
            b.readUnsignedExpGolombCodedInt(); // sps_max_dec_pic_buffering_minus1
            b.readUnsignedExpGolombCodedInt(); // sps_max_num_reorder_pics
            b.readUnsignedExpGolombCodedInt(); // sps_max_latency_increase_plus1
        }
        for (int i = 0; i < 6; i++) {
            // log2_min_luma_coding_block_size_minus3 … max_transform_hierarchy_depth_intra
            b.readUnsignedExpGolombCodedInt();
        }
        if (b.readBit() && b.readBit()) { // scaling_list_enabled_flag, sps_scaling_list_data_present_flag
            skipH265ScalingListData(b);
        }
        b.skipBits(2); // amp_enabled_flag, sample_adaptive_offset_enabled_flag
        if (b.readBit()) { // pcm_enabled_flag
            b.skipBits(8); // pcm_sample_bit_depth_luma_minus1, _chroma_minus1
            b.readUnsignedExpGolombCodedInt(); // log2_min_pcm_luma_coding_block_size_minus3
            b.readUnsignedExpGolombCodedInt(); // log2_diff_max_min_pcm_luma_coding_block_size
            b.skipBit(); // pcm_loop_filter_disabled_flag
        }
        skipH265ShortTermRefPicSets(b);
        if (b.readBit()) { // long_term_ref_pics_present_flag
            final int count = b.readUnsignedExpGolombCodedInt();
            for (int i = 0; i < count; i++) {
                b.skipBits(log2MaxPicOrderCntLsb + 1); // lt_ref_pic_poc_lsb_sps, used_by_curr_pic_lt_sps_flag
            }
        }
        b.skipBits(2); // sps_temporal_mvp_enabled_flag, strong_intra_smoothing_enabled_flag
        if (!b.readBit()) return H265Vui.ABSENT; // vui_parameters_present_flag
        skipVuiUpToChromaLoc(b);
        b.skipBit(); // neutral_chroma_indication_flag
        final boolean fieldSeq = b.readBit(); // field_seq_flag
        b.skipBit(); // frame_field_info_present_flag
        if (b.readBit()) { // default_display_window_flag
            for (int i = 0; i < 4; i++) b.readUnsignedExpGolombCodedInt();
        }
        if (!b.readBit()) return new H265Vui(true, Format.NO_VALUE); // vui_timing_info_present_flag
        return new H265Vui(true, rate(readUnsigned32(b), readUnsigned32(b), fieldSeq ? 2 : 1));
    }

    /**
     * HEVC VPS payload (after the NAL header), read up to its timing info. Picture rate: the VPS cannot
     * say whether the pictures are fields, so this answers only for an SPS that has no VUI timing.
     */
    static float h265Vps(byte[] data, int offset, int limit) {
        final ParsableNalUnitBitArray b = new ParsableNalUnitBitArray(data, offset, limit);
        b.skipBits(4 + 1 + 1 + 6); // vps_video_parameter_set_id … vps_max_layers_minus1
        final int maxSubLayersMinus1 = b.readBits(3);
        b.skipBits(1 + 16); // vps_temporal_id_nesting_flag, vps_reserved_0xffff_16bits
        skipH265ProfileTierLevel(b, maxSubLayersMinus1);
        final boolean orderingForAll = b.readBit(); // vps_sub_layer_ordering_info_present_flag
        for (int i = orderingForAll ? 0 : maxSubLayersMinus1; i <= maxSubLayersMinus1; i++) {
            b.readUnsignedExpGolombCodedInt();
            b.readUnsignedExpGolombCodedInt();
            b.readUnsignedExpGolombCodedInt();
        }
        final int maxLayerId = b.readBits(6);
        final int layerSets = b.readUnsignedExpGolombCodedInt(); // vps_num_layer_sets_minus1
        b.skipBits(layerSets * (maxLayerId + 1)); // layer_id_included_flag
        if (!b.readBit()) return Format.NO_VALUE; // vps_timing_info_present_flag
        return rate(readUnsigned32(b), readUnsigned32(b), 1);
    }

    /** The VUI fields both codecs share, from aspect_ratio_info_present_flag to chroma_loc_info. */
    private static void skipVuiUpToChromaLoc(ParsableNalUnitBitArray b) {
        if (b.readBit()) { // aspect_ratio_info_present_flag
            if (b.readBits(8) == 255) b.skipBits(32); // Extended_SAR: sar_width, sar_height
        }
        if (b.readBit()) b.skipBit(); // overscan_info_present_flag, overscan_appropriate_flag
        if (b.readBit()) { // video_signal_type_present_flag
            b.skipBits(4); // video_format, video_full_range_flag
            if (b.readBit()) b.skipBits(24); // colour_description_present_flag
        }
        if (b.readBit()) { // chroma_loc_info_present_flag
            b.readUnsignedExpGolombCodedInt();
            b.readUnsignedExpGolombCodedInt();
        }
    }

    private static float rate(long numUnitsInTick, long timeScale, int ticksPerFrame) {
        if (numUnitsInTick == 0 || timeScale == 0) return Format.NO_VALUE;
        final float rate = (float) (timeScale / ((double) ticksPerFrame * numUnitsInTick));
        // A rate no film or broadcast has is a field the encoder filled with something else.
        return rate >= 10 && rate <= 121 ? rate : Format.NO_VALUE;
    }

    private static long readUnsigned32(ParsableNalUnitBitArray b) {
        return ((long) b.readBits(16) << 16) | b.readBits(16);
    }

    private static void skipH264ScalingList(ParsableNalUnitBitArray b, int size) {
        int last = 8;
        int next = 8;
        for (int i = 0; i < size; i++) {
            if (next != 0) {
                next = (last + b.readSignedExpGolombCodedInt() + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    private static void skipH265ProfileTierLevel(ParsableNalUnitBitArray b, int maxSubLayersMinus1) {
        b.skipBits(88); // general profile space … general_inbld_flag / reserved
        b.skipBits(8); // general_level_idc
        final boolean[] profilePresent = new boolean[maxSubLayersMinus1];
        final boolean[] levelPresent = new boolean[maxSubLayersMinus1];
        for (int i = 0; i < maxSubLayersMinus1; i++) {
            profilePresent[i] = b.readBit();
            levelPresent[i] = b.readBit();
        }
        if (maxSubLayersMinus1 > 0) {
            b.skipBits(2 * (8 - maxSubLayersMinus1)); // reserved_zero_2bits
        }
        for (int i = 0; i < maxSubLayersMinus1; i++) {
            if (profilePresent[i]) b.skipBits(88);
            if (levelPresent[i]) b.skipBits(8);
        }
    }

    private static void skipH265ScalingListData(ParsableNalUnitBitArray b) {
        for (int sizeId = 0; sizeId < 4; sizeId++) {
            for (int matrixId = 0; matrixId < 6; matrixId += sizeId == 3 ? 3 : 1) {
                if (!b.readBit()) { // scaling_list_pred_mode_flag
                    b.readUnsignedExpGolombCodedInt(); // scaling_list_pred_matrix_id_delta
                } else {
                    final int coefNum = Math.min(64, 1 << (4 + (sizeId << 1)));
                    if (sizeId > 1) b.readSignedExpGolombCodedInt(); // scaling_list_dc_coef_minus8
                    for (int i = 0; i < coefNum; i++) b.readSignedExpGolombCodedInt();
                }
            }
        }
    }

    /**
     * num_short_term_ref_pic_sets and the sets, as the SPS has them (no slice-header form). A predicted
     * set is as long as its reference set's entries say, so the picture order deltas are carried along
     * (H.265 7-61, 7-62): a delta that lands on 0 is the current picture and is dropped, which a plain
     * count of the use_delta flags would get wrong.
     */
    private static void skipH265ShortTermRefPicSets(ParsableNalUnitBitArray b) {
        final int count = b.readUnsignedExpGolombCodedInt();
        int[] s0 = new int[0];
        int[] s1 = new int[0];
        for (int i = 0; i < count; i++) {
            if (i != 0 && b.readBit()) { // inter_ref_pic_set_prediction_flag
                final int sign = b.readBit() ? -1 : 1; // delta_rps_sign
                final int deltaRps = sign * (b.readUnsignedExpGolombCodedInt() + 1); // abs_delta_rps_minus1
                final int refDeltaPocs = s0.length + s1.length;
                final boolean[] useDelta = new boolean[refDeltaPocs + 1];
                for (int j = 0; j <= refDeltaPocs; j++) {
                    // used_by_curr_pic_flag; use_delta_flag, inferred 1 when the picture is used
                    useDelta[j] = b.readBit() || b.readBit();
                }
                final int[] negative = new int[refDeltaPocs + 1];
                int n = 0;
                for (int j = s1.length - 1; j >= 0; j--) {
                    final int d = s1[j] + deltaRps;
                    if (d < 0 && useDelta[s0.length + j]) negative[n++] = d;
                }
                if (deltaRps < 0 && useDelta[refDeltaPocs]) negative[n++] = deltaRps;
                for (int j = 0; j < s0.length; j++) {
                    final int d = s0[j] + deltaRps;
                    if (d < 0 && useDelta[j]) negative[n++] = d;
                }
                final int[] positive = new int[refDeltaPocs + 1];
                int p = 0;
                for (int j = s0.length - 1; j >= 0; j--) {
                    final int d = s0[j] + deltaRps;
                    if (d > 0 && useDelta[j]) positive[p++] = d;
                }
                if (deltaRps > 0 && useDelta[refDeltaPocs]) positive[p++] = deltaRps;
                for (int j = 0; j < s1.length; j++) {
                    final int d = s1[j] + deltaRps;
                    if (d > 0 && useDelta[s0.length + j]) positive[p++] = d;
                }
                s0 = java.util.Arrays.copyOf(negative, n);
                s1 = java.util.Arrays.copyOf(positive, p);
            } else {
                s0 = new int[b.readUnsignedExpGolombCodedInt()]; // num_negative_pics
                s1 = new int[b.readUnsignedExpGolombCodedInt()]; // num_positive_pics
                int poc = 0;
                for (int j = 0; j < s0.length; j++) {
                    poc -= b.readUnsignedExpGolombCodedInt() + 1; // delta_poc_s0_minus1
                    s0[j] = poc;
                    b.skipBit(); // used_by_curr_pic_s0_flag
                }
                poc = 0;
                for (int j = 0; j < s1.length; j++) {
                    poc += b.readUnsignedExpGolombCodedInt() + 1; // delta_poc_s1_minus1
                    s1[j] = poc;
                    b.skipBit(); // used_by_curr_pic_s1_flag
                }
            }
        }
    }
}

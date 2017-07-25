package org.jcodec.codecs.h264.decode;
import static org.jcodec.codecs.h264.decode.MBlockDecoderUtils.debugPrint;
import static org.jcodec.common.tools.MathUtil.wrap;

import org.jcodec.codecs.h264.H264Const;
import org.jcodec.codecs.h264.io.model.Frame;
import org.jcodec.codecs.h264.io.model.SliceHeader;
import org.jcodec.codecs.h264.io.model.SliceType;
import org.jcodec.common.IntObjectMap;
import org.jcodec.common.model.Picture8Bit;
import org.jcodec.platform.Platform;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Contains reference picture list management logic
 * 
 * @author The JCodec Project
 */
public class RefListManager {
    private SliceHeader sh;
    private int[] numRef;
    private Frame[] sRefs;
    private IntObjectMap<Frame> lRefs;
    private int framePOC;

    public RefListManager(SliceHeader sh, Frame[] sRefs, IntObjectMap<Frame> lRefs, int framePOC) {
        this.sh = sh;
        this.sRefs = sRefs;
        this.lRefs = lRefs;
        if (sh.numRefIdxActiveOverrideFlag)
            numRef = new int[] { sh.numRefIdxActiveMinus1[0] + 1, sh.numRefIdxActiveMinus1[1] + 1 };
        else
            numRef = new int[] { sh.pps.numRefIdxActiveMinus1[0] + 1, sh.pps.numRefIdxActiveMinus1[1] + 1 };
        this.framePOC = framePOC;
    }

    public Frame[][] getRefList() {
        Frame[][] refList = null;
        if (sh.sliceType == SliceType.P) {
            refList = new Frame[][] { buildRefListP(), null };
        } else if (sh.sliceType == SliceType.B) {
            refList = buildRefListB();
        }

        debugPrint("------");
        if (refList != null) {
            for (int l = 0; l < 2; l++) {
                if (refList[l] != null)
                    for (int i = 0; i < refList[l].length; i++)
                        if (refList[l][i] != null)
                            debugPrint("REF[%d][%d]: ", l, i, ((Frame) refList[l][i]).getPOC());
            }
        }
        return refList;
    }

    private Frame[] buildRefListP() {
        int frame_num = sh.frameNum;
        int maxFrames = 1 << (sh.sps.log2MaxFrameNumMinus4 + 4);
        // int nLongTerm = Math.min(lRefs.size(), numRef[0] - 1);
        Frame[] result = new Frame[numRef[0]];

        int refs = 0;
        for (int i = frame_num - 1; i >= frame_num - maxFrames && refs < numRef[0]; i--) {
            int fn = i < 0 ? i + maxFrames : i;
            if (sRefs[fn] != null) {
                result[refs] = sRefs[fn] == H264Const.NO_PIC ? null : sRefs[fn];
                ++refs;
            }
        }
        int[] keys = lRefs.keys();
        Arrays.sort(keys);
        for (int i = 0; i < keys.length && refs < numRef[0]; i++) {
            result[refs++] = lRefs.get(keys[i]);
        }

        reorder(result, 0);

        return result;
    }

    private Frame[][] buildRefListB() {

        Frame[] l0 = buildList(false, true);
        Frame[] l1 = buildList(true, false);

        if (Platform.arrayEqualsObj(l0, l1) && count(l1) > 1) {
            Frame frame = l1[1];
            l1[1] = l1[0];
            l1[0] = frame;
        }

        Frame[][] result = { Platform.copyOfObj(l0, numRef[0]), Platform.copyOfObj(l1, numRef[1]) };

        reorder(result[0], 0);
        reorder(result[1], 1);

        return result;
    }

    private Frame[] buildList(boolean fwdAsc, boolean invAsc) {
        Frame[] refs = new Frame[sRefs.length + lRefs.size()];
        Frame[] fwd = copySort(fwdAsc, framePOC);
        Frame[] inv = copySort(invAsc, framePOC);
        int nFwd = count(fwd);
        int nInv = count(inv);

        int ref = 0;
        for (int i = 0; i < nFwd; i++, ref++)
            refs[ref] = fwd[i];
        for (int i = 0; i < nInv; i++, ref++)
            refs[ref] = inv[i];

        int[] keys = lRefs.keys();
        Arrays.sort(keys);
        for (int i = 0; i < keys.length; i++, ref++)
            refs[ref] = lRefs.get(keys[i]);

        return refs;
    }

    private int count(Frame[] arr) {
        for (int nn = 0; nn < arr.length; nn++)
            if (arr[nn] == null)
                return nn;
        return arr.length;
    }

    private Frame[] copySort(boolean asc, int thisPOC) {
        Comparator<Frame> cmp = asc ? Frame.POCAsc : Frame.POCDesc;  
        Frame[] copyOf = Platform.copyOfObj(sRefs, sRefs.length);
        for (int i = 0; i < copyOf.length; i++)
            if (copyOf[i] != null && (asc && thisPOC > copyOf[i].getPOC() || !asc && thisPOC < copyOf[i].getPOC()))
                copyOf[i] = null;
        Arrays.sort(copyOf, cmp);
        return copyOf;
    }

    private void reorder(Picture8Bit[] result, int list) {
        if (sh.refPicReordering[list] == null)
            return;

        int predict = sh.frameNum;
        int maxFrames = 1 << (sh.sps.log2MaxFrameNumMinus4 + 4);

        for (int ind = 0; ind < sh.refPicReordering[list][0].length; ind++) {
            switch (sh.refPicReordering[list][0][ind]) {
            case 0:
                predict = wrap(predict - sh.refPicReordering[list][1][ind] - 1, maxFrames);
                break;
            case 1:
                predict = wrap(predict + sh.refPicReordering[list][1][ind] + 1, maxFrames);
                break;
            case 2:
                throw new RuntimeException("long term");
            }
            for (int i = numRef[list] - 1; i > ind; i--)
                result[i] = result[i - 1];
            result[ind] = sRefs[predict];
            for (int i = ind + 1, j = i; i < numRef[list] && result[i] != null; i++) {
                if (result[i] != sRefs[predict])
                    result[j++] = result[i];
            }
        }
    }

}

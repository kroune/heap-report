package shapes.extraction

import java.lang.Long.bitCount
import java.util.BitSet

/** Maps selected source ids to dense ids by their rank in the selection bitmap. */
internal class DenseObjectIdRemapper(selectedObjects: BitSet) {
    private val selectionWords = selectedObjects.toLongArray()
    private val selectedCountBeforeWord = IntArray(selectionWords.size + 1)

    init {
        for (wordIndex in selectionWords.indices) {
            selectedCountBeforeWord[wordIndex + 1] =
                selectedCountBeforeWord[wordIndex] + bitCount(selectionWords[wordIndex])
        }
    }

    fun denseId(sourceObjectId: Int): Int {
        val wordIndex = sourceObjectId ushr 6
        val bitsBeforeTarget = selectionWords[wordIndex] and
            ((1L shl (sourceObjectId and 63)) - 1)
        return selectedCountBeforeWord[wordIndex] + bitCount(bitsBeforeTarget)
    }
}

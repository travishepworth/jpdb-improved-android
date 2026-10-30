package dev.travmonkey.jpdb.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SMALL_KANA = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ".toSet()

/** Splits kana into morae: small ゃ/ゅ/ょ etc. attach to the previous character. */
fun moras(s: String): List<String> {
    val out = mutableListOf<String>()
    for (ch in s) {
        if (ch in SMALL_KANA && out.isNotEmpty()) out[out.lastIndex] += ch else out += ch.toString()
    }
    return out
}

/** Reading with jpdb's pitch pattern (e.g. "LHHL") drawn as an overline with a drop marker. */
@Composable
fun PitchReading(reading: String, pitch: List<String>, modifier: Modifier = Modifier) {
    val style = JapaneseText.copy(fontSize = 26.sp)
    val pat = pitch.firstOrNull()
    val m = moras(reading)
    if (pat == null || !pat.matches(Regex("[HL]+")) || pat.length < m.size) {
        Text(reading, style = style, modifier = modifier)
        return
    }
    val color = MaterialTheme.colorScheme.primary
    Row(modifier) {
        m.forEachIndexed { i, mora ->
            val high = pat[i] == 'H'
            val drop = high && pat.getOrNull(i + 1) == 'L'
            Text(
                mora,
                style = style,
                modifier = Modifier
                    .drawBehind {
                        val w = 2.dp.toPx()
                        if (high) drawLine(color, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                        if (drop) drawLine(color, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                    }
                    .padding(top = 4.dp),
            )
        }
    }
}

package com.tinhcd.myesalessfa.domain

import java.text.Normalizer

/**
 * Folded for comparison: lower case, and with the tone and vowel marks removed.
 *
 * Names arrive from head office spelled properly — "Tạp hoá Bà Bảy", "Nước ngọt
 * Coca-Cola" — and a rep standing in the shop types "tap hoa ba bay", because
 * nobody reaches for the tone keys one-handed. Comparing the two literally finds
 * nothing, which reads as a missing product rather than a missing accent.
 *
 * Lives in :domain rather than beside one screen because every list the rep
 * searches has the same problem, and a second copy would be a second place for
 * the đ to be forgotten.
 */
fun String.foldForSearch(): String = Normalizer
    .normalize(this, Normalizer.Form.NFD)
    .replace(CombiningMarks, "")
    .lowercase()
    // Not a marked vowel, so NFD leaves it whole and it has to be spelled out.
    .replace('đ', 'd')

private val CombiningMarks = "\\p{Mn}+".toRegex()

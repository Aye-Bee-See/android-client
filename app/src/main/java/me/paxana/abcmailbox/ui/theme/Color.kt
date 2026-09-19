package me.paxana.abcmailbox.ui.theme

import androidx.compose.ui.graphics.Color

// The web site's tokens (style.css): a paper-like off-white, near-black text,
// one red used for rules, alerts and emphasis, and a warm grey for borders.
val Paper = Color(0xFFF2F0ED)
val PaperRaised = Color(0xFFFFFFFF)
val Ink = Color(0xFF1A1A1A)
// #767676, the grey of the web templates, is 3.99:1 on Paper: below the 4.5:1 WCAG AA asks of body text.
// It only passes on pure white. This is the lightest neutral grey that passes on Paper (4.62:1).
val InkMuted = Color(0xFF6C6C6C)
val Rule = Color(0xFFD4D0CA)
val Red = Color(0xFFB33A3A)
val RedWash = Color(0xFFFDF0F0)

// Dark: the same relationships inverted, with a slightly lifted red for contrast.
val PaperDark = Color(0xFF161514)
val PaperRaisedDark = Color(0xFF211F1D)
val InkDark = Color(0xFFEDEAE5)
val InkMutedDark = Color(0xFF9A958E)
val RuleDark = Color(0xFF3A3733)
val RedDark = Color(0xFFD9625F)
val RedWashDark = Color(0xFF3A1F1F)
// RedDark on RedWashDark is 4.20:1; notices put text on that wash, so they get a lighter red that passes.
val RedOnWashDark = Color(0xFFDC6D6A)
